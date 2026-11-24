"""Approved email verification for first-time field Passkey enrollment."""
import logging
import os
import secrets
import smtplib
import ssl
from dataclasses import dataclass
from email.message import EmailMessage

from fastapi import BackgroundTasks, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import Field, field_validator

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


@dataclass(frozen=True)
class EnrollmentMail:
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
        host = os.getenv("OPS_FIELD_SMTP_HOST", "").strip()
        username = os.getenv("OPS_FIELD_SMTP_USERNAME", "").strip()
        password = os.getenv("OPS_FIELD_SMTP_PASSWORD", "")
        sender = os.getenv("OPS_FIELD_SMTP_FROM", "").strip() or username
        port = int(os.getenv("OPS_FIELD_SMTP_PORT", "465"))
        if not all((host, username, password)) or not 1 <= port <= 65535:
            raise ValueError("Email enrollment requires an authenticated SMTP-over-TLS sender.")
        return cls(host, port, username, password, normalize_email(sender))

    def send(self, email, link):
        message = EmailMessage()
        message["From"] = self.sender
        message["To"] = email
        message["Subject"] = "Protocol X 首次登录"
        message.set_content(f"在 Android 手机上打开下面的链接，然后创建 Passkey。链接 10 分钟内有效。\n\n{link}")
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
        SELECT p.* FROM field_email_links v JOIN field_people p ON p.id=v.person_id
        JOIN field_login_emails e ON e.person_id=p.id
        WHERE v.token_hash=? AND v.expires_at>? AND {ELIGIBLE_PERSON}
        """, (digest(grant), stamp())).fetchone()
    if person is None:
        raise HTTPException(409, "登录链接已失效，请重新发送链接或使用已有的 Passkey")
    return person


def register_field_email(app, field, identity, mail):
    if mail is None:
        return
    store = field.store
    with store.connect(write=True) as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS field_email_links (
                token_hash TEXT PRIMARY KEY, person_id TEXT, request_ip TEXT NOT NULL,
                requested_at TEXT NOT NULL, expires_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS field_email_link_person ON field_email_links(person_id, requested_at);
            CREATE INDEX IF NOT EXISTS field_email_link_ip ON field_email_links(request_ip, expires_at);
        """)
        at = stamp()
        conn.execute("UPDATE field_devices SET revoked_at=? WHERE auth_method<>'passkey' AND revoked_at IS NULL", (at,))
        conn.execute("UPDATE field_invites SET used_at=? WHERE used_at IS NULL", (at,))

    @app.get("/activate", include_in_schema=False)
    def activate():
        return HTMLResponse(
            '<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            '<title>登录 Protocol X</title><h1>登录 Protocol X</h1>'
            '<p>请在已安装 Protocol X 的 Android 手机上从邮件打开登录链接。</p></html>',
            headers={**NO_STORE, "Referrer-Policy": "no-referrer"},
        )

    @app.post("/api/field/events/{event_id}/people/{person_id}/login-email")
    def approve_email(event_id: str, person_id: str, body: ApprovedEmail, user=Depends(identity)):
        with store.connect(write=True) as conn:
            field.require_account_manager(conn, event_id, person_id, user)
            field.set_login_email(conn, person_id, body.email)
        return JSONResponse({"login_email": body.email}, headers=NO_STORE)

    @app.post("/api/field/email/request", status_code=202)
    def request_link(body: EmailInput, request: Request, tasks: BackgroundTasks):
        ip = request.client.host if request.client else ""
        token = secrets.token_urlsafe(32)
        with store.connect(write=True) as conn:
            at = stamp()
            conn.execute("DELETE FROM field_email_links WHERE expires_at<=?", (at,))
            rate = conn.execute("SELECT count(*) AS n FROM field_email_links WHERE request_ip=? AND expires_at>?",
                                (ip, at)).fetchone()["n"]
            if rate >= 60:
                raise HTTPException(429, "登录链接请求过于频繁，请稍后再试")
            person = conn.execute(f"""
                SELECT p.id FROM field_login_emails e JOIN field_people p ON p.id=e.person_id
                WHERE e.email=? AND {ELIGIBLE_PERSON}
                """, (body.email,)).fetchone()
            if person and conn.execute("SELECT 1 FROM field_email_links WHERE person_id=? AND requested_at>?",
                                       (person["id"], expiry(-1))).fetchone():
                person = None
            conn.execute("INSERT INTO field_email_links VALUES (?,?,?,?,?)",
                         (digest(token), person["id"] if person else None, ip, at, expiry(10)))
        if person:
            tasks.add_task(mail.send, body.email, f"https://{app.state.passkey_rp_id}/activate#{token}")
        return JSONResponse({"status": "accepted"}, status_code=202, headers=NO_STORE)
