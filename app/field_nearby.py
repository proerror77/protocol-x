"""Issue short-lived nearby & RNS/IFAC transport credentials to bound work-channel members."""
import base64
import binascii
import hashlib
import json
import secrets
from datetime import datetime, timezone
from uuid import uuid4

from cryptography.exceptions import UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Depends, HTTPException, Response
from pydantic import Field

from .field import stamp
from .models import StrictModel
from .store import dump


class RnsIdentityInput(StrictModel):
    dest_hash: str = Field(pattern=r"^[0-9a-f]{32}$")
    identity_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{32}$")
    ed25519_pub: str | None = Field(default=None, min_length=1, max_length=128)
    x25519_pub: str | None = Field(default=None, min_length=1, max_length=128)


class NearbyDevice(StrictModel):
    public_key: str = Field(min_length=1, max_length=150)
    agreement_key: str | None = Field(default=None, min_length=1, max_length=150)
    rns_identity: RnsIdentityInput | None = None


def canonical_p256(value):
    try:
        encoded = base64.b64decode(value, validate=True)
        key = serialization.load_der_public_key(encoded)
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
            raise ValueError
        canonical = base64.b64encode(key.public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode("ascii")
        if canonical != value:
            raise ValueError
        return canonical
    except (ValueError, binascii.Error, UnsupportedAlgorithm) as error:
        raise HTTPException(422, "附近通信设备公钥无效") from error


def canonical_raw_key32(value, label="RNS 公钥"):
    try:
        raw = base64.b64decode(value, validate=True)
        if len(raw) != 32:
            raise ValueError
        return base64.b64encode(raw).decode("ascii"), raw
    except (ValueError, binascii.Error) as error:
        raise HTTPException(422, f"{label}无效") from error


def derive_channel_ifac(event_id: str, channel_id: str, secret: str) -> dict:
    """Derive deterministic private RNS IFAC parameters from a channel secret."""
    generation = hashlib.sha256(secret.encode()).hexdigest()
    net_seed = f"rns-ifac-net|{event_id}|{channel_id}|{secret}".encode()
    key_seed = f"rns-ifac-key|{event_id}|{channel_id}|{secret}".encode()
    return {
        "network_name": "px-ifac-" + hashlib.sha256(net_seed).hexdigest()[:24],
        "passphrase": base64.urlsafe_b64encode(hashlib.sha256(key_seed).digest()).decode("ascii").rstrip("="),
        "generation": generation,
    }


def _active_channel_audiences(conn, *, event_id=None, channel_ids=None):
    now = stamp()
    clauses = ["c.kind='group'", "d.revoked_at IS NULL", "d.expires_at>?",
               "NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=d.person_id)"]
    params = [now]
    if event_id is not None:
        clauses.append("c.event_id=?")
        params.append(event_id)
    if channel_ids:
        placeholders = ",".join("?" for _ in channel_ids)
        clauses.append(f"c.id IN ({placeholders})")
        params.extend(channel_ids)
    rows = conn.execute(f"""
        SELECT c.id AS channel_id, c.event_id, d.token_hash
        FROM field_channels c
        JOIN field_members m ON m.channel_id=c.id
        JOIN field_devices d ON d.person_id=m.person_id
        WHERE {' AND '.join(clauses)}
        """, tuple(params)).fetchall()
    audiences = {}
    channel_events = {}
    for row in rows:
        audiences.setdefault(row["channel_id"], set()).add(row["token_hash"])
        channel_events[row["channel_id"]] = row["event_id"]
    return audiences, channel_events


def rotate_revoked_channels(conn, *, event_id=None, person_id=None, token_hash=None, reason="revoked"):
    """Revoke RNS device bindings and immediately rotate affected channel secrets/IFAC."""
    at = stamp()
    has_rns = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='field_rns_devices'"
    ).fetchone() if not conn.postgres else True
    if not has_rns:
        return []
    revoked_rows = []
    if token_hash is not None:
        revoked_rows = conn.execute(
            "SELECT token_hash, person_id, dest_hash FROM field_rns_devices WHERE token_hash=? AND revoked_at IS NULL",
            (token_hash,),
        ).fetchall()
        conn.execute(
            "UPDATE field_rns_devices SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
            (at, token_hash),
        )
    elif person_id is not None:
        revoked_rows = conn.execute(
            "SELECT token_hash, person_id, dest_hash FROM field_rns_devices WHERE person_id=? AND revoked_at IS NULL",
            (person_id,),
        ).fetchall()
        conn.execute(
            "UPDATE field_rns_devices SET revoked_at=? WHERE person_id=? AND revoked_at IS NULL",
            (at, person_id),
        )

    channel_rows = conn.execute("SELECT channel_id, secret, audience FROM field_nearby_channels").fetchall()
    if not channel_rows:
        return []
    existing_by_channel = {
        row["channel_id"]: (row["secret"], set(json.loads(row["audience"])))
        for row in channel_rows
    }
    target_channels = None
    if event_id is not None:
        target_channels = {
            row["id"] for row in conn.execute(
                "SELECT id FROM field_channels WHERE event_id=? AND kind='group'", (event_id,)
            ).fetchall()
        }
    elif person_id is not None:
        target_channels = {
            row["channel_id"] for row in conn.execute(
                "SELECT DISTINCT c.id AS channel_id FROM field_channels c LEFT JOIN field_members m ON m.channel_id=c.id "
                "WHERE c.kind='group' AND (m.person_id=? OR c.id IN (SELECT channel_id FROM field_nearby_channels))",
                (person_id,),
            ).fetchall()
        }
    elif token_hash is not None:
        target_channels = {
            ch for ch, (_, aud) in existing_by_channel.items() if token_hash in aud
        }

    audiences, channel_events = _active_channel_audiences(conn, event_id=event_id, channel_ids=target_channels)
    # Include previously provisioned channels in scope that may now have 0 active members.
    scoped_channels = set(audiences)
    if target_channels:
        scoped_channels |= (target_channels & set(existing_by_channel))
    elif event_id is None and person_id is None and token_hash is None:
        scoped_channels |= set(existing_by_channel)

    revoked_dest_by_token = {row["token_hash"]: row["dest_hash"] for row in revoked_rows}
    rotated_events = []
    for channel_id in sorted(scoped_channels):
        if channel_id not in existing_by_channel:
            continue
        prev_secret, prev_audience = existing_by_channel[channel_id]
        new_audience = audiences.get(channel_id, set())
        removed = prev_audience - new_audience
        if not removed:
            continue
        new_secret = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO field_nearby_channels VALUES (?,?,?) "
            "ON CONFLICT(channel_id) DO UPDATE SET secret=excluded.secret,audience=excluded.audience",
            (channel_id, new_secret, dump(sorted(new_audience))),
        )
        ch_event = channel_events.get(channel_id)
        if ch_event is None:
            row = conn.execute("SELECT event_id FROM field_channels WHERE id=?", (channel_id,)).fetchone()
            ch_event = row["event_id"] if row else (event_id or "")
        min_gen = hashlib.sha256(new_secret.encode()).hexdigest()
        for removed_hash in sorted(removed):
            dev = conn.execute(
                "SELECT person_id FROM field_devices WHERE token_hash=?", (removed_hash,)
            ).fetchone()
            rns_dev = conn.execute(
                "SELECT person_id, dest_hash FROM field_rns_devices WHERE token_hash=?", (removed_hash,)
            ).fetchone()
            revoked_actor = (
                (rns_dev["person_id"] if rns_dev else None)
                or (dev["person_id"] if dev else None)
                or (person_id or "")
            )
            dest_hash = (rns_dev["dest_hash"] if rns_dev else None) or revoked_dest_by_token.get(removed_hash)
            rev_id = "rev-" + uuid4().hex
            conn.execute(
                "INSERT INTO field_rns_revocations VALUES (?,?,?,?,?,?,?,?,?)",
                (rev_id, ch_event, channel_id, revoked_actor, removed_hash, dest_hash, min_gen, reason, at),
            )
            rotated_events.append({
                "id": rev_id, "event_id": ch_event, "channel_id": channel_id,
                "revoked_actor": revoked_actor, "revoked_device_hash": removed_hash,
                "revoked_dest_hash": dest_hash, "min_generation": min_gen,
                "reason": reason, "issued_at": at,
            })
    return rotated_events


def register_field_nearby(app, field, identity):
    if not field.enabled:
        return
    store = field.store
    with store.connect(write=True) as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS field_nearby_authority (
                id TEXT PRIMARY KEY, private_key BLOB NOT NULL
            );
            CREATE TABLE IF NOT EXISTS field_nearby_channels (
                channel_id TEXT PRIMARY KEY, secret TEXT NOT NULL, audience TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS field_nearby_agreement (
                token_hash TEXT PRIMARY KEY, public_key TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS field_rns_devices (
                token_hash TEXT PRIMARY KEY, person_id TEXT NOT NULL, dest_hash TEXT NOT NULL,
                identity_hash TEXT, ed25519_pub TEXT, x25519_pub TEXT,
                bound_at TEXT NOT NULL, revoked_at TEXT
            );
            CREATE INDEX IF NOT EXISTS field_rns_dest ON field_rns_devices(dest_hash, revoked_at);
            CREATE TABLE IF NOT EXISTS field_rns_revocations (
                id TEXT PRIMARY KEY, event_id TEXT NOT NULL, channel_id TEXT NOT NULL,
                person_id TEXT NOT NULL, device_hash TEXT NOT NULL, dest_hash TEXT,
                min_generation TEXT NOT NULL, reason TEXT NOT NULL, issued_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS field_rns_revocations_event ON field_rns_revocations(event_id, issued_at DESC);
        """)
    with store.connect(write=True) as conn:
        row = conn.execute(
            "SELECT private_key FROM field_nearby_authority WHERE id='v1'"
        ).fetchone()
        if row is None:
            authority = ec.generate_private_key(ec.SECP256R1())
            encoded = authority.private_bytes(serialization.Encoding.DER,
                                              serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption())
            conn.execute("INSERT INTO field_nearby_authority VALUES ('v1',?)", (encoded,))
        else:
            authority = serialization.load_der_private_key(bytes(row["private_key"]), password=None)
    public_key = base64.b64encode(authority.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode("ascii")

    @app.post("/api/field/events/{event_id}/nearby")
    def provision_nearby(event_id: str, body: NearbyDevice, response: Response, user=Depends(identity)):
        actor = field.require_person(user)
        canonical = canonical_p256(body.public_key)
        agreement = canonical_p256(body.agreement_key) if body.agreement_key else None
        if agreement == canonical:
            raise HTTPException(422, "签名和消息加密必须使用不同公钥")

        rns_bound = None
        if body.rns_identity is not None:
            ed_b64, ed_raw = (
                canonical_raw_key32(body.rns_identity.ed25519_pub, "Ed25519 公钥")
                if body.rns_identity.ed25519_pub else (None, None)
            )
            x_b64, x_raw = (
                canonical_raw_key32(body.rns_identity.x25519_pub, "X25519 公钥")
                if body.rns_identity.x25519_pub else (None, None)
            )
            id_hash = body.rns_identity.identity_hash
            if id_hash and ed_raw and x_raw:
                expected_id = hashlib.sha256(x_raw + ed_raw).hexdigest()[:32]
                if expected_id != id_hash:
                    raise HTTPException(422, "RNS 身份哈希与公钥不匹配")
            rns_bound = {
                "dest_hash": body.rns_identity.dest_hash,
                "identity_hash": id_hash,
                "ed25519_pub": ed_b64,
                "x25519_pub": x_b64,
            }

        response.headers["Cache-Control"] = "private, no-store"
        now = datetime.now(timezone.utc)
        until = int(now.timestamp()) + 12 * 60 * 60
        with store.connect(write=True) as conn:
            if not conn.execute("""
                SELECT 1 FROM field_devices d WHERE d.token_hash=? AND d.person_id=?
                AND d.revoked_at IS NULL AND d.expires_at>?
                AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=d.person_id)
                """, (user["device"], actor, stamp())).fetchone():
                raise HTTPException(401, "设备绑定已失效，请重新绑定")
            if agreement:
                conn.execute("""
                    INSERT INTO field_nearby_agreement (token_hash,public_key) VALUES (?,?)
                    ON CONFLICT(token_hash) DO UPDATE SET public_key=excluded.public_key
                    WHERE field_nearby_agreement.public_key <> excluded.public_key
                    """, (user["device"], agreement))
            if rns_bound is not None:
                conflict = conn.execute("""
                    SELECT r.token_hash FROM field_rns_devices r
                    JOIN field_devices d ON d.token_hash=r.token_hash
                    WHERE r.dest_hash=? AND r.token_hash<>? AND r.revoked_at IS NULL
                    AND d.revoked_at IS NULL AND d.expires_at>?
                    """, (rns_bound["dest_hash"], user["device"], stamp())).fetchone()
                if conflict is not None:
                    raise HTTPException(409, "此 RNS 目标地址已绑定到其他活动设备")
                conn.execute("""
                    INSERT INTO field_rns_devices
                    (token_hash, person_id, dest_hash, identity_hash, ed25519_pub, x25519_pub, bound_at, revoked_at)
                    VALUES (?,?,?,?,?,?,?,NULL)
                    ON CONFLICT(token_hash) DO UPDATE SET
                        person_id=excluded.person_id,
                        dest_hash=excluded.dest_hash,
                        identity_hash=excluded.identity_hash,
                        ed25519_pub=excluded.ed25519_pub,
                        x25519_pub=excluded.x25519_pub,
                        bound_at=excluded.bound_at,
                        revoked_at=NULL
                    """, (user["device"], actor, rns_bound["dest_hash"], rns_bound["identity_hash"],
                          rns_bound["ed25519_pub"], rns_bound["x25519_pub"], stamp()))
            rows = conn.execute("""
                SELECT c.id, d.token_hash, p.id AS actor, p.name, nd.public_key AS agreement_key,
                       rd.dest_hash AS rns_dest_hash, rd.identity_hash AS rns_identity_hash,
                       rd.ed25519_pub AS rns_ed25519_pub, rd.x25519_pub AS rns_x25519_pub
                FROM field_channels c JOIN field_members own ON own.channel_id=c.id
                JOIN field_members m ON m.channel_id=c.id JOIN field_devices d ON d.person_id=m.person_id
                JOIN field_people p ON p.id=d.person_id
                LEFT JOIN field_nearby_agreement nd ON nd.token_hash=d.token_hash
                LEFT JOIN field_rns_devices rd ON rd.token_hash=d.token_hash AND rd.revoked_at IS NULL
                WHERE c.event_id=? AND c.kind='group' AND own.person_id=?
                AND d.revoked_at IS NULL AND d.expires_at>?
                AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=d.person_id)
                ORDER BY c.id,p.id,d.expires_at DESC,d.token_hash
                """, (event_id, actor, now.isoformat())).fetchall()
            audiences = {}
            recipients = {}
            rns_peers = {}
            for row in rows:
                audiences.setdefault(row["id"], set()).add(row["token_hash"])
                if agreement and row["actor"] != actor and row["agreement_key"]:
                    recipients.setdefault(row["id"], {}).setdefault(row["actor"], {
                        "actor": row["actor"], "name": row["name"], "key": row["agreement_key"],
                    })
                if rns_bound is not None and row["actor"] != actor and row["rns_dest_hash"]:
                    rns_peers.setdefault(row["id"], {}).setdefault(row["actor"], {
                        "actor": row["actor"],
                        "name": row["name"],
                        "dest_hash": row["rns_dest_hash"],
                        "identity_hash": row["rns_identity_hash"],
                        "ed25519_pub": row["rns_ed25519_pub"],
                        "x25519_pub": row["rns_x25519_pub"],
                    })
            credentials = []
            for channel, audience in audiences.items():
                row = conn.execute("SELECT secret,audience FROM field_nearby_channels WHERE channel_id=?",
                                   (channel,)).fetchone()
                previous = set(json.loads(row["audience"])) if row else set()
                secret = row["secret"] if row and previous <= audience else secrets.token_urlsafe(32)
                if row is None or audience != previous:
                    conn.execute("""
                        INSERT INTO field_nearby_channels VALUES (?,?,?)
                        ON CONFLICT(channel_id) DO UPDATE SET secret=excluded.secret,audience=excluded.audience
                        """, (channel, secret, dump(sorted(audience))))
                ifac = derive_channel_ifac(event_id, channel, secret)
                generation = ifac["generation"]
                claims = {
                    "channel": channel, "generation": generation, "key": canonical, "expires": until,
                    "actor": actor, "name": user["name"],
                }
                if agreement:
                    claims["agreement_key"] = agreement
                if rns_bound is not None:
                    claims["rns_dest_hash"] = rns_bound["dest_hash"]
                    claims["network_id"] = ifac["network_name"]
                    if rns_bound["ed25519_pub"]:
                        claims["rns_ed25519_pub"] = rns_bound["ed25519_pub"]
                    if rns_bound["x25519_pub"]:
                        claims["rns_x25519_pub"] = rns_bound["x25519_pub"]
                certificate = dump(claims).encode()
                signature = authority.sign(certificate, ec.ECDSA(hashes.SHA256()))
                credential = {
                    "channel": channel, "secret": secret, "expires": until,
                    "voice_enabled": field.voice_policy(conn, channel)["enabled"],
                    "ifac": ifac,
                    "certificate": base64.b64encode(certificate).decode("ascii") + "." +
                    base64.b64encode(signature).decode("ascii"),
                }
                if agreement:
                    credential["recipients"] = list(recipients.get(channel, {}).values())
                if rns_bound is not None:
                    credential["rns_peers"] = list(rns_peers.get(channel, {}).values())
                credentials.append(credential)
        result = {"authority": public_key, "channels": credentials}
        if rns_bound is not None:
            gateway = getattr(app.state, "rns_gateway", None)
            result["rns_gateway"] = {
                "dest_hash": gateway.lxmf_destination_hash_hex if gateway else None,
                "identity_hash": gateway.identity_hash_hex if gateway else None,
            }
        return result

    @app.get("/api/field/events/{event_id}/nearby/revocations")
    def nearby_revocations(event_id: str, response: Response, user=Depends(identity)):
        field.require_enabled()
        field.require_event(event_id, user)
        if user["role"] != "dispatcher":
            field.require_person(user)
        response.headers["Cache-Control"] = "private, no-store"
        with store.connect() as conn:
            rows = conn.execute("""
                SELECT id, event_id, channel_id, person_id AS revoked_actor,
                       device_hash AS revoked_device_hash, dest_hash AS revoked_dest_hash,
                       min_generation, reason, issued_at
                FROM field_rns_revocations
                WHERE event_id=?
                ORDER BY issued_at DESC, id DESC LIMIT 100
                """, (event_id,)).fetchall()
        return {"authority": public_key, "revocations": [dict(row) for row in rows]}
