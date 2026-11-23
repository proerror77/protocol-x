"""Import frozen SQLite archives into an empty PostgreSQL schema and verify every row."""

from argparse import ArgumentParser
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3

from psycopg import sql

from app.database import connect, is_postgres
from app.pickup_watch import PickupWatch
from app.store import Store
from app.voice_archive import VoiceArchive


OPS_TABLES = {
    "events", "objects", "messages", "proposals", "receipts",
    "tak_deliveries", "pickup_flight_links", "pickup_flight_alerts",
}
ORDERED_TABLES = {"messages", "proposals", "receipts"}
VOICE_TABLES = {"voice_transmissions"}


def sqlite_identifier(value):
    return '"' + value.replace('"', '""') + '"'


def signature(rows):
    hashes = []
    for row in rows:
        fields = [{"bytea_sha256": hashlib.sha256(item).hexdigest(), "bytes": len(item)}
                  if isinstance(item, (bytes, bytearray, memoryview)) else item for item in row]
        hashes.append(hashlib.sha256(json.dumps(fields, ensure_ascii=False, separators=(",", ":")).encode()).digest())
    hashes.sort()
    return len(hashes), hashlib.sha256(b"".join(hashes)).hexdigest()


def table_specs(source, allowed):
    actual = {row[0] for row in source.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
    if actual != allowed:
        raise ValueError(f"Unexpected SQLite tables: missing={sorted(allowed-actual)}, extra={sorted(actual-allowed)}")
    return [(table, [row[1] for row in source.execute(f"PRAGMA table_info({sqlite_identifier(table)})")])
            for table in sorted(actual)]


def transfer_table(target, source, table, columns, verify_only):
    identifiers = sql.SQL(",").join(map(sql.Identifier, columns))
    qualified = sql.Identifier(table)
    original = ", ".join(map(sqlite_identifier, columns))
    sequence = table in ORDERED_TABLES
    source_sql = f'SELECT {"rowid, " if sequence else ""}{original} FROM {sqlite_identifier(table)}'
    if sequence:
        identifiers = sql.SQL("record_seq,") + identifiers
    select = sql.SQL("SELECT {} FROM {}").format(identifiers, qualified)
    if not verify_only:
        if target.execute(sql.SQL("SELECT 1 FROM {} LIMIT 1").format(qualified)).fetchone():
            raise ValueError(f"PostgreSQL table is not empty: {table}")
        row = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
            qualified, identifiers, sql.SQL(",").join(sql.Placeholder() for _ in range(len(columns) + sequence)))
        with target.cursor() as cursor:
            cursor.executemany(row, source.execute(source_sql))
        if sequence:
            maximum = target.execute(
                sql.SQL("SELECT max(record_seq) AS last FROM {}").format(qualified)).fetchone()["last"]
            if maximum is not None:
                target.execute("SELECT setval(pg_get_serial_sequence(%s,'record_seq'),%s,false)", (table, maximum + 1))
    original_sig = signature(source.execute(source_sql))
    target_sig = signature(tuple(row.values()) for row in target.execute(select))
    if original_sig != target_sig:
        raise ValueError(f"Row-content verification failed: {table}")
    return table, *target_sig


def run(ops, voice, dsn, verify_only):
    if not is_postgres(dsn):
        raise ValueError("OPS_DATABASE_URL must be PostgreSQL")
    if not verify_only:
        store = Store(dsn, {})
        PickupWatch(store, None)
        VoiceArchive(dsn)
    verified = []
    with connect(dsn, write=not verify_only) as target:
        for file, allowed in ((ops, OPS_TABLES), (voice, VOICE_TABLES)):
            with closing(sqlite3.connect(file.resolve().as_uri() + "?mode=ro", uri=True)) as source:
                if source.execute("PRAGMA quick_check").fetchone() != ("ok",):
                    raise ValueError("SQLite integrity check failed")
                source.execute("BEGIN")
                for table, columns in table_specs(source, allowed):
                    verified.append(transfer_table(target.raw, source, table, columns, verify_only))
    for table, count, digest in verified:
        print(f"{table}: {count} rows, SHA-256 {digest}")


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("ops", type=Path)
    parser.add_argument("voice", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    run(args.ops, args.voice, os.environ["OPS_DATABASE_URL"], args.verify_only)
