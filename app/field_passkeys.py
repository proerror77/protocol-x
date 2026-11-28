"""WebAuthn registration and sign-in against the existing field roster."""
import base64
import ipaddress
import json
import os
import re
import secrets
from uuid import uuid4

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import Field, UUID4, field_validator

from .field import InviteInput, client_ip, digest, expiry, stamp
from .field_email import email_grant_person
from .models import StrictModel

ANDROID_PACKAGE = "com.mandonothing.ops"
CACHE_HEADERS = {"Cache-Control": "no-store"}


class Ceremony(StrictModel):
    ceremony: UUID4
    credential: dict
    invite_code: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{22,128}$")

    @field_validator("credential")
    @classmethod
    def credential_size(cls, value):
        if len(json.dumps(value)) > 24000:
            raise ValueError("Passkey 响应过大")
        return value


def register_field_passkeys(app, field):
    rp_id = os.getenv("OPS_PASSKEY_RP_ID", "").strip().lower()
    certificate = os.getenv("OPS_PASSKEY_ANDROID_SHA256", "").replace(":", "").strip().upper()
    if field.email_enrollment and not rp_id:
        raise ValueError("Email enrollment requires a Passkey RP ID and Android signing certificate.")
    if rp_id or certificate:
        try:
            ipaddress.ip_address(rp_id)
        except ValueError:
            pass
        else:
            raise ValueError("Passkey RP ID must be a DNS hostname.")
        label = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        if (not field.enabled or len(rp_id) > 253 or not re.fullmatch(rf"(?:{label}\.)+{label}", rp_id)
                or not re.fullmatch(r"[0-9A-F]{64}", certificate)):
            raise ValueError("Configure a field pilot, a DNS Passkey RP ID and the approved Android signing SHA-256.")
    app.state.passkey_rp_id = rp_id

    @app.get("/api/field/passkeys")
    def status():
        return JSONResponse({"enabled": bool(rp_id), "email_enrollment": field.email_enrollment}, headers=CACHE_HEADERS)

    if not rp_id:
        return

    from webauthn import (generate_authentication_options, generate_registration_options, options_to_json,
                          verify_authentication_response, verify_registration_response)
    from webauthn.helpers import base64url_to_bytes, bytes_to_base64url
    from webauthn.helpers.exceptions import WebAuthnException
    from webauthn.helpers.structs import AuthenticatorSelectionCriteria, ResidentKeyRequirement, UserVerificationRequirement

    android_origin = "android:apk-key-hash:" + bytes_to_base64url(bytes.fromhex(certificate))
    store = field.store
    with store.connect(write=True) as conn:
        conn.execute("""CREATE TABLE IF NOT EXISTS field_passkey_ceremonies (
            id TEXT PRIMARY KEY, operation TEXT NOT NULL, address TEXT NOT NULL, challenge TEXT NOT NULL,
            invite_hash TEXT, expires_at TEXT NOT NULL, used_at TEXT
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS field_passkey_ceremony_address ON field_passkey_ceremonies(address)")

    @app.get("/.well-known/assetlinks.json")
    def assetlinks():
        fingerprint = ":".join(certificate[i:i + 2] for i in range(0, len(certificate), 2))
        return [{"relation": ["delegate_permission/common.get_login_creds", "delegate_permission/common.handle_all_urls"],
                 "target": {"namespace": "android_app", "package_name": ANDROID_PACKAGE,
                            "sha256_cert_fingerprints": [fingerprint]}}]

    def begin(conn, request, operation, challenge, options, invite_hash=None):
        address = client_ip(request)
        conn.execute("DELETE FROM field_passkey_ceremonies WHERE expires_at<=?", (stamp(),))
        count = conn.execute("SELECT count(*) AS n FROM field_passkey_ceremonies WHERE address=?", (address,)).fetchone()
        if count["n"] >= 40:
            raise HTTPException(429, "请求过多，请稍后再试")
        ceremony = str(uuid4())
        conn.execute("""INSERT INTO field_passkey_ceremonies(id,operation,address,challenge,invite_hash,expires_at)
                        VALUES (?,?,?,?,?,?)""", (ceremony, operation, address, bytes_to_base64url(challenge),
                                                  invite_hash, expiry(4)))
        return JSONResponse({"ceremony": ceremony, "options": json.loads(options_to_json(options))}, headers=CACHE_HEADERS)

    def challenge_for(conn, body, operation, invite_hash):
        row = conn.execute("""SELECT * FROM field_passkey_ceremonies WHERE id=? AND operation=? AND used_at IS NULL
                              AND expires_at>?""", (str(body.ceremony), operation, stamp())).fetchone()
        if not row or row["invite_hash"] != invite_hash:
            raise HTTPException(409, "此登录操作已失效，请重试")
        return base64url_to_bytes(row["challenge"])

    def consume(conn, body):
        conn.execute("UPDATE field_passkey_ceremonies SET used_at=? WHERE id=?", (stamp(), str(body.ceremony)))

    def registration_person(conn, code):
        return email_grant_person(conn, code) if field.email_enrollment else field.available_invite(conn, code)

    @app.post("/api/field/passkeys/register/options")
    def register_options(body: InviteInput, request: Request):
        with store.connect(write=True) as conn:
            person = registration_person(conn, body.code)
            challenge = secrets.token_bytes(32)
            options = generate_registration_options(
                rp_id=rp_id, rp_name="Protocol X", user_name=person["callsign"], user_id=person["id"].encode(),
                user_display_name=person["name"], challenge=challenge,
                authenticator_selection=AuthenticatorSelectionCriteria(
                    resident_key=ResidentKeyRequirement.REQUIRED, user_verification=UserVerificationRequirement.REQUIRED),
            )
            return begin(conn, request, "register", challenge, options, digest(body.code))

    @app.post("/api/field/passkeys/register/verify")
    def register_verify(body: Ceremony):
        if not body.invite_code:
            raise HTTPException(422, "请先完成首次登录验证")
        invite_hash = digest(body.invite_code)
        with store.connect(write=True) as conn:
            person = registration_person(conn, body.invite_code)
            challenge = challenge_for(conn, body, "register", invite_hash)
            try:
                verified = verify_registration_response(credential=body.credential, expected_challenge=challenge,
                    expected_rp_id=rp_id, expected_origin=android_origin, require_user_verification=True)
            except (WebAuthnException, ValueError, TypeError, KeyError) as error:
                raise HTTPException(401, "Passkey 登记验证失败，请重试") from error
            credential_id = bytes_to_base64url(verified.credential_id)
            if conn.execute("SELECT 1 FROM field_passkeys WHERE id=?", (credential_id,)).fetchone():
                raise HTTPException(409, "这个 Passkey 已登记，请使用 Passkey 登录")
            conn.execute("INSERT INTO field_passkeys VALUES (?,?,?,?,?)",
                         (credential_id, person["id"], verified.credential_public_key, verified.sign_count, stamp()))
            if field.email_enrollment:
                conn.execute("DELETE FROM field_email_links WHERE person_id=?", (person["id"],))
            else:
                conn.execute("UPDATE field_invites SET used_at=? WHERE token_hash=?", (stamp(), invite_hash))
            consume(conn, body)
            return field.issue_session(conn, person, auth_method="passkey")

    @app.post("/api/field/passkeys/login/options")
    def login_options(request: Request):
        challenge = secrets.token_bytes(32)
        options = generate_authentication_options(
            rp_id=rp_id, challenge=challenge, user_verification=UserVerificationRequirement.REQUIRED)
        with store.connect(write=True) as conn:
            return begin(conn, request, "login", challenge, options)

    @app.post("/api/field/passkeys/login/verify")
    def login_verify(body: Ceremony):
        if body.invite_code is not None:
            raise HTTPException(422, "请重新打开 Passkey 登录")
        credential_id = body.credential.get("id")
        if not isinstance(credential_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{8,1500}", credential_id):
            raise HTTPException(401, "Passkey 无效或登录已停用")
        with store.connect(write=True) as conn:
            challenge = challenge_for(conn, body, "login", None)
            key = conn.execute("""SELECT k.*,p.name,p.callsign,p.role FROM field_passkeys k
                JOIN field_people p ON p.id=k.person_id
                WHERE k.id=? AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=p.id)
                AND EXISTS (SELECT 1 FROM field_members m JOIN field_channels c ON c.id=m.channel_id
                            WHERE m.person_id=p.id AND c.kind='group')""", (credential_id,)).fetchone()
            if key is None:
                raise HTTPException(401, "Passkey 无效或登录已停用")
            expected_handle = bytes_to_base64url(key["person_id"].encode())
            response = body.credential.get("response")
            if not isinstance(response, dict) or response.get("userHandle") != expected_handle:
                raise HTTPException(401, "Passkey 无法匹配人员名单")
            try:
                verified = verify_authentication_response(credential=body.credential, expected_challenge=challenge,
                    expected_rp_id=rp_id, expected_origin=android_origin, credential_public_key=key["public_key"],
                    credential_current_sign_count=key["sign_count"], require_user_verification=True)
            except (WebAuthnException, ValueError, TypeError, KeyError) as error:
                raise HTTPException(401, "Passkey 登录验证失败，请重试") from error
            conn.execute("UPDATE field_passkeys SET sign_count=? WHERE id=?", (verified.new_sign_count, credential_id))
            consume(conn, body)
            return field.issue_session(conn, {**dict(key), "id": key["person_id"]}, auth_method="passkey")
