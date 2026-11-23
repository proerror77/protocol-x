"""Personal sessions and activity-scoped membership for the field client."""
import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Literal
from uuid import uuid4

from fastapi import Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import Field, field_validator

from .bindings import event_and_definition
from .models import StrictModel
from .store import dump


def stamp():
    return datetime.now(timezone.utc).isoformat()


def expiry(minutes):
    return (datetime.now(timezone.utc) + timedelta(minutes=minutes)).isoformat()


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def clean(value):
    value = value.strip()
    if not value or not value.isprintable():
        raise ValueError("请填写有效的名称")
    return value


class ChannelInput(StrictModel):
    name: str = Field(min_length=1, max_length=40)
    _clean = field_validator("name")(clean)


class RosterPerson(StrictModel):
    name: str = Field(min_length=1, max_length=32)
    callsign: str = Field(min_length=1, max_length=24)
    _clean = field_validator("name", "callsign")(clean)


class PersonInput(RosterPerson):
    role: Literal["staff", "dispatcher"] = "staff"
    channel_ids: list[str] = Field(min_length=1, max_length=16)


class RosterImport(StrictModel):
    channel_id: str = Field(pattern=r"^group-[a-f0-9]{32}$")
    people: list[RosterPerson] = Field(min_length=1, max_length=100)


class InviteInput(StrictModel):
    code: str = Field(pattern=r"^[A-Za-z0-9_-]{22,128}$")


class AccountStatusInput(StrictModel):
    suspended: bool


class IndividualAccess(StrictModel):
    person_id: str = Field(pattern=r"^person-[a-f0-9]{32}$")
    access: Literal["allow", "deny", "inherit"]


class TeamAccess(StrictModel):
    source_id: str = Field(pattern=r"^group-[a-f0-9]{32}$")
    enabled: bool


class DirectInput(StrictModel):
    person_id: str = Field(pattern=r"^person-[a-f0-9]{32}$")


class Field:
    def __init__(self, store, definitions, enabled):
        self.store = store
        self.definitions = definitions
        self.enabled = enabled
        if not enabled:
            return
        with store.connect(write=True) as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS field_people (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, callsign TEXT NOT NULL, role TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_suspensions (
                    person_id TEXT PRIMARY KEY, suspended_at TEXT NOT NULL, suspended_by TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_passkeys (
                    id TEXT PRIMARY KEY, person_id TEXT NOT NULL, public_key BLOB NOT NULL,
                    sign_count BIGINT NOT NULL, created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS field_passkey_person ON field_passkeys(person_id);
                CREATE TABLE IF NOT EXISTS field_channels (
                    id TEXT PRIMARY KEY, event_id TEXT NOT NULL, name TEXT NOT NULL,
                    kind TEXT NOT NULL CHECK(kind IN ('group','direct')), created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_members (
                    channel_id TEXT NOT NULL, person_id TEXT NOT NULL,
                    PRIMARY KEY(channel_id,person_id)
                );
                CREATE TABLE IF NOT EXISTS field_access_migrations (id TEXT PRIMARY KEY);
                CREATE TABLE IF NOT EXISTS field_event_roster (
                    event_id TEXT NOT NULL, person_id TEXT NOT NULL, PRIMARY KEY(event_id,person_id)
                );
                CREATE TABLE IF NOT EXISTS field_team_members (
                    channel_id TEXT NOT NULL, person_id TEXT NOT NULL, PRIMARY KEY(channel_id,person_id)
                );
                CREATE TABLE IF NOT EXISTS field_team_access (
                    source_id TEXT NOT NULL, target_id TEXT NOT NULL, PRIMARY KEY(source_id,target_id)
                );
                CREATE TABLE IF NOT EXISTS field_channel_exclusions (
                    channel_id TEXT NOT NULL, person_id TEXT NOT NULL, PRIMARY KEY(channel_id,person_id)
                );
                CREATE TABLE IF NOT EXISTS field_invites (
                    token_hash TEXT PRIMARY KEY, person_id TEXT NOT NULL, expires_at TEXT NOT NULL,
                    used_at TEXT
                );
                CREATE TABLE IF NOT EXISTS field_devices (
                    token_hash TEXT PRIMARY KEY, person_id TEXT NOT NULL,
                    expires_at TEXT NOT NULL, revoked_at TEXT
                );
                CREATE TABLE IF NOT EXISTS field_channel_voice (
                    channel_id TEXT PRIMARY KEY, mode TEXT NOT NULL, enabled INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_voice_disconnects (session_id TEXT PRIMARY KEY);
                CREATE TABLE IF NOT EXISTS field_voice_log (
                    id TEXT PRIMARY KEY, channel_id TEXT NOT NULL, actor TEXT NOT NULL,
                    action TEXT NOT NULL, recorded_at TEXT NOT NULL, detail TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS field_voice_log_channel ON field_voice_log(channel_id,recorded_at DESC,id DESC);
                CREATE TABLE IF NOT EXISTS field_radio_sessions (
                    id TEXT PRIMARY KEY, channel_id TEXT NOT NULL,
                    wire_room TEXT NOT NULL, device_hash TEXT NOT NULL,
                    expires_at TEXT NOT NULL, removed_at TEXT
                );
                CREATE TABLE IF NOT EXISTS field_calls (
                    id TEXT PRIMARY KEY, event_id TEXT NOT NULL, channel_id TEXT NOT NULL,
                    source TEXT NOT NULL, recipient TEXT NOT NULL, created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS field_calls_recipient ON field_calls(event_id,recipient,created_at);
                CREATE INDEX IF NOT EXISTS field_members_person
                    ON field_members(person_id, channel_id);
                CREATE INDEX IF NOT EXISTS field_radio_devices
                    ON field_radio_sessions(device_hash, removed_at);
            """)
            if conn.execute("INSERT OR IGNORE INTO field_access_migrations VALUES ('channel-access-v1')").rowcount:
                conn.execute("""INSERT INTO field_team_members
                    SELECT m.channel_id,m.person_id FROM field_members m JOIN field_channels c ON c.id=m.channel_id
                    WHERE c.kind='group'""")
                conn.execute("""INSERT INTO field_event_roster SELECT DISTINCT c.event_id,m.person_id
                    FROM field_members m JOIN field_channels c ON c.id=m.channel_id WHERE c.kind='group'""")

    @staticmethod
    def assign_direct(conn, channel_id, actor):
        conn.execute("INSERT OR IGNORE INTO field_team_members VALUES (?,?)", (channel_id, actor))
        conn.execute("""INSERT INTO field_event_roster SELECT event_id,? FROM field_channels WHERE id=?
                        ON CONFLICT(event_id,person_id) DO NOTHING""", (actor, channel_id))
        conn.execute("DELETE FROM field_channel_exclusions WHERE channel_id=? AND person_id=?", (channel_id, actor))

    @staticmethod
    def refresh_access(conn, event_id):
        conn.execute("""DELETE FROM field_members WHERE channel_id IN
                     (SELECT id FROM field_channels WHERE event_id=? AND kind='group')""", (event_id,))
        conn.execute("""
            INSERT INTO field_members(channel_id,person_id)
            SELECT candidates.channel_id,candidates.person_id FROM (
                SELECT channel_id,person_id FROM field_team_members
                UNION
                SELECT a.target_id AS channel_id,t.person_id FROM field_team_access a
                JOIN field_team_members t ON t.channel_id=a.source_id
            ) candidates JOIN field_channels c ON c.id=candidates.channel_id
            WHERE c.event_id=? AND c.kind='group' AND NOT EXISTS (
                SELECT 1 FROM field_channel_exclusions x
                WHERE x.channel_id=candidates.channel_id AND x.person_id=candidates.person_id
            )
            """, (event_id,))
        conn.execute("""
            INSERT INTO field_voice_disconnects(session_id)
            SELECT s.id FROM field_radio_sessions s JOIN field_devices d ON d.token_hash=s.device_hash
            JOIN field_channels c ON c.id=s.channel_id WHERE c.event_id=? AND c.kind='group' AND s.removed_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM field_members m WHERE m.channel_id=c.id AND m.person_id=d.person_id)
            ON CONFLICT(session_id) DO NOTHING
            """, (event_id,))

    @staticmethod
    def roster_access(conn, event_id, actor):
        return conn.execute("SELECT 1 FROM field_event_roster WHERE event_id=? AND person_id=?",
                            (event_id, actor)).fetchone() is not None

    def require_enabled(self):
        if not self.enabled:
            raise HTTPException(404, "人员绑定尚未启用")

    @staticmethod
    def person(row):
        return {"actor": row["id"], "name": row["name"], "callsign": row["callsign"], "role": row["role"]}

    def authenticate(self, header, conn=None):
        scheme, _, token = (header or "").partition(" ")
        if not self.enabled or scheme.lower() != "bearer" or not token.startswith("pxd-"):
            return None
        key = digest(token)
        if conn is None:
            with self.store.connect() as connection:
                return self.authenticate(header, connection)
        row = conn.execute("""
            SELECT p.* FROM field_devices d JOIN field_people p ON p.id=d.person_id
            WHERE d.token_hash=? AND d.revoked_at IS NULL AND d.expires_at>?
            AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=p.id)
            """, (key, stamp())).fetchone()
        if row is None:
            raise HTTPException(401, "设备绑定已失效，请重新绑定")
        return {**self.person(row), "device": key}

    @staticmethod
    def require_person(user):
        if "device" not in user:
            raise HTTPException(403, "请先绑定人员")
        return user["actor"]

    def require_dispatcher(self, user):
        self.require_enabled()
        if user["role"] != "dispatcher":
            raise HTTPException(403, "仅调度员可管理人员或频道")

    @staticmethod
    def event_access(conn, event_id, person_id):
        return conn.execute("""
            SELECT 1 FROM field_members m JOIN field_channels c ON c.id=m.channel_id
            WHERE m.person_id=? AND c.event_id=? AND c.kind='group' LIMIT 1
            """, (person_id, event_id)).fetchone() is not None

    def require_event(self, event_id, user):
        if "device" in user:
            with self.store.connect() as conn:
                if not self.event_access(conn, event_id, user["actor"]):
                    raise HTTPException(403, "未分配到此活动")

    @staticmethod
    def invitation(conn, person_id):
        if conn.execute("SELECT 1 FROM field_suspensions WHERE person_id=?", (person_id,)).fetchone():
            raise HTTPException(409, "该人员登录已停用")
        code = secrets.token_urlsafe(24)
        until = expiry(15)
        conn.execute("UPDATE field_invites SET used_at=? WHERE person_id=? AND used_at IS NULL", (stamp(), person_id))
        conn.execute("INSERT INTO field_invites VALUES (?,?,?,NULL)", (digest(code), person_id, until))
        return {"binding_code": code, "expires_at": until}

    def available_invite(self, conn, code):
        self.require_enabled()
        row = conn.execute("""
            SELECT p.* FROM field_invites i JOIN field_people p ON p.id=i.person_id
            WHERE i.token_hash=? AND i.used_at IS NULL AND i.expires_at>?
            AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=p.id)
            """, (digest(code), stamp())).fetchone()
        if row is None:
            raise HTTPException(404, "登录包已使用、失效或撤销，请联系调度员")
        return row

    def issue_session(self, conn, row):
        at = stamp()
        conn.execute("UPDATE field_devices SET revoked_at=? WHERE person_id=? AND revoked_at IS NULL",
                     (at, row["id"]))
        token = "pxd-" + secrets.token_urlsafe(32)
        conn.execute("INSERT INTO field_devices VALUES (?,?,?,NULL)", (digest(token), row["id"], expiry(7*24*60)))
        return JSONResponse({"session_token": token, "identity": self.person(row)}, headers={"Cache-Control": "no-store"})

    @staticmethod
    def authorized_channel(conn, event_id, channel_id, actor):
        channel = conn.execute("""
            SELECT c.* FROM field_channels c JOIN field_members m ON m.channel_id=c.id
            WHERE c.event_id=? AND c.id=? AND m.person_id=?
            """, (event_id, channel_id, actor)).fetchone()
        if channel is None:
            raise HTTPException(404, "找不到可访问的频道")
        return channel

    @staticmethod
    def voice_policy(conn, channel_id):
        row = conn.execute("SELECT mode,enabled FROM field_channel_voice WHERE channel_id=?", (channel_id,)).fetchone()
        return {"mode": row["mode"], "enabled": bool(row["enabled"])} if row else {"mode": "always", "enabled": True}

    @staticmethod
    def channel_info(conn, channel, viewer):
        members = conn.execute("""
            SELECT p.* FROM field_members m JOIN field_people p ON p.id=m.person_id
            WHERE m.channel_id=? AND (? OR NOT EXISTS (
                SELECT 1 FROM field_suspensions s WHERE s.person_id=p.id
            )) ORDER BY p.name,p.id
            """, (channel["id"], channel["kind"] == "direct")).fetchall()
        name = channel["name"]
        if channel["kind"] == "direct":
            name = next(row["name"] for row in members if row["id"] != viewer)
        return {"id": channel["id"], "name": name, "kind": channel["kind"],
                "members": [Field.person(row) for row in members],
                "voice": Field.voice_policy(conn, channel["id"])}

    def register(self, app, identity):
        store = self.store

        @app.get("/api/field/events/{event_id}/directory")
        def directory(event_id: str, user=Depends(identity)):
            self.require_enabled()
            self.require_event(event_id, user)
            if user["role"] != "dispatcher":
                self.require_person(user)
            with store.connect() as conn:
                event_and_definition(conn, event_id, self.definitions)
                people = conn.execute("""
                    SELECT DISTINCT p.*,
                        EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=p.id) AS suspended,
                        EXISTS (SELECT 1 FROM field_passkeys k WHERE k.person_id=p.id) AS passkey_registered
                    FROM field_people p WHERE EXISTS (
                        SELECT 1 FROM field_members m JOIN field_channels c ON c.id=m.channel_id
                        WHERE m.person_id=p.id AND c.event_id=? AND c.kind='group'
                    ) OR (? AND EXISTS (
                        SELECT 1 FROM field_event_roster r WHERE r.person_id=p.id AND r.event_id=?
                    )) ORDER BY p.name,p.id
                    """, (event_id, user["role"] == "dispatcher", event_id)).fetchall()
                channels = conn.execute("""
                    SELECT DISTINCT c.* FROM field_channels c LEFT JOIN field_members m ON m.channel_id=c.id
                    WHERE c.event_id=? AND (m.person_id=? OR (c.kind='group' AND ?)) ORDER BY c.kind DESC,c.name,c.id
                    """, (event_id, user["actor"], user["role"] == "dispatcher" and "device" not in user)).fetchall()
                return {"people": [{**self.person(row), **(
                            {"login_status": "suspended" if row["suspended"] else "active",
                             "passkey_registered": bool(row["passkey_registered"])}
                            if user["role"] == "dispatcher" else {})} for row in people],
                        "channels": [self.channel_info(conn, row, user["actor"]) for row in channels],
                        "private_calls_enabled": bool(getattr(app.state, "field_conferences_enabled", False))}

        @app.post("/api/field/events/{event_id}/channels", status_code=201)
        def create_channel(event_id: str, body: ChannelInput, user=Depends(identity)):
            self.require_dispatcher(user)
            self.require_event(event_id, user)
            with store.connect(write=True) as conn:
                event_and_definition(conn, event_id, self.definitions)
                if conn.execute("SELECT 1 FROM field_channels WHERE event_id=? AND kind='group' AND name=?", (event_id, body.name)).fetchone():
                    raise HTTPException(409, "活动内已有同名频道")
                new_id = "group-" + uuid4().hex
                conn.execute("INSERT INTO field_channels VALUES (?,?,?,?,?)", (new_id, event_id, body.name, "group", stamp()))
                if "device" in user:
                    self.assign_direct(conn, new_id, user["actor"])
                    self.refresh_access(conn, event_id)
                return {"id": new_id, "name": body.name}

        @app.post("/api/field/events/{event_id}/channels/{channel_id}/members")
        def add_member(event_id: str, channel_id: str, body: DirectInput, user=Depends(identity)):
            self.require_dispatcher(user)
            self.require_event(event_id, user)
            with store.connect(write=True) as conn:
                if not self.event_access(conn, event_id, body.person_id):
                    raise HTTPException(422, "请选择此活动中的人员")
                if conn.execute("SELECT 1 FROM field_channels WHERE id=? AND event_id=? AND kind='group'",
                                (channel_id, event_id)).fetchone() is None:
                    raise HTTPException(404, "频道不存在")
                if "device" in user:
                    self.authorized_channel(conn, event_id, channel_id, user["actor"])
                self.assign_direct(conn, channel_id, body.person_id)
                self.refresh_access(conn, event_id)
            return {"status": "added"}

        @app.post("/api/field/events/{event_id}/people", status_code=201)
        def create_person(event_id: str, body: PersonInput, user=Depends(identity)):
            self.require_dispatcher(user)
            self.require_event(event_id, user)
            with store.connect(write=True) as conn:
                for channel in set(body.channel_ids):
                    if conn.execute("SELECT 1 FROM field_channels WHERE id=? AND event_id=? AND kind='group'", (channel, event_id)).fetchone() is None:
                        raise HTTPException(422, "人员只能加入本活动的群组频道")
                    if "device" in user:
                        self.authorized_channel(conn, event_id, channel, user["actor"])
                person_id = "person-" + uuid4().hex
                conn.execute("INSERT INTO field_people VALUES (?,?,?,?)", (person_id, body.name, body.callsign, body.role))
                for channel in set(body.channel_ids):
                    self.assign_direct(conn, channel, person_id)
                self.refresh_access(conn, event_id)
                return JSONResponse({**self.invitation(conn, person_id), "actor": person_id},
                                    status_code=201, headers={"Cache-Control": "no-store"})

        @app.post("/api/field/events/{event_id}/people/import")
        def import_people(event_id: str, body: RosterImport, user=Depends(identity)):
            self.require_dispatcher(user)
            callsigns = [person.callsign.casefold() for person in body.people]
            if len(callsigns) != len(set(callsigns)):
                raise HTTPException(422, "本批名单的呼号不可重复")
            with store.connect(write=True) as conn:
                channel = conn.execute("SELECT * FROM field_channels WHERE id=? AND event_id=? AND kind='group'",
                                       (body.channel_id, event_id)).fetchone()
                if channel is None:
                    raise HTTPException(404, "找不到活动群组")
                if "device" in user:
                    self.authorized_channel(conn, event_id, body.channel_id, user["actor"])
                existing = {}
                for row in conn.execute("""
                        SELECT p.* FROM field_people p JOIN field_event_roster r ON r.person_id=p.id
                        WHERE r.event_id=?
                        """, (event_id,)):
                    existing.setdefault(row["callsign"].casefold(), []).append(row)
                created, assigned, unchanged = 0, 0, 0
                for person, key in zip(body.people, callsigns):
                    matches = existing.get(key, [])
                    if matches:
                        if len(matches) != 1 or matches[0]["name"] != person.name or matches[0]["role"] != "staff":
                            raise HTTPException(409, f"呼号 {person.callsign} 已用于其他人员或角色")
                        actor = matches[0]["id"]
                        if conn.execute("SELECT 1 FROM field_suspensions WHERE person_id=?", (actor,)).fetchone():
                            raise HTTPException(409, f"{person.callsign} 的登录已停用，请先在登录名单核对")
                        if conn.execute("SELECT 1 FROM field_team_members WHERE channel_id=? AND person_id=?",
                                        (body.channel_id, actor)).fetchone():
                            unchanged += 1
                            continue
                        assigned += 1
                    else:
                        actor = "person-" + uuid4().hex
                        conn.execute("INSERT INTO field_people VALUES (?,?,?,?)",
                                     (actor, person.name, person.callsign, "staff"))
                        created += 1
                    self.assign_direct(conn, body.channel_id, actor)
                self.refresh_access(conn, event_id)
            return {"created": created, "assigned": assigned, "unchanged": unchanged}

        def manage_channel(conn, event, channel, user):
            self.require_dispatcher(user)
            row = self.authorized_channel(conn, event, channel, self.require_person(user))
            if row["kind"] != "group":
                raise HTTPException(404, "仅工作群组可配置访问权限")

        access_path = "/api/field/events/{event_id}/channels/{channel_id}/access"

        @app.get(access_path)
        def channel_access(event_id: str, channel_id: str, user=Depends(identity)):
            with store.connect() as conn:
                manage_channel(conn, event_id, channel_id, user)
                sources = [r["source_id"] for r in conn.execute(
                    "SELECT source_id FROM field_team_access WHERE target_id=? ORDER BY source_id", (channel_id,))]
                direct = [r["person_id"] for r in conn.execute(
                    "SELECT person_id FROM field_team_members WHERE channel_id=? ORDER BY person_id", (channel_id,))]
                excluded = [r["person_id"] for r in conn.execute(
                    "SELECT person_id FROM field_channel_exclusions WHERE channel_id=? ORDER BY person_id", (channel_id,))]
            return {"sources": sources, "direct": direct, "excluded": excluded}

        @app.post(access_path + "/individual")
        def individual_access(event_id: str, channel_id: str, body: IndividualAccess, user=Depends(identity)):
            with store.connect(write=True) as conn:
                manage_channel(conn, event_id, channel_id, user)
                if body.person_id == user["actor"] and body.access != "allow":
                    raise HTTPException(409, "请由另一位调度员调整你的频道权限")
                if not self.roster_access(conn, event_id, body.person_id):
                    raise HTTPException(404, "找不到本活动人员")
                if body.access == "allow":
                    self.assign_direct(conn, channel_id, body.person_id)
                else:
                    conn.execute("DELETE FROM field_team_members WHERE channel_id=? AND person_id=?", (channel_id, body.person_id))
                    if body.access == "deny":
                        conn.execute("INSERT OR IGNORE INTO field_channel_exclusions VALUES (?,?)", (channel_id, body.person_id))
                    else:
                        conn.execute("DELETE FROM field_channel_exclusions WHERE channel_id=? AND person_id=?",
                                     (channel_id, body.person_id))
                self.refresh_access(conn, event_id)
            return {"access": body.access}

        @app.post(access_path + "/team")
        def team_access(event_id: str, channel_id: str, body: TeamAccess, user=Depends(identity)):
            with store.connect(write=True) as conn:
                manage_channel(conn, event_id, channel_id, user)
                manage_channel(conn, event_id, body.source_id, user)
                if channel_id == body.source_id:
                    raise HTTPException(422, "请选择另一个小组")
                if body.enabled:
                    conn.execute("INSERT OR IGNORE INTO field_team_access VALUES (?,?)", (body.source_id, channel_id))
                else:
                    conn.execute("DELETE FROM field_team_access WHERE source_id=? AND target_id=?", (body.source_id, channel_id))
                self.refresh_access(conn, event_id)
                if not conn.execute("SELECT 1 FROM field_members WHERE channel_id=? AND person_id=?",
                                    (channel_id, user["actor"])).fetchone():
                    raise HTTPException(409, "请由另一位调度员移除这项继承权限")
            return {"enabled": body.enabled}

        @app.post("/api/field/events/{event_id}/people/{person_id}/invite")
        def invite_person(event_id: str, person_id: str, user=Depends(identity)):
            self.require_dispatcher(user)
            self.require_event(event_id, user)
            with store.connect(write=True) as conn:
                if not self.event_access(conn, event_id, person_id):
                    raise HTTPException(404, "此人员不属于当前活动")
                return JSONResponse(self.invitation(conn, person_id), headers={"Cache-Control": "no-store"})

        @app.post("/api/field/events/{event_id}/people/{person_id}/revoke")
        def revoke_person(event_id: str, person_id: str, user=Depends(identity)):
            self.require_dispatcher(user)
            self.require_event(event_id, user)
            with store.connect(write=True) as conn:
                if not self.event_access(conn, event_id, person_id):
                    raise HTTPException(404, "此人员不属于当前活动")
                at = stamp()
                conn.execute("UPDATE field_invites SET used_at=? WHERE person_id=? AND used_at IS NULL", (at, person_id))
                conn.execute("UPDATE field_devices SET revoked_at=? WHERE person_id=? AND revoked_at IS NULL", (at, person_id))
            return {"status": "revoked"}

        @app.post("/api/field/events/{event_id}/people/{person_id}/account-status")
        def set_account_status(event_id: str, person_id: str, body: AccountStatusInput, user=Depends(identity)):
            self.require_dispatcher(user)
            if person_id == user["actor"]:
                raise HTTPException(409, "请由另一名调度员维护你的登录权限")
            with store.connect(write=True) as conn:
                if not self.event_access(conn, event_id, person_id):
                    raise HTTPException(404, "此人员不属于当前活动")
                if "device" in user:
                    groups = conn.execute("""
                        SELECT DISTINCT c.event_id FROM field_members m JOIN field_channels c ON c.id=m.channel_id
                        WHERE m.person_id=? AND c.kind='group'
                        """, (person_id,)).fetchall()
                    if any(not self.event_access(conn, row["event_id"], user["actor"]) for row in groups):
                        raise HTTPException(403, "跨活动人员须由有权管理所有相关活动的调度员处理")
                if body.suspended:
                    at = stamp()
                    conn.execute("INSERT OR IGNORE INTO field_suspensions VALUES (?,?,?)", (person_id, at, user["actor"]))
                    conn.execute("UPDATE field_invites SET used_at=? WHERE person_id=? AND used_at IS NULL", (at, person_id))
                    conn.execute("UPDATE field_devices SET revoked_at=? WHERE person_id=? AND revoked_at IS NULL", (at, person_id))
                    conn.execute("DELETE FROM field_passkeys WHERE person_id=?", (person_id,))
                else:
                    conn.execute("DELETE FROM field_suspensions WHERE person_id=?", (person_id,))
            return {"login_status": "suspended" if body.suspended else "active"}

        @app.post("/api/field/invitation")
        def inspect_invite(body: InviteInput):
            with store.connect() as conn:
                row = self.available_invite(conn, body.code)
                groups = conn.execute("""
                    SELECT c.name FROM field_channels c JOIN field_members m ON c.id=m.channel_id
                    WHERE m.person_id=? AND c.kind='group' ORDER BY c.name
                    """, (row["id"],)).fetchall()
                return JSONResponse({"identity": self.person(row), "groups": [group["name"] for group in groups],
                                     "passkey_required": bool(getattr(app.state, "passkey_rp_id", None))},
                                    headers={"Cache-Control": "no-store"})

        @app.post("/api/field/bind")
        def bind(body: InviteInput):
            if getattr(app.state, "passkey_rp_id", None):
                raise HTTPException(409, "请用新版 Protocol X 登记 Passkey")
            with store.connect(write=True) as conn:
                row = self.available_invite(conn, body.code)
                conn.execute("UPDATE field_invites SET used_at=? WHERE token_hash=?", (stamp(), digest(body.code)))
                return self.issue_session(conn, row)

        @app.post("/api/field/logout")
        def logout(user=Depends(identity)):
            self.require_person(user)
            with store.connect(write=True) as conn:
                conn.execute("UPDATE field_devices SET revoked_at=? WHERE token_hash=?", (stamp(), user["device"]))
            return {"status": "revoked"}

        @app.post("/api/field/events/{event_id}/direct")
        def direct(event_id: str, body: DirectInput, user=Depends(identity)):
            actor = self.require_person(user)
            if actor == body.person_id:
                raise HTTPException(422, "请选择其他成员")
            pair = sorted((actor, body.person_id))
            channel_id = "direct-" + digest(dump([event_id, pair]))[:32]
            with store.connect(write=True) as conn:
                if not all(self.event_access(conn, event_id, person_id) for person_id in pair):
                    raise HTTPException(404, "此联系人不属于当前活动")
                conn.execute("INSERT OR IGNORE INTO field_channels VALUES (?,?,?,?,?)",
                             (channel_id, event_id, "私呼", "direct", stamp()))
                conn.executemany("INSERT OR IGNORE INTO field_members VALUES (?,?)",
                                 [(channel_id, person_id) for person_id in pair])
                conn.execute("INSERT INTO field_calls VALUES (?,?,?,?,?,?)",
                             (uuid4().hex, event_id, channel_id, actor, body.person_id, stamp()))
                channel = self.authorized_channel(conn, event_id, channel_id, actor)
                return self.channel_info(conn, channel, actor)
