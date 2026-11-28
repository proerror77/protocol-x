import hashlib
import json
from urllib.parse import urlsplit
from uuid import uuid4

import cbor2
import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware
from webauthn.helpers import bytes_to_base64url

from app.main import create_app
from app.field_email import EnrollmentMail
from app.field import expiry
from test_arrival_desk import DISPATCH, EVENT, ok

BASE = f"/api/field/events/{EVENT}"
PASSKEY = "/api/field/passkeys"
RP = "login.example.test"
CERT = "11" * 32


def configure(monkeypatch, rp=False):
    monkeypatch.setenv("OPS_PILOT_DISPATCHER_TOKEN", "D" * 40)
    monkeypatch.setenv("OPS_PILOT_STAFF_TOKEN", "S" * 40)
    if rp:
        monkeypatch.setenv("OPS_PASSKEY_RP_ID", RP)
        monkeypatch.setenv("OPS_PASSKEY_ANDROID_SHA256", CERT)


def configure_email(monkeypatch):
    for key, value in {
        "OPS_FIELD_EMAIL_ENROLLMENT": "true",
        "OPS_FIELD_SMTP_HOST": "smtp.example.test",
        "OPS_FIELD_SMTP_USERNAME": "sender@example.test",
        "OPS_FIELD_SMTP_PASSWORD": "fixture",
    }.items():
        monkeypatch.setenv(key, value)
    sent = {}
    monkeypatch.setattr(EnrollmentMail, "send", lambda _, address, link: sent.update({address: link}))
    return sent


def add_person(client, name, group, role="staff"):
    return ok(client.post(BASE + "/people", headers=DISPATCH,
                          json={"name": name, "callsign": name, "channel_ids": [group], "role": role}))


def group(client):
    return ok(client.post(BASE + "/channels", headers=DISPATCH, json={"name": "接待组"}))["id"]


def test_login_roster_revokes_session_and_outstanding_invites(tmp_path, monkeypatch):
    configure(monkeypatch)
    client = TestClient(create_app(tmp_path / "roster.db"))
    person = add_person(client, "接机员", group(client))
    invitation = {"code": person["binding_code"]}
    assert not ok(client.post("/api/field/invitation", json=invitation))["passkey_required"]
    bound = ok(client.post("/api/field/bind", json=invitation))
    assert client.post("/api/field/bind", json=invitation).status_code == 404
    session = {"Authorization": "Bearer " + bound["session_token"]}
    url = f"{BASE}/people/{person['actor']}"
    outstanding = ok(client.post(url + "/invite", headers=DISPATCH))["binding_code"]
    assert client.post(url + "/account-status", headers=session, json={"suspended": True}).status_code == 403
    assert ok(client.post(url + "/account-status", headers=DISPATCH, json={"suspended": True}))["login_status"] == "suspended"
    assert client.get("/api/bootstrap", headers=session).status_code == 401
    assert client.post("/api/field/invitation", json={"code": outstanding}).status_code == 404
    assert client.post(url + "/invite", headers=DISPATCH).status_code == 409
    roster = ok(client.get(BASE + "/directory", headers=DISPATCH))
    assert roster["people"][0]["login_status"] == "suspended"
    ok(client.post(url + "/account-status", headers=DISPATCH, json={"suspended": False}))
    assert client.post("/api/field/invitation", json={"code": outstanding}).status_code == 404
    assert ok(client.post(url + "/invite", headers=DISPATCH))["binding_code"] != outstanding


@pytest.mark.parametrize("email_enrollment", [False, True], ids=["invitation", "email"])
def test_passkey_registration_login_and_revocation_with_signed_assertions(tmp_path, monkeypatch, email_enrollment):
    path = tmp_path / "webauthn.db"
    configure(monkeypatch, rp=not email_enrollment)
    client = TestClient(create_app(path))
    person = add_person(client, "接机员", group(client))
    invite = person["binding_code"]
    if email_enrollment:
        legacy = ok(client.post("/api/field/bind", json={"code": invite}))["session_token"]
        url = f"{BASE}/people/{person['actor']}"
        invite = ok(client.post(url + "/invite", headers=DISPATCH))["binding_code"]
        configure(monkeypatch, rp=True)
        sent = configure_email(monkeypatch)
        client = TestClient(create_app(path))
        assert ok(client.get(PASSKEY))["email_enrollment"]
        assert client.get("/api/bootstrap", headers={"Authorization": f"Bearer {legacy}"}).status_code == 401
        assert client.get("/api/bootstrap", headers={"Authorization": "Bearer " + "S" * 40}).status_code == 401
        assert client.post(url + "/invite", headers=DISPATCH).status_code == 409
        assert client.post("/api/field/invitation", json={"code": invite}).status_code == 409
        assert client.post(PASSKEY + "/register/options", json={"code": invite}).status_code == 409
        assert client.post("/api/field/bind", json={"code": invite}).status_code == 409
        email = "crew@example.test"
        assert ok(client.post(url + "/login-email", headers=DISPATCH, json={"email": email.upper()}))["login_email"] == email
        assert client.post(url + "/login-email", json={"email": "other@example.test"}).status_code == 401
        request = client.post("/api/field/email/request", json={"email": email})
        assert request.status_code == 202 and request.json() == {"status": "accepted"}
        link = urlsplit(sent[email])
        assert (link.scheme, link.netloc, link.path, link.query) == ("https", RP, "/activate", "")
        assert client.get(link.path).status_code == 200
        assert client.get(link.path).headers["Cache-Control"] == "no-store"
        invite = link.fragment
        assert client.get("/api/bootstrap", headers={"Authorization": f"Bearer {invite}"}).status_code == 401
        assert client.post("/api/field/bind", json={"code": invite}).status_code == 409
    else:
        assert ok(client.post("/api/field/invitation", json={"code": invite}))["passkey_required"]
    assert ok(client.get(PASSKEY))["enabled"]
    assets = ok(client.get("/.well-known/assetlinks.json"))
    assert assets[0]["target"]["package_name"] == "com.mandonothing.ops"
    assert assets[0]["target"]["sha256_cert_fingerprints"] == [":".join(["11"] * 32)]
    assert client.post("/api/field/bind", json={"code": invite}).status_code == 409

    key = ec.generate_private_key(ec.SECP256R1())
    numbers = key.public_key().public_numbers()
    cose = cbor2.dumps({1: 2, 3: -7, -1: 1, -2: numbers.x.to_bytes(32, "big"), -3: numbers.y.to_bytes(32, "big")})
    raw_id = uuid4().bytes
    credential_id = bytes_to_base64url(raw_id)
    rp_hash = hashlib.sha256(RP.encode()).digest()
    origin = "android:apk-key-hash:" + bytes_to_base64url(bytes.fromhex(CERT))

    def client_data(challenge, operation, app_origin=origin):
        return json.dumps({"type": "webauthn." + operation, "challenge": challenge,
                           "origin": app_origin, "crossOrigin": False}).encode()

    def registration(reg, app_origin=origin):
        data = rp_hash + b"\x45" + bytes(4 + 16) + len(raw_id).to_bytes(2, "big") + raw_id + cose
        attestation = cbor2.dumps({"fmt": "none", "attStmt": {}, "authData": data})
        return {"ceremony": reg["ceremony"], "invite_code": invite, "credential": {
            "id": credential_id, "rawId": credential_id, "type": "public-key", "response": {
                "clientDataJSON": bytes_to_base64url(client_data(reg["options"]["challenge"], "create", app_origin)),
                "attestationObject": bytes_to_base64url(attestation),
            }}}

    reg = ok(client.post(PASSKEY + "/register/options", json={"code": invite}))
    assert reg["options"]["authenticatorSelection"]["residentKey"] == "required"
    assert client.post(PASSKEY + "/register/verify", json=registration(reg, "https://invalid.example")).status_code == 401
    first = ok(client.post(PASSKEY + "/register/verify", json=registration(reg)))
    assert first["identity"]["actor"] == person["actor"]
    assert client.post(PASSKEY + "/register/verify", json=registration(reg)).status_code == (409 if email_enrollment else 404)
    first_header = {"Authorization": "Bearer " + first["session_token"]}
    bootstrap = ok(client.get("/api/bootstrap", headers=first_header))
    assert bootstrap["passkey_session_expires_at"]
    assert bootstrap["events"][0]["id"] == EVENT
    assert ok(client.get(BASE + "/directory", headers=first_header))["people"][0].get("login_email") is None
    if email_enrollment:
        sent.clear()
        with client.app.state.store.connect(write=True) as conn:
            conn.execute("UPDATE field_email_links SET requested_at=?", (expiry(-2),))
        request = client.post("/api/field/email/request", json={"email": email})
        assert request.status_code == 202
        assert not sent
        assert client.post(PASSKEY + "/register/options", json={"code": invite}).status_code == 409

    def assertion(login, flags=b"\x05", counter=1):
        data = client_data(login["options"]["challenge"], "get")
        auth = rp_hash + flags + counter.to_bytes(4, "big")
        signature = key.sign(auth + hashlib.sha256(data).digest(), ec.ECDSA(hashes.SHA256()))
        return {"ceremony": login["ceremony"], "credential": {
            "id": credential_id, "rawId": credential_id, "type": "public-key", "response": {
                "clientDataJSON": bytes_to_base64url(data), "authenticatorData": bytes_to_base64url(auth),
                "signature": bytes_to_base64url(signature), "userHandle": bytes_to_base64url(person["actor"].encode()),
            }}}

    login = ok(client.post(PASSKEY + "/login/options"))
    assert login["options"]["userVerification"] == "required"
    assert client.post(PASSKEY + "/login/verify", json=assertion(login, b"\x01")).status_code == 401
    second = ok(client.post(PASSKEY + "/login/verify", json=assertion(login)))
    assert second["identity"] == first["identity"]
    assert client.post(PASSKEY + "/login/verify", json=assertion(login)).status_code == 409
    assert client.get("/api/bootstrap", headers={"Authorization": "Bearer " + first["session_token"]}).status_code == 401
    assert ok(client.get(BASE + "/directory", headers=DISPATCH))["people"][0]["passkey_registered"]
    ok(client.post(f"{BASE}/people/{person['actor']}/account-status", headers=DISPATCH, json={"suspended": True}))
    later = ok(client.post(PASSKEY + "/login/options"))
    assert client.post(PASSKEY + "/login/verify", json=assertion(later, counter=2)).status_code == 401


def test_email_links_match_the_approved_recipient_and_expire(tmp_path, monkeypatch):
    configure(monkeypatch, rp=True)
    sent = configure_email(monkeypatch)
    client = TestClient(create_app(tmp_path / "email-links.db"))
    channel = group(client)
    email = "crew@example.test"
    body = {"name": "接机员", "callsign": "CREW", "channel_ids": [channel], "login_email": email}
    person = ok(client.post(BASE + "/people", headers=DISPATCH, json=body))["actor"]
    assert client.post(BASE + "/people", headers=DISPATCH, json=body).status_code == 409
    approval = f"{BASE}/people/{person}/login-email"

    def request_link(address):
        result = client.post("/api/field/email/request", json={"email": address})
        assert result.status_code == 202 and result.json() == {"status": "accepted"}

    request_link("other@example.test")
    assert not sent
    request_link(email)
    link = sent.pop(email)
    request_link(email.upper())
    assert not sent

    def begin(link):
        return client.post(PASSKEY + "/register/options", json={"code": urlsplit(link).fragment})

    assert ok(begin(link))["options"]["user"]["displayName"] == body["name"]
    with client.app.state.store.connect(write=True) as conn:
        conn.execute("UPDATE field_email_links SET expires_at=? WHERE person_id=?", (expiry(-1), person))
    assert begin(link).status_code == 409
    request_link(email)
    link = sent[email]
    ok(client.post(approval, headers=DISPATCH, json={"email": None}))
    assert begin(link).status_code == 409


def test_email_enrollment_fails_closed_without_passkeys(tmp_path, monkeypatch):
    configure(monkeypatch)
    configure_email(monkeypatch)
    with pytest.raises(ValueError, match="requires a Passkey RP ID"):
        create_app(tmp_path / "no-passkey.db")


def test_batch_roster_import_retries_and_permission(tmp_path, monkeypatch):
    configure(monkeypatch)
    client = TestClient(create_app(tmp_path / "batch.db"))
    first = group(client)
    second = ok(client.post(BASE + "/channels", headers=DISPATCH, json={"name": "后勤组"}))["id"]
    operator = add_person(client, "值班调度", first, "dispatcher")
    device = ok(client.post("/api/field/bind", json={"code": operator["binding_code"]}))
    dispatcher = {"Authorization": "Bearer " + device["session_token"]}
    people = [{"name": "王楠", "callsign": "OPS-1"}, {"name": "赵然", "callsign": "OPS-2"}]
    url = BASE + "/people/import"
    first_batch = {"channel_id": first, "people": people}
    assert ok(client.post(url, headers=dispatcher, json=first_batch)) == {"created": 2, "assigned": 0, "unchanged": 0}
    assert ok(client.post(url, headers=dispatcher, json=first_batch)) == {"created": 0, "assigned": 0, "unchanged": 2}
    assert client.post(url, headers=dispatcher, json={"channel_id": second, "people": people}).status_code == 404
    assert ok(client.post(url, headers=DISPATCH, json={"channel_id": second, "people": people}))["assigned"] == 2
    duplicate = {"channel_id": first, "people": [*people, {"name": "不同人", "callsign": "ops-1"}]}
    assert client.post(url, headers=dispatcher, json=duplicate).status_code == 422
    conflict = {"channel_id": first, "people": [{"name": "事务回滚", "callsign": "OPS-3"},
                                              {"name": "冲突人员", "callsign": "ops-2"}]}
    assert client.post(url, headers=dispatcher, json=conflict).status_code == 409
    directory = ok(client.get(BASE + "/directory", headers=dispatcher))
    assert {p["callsign"] for p in directory["people"]} == {"值班调度", "OPS-1", "OPS-2"}


def test_rate_limits_trust_configured_proxy_and_reject_spoofed_headers(tmp_path, monkeypatch):
    configure(monkeypatch, rp=True)
    configure_email(monkeypatch)
    proxied = ProxyHeadersMiddleware(create_app(tmp_path / "proxy-limits.db"), trusted_hosts="172.18.0.1")
    trusted_proxy = TestClient(proxied, client=("172.18.0.1", 50000))
    untrusted_peer = TestClient(proxied, client=("198.51.100.99", 50000))

    for index in range(60):
        response = trusted_proxy.post("/api/field/email/request", json={"email": f"u{index}@example.test"},
                                      headers={"X-Forwarded-For": "198.51.100.10"})
        assert response.status_code == 202
    assert trusted_proxy.post("/api/field/email/request", json={"email": "blocked@example.test"},
                              headers={"X-Forwarded-For": "198.51.100.10"}).status_code == 429
    assert trusted_proxy.post("/api/field/email/request", json={"email": "mapped@example.test"},
                              headers={"X-Forwarded-For": "::ffff:198.51.100.10"}).status_code == 429
    assert trusted_proxy.post("/api/field/email/request", json={"email": "appended@example.test"},
                              headers={"X-Forwarded-For": "203.0.113.5, 198.51.100.10"}).status_code == 429
    assert trusted_proxy.post("/api/field/email/request", json={"email": "other@example.test"},
                              headers={"X-Forwarded-For": "198.51.100.11"}).status_code == 202

    for _ in range(40):
        assert trusted_proxy.post(PASSKEY + "/login/options",
                                  headers={"X-Forwarded-For": "2001:0db8::1"}).status_code == 200
    assert trusted_proxy.post(PASSKEY + "/login/options",
                              headers={"X-Forwarded-For": "2001:db8::1"}).status_code == 429
    assert trusted_proxy.post(PASSKEY + "/login/options",
                              headers={"X-Forwarded-For": "2001:db8::2"}).status_code == 200

    for index in range(40):
        assert untrusted_peer.post(PASSKEY + "/login/options",
                                   headers={"X-Forwarded-For": f"203.0.113.{index}"}).status_code == 200
    assert untrusted_peer.post(PASSKEY + "/login/options",
                               headers={"X-Forwarded-For": "203.0.113.250"}).status_code == 429

    for index in range(60):
        assert untrusted_peer.post("/api/field/email/request", json={"email": f"spoof{index}@example.test"},
                                   headers={"X-Forwarded-For": f"203.0.113.{index}"}).status_code == 202
    assert untrusted_peer.post("/api/field/email/request", json={"email": "spoof60@example.test"},
                               headers={"X-Forwarded-For": "203.0.113.250"}).status_code == 429
