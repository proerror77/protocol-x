"""Issue short-lived nearby transport credentials to bound work-channel members."""
import base64
import binascii
import hashlib
import json
import secrets
from datetime import datetime, timezone

from cryptography.exceptions import UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Depends, HTTPException, Response
from pydantic import Field

from .field import stamp
from .models import StrictModel
from .store import dump


class NearbyDevice(StrictModel):
    public_key: str = Field(min_length=1, max_length=150)
    agreement_key: str | None = Field(default=None, min_length=1, max_length=150)


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
            rows = conn.execute("""
                SELECT c.id, d.token_hash, p.id AS actor, p.name, nd.public_key AS agreement_key
                FROM field_channels c JOIN field_members own ON own.channel_id=c.id
                JOIN field_members m ON m.channel_id=c.id JOIN field_devices d ON d.person_id=m.person_id
                JOIN field_people p ON p.id=d.person_id
                LEFT JOIN field_nearby_agreement nd ON nd.token_hash=d.token_hash
                WHERE c.event_id=? AND c.kind='group' AND own.person_id=?
                AND d.revoked_at IS NULL AND d.expires_at>?
                AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=d.person_id)
                ORDER BY c.id,p.id,d.expires_at DESC,d.token_hash
                """, (event_id, actor, now.isoformat())).fetchall()
            audiences = {}
            recipients = {}
            for row in rows:
                audiences.setdefault(row["id"], set()).add(row["token_hash"])
                if agreement and row["actor"] != actor and row["agreement_key"]:
                    recipients.setdefault(row["id"], {}).setdefault(row["actor"], {
                        "actor": row["actor"], "name": row["name"], "key": row["agreement_key"],
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
                generation = hashlib.sha256(secret.encode()).hexdigest()
                claims = {
                    "channel": channel, "generation": generation, "key": canonical, "expires": until,
                    "actor": actor, "name": user["name"],
                }
                if agreement:
                    claims["agreement_key"] = agreement
                certificate = dump(claims).encode()
                signature = authority.sign(certificate, ec.ECDSA(hashes.SHA256()))
                credential = {
                    "channel": channel, "secret": secret, "expires": until,
                    "voice_enabled": field.voice_policy(conn, channel)["enabled"],
                    "certificate": base64.b64encode(certificate).decode("ascii") + "." +
                    base64.b64encode(signature).decode("ascii"),
                }
                if agreement:
                    credential["recipients"] = list(recipients.get(channel, {}).values())
                credentials.append(credential)
        return {"authority": public_key, "channels": credentials}
