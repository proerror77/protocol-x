# RNS-05 / RNS-09 / RNS-11: TOC Backend, Offline Sync & TAK Gateway Implementation

**Branch:** `rns/backend-complete`  
**Contract Reference:** [docs/RNS-TOC-CONTRACT.md](RNS-TOC-CONTRACT.md) (`protocol-x.rns.envelope/v1`)  

---

## 1. Implemented Capabilities

### 1.1 RNS-05: IFAC Derivation, Device Binding & Immediate Revocation (`app/field_nearby.py`, `app/field.py`)
- **RNS Identity Provisioning (`POST /api/field/events/{event_id}/nearby`):**
  - Extended `NearbyDevice` with optional `rns_identity: RnsIdentityInput` (`dest_hash`, `identity_hash`, `ed25519_pub`, `x25519_pub`).
  - Persists active device bindings in `field_rns_devices` (`PRIMARY KEY(token_hash)`, indexed on `(dest_hash, revoked_at)`), rejecting duplicate `dest_hash` claims across active devices (`409 Conflict`) and mismatched `identity_hash` / raw 32-byte keys (`422`).
  - Derives deterministic per-event/channel IFAC parameters via `derive_channel_ifac(event_id, channel_id, secret)` (`network_name = "px-ifac-<24hex>"`, `passphrase`, `generation = sha256(secret)`).
  - Embeds `rns_dest_hash`, `network_id`, and optional `rns_ed25519_pub` / `rns_x25519_pub` into the P-256 ECDSA authority-signed `certificate` claims while preserving backward compatibility for legacy `recipients` entries (`rns_peers` is returned separately when `rns_identity` is supplied).
- **Immediate Revocation & Key Rotation (`rotate_revoked_channels`):**
  - Hooked into `Field.refresh_access`, `Field.issue_session`, `revoke_person`, `set_account_status(suspended=True)`, and `logout`.
  - Marks `field_rns_devices.revoked_at`, rotates `field_nearby_channels.secret` whenever any prior `token_hash` leaves a channel's active audience, and records a revocation entry in `field_rns_revocations` (`min_generation`, `revoked_actor`, `revoked_device_hash`, `revoked_dest_hash`, `reason`).
  - Exposed `GET /api/field/events/{event_id}/nearby/revocations` for mesh CRL sync.

### 1.2 RNS-09 / RNS-11: Unified Envelope Ingress & Causal Reconciliation (`app/rns_envelopes.py`, `app/actions.py`, `app/arrival_desk.py`)
- **Ingress Endpoints:**
  - `POST /api/rns/v1/envelopes`: single `EnvelopeV1` (`protocol-x.rns.envelope/v1`) ingestion.
  - `POST /api/rns/v1/envelopes/batch`: causal batch ingestion (`EnvelopeBatchInput`), sorting envelopes by `(payload.client_seq, created_at)` and returning per-item outcomes (`200` committed/deduplicated vs `409` conflict vs rejected) without aborting independent items.
- **3-Tier Deduplication & Zero Duplicate Business Actions:**
  - `rns_envelopes` (`PRIMARY KEY(event_id, envelope_id)`) deduplicates identical `envelope_id` + `fingerprint` replays and rejects mismatched fingerprints with `409`.
  - Extracted shared `execute_task_action` (`app/actions.py`) used by both `POST /api/events/{event_id}/actions` and `POST /api/rns/v1/envelopes` so HTTP and RNS paths share the exact `receipts` `UNIQUE(event_id, idempotency_key)` + payload `fingerprint` check (`0` duplicate state mutations across 5G/Wi-Fi/RNS failover).
  - Extended `StageInput` (`app/arrival_desk.py`) with optional `idempotency_key: UUID4` persisted in `receipts` (`"arrival-stage:<uuid>"`) before checking `expected_version`.
  - Enriches `409` version-drift errors inside `EnvelopeProcessor` with `current_version`, `current_status`, `current_progress`, and `allowed_actions` for deterministic client reconciliation.
- **Originator Authentication for Relayed Envelopes:**
  - Bound devices submitting their own envelopes authenticate via `pxd-` Bearer tokens.
  - Relayed envelopes (via dispatcher/gateway or verified LXMF transport) validate originator `field_people` + non-suspended status and verify `envelope.auth` (`ecdsa_p256` against authority-signed `certificate` or `ed25519_rns` against `field_rns_devices.ed25519_pub`).
- **Channel Messages, Voice Notes & Attachments:**
  - Persists channel/private/voice-note envelopes into `field_channel_messages` (and `VoiceArchive` `voice_transmissions` for `voice_note` without auto-playing as live PTT), validates `task_photo` (`field_photos`) and `channel_photo`/`channel_video` (`field_channel_attachments`), and bumps `channel_feed_versions` (`ops`) via `bump_feed` (`app/channel_feed.py`) so `/changes` wakes immediately. `ptt_agent.channel_updates` surfaces `channel_messages`.

### 1.3 RNS-11: Python RNS + LXMF Gateway & TAK CoT Bridge (`app/rns_gateway.py`, `app/tak.py`)
- **Private IFAC RNS/LXMF Gateway (`RnsGateway`):**
  - Runs official Python `rns==1.6.1` + `lxmf==1.3.1` with `share_instance = No`, `0` public interfaces, and private `TCPServerInterface` / `TCPClientInterface` configured with `network_name` and `passphrase` derived from `derive_channel_ifac`.
  - Accepts both canonical `EnvelopeV1` JSON (`FIELD_CUSTOM_TYPE = 0xFB`, `FIELD_CUSTOM_DATA = 0xFC`) and Android `RnsEnvelopeCodec` compact title/content + `FIELD_FILE_ATTACHMENTS` (`0x05`).
  - Validates `lxm.signature_validated` and active non-revoked `field_rns_devices` membership, ingests into `EnvelopeProcessor`, projects eligible `location` / `ptt_mic_lifecycle` envelopes to OpenTAKServer via `app/tak.py:record_delivery`, and sends a signed `delivery_receipt` back over LXMF.
  - Supports downward TOC/TAK -> field RNS dispatch (`dispatch_to_field`, `POST /api/rns/v1/gateway/dispatch`, `GET /api/rns/v1/gateway/status`) tracked in `rns_outbound_deliveries`.
- **Bidirectional TAK CoT Extensions (`app/tak.py`):**
  - Added `PEER_PLI_TYPE` (`a-f-G-U-C`, `OpsPeer-{event_id}-{actor}`) and `TASK_MARKER_TYPE` (`b-m-p-s-m`, `OpsTask-{event_id}-{task_id}`) CoT projection and readback checks.
  - Added `ingest_inbound_cot` (`POST /api/events/{event_id}/tak/inbound`, `GET /api/events/{event_id}/tak/deliveries`) with echo/loop suppression (`is_echo_cot` checking `sender_uid`, `OpsPoint-`/`OpsPtt-`/`OpsPeer-`/`OpsTask-` prefixes, and `tak_deliveries.cot_uid`) and deterministic `uuid5` `envelope_id` deduplication in `tak_inbound_events`.

---

## 2. Verification Environment & Commands

Isolated environment (`.venv-rns-backend`, gitignored):
```sh
uv venv .venv-rns-backend --python 3.12
uv pip install --python .venv-rns-backend/bin/python -r requirements.txt -r requirements-dev.txt -r requirements-pilot.txt pytest rns==1.6.1 lxmf==1.3.1
```

Test commands:
```sh
cd /Users/proerror/Documents/toc/protocol-x-rns-backend
.venv-rns-backend/bin/python -m unittest discover -s tests -p "test_*.py"
.venv-rns-backend/bin/python -m pytest -q
```
