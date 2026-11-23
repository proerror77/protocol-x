#!/usr/bin/env python3
"""Publish selected Hong Kong Observatory station observations to OpenTAKServer."""

import csv
import io
import json
import math
import os
import re
import socket
import ssl
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import urlopen
from xml.etree.ElementTree import Element, SubElement, tostring
from zoneinfo import ZoneInfo

HKT = ZoneInfo("Asia/Hong_Kong")
REGIONAL = "https://data.weather.gov.hk/weatherAPI/hko_data/regional-weather"
SOURCES = {
    "wind": f"{REGIONAL}/latest_10min_wind.csv",
    "visibility": f"{REGIONAL}/latest_10min_visibility.csv",
    "rain": "https://data.weather.gov.hk/weatherAPI/opendata/hourlyRainfall.php?lang=en",
}
# Coordinates and observation identifiers from HKO's CSDI station datasets (WGS84).
STATIONS = (
    ("central", "Central Pier", 22.288889, 114.155833, "Central Pier", "Central", None),
    ("hkg", "Chek Lap Kok", 22.309444, 113.921944, "Chek Lap Kok", "Chek Lap Kok", None),
    ("kai-tak", "Kai Tak", 22.309722, 114.213333, "Kai Tak", None, "RF028"),
    ("kings-park", "King's Park", 22.311944, 114.172778, "King's Park", None, None),
    ("observatory", "Observatory", 22.301944, 114.174167, None, None, "RF025"),
    ("waglan", "Waglan Island", 22.182222, 114.303333, "Waglan Island", "Waglan Island", "RF012"),
)
MAX_AGE = timedelta(minutes=45)


def numeric(value):
    if re.fullmatch(r"\d+(?:\.\d+)?", value):
        result = float(value)
        if math.isfinite(result):
            return f"{result:g}"
    return None


def read_observations(kind):
    with urlopen(SOURCES[kind], timeout=12) as response:
        payload = response.read(512 * 1024 + 1)
    if len(payload) > 512 * 1024:
        raise ValueError("response too large")
    data = payload.decode("utf-8-sig")
    if kind == "rain":
        sample = json.loads(data)
        stamp = datetime.fromisoformat(sample["obsTime"])
        return {
            item["automaticWeatherStationID"]: (stamp, f"Past hour rain {amount} mm")
            for item in sample["hourlyRainfall"] if (amount := numeric(str(item["value"]))) is not None
        }

    readings = {}
    rows = csv.reader(io.StringIO(data))
    next(rows, None)
    for row in rows:
        if len(row) < (4 if kind == "wind" else 3) or not re.fullmatch(r"\d{12}", row[0].strip()):
            continue
        stamp = datetime.strptime(row[0].strip(), "%Y%m%d%H%M").replace(tzinfo=HKT)
        station, value = (part.strip() for part in row[1:3])
        if kind == "visibility" and re.fullmatch(r"\d+(?:\.\d+)?\s*(?:m|km)", value):
            readings[station] = (stamp, f"10-min visibility {value}")
        if kind == "wind" and (speed := numeric(row[3].strip())) is not None:
            gust = numeric(row[4].strip()) if len(row) > 4 else None
            direction = f" {value}" if re.fullmatch(r"[A-Za-z -]{1,20}", value) else ""
            text = f"10-min wind{direction} {speed} km/h"
            if gust is not None:
                text += f", gust {gust} km/h"
            readings[station] = (stamp, text)
    return readings


def cot_time(stamp):
    return stamp.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def cot_event(station, readings, now):
    key, name, lat, lon, *identifiers = station
    remarks = []
    expiries = []
    for kind, identifier in zip(SOURCES, identifiers):
        sample = readings.get(kind, {}).get(identifier)
        if sample is None:
            continue
        stamp, description = sample
        if stamp.tzinfo is None or not now - MAX_AGE < stamp <= now + timedelta(minutes=2):
            continue
        remarks.append(f"{description} at {stamp.astimezone(HKT):%Y-%m-%d %H:%M} HKT")
        expiries.append(stamp + MAX_AGE)
    if not remarks:
        return None
    event = Element("event", {
        "version": "2.0", "uid": f"golf-hk-weather-{key}", "type": "a-n-G-I",
        "how": "m-g", "time": cot_time(now), "start": cot_time(now),
        "stale": cot_time(min(min(expiries), now + timedelta(minutes=12))),
    })
    SubElement(event, "point", lat=str(lat), lon=str(lon), hae="9999999", ce="9999999", le="9999999")
    detail = SubElement(event, "detail")
    SubElement(detail, "contact", callsign=f"HKO WX {name}")
    SubElement(detail, "remarks").text = "Hong Kong Observatory — " + "; ".join(remarks)
    return tostring(event, encoding="utf-8") + b"\n"


def main():
    readings = {}
    with ThreadPoolExecutor(max_workers=len(SOURCES)) as executor:
        futures = {kind: executor.submit(read_observations, kind) for kind in SOURCES}
        for kind, operation in futures.items():
            try:
                readings[kind] = operation.result()
            except (OSError, ValueError, KeyError, TypeError) as error:
                print(f"HKO {kind} unavailable ({type(error).__name__})", flush=True)
    now = datetime.now(timezone.utc)
    payloads = [event for station in STATIONS if (event := cot_event(station, readings, now))]
    if not payloads:
        raise SystemExit("No recent HKO observations available")

    credentials = Path(os.environ.get("CREDENTIALS_DIRECTORY", "/etc/golf-hk-weather"))
    tls = ssl.create_default_context(cafile=str(credentials / "ca.pem"))
    tls.load_cert_chain(credentials / "client.pem", credentials / "client.key")
    with socket.create_connection(("127.0.0.1", 8089), timeout=8) as raw:
        with tls.wrap_socket(raw, server_hostname="47.238.64.250") as connection:
            for event in payloads:
                connection.sendall(event)
            time.sleep(1)
    print(f"Published {len(payloads)} HKO station observations", flush=True)


if __name__ == "__main__":
    main()
