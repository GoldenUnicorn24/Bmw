import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path


class Store:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
        with self.write() as conn:
            version = conn.execute("PRAGMA user_version").fetchone()[0]
            if version > 2:
                raise RuntimeError("Datenbank wurde mit einer neueren NightDesk-Version erstellt")
            if version == 0:
                for statement in (
                    "CREATE TABLE state (name TEXT PRIMARY KEY, value TEXT NOT NULL)",
                    """CREATE TABLE orders (id TEXT PRIMARY KEY, mode TEXT NOT NULL, symbol TEXT NOT NULL,
                     side TEXT NOT NULL, kind TEXT NOT NULL, quantity TEXT NOT NULL, limit_price TEXT,
                     status TEXT NOT NULL, exchange_id TEXT UNIQUE, filled TEXT NOT NULL DEFAULT '0',
                     cost TEXT NOT NULL DEFAULT '0', fee TEXT NOT NULL DEFAULT '0', at INTEGER NOT NULL,
                     strategy TEXT, reference TEXT, error TEXT)""",
                    "CREATE INDEX orders_mode_status ON orders(mode,status)",
                    "CREATE TABLE events (id INTEGER PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, details TEXT NOT NULL)",
                    "CREATE TABLE marks (mode TEXT, day TEXT, opening TEXT, peak TEXT, PRIMARY KEY(mode,day))",
                    "CREATE TABLE fills (id TEXT PRIMARY KEY, order_id TEXT NOT NULL REFERENCES orders(id), volume TEXT NOT NULL, cost TEXT NOT NULL, fee TEXT NOT NULL, at INTEGER NOT NULL)",
                ):
                    conn.execute(statement)
                conn.execute("PRAGMA user_version=1")
            if version < 2:
                conn.execute("""CREATE TABLE equity_samples
                    (mode TEXT NOT NULL, at INTEGER NOT NULL, amount TEXT NOT NULL, PRIMARY KEY(mode, at))""")
                conn.execute("PRAGMA user_version=2")

    @contextmanager
    def connection(self):
        conn = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA synchronous=FULL")
        try:
            yield conn
        finally:
            conn.close()

    @contextmanager
    def write(self):
        with self.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                yield conn
                conn.commit()
            except BaseException:
                conn.rollback()
                raise


def read(conn, key, default=None):
    row = conn.execute("SELECT value FROM state WHERE name=?", (key,)).fetchone()
    return json.loads(row["value"]) if row else default


def save(conn, key, value):
    conn.execute("INSERT INTO state VALUES (?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value",
                 (key, json.dumps(value)))


def event(conn, kind, details):
    conn.execute("INSERT INTO events(at,kind,details) VALUES (unixepoch(),?,?)", (kind, json.dumps(details)))
