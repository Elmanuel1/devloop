"""
SQLite-backed provider — drop-in replacement for FileStateProvider.

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

from state import (
    WatchProvider, EventProvider, DeadEventProvider, DesignProvider, SessionProvider,
    CHANNELS, _channel_for,
)

_MIGRATIONS_DIR = Path(__file__).resolve().parent / "migrations"


class SQLiteStateProvider(WatchProvider, EventProvider, DeadEventProvider, DesignProvider, SessionProvider):
    """SQLite implementation. Stores everything in a single .db file."""

    MAX_EVENT_ATTEMPTS = 3

    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._run_migrations()
        # Round-robin state for multi-channel pop
        self._rr_index = 0

    def _run_migrations(self) -> None:
        """Run versioned .sql migration files from migrations/ directory.

        Each migration is executed statement-by-statement so that idempotent
        DDL (IF NOT EXISTS, duplicate column adds) can be tolerated.
        """
        self._conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)"
        )
        row = self._conn.execute("SELECT version FROM schema_version LIMIT 1").fetchone()
        if row is None:
            current_v = 0
            self._conn.execute("INSERT INTO schema_version (version) VALUES (0)")
            self._conn.commit()
        else:
            current_v = row["version"]

        for f in sorted(_MIGRATIONS_DIR.glob("V*__*.sql")):
            v = int(f.name.split("__")[0][1:])
            if v > current_v:
                self._exec_migration(f)
                with self._conn:
                    self._conn.execute(
                        "UPDATE schema_version SET version = ?", (v,)
                    )
                current_v = v

    def _exec_migration(self, path: Path) -> None:
        """Execute a .sql file statement-by-statement, tolerating duplicate-column errors."""
        sql = path.read_text()
        for stmt in sql.split(";"):
            lines = [l for l in stmt.splitlines() if not l.strip().startswith("--")]
            stmt = "\n".join(lines).strip()
            if not stmt:
                continue
            try:
                self._conn.execute(stmt)
            except sqlite3.OperationalError as e:
                if "duplicate column" in str(e).lower():
                    continue
                raise
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
    # Events (multi-channel)
    # ------------------------------------------------------------------

    def push_event(self, event: dict, channel: str = "system") -> None:
        ts = datetime.now(timezone.utc).isoformat()
        etype = event.get("type", "unknown")
        attempts = event.get("_attempts", 0)
        data = {k: v for k, v in event.items() if k not in ("type", "_attempts", "_channel")}
        with self._conn:
            self._conn.execute(
                "INSERT INTO events (ts, type, channel, attempts, data) VALUES (?,?,?,?,?)",
                (ts, etype, channel, attempts, json.dumps(data)),
            )

    def _pop_from_channel(self, channel: str) -> Optional[dict]:
        """Pop oldest event from a specific channel. Dead-letters poison events."""
        while True:
            row = self._conn.execute(
                "SELECT id, ts, type, channel, attempts, data FROM events WHERE channel = ? ORDER BY id LIMIT 1",
                (channel,),
            ).fetchone()
            if row is None:
                return None

            attempts = row["attempts"]
            if attempts >= self.MAX_EVENT_ATTEMPTS:
                with self._conn:
                    self._conn.execute(
                        "INSERT INTO dead_events (ts, type, attempts, data, dead_at) VALUES (?,?,?,?,?)",
                        (row["ts"], row["type"], attempts, row["data"],
                         datetime.now(timezone.utc).isoformat()),
                    )
                    self._conn.execute("DELETE FROM events WHERE id = ?", (row["id"],))
                continue

            event = json.loads(row["data"])
            event["type"] = row["type"]
            event["_attempts"] = attempts
            event["_channel"] = row["channel"]
            with self._conn:
                self._conn.execute("DELETE FROM events WHERE id = ?", (row["id"],))
            return event

    def pop_event(self, channel: Optional[str] = None) -> Optional[dict]:
        if channel is not None:
            return self._pop_from_channel(channel)
        # Round-robin across all channels
        for _ in range(len(CHANNELS)):
            ch = CHANNELS[self._rr_index % len(CHANNELS)]
            self._rr_index += 1
            event = self._pop_from_channel(ch)
            if event is not None:
                return event
        return None

    def read_events(self, channel: Optional[str] = None) -> List[dict]:
        if channel:
            rows = self._conn.execute(
                "SELECT type, channel, attempts, data FROM events WHERE channel = ? ORDER BY id", (channel,)
            ).fetchall()
        else:
            rows = self._conn.execute(
                "SELECT type, channel, attempts, data FROM events ORDER BY id"
            ).fetchall()
        result = []
        for r in rows:
            event = json.loads(r["data"])
            event["type"] = r["type"]
            event["_attempts"] = r["attempts"]
            event["_channel"] = r["channel"]
            result.append(event)
        return result

    def nack_event(self, event: dict) -> None:
        event["_attempts"] = event.get("_attempts", 0) + 1
        channel = event.pop("_channel", "system")
        self.push_event(event, channel=channel)

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
                channel = _channel_for(event.get("type", "unknown"))
                self.push_event(event, channel=channel)
                count += 1
            self._conn.execute("DELETE FROM dead_events")
        return count

    # ------------------------------------------------------------------
    # Designs
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
    # Migration from file-based state
    # ------------------------------------------------------------------

    @classmethod
    def migrate_from_files(cls, file_base: Path, db_path: Path) -> "SQLiteStateProvider":
        """Create a new SQLite DB and migrate all file-based state into it.

        Preserves all data — designs, sessions, watches, events.
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

        # Migrate events (flat files or channel subdirs)
        events_dir = file_base / "events"
        if events_dir.exists():
            # Flat event files (legacy)
            for f in sorted(events_dir.glob("*.json")):
                try:
                    event = json.loads(f.read_text())
                    channel = _channel_for(event.get("type", "unknown"))
                    provider.push_event(event, channel=channel)
                except (json.JSONDecodeError, OSError):
                    pass
            # Channel subdirs
            for ch in CHANNELS:
                ch_dir = events_dir / ch
                if ch_dir.exists():
                    for f in sorted(ch_dir.glob("*.json")):
                        try:
                            event = json.loads(f.read_text())
                            provider.push_event(event, channel=ch)
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

        return provider
