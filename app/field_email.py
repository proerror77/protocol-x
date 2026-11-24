"""Approved email verification for first-time field Passkey enrollment."""
import hmac
import logging
import os
import secrets
import smtplib
import ssl
from dataclasses import dataclass
from email.message import EmailMessage
from uuid import uuid4

from fastapi import BackgroundTasks, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import Field, UUID4, field_validator

from .field import digest, expiry, normalize_email, stamp
from .models import StrictModel

LOG = logging.getLogger(__name__)
NO_STORE = {"Cache-Control": "no-store"}


class EmailInput(StrictModel):
    email: str = Field(max_length=254)
    _normalize = field_validator("email")(normalize_email)


class ApprovedEmail(StrictModel):
    email: str | None = Field(max_length=254)

    _normalize = field_validator("email")(normalize_email)


class EmailVerification(StrictModel):
    request_id: UUID4
    code: str = Field(pattern=r"^[0-9]{8}$")


@dataclass(frozen=True)
class EnrollmentMail:
    secret: bytes
    host: str
    port: int
    username: str
    password: str
    sender: str

    @classmethod
    def from_env(cls):
        enabled = os.getenv("OPS_FIELD_EMAIL_ENROLLMENT", "false").strip().lower()
        if enabled not in {"true", "false"}:
            raise ValueError("OPS_FIELD_EMAIL_ENROLLMENT must be true or false.")
        if enabled == "false":
            return None
        secret = os.getenv("OPS_FIELD_EMAIL_OTP_SECRET", "").encode()
        host = os.getenv("OPS_FIELD_SMTP_HOST", "").strip()
        username = os.getenv("OPS_FIELD_SMTP_USERNAME", "").strip()
        password = os.getenv("OPS_FIELD_SMTP_PASSWORD", "")
        sender = os.getenv("OPS_FIELD_SMTP_FROM", "").strip() or username
        port = int(os.getenv("OPS_FIELD_SMTP_PORT", "465"))
        if len(secret) < 32 or not all((host, username, password)) or not 1 <= port <= 65535:
            raise ValueError("Email enrollment requires an OTP secret and an authenticated SMTP-over-TLS sender.")
        return cls(secret, host, port, username, password, normalize_email(sender))

    def hash_value(self, purpose, value):
        return hmac.digest(self.secret, f"{purpose}:{value}".encode(), "sha256").hex()

    def send(self, email, code):
        message = EmailMessage()
        message["From"] = self.sender
        message["To"] = email
        message["Subject"] = "Protocol X 首次登录验证码"
        message.set_content(f"验证码为 {code}，10 分钟内有效。\n\n请在 Protocol X 中输入验证码并创建 Passkey。")
        try:
            with smtplib.SMTP_SSL(self.host, self.port, timeout=8, context=ssl.create_default_context()) as smtp:
                smtp.login(self.username, self.password)
                smtp.send_message(message)
        except (OSError, smtplib.SMTPException) as error:
            LOG.warning("Protocol X enrollment email delivery failed (%s)", type(error).__name__)


# Every step checks that the same person still has project access and remains eligible to enroll.
ELIGIBLE_PERSON = """
    NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=p.id)
    AND NOT EXISTS (SELECT 1 FROM field_passkeys k WHERE k.person_id=p.id)
    AND EXISTS (SELECT 1 FROM field_members m JOIN field_channels c ON c.id=m.channel_id
                WHERE m.person_id=p.id AND c.kind='group')
"""


def email_grant_person(conn, grant):
    person = conn.execute(f"""
        SELECT p.* FROM field_email_codes v JOIN field_people p ON p.id=v.person_id
        JOIN field_login_emails e ON e.person_id=p.id
        WHERE v.grant_hash=? AND v.grant_expires_at>? AND {ELIGIBLE_PERSON}
        """, (digest(grant), stamp())).fetchone()
    if person is None:
        raise HTTPException(409, "邮箱验证已失效，请重新验证或使用已有的 Passkey")
    return person


def register_field_email(app, field, identity, mail):
    if mail is None:
        return
    store = field.store
    with store.connect(write=True) as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS field_email_codes (
                id TEXT PRIMARY KEY, person_id TEXT, address_key TEXT NOT NULL, request_ip TEXT NOT NULL,
                requested_at TEXT NOT NULL, expires_at TEXT NOT NULL, code_hash TEXT, attempts INTEGER NOT NULL DEFAULT 0,
                grant_hash TEXT UNIQUE, grant_expires_at TEXT
            );
            CREATE INDEX IF NOT EXISTS field_email_code_address ON field_email_codes(address_key, requested_at);
            CREATE INDEX IF NOT EXISTS field_email_code_ip ON field_email_codes(request_ip, expires_at);
        """)
        at = stamp()
        conn.execute("UPDATE field_devices SET revoked_at=? WHERE auth_method<>'passkey' AND revoked_at IS NULL", (at,))
        conn.execute("UPDATE field_invites SET used_at=? WHERE used_at IS NULL", (at,))

    @app.post("/api/field/events/{event_id}/people/{person_id}/login-email")
    def approve_email(event_id: str, person_id: str, body: ApprovedEmail, user=Depends(identity)):
        with store.connect(write=True) as conn:
            field.require_account_manager(conn, event_id, person_id, user)
            field.set_login_email(conn, person_id, body.email)
        return JSONResponse({"login_email": body.email}, headers=NO_STORE)

    @app.post("/api/field/email/request", status_code=202)
    def request_code(body: EmailInput, request: Request, tasks: BackgroundTasks):
        address_key = mail.hash_value("email", body.email)
        ip = request.client.host if request.client else ""
        request_id = str(uuid4())
        code = f"{secrets.randbelow(100_000_000):08d}"
        with store.connect(write=True) as conn:
            at = stamp()
            conn.execute("DELETE FROM field_email_codes WHERE expires_at<=? AND (grant_expires_at IS NULL OR grant_expires_at<=?)",
                         (at, at))
            rate = conn.execute("SELECT count(*) AS n FROM field_email_codes WHERE request_ip=? AND expires_at>?",
                                (ip, at)).fetchone()["n"]
            recent = conn.execute("SELECT 1 FROM field_email_codes WHERE address_key=? AND requested_at>?",
                                  (address_key, expiry(-1))).fetchone()
            if rate >= 60 or recent:
                raise HTTPException(429, "验证码请求过于频繁，请稍后再试")
            person = conn.execute(f"""
                SELECT p.id FROM field_login_emails e JOIN field_people p ON p.id=e.person_id
                WHERE e.email=? AND {ELIGIBLE_PERSON}
                """, (body.email,)).fetchone()
            conn.execute("""INSERT INTO field_email_codes
                (id,person_id,address_key,request_ip,requested_at,expires_at,code_hash) VALUES (?,?,?,?,?,?,?)""",
                         (request_id, person["id"] if person else None, address_key, ip, at, expiry(10),
                          mail.hash_value(request_id, code)))
        if person:
            tasks.add_task(mail.send, body.email, code)
        return JSONResponse({"request_id": request_id}, status_code=202, headers=NO_STORE)

    @app.post("/api/field/email/verify")
    def verify_code(body: EmailVerification):
        request_id = str(body.request_id)
        with store.connect(write=True) as conn:
            entry = conn.execute("""SELECT * FROM field_email_codes WHERE id=? AND expires_at>?
                                 AND attempts<5 AND code_hash IS NOT NULL""", (request_id, stamp())).fetchone()
            if entry is not None:
                conn.execute("UPDATE field_email_codes SET attempts=attempts+1 WHERE id=?", (request_id,))
                if hmac.compare_digest(entry["code_hash"], mail.hash_value(request_id, body.code)):
                    person = conn.execute(f"""
                        SELECT 1 FROM field_people p JOIN field_login_emails e ON e.person_id=p.id
                        WHERE p.id=? AND {ELIGIBLE_PERSON}
                        """, (entry["person_id"],)).fetchone()
                    if person:
                        grant = secrets.token_urlsafe(32)
                        conn.execute("""UPDATE field_email_codes SET code_hash=NULL,grant_hash=?,grant_expires_at=?
                                     WHERE id=?""", (digest(grant), expiry(10), request_id))
                        return JSONResponse({"enrollment_token": grant}, headers=NO_STORE)
        raise HTTPException(401, "验证码无效、已过期或已达尝试上限")
