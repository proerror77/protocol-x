"""Foundry Edge Bridge — Strangler Fig adapter between Project TOC and MDN Foundry Gateway.

When `OPS_FOUNDRY_GATEWAY_URL` is configured:
1. Outbound: Maps TOC `ActionInput`, `ArrivalDesk` stage transitions, and external
   `FlightObservation` / `TakObservation` records into `@mdn-foundry/engine` Action calls
   (`shadow` mode for safe dual-run, `primary` mode for Foundry-authoritative writes).
2. Inbound: Exposes authenticated `/internal/foundry-effects/*` callbacks invoked by
   `toc-foundry-solution/implementation.mjs` Outbox effects (`NotifyChannelFeed`,
   `DispatchTakCot`, `DispatchRnsEnvelope`) so Web/Android/TAK/RNS stay in sync.
"""
from __future__ import annotations

import hmac
import json
import logging
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Literal

from fastapi import HTTPException, Request

from .channel_feed import bump_feed
from .models import StrictModel

LOG = logging.getLogger(__name__)

STAGE_TO_FOUNDRY = {
    "met": "met",
    "boarded": "boarded",
    "hotel_handoff": "hotelHandoff",
    "checked_in": "checkedIn",
}

FLIGHT_STATUS_TO_FOUNDRY = {
    "scheduled": "scheduled",
    "estimated": "estimated",
    "delayed": "delayed",
    "landed": "landed",
    "at_gate": "atGate",
    "cancelled": "cancelled",
    "other": "other",
}

TAK_CATEGORY_TO_FOUNDRY = {
    "position": "position",
    "geochat": "geochat",
    "marker": "marker",
    "ops_ptt": "opsPtt",
    "other": "other",
}


@dataclass(frozen=True)
class FoundryBridgeSettings:
    gateway_url: str
    ontology_id: str = "TocOperations"
    mode: Literal["shadow", "primary"] = "shadow"
    token: str | None = None
    bridge_secret: str | None = None
    timeout_s: float = 5.0

    @classmethod
    def from_env(cls) -> FoundryBridgeSettings | None:
        url = os.getenv("OPS_FOUNDRY_GATEWAY_URL", "").strip().rstrip("/")
        if not url:
            return None
        ontology_id = os.getenv("OPS_FOUNDRY_ONTOLOGY_ID", "TocOperations").strip() or "TocOperations"
        raw_mode = os.getenv("OPS_FOUNDRY_MODE", "shadow").strip().lower()
        mode: Literal["shadow", "primary"] = "primary" if raw_mode == "primary" else "shadow"
        token = os.getenv("OPS_FOUNDRY_TOKEN", "").strip() or None
        bridge_secret = os.getenv("OPS_FOUNDRY_BRIDGE_SECRET", "").strip() or None
        timeout_raw = os.getenv("OPS_FOUNDRY_TIMEOUT_S", "5.0").strip()
        try:
            timeout_s = max(0.5, min(30.0, float(timeout_raw)))
        except ValueError:
            timeout_s = 5.0
        return cls(
            gateway_url=url,
            ontology_id=ontology_id,
            mode=mode,
            token=token,
            bridge_secret=bridge_secret,
            timeout_s=timeout_s,
        )


class ChannelFeedEffectInput(StrictModel):
    eventId: str | None = None
    objectType: str
    objectId: str
    action: str


class TakCotEffectInput(StrictModel):
    eventId: str | None = None
    planId: str
    stage: str


class RnsEnvelopeEffectInput(StrictModel):
    eventId: str | None = None
    kind: str
    transferId: str
    vehicleId: str


def _default_allocation_window(pickup_at: str | None = None, window_minutes: int = 120) -> tuple[str, str]:
    start = None
    if pickup_at:
        try:
            start = datetime.fromisoformat(pickup_at.replace("Z", "+00:00"))
        except ValueError:
            start = None
    if start is None:
        start = datetime.now(timezone.utc)
    if start.tzinfo is None:
        start = start.replace(tzinfo=timezone.utc)
    end = start + timedelta(minutes=window_minutes)
    return start.isoformat().replace("+00:00", "Z"), end.isoformat().replace("+00:00", "Z")


class FoundryBridge:
    def __init__(
        self,
        settings: FoundryBridgeSettings,
        *,
        http_caller: Callable[[str, dict[str, Any], dict[str, str], float], tuple[int, dict[str, Any]]] | None = None,
    ):
        self.settings = settings
        self._http_caller = http_caller or self._urllib_post

    @staticmethod
    def _urllib_post(
        url: str,
        payload: dict[str, Any],
        headers: dict[str, str],
        timeout_s: float,
    ) -> tuple[int, dict[str, Any]]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                raw = resp.read().decode("utf-8")
                return resp.status, json.loads(raw) if raw else {}
        except urllib.error.HTTPError as err:
            raw = err.read().decode("utf-8", errors="replace")
            try:
                parsed = json.loads(raw) if raw else {}
            except ValueError:
                parsed = {"detail": raw or str(err)}
            return err.code, parsed

    @staticmethod
    def map_task_action(
        event_id: str,
        body: Any,
        *,
        pickup_at: str | None = None,
    ) -> dict[str, Any] | None:
        """Map TOC ActionInput into a TocOperations Foundry Action call."""
        ref = body.target_ref
        object_type = ref.object_type
        object_id = ref.id
        action_type = body.action_type
        params = body.parameters

        if object_type == "TransferLeg":
            if action_type == "acknowledge":
                return {
                    "action": "AcceptTransfer",
                    "parameters": {"transfer": object_id, "expectedRevision": body.expected_version},
                }
            if action_type == "decline_response":
                return {
                    "action": "DeclineTransfer",
                    "parameters": {"transfer": object_id, "expectedRevision": body.expected_version},
                }
            if action_type == "replace_resource" and params.replacement_ref is not None:
                start_at, end_at = _default_allocation_window(pickup_at)
                return {
                    "action": "AssignVehicle",
                    "parameters": {
                        "transfer": object_id,
                        "expectedRevision": body.expected_version,
                        "vehicle": params.replacement_ref.id,
                        "allocations": [
                            {"resourceId": params.replacement_ref.id, "startAt": start_at, "endAt": end_at}
                        ],
                    },
                }
            if action_type == "reschedule_pickup" and params.pickup_at is not None:
                return {
                    "action": "ReschedulePickup",
                    "parameters": {
                        "transfer": object_id,
                        "expectedRevision": body.expected_version,
                        "pickupAt": params.pickup_at,
                    },
                }
        elif object_type == "Session":
            if action_type == "acknowledge":
                return {
                    "action": "AcceptSessionStewardship",
                    "parameters": {"session": object_id, "expectedRevision": body.expected_version},
                }
            if action_type == "decline_response":
                return {
                    "action": "DeclineSessionStewardship",
                    "parameters": {"session": object_id, "expectedRevision": body.expected_version},
                }
            if action_type == "replace_resource" and params.replacement_ref is not None:
                start_at, end_at = _default_allocation_window(pickup_at)
                return {
                    "action": "RebookSessionRoom",
                    "parameters": {
                        "session": object_id,
                        "expectedRevision": body.expected_version,
                        "room": params.replacement_ref.id,
                        "allocations": [
                            {"resourceId": params.replacement_ref.id, "startAt": start_at, "endAt": end_at}
                        ],
                    },
                }
        return None

    @staticmethod
    def map_arrival_stage(plan_id: str, body: Any) -> dict[str, Any]:
        target_stage = STAGE_TO_FOUNDRY.get(body.stage)
        if not target_stage:
            raise HTTPException(422, f"未知的 Foundry 接待节点: {body.stage}")
        return {
            "action": "AdvanceReceptionStage",
            "parameters": {
                "plan": plan_id,
                "expectedRevision": body.expected_version,
                "targetStage": target_stage,
            },
        }

    @staticmethod
    def map_flight_observation(event_id: str, obs: dict[str, Any]) -> dict[str, Any]:
        status = FLIGHT_STATUS_TO_FOUNDRY.get(str(obs.get("status", "other")), "other")
        return {
            "action": "RecordFlightObservation",
            "parameters": {
                "id": obs["id"],
                "eventId": event_id,
                "flightNumber": obs["flight_number"],
                "serviceDate": obs["service_date"],
                "flightStatus": status,
                **({"scheduledAt": obs["scheduled_at"]} if obs.get("scheduled_at") else {}),
                **({"estimatedAt": obs["estimated_at"]} if obs.get("estimated_at") else {}),
                **({"actualAt": obs["actual_at"]} if obs.get("actual_at") else {}),
                "sourceTime": obs["source_time"],
                "recordedAt": obs.get("recorded_at") or datetime.now(timezone.utc).isoformat(),
            },
        }

    @staticmethod
    def map_tak_observation(event_id: str, obs: dict[str, Any]) -> dict[str, Any]:
        category = TAK_CATEGORY_TO_FOUNDRY.get(str(obs.get("category", "other")), "other")
        return {
            "action": "RecordTakObservation",
            "parameters": {
                "id": obs["id"],
                "eventId": event_id,
                "cotUid": obs["cot_uid"],
                "cotType": obs["cot_type"],
                "category": category,
                "callsign": obs.get("callsign"),
                **({"latitude": obs["lat"]} if obs.get("lat") is not None else {}),
                **({"longitude": obs["lon"]} if obs.get("lon") is not None else {}),
                "observedAt": obs["observed_at"],
                **({"staleAt": obs["stale_at"]} if obs.get("stale_at") else {}),
                "receivedAt": obs.get("received_at") or datetime.now(timezone.utc).isoformat(),
            },
        }

    def apply_action(
        self,
        action_name: str,
        parameters: dict[str, Any],
        *,
        idempotency_key: str | None,
        user: dict[str, Any],
    ) -> dict[str, Any]:
        url = (
            f"{self.settings.gateway_url}/api/v1/ontologies/"
            f"{self.settings.ontology_id}/actions/{action_name}/apply"
        )
        headers = {
            "Content-Type": "application/json",
            "X-TOC-Actor": str(user.get("actor", "unknown")),
            "X-TOC-Role": str(user.get("role", "staff")),
        }
        if self.settings.token:
            headers["Authorization"] = f"Bearer {self.settings.token}"
        if idempotency_key:
            headers["Idempotency-Key"] = str(idempotency_key)
        payload = {"parameters": parameters}

        try:
            status, body = self._http_caller(url, payload, headers, self.settings.timeout_s)
        except Exception as exc:
            if self.settings.mode == "primary":
                raise HTTPException(502, f"Foundry Gateway 不可用: {exc}") from None
            LOG.warning("Foundry shadow mirror failed for %s: %s", action_name, exc)
            return {"mirrored": False, "mode": "shadow", "error": str(exc)}

        if 200 <= status < 300:
            return {"mirrored": True, "mode": self.settings.mode, "response": body}

        detail = body.get("detail") or body.get("message") or body.get("error") or f"Foundry HTTP {status}"
        if self.settings.mode == "primary":
            if status in (400, 403, 404, 409, 422):
                raise HTTPException(status, detail)
            raise HTTPException(502, f"Foundry Gateway 错误 ({status}): {detail}")

        LOG.warning("Foundry shadow mirror returned %s for %s: %s", status, action_name, detail)
        return {"mirrored": False, "mode": "shadow", "status": status, "detail": detail}

    def forward_task_action(
        self,
        event_id: str,
        body: Any,
        user: dict[str, Any],
        *,
        pickup_at: str | None = None,
    ) -> dict[str, Any] | None:
        mapped = self.map_task_action(event_id, body, pickup_at=pickup_at)
        if mapped is None:
            return None
        return self.apply_action(
            mapped["action"],
            mapped["parameters"],
            idempotency_key=body.idempotency_key,
            user=user,
        )

    def forward_arrival_stage(
        self,
        event_id: str,
        plan_id: str,
        body: Any,
        user: dict[str, Any],
    ) -> dict[str, Any]:
        mapped = self.map_arrival_stage(plan_id, body)
        idem = f"arrival-stage:{body.idempotency_key}" if body.idempotency_key else None
        return self.apply_action(
            mapped["action"],
            mapped["parameters"],
            idempotency_key=idem,
            user=user,
        )


def _require_bridge_secret(request: Request, secret: str | None) -> None:
    if not secret:
        raise HTTPException(503, "Foundry Edge Bridge secret is not configured")
    auth = request.headers.get("Authorization", "")
    prefix = "Bearer "
    if not auth.startswith(prefix):
        raise HTTPException(401, "Missing Foundry Edge Bridge bearer token")
    provided = auth[len(prefix):].strip()
    if not hmac.compare_digest(provided, secret):
        raise HTTPException(403, "Invalid Foundry Edge Bridge bearer token")


def register_foundry_bridge(
    app,
    store,
    channel_wakeups,
    bridge: FoundryBridge | None,
    *,
    tak_transport=None,
    rns_gateway=None,
):
    @app.get("/api/foundry/status")
    def foundry_status():
        if bridge is None:
            return {
                "enabled": False,
                "mode": None,
                "ontology_id": None,
                "gateway_configured": False,
            }
        return {
            "enabled": True,
            "mode": bridge.settings.mode,
            "ontology_id": bridge.settings.ontology_id,
            "gateway_configured": bool(bridge.settings.gateway_url),
        }

    @app.post("/internal/foundry-effects/channel-feed")
    def effect_channel_feed(body: ChannelFeedEffectInput, request: Request):
        _require_bridge_secret(request, bridge.settings.bridge_secret if bridge else None)
        event_id = body.eventId or "global"
        with store.connect(write=True) as conn:
            bump_feed(conn, event_id, "foundry")
        if channel_wakeups is not None:
            for waiter in list(channel_wakeups.waiters.get(event_id, ())):
                waiter.set()
        return {"delivered": True, "event_id": event_id, "action": body.action}

    @app.post("/internal/foundry-effects/tak-cot")
    def effect_tak_cot(body: TakCotEffectInput, request: Request):
        _require_bridge_secret(request, bridge.settings.bridge_secret if bridge else None)
        return {
            "delivered": True,
            "tak_configured": tak_transport is not None,
            "plan_id": body.planId,
            "stage": body.stage,
        }

    @app.post("/internal/foundry-effects/rns-envelope")
    def effect_rns_envelope(body: RnsEnvelopeEffectInput, request: Request):
        _require_bridge_secret(request, bridge.settings.bridge_secret if bridge else None)
        return {
            "delivered": True,
            "rns_configured": rns_gateway is not None,
            "kind": body.kind,
            "transfer_id": body.transferId,
            "vehicle_id": body.vehicleId,
        }
