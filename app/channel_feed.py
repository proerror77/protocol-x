"""Transactional cursors for channel updates. Notifications only wake waiting reads."""
import asyncio
from contextlib import asynccontextmanager, contextmanager, suppress

from .database import is_postgres


def install_feed_schema(conn):
    conn.execute("""CREATE TABLE IF NOT EXISTS channel_feed_versions (
        scope TEXT NOT NULL, kind TEXT NOT NULL, version BIGINT NOT NULL, PRIMARY KEY(scope,kind)
    )""")
    if conn.postgres:
        conn.execute("""
            CREATE OR REPLACE FUNCTION ops_bump_feed(p_scope text,p_kind text)
            RETURNS bigint LANGUAGE plpgsql AS $$
            DECLARE next_version bigint;
            BEGIN
                IF p_scope IS NULL OR p_scope='' THEN RETURN NULL; END IF;
                INSERT INTO channel_feed_versions VALUES (p_scope,p_kind,1)
                ON CONFLICT(scope,kind) DO UPDATE SET version=channel_feed_versions.version+1
                RETURNING version INTO next_version;
                PERFORM pg_notify('ops_channel_changed',p_scope);
                RETURN next_version;
            END
            $$
        """)


def install_ops_feed(conn):
    install_feed_schema(conn)
    if conn.postgres:
        conn.executescript("""
            CREATE INDEX IF NOT EXISTS ops_channel_messages ON messages
                (event_id, (data::jsonb->>'voice_channel_id'), record_seq);
            CREATE INDEX IF NOT EXISTS ops_channel_receipts ON receipts
                (event_id, (data::jsonb->'result'->>'message_id'));
        """)
        conn.execute("""
            CREATE OR REPLACE FUNCTION ops_signal_channel() RETURNS trigger LANGUAGE plpgsql AS $$
            DECLARE room text;
            BEGIN
                IF TG_TABLE_NAME='messages' THEN
                    room := NEW.data::jsonb->>'voice_channel_id';
                ELSIF NEW.data::jsonb->>'action_type' IN ('ack_qrf_exception','receive_dispatch_instruction') THEN
                    SELECT data::jsonb->>'voice_channel_id' INTO room FROM messages
                    WHERE id=NEW.data::jsonb->'result'->>'message_id' AND event_id=NEW.event_id;
                END IF;
                PERFORM ops_bump_feed(room,'ops');
                RETURN NEW;
            END
            $$
        """)
        for table in ("messages", "receipts"):
            conn.execute(f"DROP TRIGGER IF EXISTS ops_channel_notify ON {table}")
            conn.execute(f"""CREATE TRIGGER ops_channel_notify AFTER INSERT ON {table}
                             FOR EACH ROW EXECUTE FUNCTION ops_signal_channel()""")
    else:
        for table, query in (
            ("messages", "SELECT json_extract(NEW.data,'$.voice_channel_id') AS room"),
            ("receipts", """
                SELECT json_extract(m.data,'$.voice_channel_id') AS room FROM messages m
                WHERE m.id=json_extract(NEW.data,'$.result.message_id') AND m.event_id=NEW.event_id
                AND json_extract(NEW.data,'$.action_type') IN ('ack_qrf_exception','receive_dispatch_instruction')
            """),
        ):
            conn.execute(f"""
                CREATE TRIGGER IF NOT EXISTS ops_channel_notify_{table} AFTER INSERT ON {table}
                BEGIN
                    INSERT INTO channel_feed_versions
                    SELECT room,'ops',1 FROM ({query}) WHERE room IS NOT NULL AND room!=''
                    ON CONFLICT(scope,kind) DO UPDATE SET version=channel_feed_versions.version+1;
                END
            """)


def install_voice_feed(conn):
    install_feed_schema(conn)
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS voice_feed_entries (
            scope TEXT NOT NULL, track_sid TEXT NOT NULL, revision BIGINT NOT NULL,
            changed_at TEXT NOT NULL, PRIMARY KEY(scope,track_sid)
        );
        CREATE INDEX IF NOT EXISTS voice_feed_cursor ON voice_feed_entries(scope,revision);
    """)
    if conn.postgres:
        conn.execute("""
            CREATE OR REPLACE FUNCTION ops_signal_voice() RETURNS trigger LANGUAGE plpgsql AS $$
            DECLARE room text; track text; rev bigint;
            BEGIN
                IF TG_OP='DELETE' THEN
                    IF OLD.status IN ('recording','silent') THEN RETURN OLD; END IF;
                    room := OLD.event_id; track := OLD.track_sid;
                ELSE
                    IF NEW.status IN ('recording','silent') THEN RETURN NEW; END IF;
                    IF TG_OP='UPDATE' THEN
                        IF OLD.status=NEW.status AND OLD.text IS NOT DISTINCT FROM NEW.text
                           AND OLD.partial=NEW.partial AND OLD.duration_ms=NEW.duration_ms
                           AND (OLD.audio IS NULL)=(NEW.audio IS NULL)
                           AND OLD.review_engine IS NOT DISTINCT FROM NEW.review_engine THEN
                            RETURN NEW;
                        END IF;
                    END IF;
                    room := NEW.event_id; track := NEW.track_sid;
                END IF;
                rev := ops_bump_feed(room,'voice');
                INSERT INTO voice_feed_entries VALUES
                    (room,track,rev,to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US') || '+00:00')
                ON CONFLICT(scope,track_sid) DO UPDATE SET revision=excluded.revision,changed_at=excluded.changed_at;
                IF TG_OP='DELETE' THEN RETURN OLD; END IF;
                RETURN NEW;
            END
            $$
        """)
        conn.execute("DROP TRIGGER IF EXISTS ops_voice_notify ON voice_transmissions")
        conn.execute("""CREATE TRIGGER ops_voice_notify AFTER INSERT OR UPDATE OR DELETE ON voice_transmissions
                        FOR EACH ROW EXECUTE FUNCTION ops_signal_voice()""")
    else:
        for verb, row, extra in (
            ("insert", "NEW", ""),
            ("update", "NEW", """AND (OLD.status!=NEW.status OR OLD.text IS NOT NEW.text OR
                OLD.partial!=NEW.partial OR OLD.duration_ms!=NEW.duration_ms OR
                (OLD.audio IS NULL)!=(NEW.audio IS NULL) OR OLD.review_engine IS NOT NEW.review_engine)"""),
            ("delete", "OLD", ""),
        ):
            conn.execute(f"""
                CREATE TRIGGER IF NOT EXISTS ops_voice_notify_{verb} AFTER {verb.upper()} ON voice_transmissions
                WHEN {row}.status NOT IN ('recording','silent') {extra}
                BEGIN
                    INSERT INTO channel_feed_versions VALUES ({row}.event_id,'voice',1)
                    ON CONFLICT(scope,kind) DO UPDATE SET version=channel_feed_versions.version+1;
                    INSERT INTO voice_feed_entries
                    SELECT scope,{row}.track_sid,version,strftime('%Y-%m-%dT%H:%M:%f+00:00','now')
                    FROM channel_feed_versions WHERE scope={row}.event_id AND kind='voice'
                    ON CONFLICT(scope,track_sid) DO UPDATE SET revision=excluded.revision,changed_at=excluded.changed_at;
                END
            """)


def feed_version(conn, scope, kind):
    row = conn.execute("SELECT version FROM channel_feed_versions WHERE scope=? AND kind=?", (scope, kind)).fetchone()
    return row["version"] if row else 0


def bump_feed(conn, scope, kind="ops"):
    if not scope:
        return 0
    if conn.postgres:
        row = conn.execute("SELECT ops_bump_feed(?,?) AS v", (scope, kind)).fetchone()
        return row["v"] if row and row["v"] is not None else 0
    conn.execute(
        "INSERT INTO channel_feed_versions VALUES (?,?,1) "
        "ON CONFLICT(scope,kind) DO UPDATE SET version=channel_feed_versions.version+1",
        (scope, kind),
    )
    return feed_version(conn, scope, kind)


class ChannelWakeups:
    def __init__(self, paths):
        self.paths = {str(path) for path in paths if path and is_postgres(path)}
        self.waiters = {}

    @contextmanager
    def watch(self, scope):
        event = asyncio.Event()
        group = self.waiters.setdefault(scope, set())
        group.add(event)
        try:
            yield event
        finally:
            group.remove(event)
            if not group:
                self.waiters.pop(scope, None)

    async def _listen(self, path):
        import psycopg

        while True:
            try:
                async with await psycopg.AsyncConnection.connect(path, autocommit=True, connect_timeout=5) as conn:
                    await conn.execute("LISTEN ops_channel_changed")
                    async for notice in conn.notifies():
                        for event in self.waiters.get(notice.payload, ()):
                            event.set()
            except psycopg.Error:
                await asyncio.sleep(1)

    @asynccontextmanager
    async def running(self):
        tasks = [asyncio.create_task(self._listen(path)) for path in self.paths]
        try:
            yield
        finally:
            for task in tasks:
                task.cancel()
            for task in tasks:
                with suppress(asyncio.CancelledError):
                    await task
