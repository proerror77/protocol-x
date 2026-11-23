"""Database connections for deployed Ops and local development."""

from contextlib import closing, contextmanager
import re
import sqlite3


def is_postgres(target):
    return str(target).startswith(("postgresql://", "postgres://"))


def postgres_sql(sql):
    sql = sql.strip().replace("?", "%s")
    if sql.startswith("INSERT OR IGNORE INTO "):
        sql = sql.replace("INSERT OR IGNORE INTO ", "INSERT INTO ", 1).rstrip(";") + " ON CONFLICT DO NOTHING"
    return sql

class Connection:
    def __init__(self, raw, postgres):
        self.raw = raw
        self.postgres = postgres

    def execute(self, sql, params=()):
        if self.postgres:
            sql = postgres_sql(sql)
        return self.raw.execute(sql, params) if params else self.raw.execute(sql)

    def executemany(self, sql, rows):
        if self.postgres:
            with self.raw.cursor() as cursor:
                cursor.executemany(postgres_sql(sql), rows)
            return
        self.raw.executemany(sql, rows)

    def executescript(self, ddl):
        if self.postgres:
            ddl = re.sub(r"\bBLOB\b", "BYTEA", ddl)
        for statement in ddl.split(";"):
            if statement.strip():
                self.execute(statement)


@contextmanager
def connect(target, write=False):
    if is_postgres(target):
        import psycopg
        from psycopg.rows import dict_row

        with psycopg.connect(str(target), row_factory=dict_row, connect_timeout=5,
                             application_name="golf_ops") as raw:
            if write:
                # Preserve the existing one-writer invariant for versions, reservations and receipts.
                raw.execute("SELECT pg_advisory_xact_lock(1485673902)")
            else:
                raw.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            yield Connection(raw, True)
        return
    with closing(sqlite3.connect(target, timeout=10)) as raw, raw:
        raw.row_factory = sqlite3.Row
        if write:
            raw.execute("BEGIN IMMEDIATE")
        yield Connection(raw, False)


def enable_local_wal(target):
    if not is_postgres(target):
        with connect(target) as conn:
            conn.execute("PRAGMA journal_mode=WAL").fetchone()
