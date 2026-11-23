"""Wake activity clients after committed workspace writes."""
import asyncio
import logging
import sqlite3
from contextlib import asynccontextmanager, closing, suppress
from uuid import uuid4

from fastapi import Depends, HTTPException, Query, Response

from .database import is_postgres

LOG = logging.getLogger(__name__)
CHANNEL = "ops_workspace_changed"
EVENT_TABLES = (
    "objects", "messages", "receipts", "proposals", "pickup_flight_links",
    "arrival_plans", "arrival_history", "arrival_photos", "field_task_delivery",
    "tak_deliveries",
)
FIELD_EVENT_TABLES = ("field_channels", "field_event_roster")
FIELD_ACCESS_TABLES = ("field_members", "field_people", "field_suspensions", "field_devices", "field_passkeys")


class WorkspaceChanges:
    def __init__(self, store, field_enabled):
        self.path = store.path
        self.postgres = is_postgres(self.path)
        self.changed = asyncio.Condition()
        self.generation = uuid4().hex
        self.sequence = 0
        self.global_revision = 0
        self.event_revisions = {}
        self.ready = False
        if self.postgres:
            self.install_triggers(store, field_enabled)

    @staticmethod
    def install_triggers(store, field_enabled):
        with store.connect(write=True) as conn:
            conn.execute(f"""
                CREATE OR REPLACE FUNCTION ops_notify_workspace_change() RETURNS trigger AS $$
                BEGIN
                    IF TG_ARGV[0] = 'all' THEN
                        PERFORM pg_notify('{CHANNEL}', '');
                    ELSIF TG_OP = 'DELETE' THEN
                        PERFORM pg_notify('{CHANNEL}', OLD.event_id);
                    ELSE
                        PERFORM pg_notify('{CHANNEL}', NEW.event_id);
                        IF TG_OP = 'UPDATE' THEN
                            IF OLD.event_id IS DISTINCT FROM NEW.event_id THEN
                                PERFORM pg_notify('{CHANNEL}', OLD.event_id);
                            END IF;
                        END IF;
                    END IF;
                    RETURN NULL;
                END;
                $$ LANGUAGE plpgsql
            """)
            tables = dict.fromkeys(EVENT_TABLES, "event")
            if field_enabled:
                tables.update(dict.fromkeys(FIELD_EVENT_TABLES, "event"))
                tables.update(dict.fromkeys(FIELD_ACCESS_TABLES, "all"))
            for table, scope in tables.items():
                # Both names and trigger arguments come from the static lists above.
                conn.execute(f"DROP TRIGGER IF EXISTS ops_workspace_change ON {table}")
                conn.execute(f"""
                    CREATE TRIGGER ops_workspace_change AFTER INSERT OR UPDATE OR DELETE ON {table}
                    FOR EACH ROW EXECUTE FUNCTION ops_notify_workspace_change('{scope}')
                """)

    async def publish(self, event_id="", *, ready=True):
        async with self.changed:
            self.ready = ready
            self.sequence += 1
            if event_id:
                self.event_revisions[event_id] = self.sequence
            else:
                self.global_revision = self.sequence
            self.changed.notify_all()

    def revision(self, event_id):
        return f"{self.generation}.{self.global_revision}.{self.event_revisions.get(event_id, 0)}"

    async def wait(self, event_id, after):
        async with self.changed:
            if self.ready and after == self.revision(event_id):
                with suppress(TimeoutError):
                    await asyncio.wait_for(
                        self.changed.wait_for(lambda: not self.ready or after != self.revision(event_id)),
                        timeout=20,
                    )
            if not self.ready:
                raise HTTPException(503, "活动订阅暂不可用")
            return self.revision(event_id)

    async def listen_postgres(self):
        import psycopg

        async with await psycopg.AsyncConnection.connect(
            self.path, autocommit=True, connect_timeout=5, application_name="golf_ops_workspace"
        ) as conn:
            await conn.execute(f"LISTEN {CHANNEL}")
            await self.publish()
            async for notice in conn.notifies():
                await self.publish(notice.payload)

    async def watch_local_demo(self):
        with closing(sqlite3.connect(self.path, timeout=0.1)) as conn:
            previous = conn.execute("PRAGMA data_version").fetchone()[0]
            await self.publish()
            while True:
                await asyncio.sleep(0.35)
                current = conn.execute("PRAGMA data_version").fetchone()[0]
                if current != previous:
                    previous = current
                    await self.publish()

    async def run(self):
        while True:
            try:
                if self.postgres:
                    await self.listen_postgres()
                else:
                    await self.watch_local_demo()
            except Exception:
                LOG.exception("Workspace change listener unavailable")
            await self.publish(ready=False)
            await asyncio.sleep(2)

    @asynccontextmanager
    async def running(self):
        listener = asyncio.create_task(self.run())
        try:
            yield
        finally:
            listener.cancel()
            with suppress(asyncio.CancelledError):
                await listener

    def register(self, app, identity, activities):
        @app.get("/api/events/{event_id}/workspace/changes")
        async def workspace_changes(
            event_id: str, response: Response, after: str | None = Query(default=None, max_length=100),
            _user=Depends(identity),
        ):
            if event_id not in activities:
                raise HTTPException(404, "活动不存在")
            response.headers["Cache-Control"] = "private, no-store"
            return {"revision": await self.wait(event_id, after)}
