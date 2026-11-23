"""Observe Hong Kong arrivals linked to event-scoped pickup tasks."""

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
import json
import logging
import re

from fastapi import Depends, HTTPException
from pydantic import Field

from .bindings import event_and_definition, require_task, resolve
from .flights import AIRPORT_TZ, HKG_EVENTS
from .flight_dispatch import reconcile
from .models import ObjectRef, StrictModel
from .store import dump


LOG = logging.getLogger(__name__)
TERMINAL = {"landed", "at_gate", "cancelled"}


class PickupFlight(StrictModel):
    task_ref: ObjectRef
    date: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    flight: str = Field(min_length=3, max_length=16)


def flight_number(value):
    return "".join(value.upper().split())


def flight_index(snapshot):
    index = {}
    for row in snapshot["arrivals"]:
        key = flight_number(row["flights"][0])
        index[key] = None if key in index else row
    return index


def arrival_time(day, value, scheduled=None):
    if not value:
        return None
    try:
        if "(" in value:
            return datetime.strptime(value, "%H:%M (%d/%m/%Y)").replace(tzinfo=AIRPORT_TZ)
        actual = datetime.strptime(f"{day} {value}", "%Y-%m-%d %H:%M").replace(tzinfo=AIRPORT_TZ)
        planned = arrival_time(day, scheduled) if scheduled else None
        if planned and actual - planned < -timedelta(hours=12):
            actual += timedelta(days=1)
        return actual
    except ValueError:
        return None


def estimated_time(day, flight):
    if flight["status"] != "estimated":
        return None
    scheduled = arrival_time(day, flight["scheduled"])
    estimated = arrival_time(day, flight["estimated"])
    if not scheduled or not estimated:
        return None
    if "(" not in flight["estimated"]:
        # A clock-only estimate near midnight belongs to the nearest service day.
        if estimated - scheduled < -timedelta(hours=12):
            estimated += timedelta(days=1)
        elif estimated - scheduled > timedelta(hours=12):
            estimated -= timedelta(days=1)
    return estimated


def delay_minutes(day, flight):
    eta = estimated_time(day, flight)
    scheduled = arrival_time(day, flight["scheduled"])
    return max(0, int((eta - scheduled).total_seconds() // 60)) if eta and scheduled else 0


class PickupWatch:
    def __init__(self, store, feed, definitions=None):
        self.store = store
        self.feed = feed
        self.definitions = definitions
        self.on_observation = None
        with store.connect(write=True) as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS pickup_flight_links (
                    event_id TEXT NOT NULL, task_ref TEXT NOT NULL, dispatcher_id TEXT NOT NULL,
                    day TEXT NOT NULL, flight TEXT NOT NULL, last_source INTEGER,
                    observation TEXT,
                    PRIMARY KEY(event_id, task_ref)
                );
                CREATE TABLE IF NOT EXISTS pickup_flight_alerts (
                    event_id TEXT NOT NULL, task_ref TEXT NOT NULL, day TEXT NOT NULL,
                    flight TEXT NOT NULL, kind TEXT NOT NULL, source_time TEXT NOT NULL,
                    recorded_at TEXT NOT NULL, payload TEXT NOT NULL,
                    PRIMARY KEY(event_id, task_ref, day, flight, kind)
                );
            """)

    def bind(self, event_id, user, form):
        snapshot = self.feed.get()
        number = flight_number(form.flight)
        if snapshot["state"] != "current" or form.date != snapshot["date"]:
            raise HTTPException(409, "请先刷新香港机场当天数据")
        if not re.fullmatch(r"[A-Z0-9]{2}\d{1,4}[A-Z]?", number) or not flight_index(snapshot).get(number):
            raise HTTPException(409, "请选机场记录中可唯一识别的主航班号")
        ref = dump(form.task_ref.model_dump())
        with self.store.connect(write=True) as conn:
            if self.definitions:
                task = resolve(conn, event_id, form.task_ref.model_dump())
                if json.loads(task["data"]).get("arrival_managed"):
                    raise HTTPException(409, "请从球员接待安排更新此航班")
            row = conn.execute(
                "SELECT dispatcher_id FROM pickup_flight_links WHERE event_id=? AND task_ref=?",
                (event_id, ref),
            ).fetchone()
            if row and row["dispatcher_id"] != user["actor"]:
                raise HTTPException(403, "该接机任务由其他调度员跟进")
            conn.execute("""
                INSERT INTO pickup_flight_links(event_id,task_ref,dispatcher_id,day,flight) VALUES (?,?,?,?,?)
                ON CONFLICT(event_id,task_ref) DO UPDATE SET
                    day=excluded.day, flight=excluded.flight, last_source=NULL, observation=NULL
                WHERE day != excluded.day OR flight != excluded.flight
            """, (event_id, ref, user["actor"], form.date, number))
        self.observe(snapshot)

    def observe(self, snapshot):
        if snapshot["state"] != "current":
            return
        date = snapshot["date"]
        source = snapshot["source_updated_at"]
        source_epoch = int(datetime.fromisoformat(source).timestamp())
        flights = flight_index(snapshot)
        with self.store.connect(write=True) as conn:
            bindings = conn.execute("SELECT * FROM pickup_flight_links WHERE day=?", (date,)).fetchall()
            for link in bindings:
                row = flights.get(link["flight"])
                key = (link["event_id"], link["task_ref"])
                if not row or (link["last_source"] is not None and source_epoch <= link["last_source"]):
                    continue
                previous = json.loads(link["observation"]) if link["observation"] else None
                if previous and previous["status"] in TERMINAL:
                    if previous["status"] != "landed" or row["status"] != "at_gate":
                        continue
                minutes = delay_minutes(date, row)
                eta = estimated_time(date, row)
                observation = {**{name: row.get(name) for name in ("flights", "scheduled", "estimated", "actual", "status", "arrivals_hall", "baggage_belt")},
                               "delay_minutes": minutes, "source_time": source,
                               "estimated_at": eta.isoformat() if eta else None}
                conn.execute("UPDATE pickup_flight_links SET last_source=?, observation=? WHERE event_id=? AND task_ref=?",
                             (source_epoch, dump(observation), *key))
                if self.definitions:
                    _, definition = event_and_definition(conn, link["event_id"], self.definitions)
                    reconcile(conn, link, observation, definition)
                if self.on_observation:
                    self.on_observation(conn, link, previous, observation)
                if minutes or row["status"] == "delayed":
                    kind = "delay"
                elif row["status"] in {"landed", "at_gate"}:
                    kind = row["status"]
                elif row["status"] == "cancelled":
                    kind = "cancelled"
                else:
                    kind = None
                if kind:
                    payload = {**observation, "initial_observation": previous is None}
                    conn.execute("""
                        INSERT INTO pickup_flight_alerts
                        (event_id,task_ref,day,flight,kind,source_time,recorded_at,payload) VALUES (?,?,?,?,?,?,?,?)
                        ON CONFLICT(event_id,task_ref,day,flight,kind) DO NOTHING
                    """, (*key, date, link["flight"], kind, source, datetime.now(timezone.utc).isoformat(), dump(payload)))

    def poll(self):
        today = datetime.now(AIRPORT_TZ).date()
        recent = (today.isoformat(), (today - timedelta(days=1)).isoformat())
        with self.store.connect() as conn:
            active = conn.execute("SELECT day,observation FROM pickup_flight_links WHERE day IN (?,?)", recent).fetchall()
        days = {row["day"] for row in active if not row["observation"] or
                json.loads(row["observation"])["status"] not in {"at_gate", "cancelled"}}
        for day in sorted(days, reverse=True):
            try:
                self.observe(self.feed.get() if day == recent[0] else self.feed.get(day))
            except HTTPException as error:
                if error.status_code != 503:
                    raise

    @asynccontextmanager
    async def running(self, enabled):
        stop = asyncio.Event()

        async def observe_until_stopped():
            while not stop.is_set():
                try:
                    await asyncio.to_thread(self.poll)
                except Exception:
                    LOG.exception("Pickup arrival observation failed")
                try:
                    await asyncio.wait_for(stop.wait(), timeout=15)
                except TimeoutError:
                    pass

        task = asyncio.create_task(observe_until_stopped()) if enabled else None
        try:
            yield
        finally:
            stop.set()
            if task:
                await task

    @staticmethod
    def project(conn, event_id, user):
        if event_id not in HKG_EVENTS or user["role"] != "dispatcher":
            return None
        scope = (event_id, user["actor"])
        rows = conn.execute("""
            SELECT task_ref, day, flight, dispatcher_id, observation FROM pickup_flight_links
            WHERE event_id=? AND dispatcher_id=? ORDER BY task_ref
        """, scope)
        links = [{"task_ref": json.loads(row["task_ref"]), "date": row["day"], "flight": row["flight"], "dispatcher_id": row["dispatcher_id"],
                  "observation": json.loads(row["observation"]) if row["observation"] else None} for row in rows]
        rows = conn.execute("""
            SELECT a.task_ref, a.kind, a.recorded_at, a.payload FROM pickup_flight_alerts a JOIN pickup_flight_links l
            USING(event_id,task_ref,day,flight)
            WHERE l.event_id=? AND l.dispatcher_id=? ORDER BY a.recorded_at DESC LIMIT 20
        """, scope)
        reminders = [{"task_ref": json.loads(row["task_ref"]), "kind": row["kind"],
                      "recorded_at": row["recorded_at"], **json.loads(row["payload"])} for row in rows]
        return {"links": links, "reminders": reminders}


def register_pickup_watch(app, identity, watch, definitions):
    @app.post("/api/events/{event_id}/pickup-flight")
    def bind_pickup(event_id: str, form: PickupFlight, user=Depends(identity)):
        if user["role"] != "dispatcher":
            raise HTTPException(403, "只有调度员能关联接机航班")
        if event_id not in HKG_EVENTS:
            raise HTTPException(404, "该活动没有香港接机配置")
        with watch.store.connect() as conn:
            _, definition = event_and_definition(conn, event_id, definitions)
            require_task(resolve(conn, event_id, form.task_ref.model_dump()), definition)
        watch.bind(event_id, user, form)
        return {"success": True}
