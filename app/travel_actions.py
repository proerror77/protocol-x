"""Read-only transfers from a Protocol X reception plan to maps and rides."""

import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

from fastapi import HTTPException
from pydantic import Field

from .models import StrictModel

GOOGLE_ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes"
UBER = "https://m.uber.com/"
GOOGLE_KEY_FILE = "OPS_GOOGLE_MAPS_API_KEY_FILE"


class HongKongPoint(StrictModel):
    latitude: float = Field(ge=22.1, le=22.6)
    longitude: float = Field(ge=113.8, le=114.5)


def endpoints(plan):
    hotel = plan["hotel"]
    return (
        (f"香港國際機場，{plan['meeting_point']}", plan.get("meeting_coordinates")),
        (hotel["address"], hotel.get("coordinates")),
    )


def map_location(endpoint):
    address, point = endpoint
    return f"{point['latitude']},{point['longitude']}" if point else address


def travel_links(plan):
    pickup, dropoff = endpoints(plan)
    directions = "https://www.google.com/maps/dir/?" + urlencode({
        "api": 1, "origin": map_location(pickup), "destination": map_location(dropoff), "travelmode": "driving",
    })
    stay = plan["hotel"]
    hotel = "https://www.google.com/maps/search/?" + urlencode({"api": 1, "query": stay["name"] + " " + dropoff[0]})
    if len(hotel) > 2048:
        hotel = "https://www.google.com/maps/search/?" + urlencode({"api": 1, "query": dropoff[0]})
    uber = UBER
    has_pins = bool(pickup[1] and dropoff[1])
    if has_pins:
        def location(endpoint):
            address, point = endpoint
            return json.dumps({**point, "addressLine1": address}, ensure_ascii=False, separators=(",", ":"))

        parameters = {"pickup": location(pickup), "drop[0]": location(dropoff)}
        client_id = os.getenv("OPS_UBER_CLIENT_ID", "").strip()
        if client_id:
            parameters["client_id"] = client_id
        uber += "looking?" + urlencode(parameters)
    return {
        "google_directions": directions if len(directions) <= 2048 else None,
        "google_hotel": hotel,
        "google_estimate_configured": bool(os.getenv(GOOGLE_KEY_FILE, "").strip()),
        "uber": uber,
        "uber_prefilled": has_pins,
    }


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def google_waypoint(endpoint):
    address, point = endpoint
    return {"location": {"latLng": point}} if point else {"address": address}


def estimate_transfer(plan):
    key_path = os.getenv(GOOGLE_KEY_FILE, "").strip()
    try:
        key = Path(key_path).read_text(encoding="utf-8").strip() if key_path else ""
    except (OSError, UnicodeError):
        key = ""
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", key):
        raise HTTPException(503, "Google Maps 車程查詢尚未配置。")
    pickup, dropoff = endpoints(plan)
    request = Request(GOOGLE_ROUTES, method="POST", headers={
        "Content-Type": "application/json",
        "X-Goog-Api-Key": key,
        "X-Goog-FieldMask": "routes.distanceMeters,routes.duration",
    }, data=json.dumps({
        "origin": google_waypoint(pickup), "destination": google_waypoint(dropoff),
        "travelMode": "DRIVE", "regionCode": "HK",
    }).encode("utf-8"))
    try:
        with build_opener(NoRedirect()).open(request, timeout=7) as response:
            raw = response.read(65_537)
        if len(raw) > 65_536 or key.encode() in raw:
            raise ValueError("Invalid response")
        routes = json.loads(raw).get("routes", [])
        if routes == []:
            raise HTTPException(422, "Google Maps 未找到車路，請核對會合點與酒店地址。")
        route = routes[0]
        distance = route.get("distanceMeters", 0)
        duration = re.fullmatch(r"(\d{1,8})(?:\.(\d{1,9}))?s", route["duration"])
        if type(distance) is not int or distance < 0 or not duration:
            raise ValueError("Invalid route")
        seconds = int(duration[1])
        if duration[2] and int(duration[2]):
            seconds += 1
    except HTTPError as error:
        error.close()
        if error.code == 429:
            raise HTTPException(503, "Google Maps 車程查詢達到頻率或用量限制。") from None
        raise HTTPException(502, "Google Maps 車程查詢失敗。") from None
    except (URLError, OSError, ValueError, TypeError, AttributeError, KeyError, IndexError):
        raise HTTPException(502, "Google Maps 未返回可用車程。") from None
    return {
        "provider": "Google Maps", "distance_meters": distance, "duration_seconds": seconds,
        "observed_at": datetime.now(timezone.utc).isoformat(),
    }
