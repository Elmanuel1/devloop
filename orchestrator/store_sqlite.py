"""
SQLite-backed StateProvider — drop-in replacement for FileStateProvider.

Activated by setting STATE_BACKEND=sqlite in .env (or env var).
On first use, auto-migrates existing file-based state if present.

Schema is append-only (no ALTER TABLE needed for upgrades).
All JSON payloads stored as TEXT — queryable via json_extract() in SQLite.
"""

import json
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

# Import the abstract interface from state.py
from state import StateProvider

SCHEMA_VERSION = 1


class SQLiteStateProvider(StateProvider):
    """SQLite implementation of StateProvider. Stores everything in a single .db file."""

    MAX_EVENT_ATTEMPTS = 3

    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._init_schema()

    def _init_schema(self) -> None:
        """Create tables if they don't exist."""
        self._conn.executescript("""
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS watches (
                id          TEXT PRIMARY KEY,
                type        TEXT NOT NULL,
                interval    INTEGER NOT NULL DEFAULT 30,
                added_at    TEXT NOT NULL,
                expires_at  TEXT,
                data        TEXT NOT NULL DEFAULT '{}'
            );

            CREATE TABLE IF NOT EXISTS events (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                ts          TEXT NOT NULL,
                type        TEXT NOT NULL,
                attempts    INTEGER NOT NULL DEFAULT 0,
                data        TEXT NOT NULL DEFAULT '{}'
            );

            CREATE TABLE IF NOT EXISTS dead_events (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                ts          TEXT NOT NULL,
                type        TEXT NOT NULL,
                attempts    INTEGER NOT NULL DEFAULT 0,
                data        TEXT NOT NULL DEFAULT '{}',
                dead_at     TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS designs (
                id          TEXT PRIMARY KEY,
                data        TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS sessions (
                issue_key   TEXT PRIMARY KEY,
                data        TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS log (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                ts          TEXT NOT NULL,
                action      TEXT NOT NULL,
                data        TEXT NOT NULL DEFAULT '{}'
            );

            CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts);
            CREATE INDEX IF NOT EXISTS idx_log_ts ON log(ts);
            CREATE INDEX IF NOT EXISTS idx_watches_type ON watches(type);
        """)

        # Track schema version
        row = self._conn.execute("SELECT version FROM schema_version LIMIT 1").fetchone()
        if row is None:
            self._conn.execute("INSERT INTO schema_version (version) VALUES (?)", (SCHEMA_VERSION,))
        self._conn.commit()

    # ------------------------------------------------------------------
    # Watches
    # ------------------------------------------------------------------

    def read_watches(self) -> List[dict]:
        rows = self._conn.execute("SELECT * FROM watches").fetchall()
        result = []
        for r in rows:
            watch = json.loads(r["data"])
            watch.update({
                "id": r["id"],
                "type": r["type"],
                "interval": r["interval"],
                "addedAt": r["added_at"],
            })
            if r["expires_at"]:
                watch["expiresAt"] = r["expires_at"]
            result.append(watch)
        return result

    def write_watches(self, watches: List[dict]) -> None:
        """Replace all watches atomically."""
        with self._conn:
            self._conn.execute("DELETE FROM watches")
            for w in watches:
                extra = {k: v for k, v in w.items()
                         if k not in ("id", "type", "interval", "addedAt", "expiresAt")}
                self._conn.execute(
                    "INSERT INTO watches (id, type, interval, added_at, expires_at, data) VALUES (?,?,?,?,?,?)",
                    (w["id"], w["type"], w.get("interval", 30),
                     w.get("addedAt", ""), w.get("expiresAt"), json.dumps(extra)),
                )

    # ------------------------------------------------------------------
    # Events
    # ------------------------------------------------------------------

    def push_event(self, event: dict) -> None:
        ts = datetime.now(timezone.utc).isoformat()
        etype = event.get("type", "unknown")
        attempts = event.get("_attempts", 0)
        # Store everything except _attempts in data (attempts is a column)
        data = {k: v for k, v in event.items() if k not in ("type", "_attempts")}
        with self._conn:
            self._conn.execute(
                "INSERT INTO events (ts, type, attempts, data) VALUES (?,?,?,?)",
                (ts, etype, attempts, json.dumps(data)),
            )

    def pop_event(self) -> Optional[dict]:
        while True:
            row = self._conn.execute(
                "SELECT id, ts, type, attempts, data FROM events ORDER BY id LIMIT 1"
            ).fetchone()
            if row is None:
                return None

            attempts = row["attempts"]
            if attempts >= self.MAX_EVENT_ATTEMPTS:
                # Dead-letter it
                with self._conn:
                    self._conn.execute(
                        "INSERT INTO dead_events (ts, type, attempts, data, dead_at) VALUES (?,?,?,?,?)",
                        (row["ts"], row["type"], attempts, row["data"],
                         datetime.now(timezone.utc).isoformat()),
                    )
                    self._conn.execute("DELETE FROM events WHERE id = ?", (row["id"],))
                continue

            # Increment attempts and delete
            event = json.loads(row["data"])
            event["type"] = row["type"]
            event["_attempts"] = attempts + 1
            with self._conn:
                self._conn.execute("DELETE FROM events WHERE id = ?", (row["id"],))
            return event

    def read_events(self) -> List[dict]:
        rows = self._conn.execute("SELECT type, attempts, data FROM events ORDER BY id").fetchall()
        result = []
        for r in rows:
            event = json.loads(r["data"])
            event["type"] = r["type"]
            event["_attempts"] = r["attempts"]
            result.append(event)
        return result

    def nack_event(self, event: dict) -> None:
        event["_attempts"] = event.get("_attempts", 0) + 1
        self.push_event(event)

    # ------------------------------------------------------------------
    # Designs (extended beyond base StateProvider for full migration)
    # ------------------------------------------------------------------

    def read_design(self, design_id: str) -> Optional[dict]:
        row = self._conn.execute(
            "SELECT data FROM designs WHERE id = ?", (design_id,)
        ).fetchone()
        return json.loads(row["data"]) if row else None

    def write_design(self, design: dict) -> None:
        with self._conn:
            self._conn.execute(
                "INSERT OR REPLACE INTO designs (id, data) VALUES (?,?)",
                (design["id"], json.dumps(design)),
            )

    def delete_design(self, design_id: str) -> bool:
        with self._conn:
            cursor = self._conn.execute("DELETE FROM designs WHERE id = ?", (design_id,))
            return cursor.rowcount > 0

    def list_designs(self) -> List[dict]:
        rows = self._conn.execute("SELECT data FROM designs").fetchall()
        return [json.loads(r["data"]) for r in rows]

    # ------------------------------------------------------------------
    # Sessions
    # ------------------------------------------------------------------

    def read_session(self, issue_key: str) -> Optional[dict]:
        row = self._conn.execute(
            "SELECT data FROM sessions WHERE issue_key = ?", (issue_key,)
        ).fetchone()
        return json.loads(row["data"]) if row else None

    def write_session(self, issue_key: str, session: dict) -> None:
        with self._conn:
            self._conn.execute(
                "INSERT OR REPLACE INTO sessions (issue_key, data) VALUES (?,?)",
                (issue_key, json.dumps(session)),
            )

    def delete_session(self, issue_key: str) -> bool:
        with self._conn:
            cursor = self._conn.execute("DELETE FROM sessions WHERE issue_key = ?", (issue_key,))
            return cursor.rowcount > 0

    def list_sessions(self) -> List[dict]:
        rows = self._conn.execute("SELECT data FROM sessions").fetchall()
        return [json.loads(r["data"]) for r in rows]

    # ------------------------------------------------------------------
    # Log
    # ------------------------------------------------------------------

    def append_log(self, action: str, **data: Any) -> None:
        ts = datetime.now(timezone.utc).isoformat()
        with self._conn:
            self._conn.execute(
                "INSERT INTO log (ts, action, data) VALUES (?,?,?)",
                (ts, action, json.dumps(data)),
            )

    def read_log(self, last: int = 50) -> List[dict]:
        rows = self._conn.execute(
            "SELECT ts, action, data FROM log ORDER BY id DESC LIMIT ?", (last,)
        ).fetchall()
        result = []
        for r in reversed(rows):  # Reverse to get chronological order
            entry = json.loads(r["data"])
            entry["ts"] = r["ts"]
            entry["action"] = r["action"]
            result.append(entry)
        return result

    # ------------------------------------------------------------------
    # Dead events
    # ------------------------------------------------------------------

    def list_dead_events(self) -> List[dict]:
        rows = self._conn.execute(
            "SELECT ts, type, attempts, data, dead_at FROM dead_events ORDER BY id"
        ).fetchall()
        result = []
        for r in rows:
            event = json.loads(r["data"])
            event["type"] = r["type"]
            event["_attempts"] = r["attempts"]
            event["_dead_at"] = r["dead_at"]
            result.append(event)
        return result

    def retry_dead_events(self) -> int:
        """Move all dead events back to live queue with attempts reset."""
        rows = self._conn.execute("SELECT type, data FROM dead_events").fetchall()
        count = 0
        with self._conn:
            for r in rows:
                event = json.loads(r["data"])
                event["type"] = r["type"]
                event["_attempts"] = 0
                self.push_event(event)
                count += 1
            self._conn.execute("DELETE FROM dead_events")
        return count

    # ------------------------------------------------------------------
    # Migration from file-based state
    # ------------------------------------------------------------------

    @classmethod
    def migrate_from_files(cls, file_base: Path, db_path: Path) -> "SQLiteStateProvider":
        """Create a new SQLite DB and migrate all file-based state into it.

        Preserves all data — designs, sessions, watches, events, logs.
        Original files are NOT deleted (caller decides when to clean up).
        """
        provider = cls(db_path)

        # Migrate watches
        watches_file = file_base / "watches.json"
        if watches_file.exists():
            try:
                watches = json.loads(watches_file.read_text())
                if watches:
                    provider.write_watches(watches)
            except (json.JSONDecodeError, OSError):
                pass

        # Migrate events
        events_dir = file_base / "events"
        if events_dir.exists():
            for f in sorted(events_dir.glob("*.json")):
                try:
                    event = json.loads(f.read_text())
                    provider.push_event(event)
                except (json.JSONDecodeError, OSError):
                    pass

        # Migrate dead events
        dead_dir = file_base / "dead_events"
        if dead_dir.exists():
            for f in sorted(dead_dir.glob("*.json")):
                try:
                    event = json.loads(f.read_text())
                    ts = datetime.now(timezone.utc).isoformat()
                    etype = event.get("type", "unknown")
                    attempts = event.get("_attempts", 0)
                    data = {k: v for k, v in event.items() if k not in ("type", "_attempts")}
                    with provider._conn:
                        provider._conn.execute(
                            "INSERT INTO dead_events (ts, type, attempts, data, dead_at) VALUES (?,?,?,?,?)",
                            (ts, etype, attempts, json.dumps(data), ts),
                        )
                except (json.JSONDecodeError, OSError):
                    pass

        # Migrate designs
        designs_dir = file_base / "designs"
        if designs_dir.exists():
            for f in sorted(designs_dir.glob("*.json")):
                try:
                    design = json.loads(f.read_text())
                    provider.write_design(design)
                except (json.JSONDecodeError, OSError):
                    pass

        # Migrate sessions
        sessions_dir = file_base / "sessions"
        if sessions_dir.exists():
            for f in sorted(sessions_dir.glob("*.json")):
                try:
                    session = json.loads(f.read_text())
                    issue_key = session.get("issueKey", f.stem)
                    provider.write_session(issue_key, session)
                except (json.JSONDecodeError, OSError):
                    pass

        # Migrate log
        log_file = file_base / "log.jsonl"
        if log_file.exists():
            try:
                for line in log_file.read_text().strip().split("\n"):
                    if not line:
                        continue
                    entry = json.loads(line)
                    ts = entry.pop("ts", datetime.now(timezone.utc).isoformat())
                    action = entry.pop("action", "unknown")
                    with provider._conn:
                        provider._conn.execute(
                            "INSERT INTO log (ts, action, data) VALUES (?,?,?)",
                            (ts, action, json.dumps(entry)),
                        )
            except (json.JSONDecodeError, OSError):
                pass

        return provider
