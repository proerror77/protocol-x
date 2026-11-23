"""Read the Hong Kong airport passenger-arrivals feed for the private pilot."""

from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import re
import subprocess
from threading import Lock
from time import monotonic
from urllib.parse import urlencode
from zoneinfo import ZoneInfo

from fastapi import Depends, HTTPException, Request


AIRPORT_TZ = ZoneInfo("Asia/Hong_Kong")
ARRIVALS_PAGE = "https://www.hongkongairport.com/en/flights/arrivals/passenger.page"
FEED = "https://www.hongkongairport.com/flightinfo-rest/rest/flights"
STATUS_TIME = re.compile(
    r"^(Est\.?|Estimated|Landed|At gate)(?:\s+at)?\s+(\d{2}:\d{2})(?:\s+\((\d{2}/\d{2}/\d{4})\))?", re.I
)
CACHE_SECONDS = 90
HKG_EVENTS = ("golf-demo", "golf-hkg-dispatch-demo", "golf-hkg-arrivals-demo")


def operating_callsign(flight):
    carrier = flight.get("airline") or ""
    number = re.fullmatch(r"[A-Z0-9]{2}\s*(\d{1,4}[A-Z]?)", flight.get("no") or "")
    return carrier + number[1] if re.fullmatch(r"[A-Z]{3}", carrier) and number else None


def correlation_window(day, time, minutes):
    pattern = "%H:%M (%d/%m/%Y)" if "(" in time else "%Y-%m-%d %H:%M"
    value = time if "(" in time else f"{day} {time}"
    try:
        anchor = datetime.strptime(value, pattern).replace(tzinfo=AIRPORT_TZ)
    except ValueError:
        return None
    margin = timedelta(minutes=minutes)
    return [int((anchor - margin).timestamp()), int((anchor + margin).timestamp())]


def normalize_arrivals(payload, day):
    if not isinstance(payload, list):
        raise ValueError("Invalid Hong Kong airport response")
    source = next((item for item in payload if item.get("date") == day
                   and item.get("arrival") is True and item.get("cargo") is False), None)
    if source is None or not isinstance(source.get("list"), list):
        raise ValueError("Hong Kong arrivals response does not contain the requested date")
    updated = datetime.fromisoformat(source["lastUpdatedTime"])
    if updated.tzinfo is None:
        raise ValueError("Hong Kong arrivals timestamp has no timezone")
    arrivals = []
    for row in source["list"]:
        published = row.get("flight") or []
        numbers = [flight["no"] for flight in published if flight.get("no")]
        if not numbers:
            continue
        status = (row.get("status") or "").strip()
        match = STATUS_TIME.match(status)
        kind = match[1].lower().rstrip(".") if match else ""
        shown_time = match[2] + (f" ({match[3]})" if match[3] else "") if match else None
        status_code = {"est": "estimated", "estimated": "estimated", "landed": "landed",
                       "at gate": "at_gate"}.get(kind)
        if status_code is None:
            status_code = {"delayed": "delayed", "cancelled": "cancelled", "": "scheduled"}.get(status.lower(), "other")
        margin = {"landed": 45, "at_gate": 45, "estimated": 90, "scheduled": 120}.get(status_code)
        window = correlation_window(day, shown_time or row.get("time") or "", margin) if margin else None
        arrivals.append({
            "flights": numbers,
            "callsign": operating_callsign(published[0]) if window else None,
            "track_window": window,
            "origins": row.get("origin") or [],
            "scheduled": row.get("time"),
            "estimated": shown_time if kind in {"est", "estimated"} else None,
            "actual": shown_time if kind in {"landed", "at gate"} else None,
            "status": status_code,
            "arrivals_hall": row.get("hall") or None,
            "baggage_belt": row.get("baggage") or None,
        })
    counts = Counter(item["callsign"] for item in arrivals)
    for item in arrivals:
        if counts[item["callsign"]] > 1:
            item["callsign"] = None
    return {"provider": "HKIA", "airport": "HKG", "date": day, "source_updated_at": updated.isoformat(),
            "source_url": ARRIVALS_PAGE, "arrivals": arrivals}


class ArrivalFeed:
    def __init__(self):
        self.lock = Lock()
        self.cache = {}

    def get(self, day=None):
        with self.lock:
            now = datetime.now(timezone.utc)
            today = now.astimezone(AIRPORT_TZ).date()
            dates = (today.isoformat(), (today - timedelta(days=1)).isoformat())
            day = day or dates[0]
            if day not in dates:
                raise ValueError("Arrival feed supports today and the previous Hong Kong day")
            self.cache = {key: value for key, value in self.cache.items() if key in dates}
            snapshot, next_fetch, failed = self.cache.get(day, (None, 0, False))
            if monotonic() >= next_fetch:
                fresh = self.refresh(day)
                failed = fresh is None
                snapshot = fresh or snapshot
                self.cache[day] = (snapshot, monotonic() + (30 if failed else CACHE_SECONDS), failed)
            if snapshot is None:
                raise HTTPException(503, "暂时无法读取机场到港数据，请查看机场官方到港页面。")
            updated = datetime.fromisoformat(snapshot["source_updated_at"])
            stale = failed or (now - updated).total_seconds() > 600
            return {**snapshot, "state": "stale" if stale else "current"}

    def refresh(self, day):
        query = urlencode({"span": 1, "date": day, "lang": "en", "cargo": "false", "arrival": "true"})
        try:
            result = subprocess.run(
                ["/usr/bin/curl", "--fail", "--silent", "--show-error", "--max-time", "9",
                 "--max-filesize", "524288", FEED + "?" + query],
                capture_output=True, check=True, timeout=11,
            )
            snapshot = normalize_arrivals(json.loads(result.stdout), day)
            return {**snapshot, "checked_at": datetime.now(timezone.utc).isoformat()}
        except (OSError, subprocess.SubprocessError, ValueError, TypeError, KeyError, AttributeError):
            return None


def register_flights(app, identity, feed=None):
    feed = feed or ArrivalFeed()

    @app.get("/api/events/{event_id}/flight-arrivals")
    def flight_arrivals(event_id: str, _user=Depends(identity)):
        if event_id not in HKG_EVENTS:
            raise HTTPException(404, "该活动没有配置到港航班")
        return feed.get()

    @app.get("/internal/hkg-map-arrivals", include_in_schema=False)
    def map_arrivals(request: Request):
        # The TAK reverse proxy authorizes its own session before forwarding this loopback-only route.
        if not request.client or request.client.host != "127.0.0.1":
            raise HTTPException(404)
        return feed.get()
