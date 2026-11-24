"""Committed arrival events and staff receipts for the Protocol X arrival process."""

from datetime import datetime, timezone
import json

from fastapi import HTTPException

from .store import dump


def install(conn):
    sequence = ("BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY" if conn.postgres
                else "INTEGER PRIMARY KEY AUTOINCREMENT")
    conn.executescript(f"""
        CREATE TABLE IF NOT EXISTS arrival_runtime_events (
            seq {sequence}, event_id TEXT NOT NULL, flight_date TEXT NOT NULL,
            flight TEXT NOT NULL, source_at TEXT NOT NULL, recorded_at TEXT NOT NULL,
            data TEXT NOT NULL, UNIQUE(event_id,flight_date,flight)
        );
        CREATE TABLE IF NOT EXISTS arrival_notice_receipts (
            event_id TEXT NOT NULL, event_seq BIGINT NOT NULL, actor TEXT NOT NULL,
            received_at TEXT, acknowledged_at TEXT,
            PRIMARY KEY(event_seq,actor),
            FOREIGN KEY(event_seq) REFERENCES arrival_runtime_events(seq) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS arrival_runtime_event_cursor ON arrival_runtime_events(event_id,seq);
    """)


def now():
    return datetime.now(timezone.utc).isoformat()


def record_event(conn, link, observation, process):
    rule = next((item for item in process["automations"] if item["id"] == "NotifyFlightArrival"), None)
    if not rule or observation["status"] not in rule["states"]:
        return
    data = {
        "type": rule["event"], "version": 1, "rule": rule["id"], "source": "HKIA",
        "airport": "HKG", "status": observation["status"], "actual": observation["actual"],
    }
    conn.execute("""
        INSERT INTO arrival_runtime_events(event_id,flight_date,flight,source_at,recorded_at,data)
        VALUES (?,?,?,?,?,?) ON CONFLICT(event_id,flight_date,flight) DO NOTHING
    """, (link["event_id"], link["day"], link["flight"], observation["source_time"], now(), dump(data)))
    return conn.execute("""
        SELECT seq FROM arrival_runtime_events WHERE event_id=? AND flight_date=? AND flight=?
    """, (link["event_id"], link["day"], link["flight"])).fetchone()["seq"]


def public_event(row):
    return {**json.loads(row["data"]), **{key: row[key] for key in
              ("seq", "event_id", "flight_date", "flight", "source_at", "recorded_at")}}


def events(conn, event_id, after):
    rows = conn.execute("""
        SELECT * FROM arrival_runtime_events WHERE event_id=? AND seq>? ORDER BY seq LIMIT 51
    """, (event_id, after)).fetchall()
    items = [public_event(row) for row in rows[:50]]
    return {"items": items, "next_after": items[-1]["seq"] if items else after, "has_more": len(rows) > 50}


def crew_cards(conn, event, actor):
    driver = "o.data::jsonb->>'assigned_driver'" if conn.postgres else "json_extract(o.data,'$.assigned_driver')"
    greeter = "p.data::jsonb->>'greeter'" if conn.postgres else "json_extract(p.data,'$.greeter')"
    flight = "p.data::jsonb->>'flight'" if conn.postgres else "json_extract(p.data,'$.flight')"
    day = "p.data::jsonb->>'flight_date'" if conn.postgres else "json_extract(p.data,'$.flight_date')"
    return conn.execute(f"""
        SELECT p.task_id
        FROM arrival_flight_notices n
        JOIN arrival_plans p ON p.event_id=n.event_id AND p.task_id=n.task_id
        JOIN objects o ON o.event_id=n.event_id AND o.id=n.task_id
            AND o.ontology_id='golf.v1' AND o.object_type='TransferLeg'
        WHERE n.event_id=? AND n.flight_date=? AND n.flight=? AND n.kind IN ('landed','at_gate')
          AND n.flight=({flight}) AND n.flight_date=({day})
          AND (({driver})=? OR ({greeter})=?)
          AND EXISTS (
              SELECT 1 FROM field_members m JOIN field_channels c ON c.id=m.channel_id
              WHERE m.person_id=? AND c.event_id=n.event_id AND c.kind='group'
          )
        LIMIT 1
    """, (event["event_id"], event["flight_date"], event["flight"], actor, actor, actor)).fetchone()


def record_receipt(conn, event_id, seq, user, status):
    if user["role"] != "staff" or "device" not in user:
        raise HTTPException(403, "请使用分配给本人的现场账号确认")
    actor = user["actor"]
    event = conn.execute("SELECT * FROM arrival_runtime_events WHERE event_id=? AND seq=?",
                         (event_id, seq)).fetchone()
    if not event or not crew_cards(conn, event, actor):
        raise HTTPException(404, "提醒不可见")
    at = now()
    conn.execute("""
        INSERT INTO arrival_notice_receipts(event_id,event_seq,actor,received_at,acknowledged_at)
        VALUES (?,?,?,?,?)
        ON CONFLICT(event_seq,actor) DO UPDATE SET
            received_at=COALESCE(arrival_notice_receipts.received_at,excluded.received_at),
            acknowledged_at=COALESCE(arrival_notice_receipts.acknowledged_at,excluded.acknowledged_at)
        WHERE arrival_notice_receipts.received_at IS NULL
          OR (arrival_notice_receipts.acknowledged_at IS NULL AND excluded.acknowledged_at IS NOT NULL)
    """, (event_id, seq, actor, at, at if status == "acknowledged" else None))
    receipt = conn.execute("""
        SELECT received_at,acknowledged_at FROM arrival_notice_receipts WHERE event_seq=? AND actor=?
    """, (seq, actor)).fetchone()
    return {"event_seq": seq, **dict(receipt)}


def plan_notice(conn, event_id, plan, task, user, names):
    event = conn.execute("""
        SELECT * FROM arrival_runtime_events WHERE event_id=? AND flight_date=? AND flight=?
    """, (event_id, plan["flight_date"], plan["flight"])).fetchone()
    if not event:
        return None
    receipts = {row["actor"]: row for row in conn.execute("""
        SELECT actor,received_at,acknowledged_at FROM arrival_notice_receipts WHERE event_seq=?
    """, (event["seq"],))}
    crew = {}
    for role, actor in (("greeter", plan["greeter"]), ("driver", task["assigned_driver"])):
        if user["role"] == "dispatcher" or actor == user["actor"]:
            crew.setdefault(actor, []).append(role)
    return {"event_seq": event["seq"], "status": json.loads(event["data"])["status"],
            "source_at": event["source_at"], "recipients": [
                {"actor": actor, "name": names.get(actor, actor), "roles": roles,
                 "received_at": receipts[actor]["received_at"] if actor in receipts else None,
                 "acknowledged_at": receipts[actor]["acknowledged_at"] if actor in receipts else None}
                for actor, roles in crew.items()
            ]}
