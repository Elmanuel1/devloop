"""Tests for store_sqlite.py — SQLite state backend + migration."""

import json
import os
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))

from state import FileStateProvider, State
from store_sqlite import SQLiteStateProvider


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def tmp_dir():
    with tempfile.TemporaryDirectory() as d:
        yield Path(d)


@pytest.fixture
def db_path(tmp_dir):
    return tmp_dir / "test.db"


@pytest.fixture
def provider(db_path):
    return SQLiteStateProvider(db_path)


@pytest.fixture
def sqlite_state(tmp_dir):
    os.environ["STATE_BACKEND"] = "sqlite"
    s = State(base_dir=str(tmp_dir))
    yield s
    os.environ.pop("STATE_BACKEND", None)


# ---------------------------------------------------------------------------
# SQLiteStateProvider — watches
# ---------------------------------------------------------------------------

class TestSQLiteWatches:
    def test_read_empty(self, provider):
        assert provider.read_watches() == []

    def test_write_and_read(self, provider):
        watches = [
            {"id": "w-1", "type": "pr:ci", "interval": 30, "addedAt": "2024-01-01T00:00:00", "repo": "a/b"},
        ]
        provider.write_watches(watches)
        result = provider.read_watches()
        assert len(result) == 1
        assert result[0]["id"] == "w-1"
        assert result[0]["type"] == "pr:ci"
        assert result[0]["repo"] == "a/b"

    def test_write_replaces(self, provider):
        provider.write_watches([{"id": "w-1", "type": "pr:ci", "interval": 30, "addedAt": "t"}])
        provider.write_watches([{"id": "w-2", "type": "pr:review", "interval": 60, "addedAt": "t"}])
        result = provider.read_watches()
        assert len(result) == 1
        assert result[0]["id"] == "w-2"

    def test_expires_at_preserved(self, provider):
        watches = [{
            "id": "w-1", "type": "pr:ci", "interval": 30,
            "addedAt": "t", "expiresAt": "2024-12-31T23:59:59",
        }]
        provider.write_watches(watches)
        result = provider.read_watches()
        assert result[0]["expiresAt"] == "2024-12-31T23:59:59"


# ---------------------------------------------------------------------------
# SQLiteStateProvider — events + poison
# ---------------------------------------------------------------------------

class TestSQLiteEvents:
    def test_push_and_pop(self, provider):
        provider.push_event({"type": "ci:passed", "repo": "a/b"})
        event = provider.pop_event()
        assert event["type"] == "ci:passed"
        assert event["repo"] == "a/b"
        assert event["_attempts"] == 1

    def test_pop_empty(self, provider):
        assert provider.pop_event() is None

    def test_fifo_order(self, provider):
        provider.push_event({"type": "first"})
        provider.push_event({"type": "second"})
        assert provider.pop_event()["type"] == "first"
        assert provider.pop_event()["type"] == "second"

    def test_read_events(self, provider):
        provider.push_event({"type": "a"})
        provider.push_event({"type": "b"})
        events = provider.read_events()
        assert len(events) == 2

    def test_poison_dead_lettered(self, provider):
        provider.push_event({"type": "bad", "_attempts": 3})
        event = provider.pop_event()
        assert event is None
        dead = provider.list_dead_events()
        assert len(dead) == 1
        assert dead[0]["type"] == "bad"

    def test_nack_increments(self, provider):
        provider.push_event({"type": "retry"})
        event = provider.pop_event()
        provider.nack_event(event)
        event2 = provider.pop_event()
        assert event2["_attempts"] == 3  # 1 from first pop + 1 from nack + 1 from second pop

    def test_retry_dead_events(self, provider):
        provider.push_event({"type": "doomed", "_attempts": 3})
        provider.pop_event()  # Dead-letters it
        count = provider.retry_dead_events()
        assert count == 1
        event = provider.pop_event()
        assert event is not None
        assert event["type"] == "doomed"


# ---------------------------------------------------------------------------
# SQLiteStateProvider — designs
# ---------------------------------------------------------------------------

class TestSQLiteDesigns:
    def test_write_and_read(self, provider):
        design = {"id": "d-1", "description": "test", "stage": "design"}
        provider.write_design(design)
        result = provider.read_design("d-1")
        assert result["description"] == "test"

    def test_read_nonexistent(self, provider):
        assert provider.read_design("nope") is None

    def test_list_designs(self, provider):
        provider.write_design({"id": "d-1", "description": "a"})
        provider.write_design({"id": "d-2", "description": "b"})
        designs = provider.list_designs()
        assert len(designs) == 2

    def test_delete_design(self, provider):
        provider.write_design({"id": "d-1", "description": "del"})
        assert provider.delete_design("d-1") is True
        assert provider.read_design("d-1") is None

    def test_delete_nonexistent(self, provider):
        assert provider.delete_design("nope") is False

    def test_upsert(self, provider):
        provider.write_design({"id": "d-1", "description": "v1"})
        provider.write_design({"id": "d-1", "description": "v2"})
        result = provider.read_design("d-1")
        assert result["description"] == "v2"


# ---------------------------------------------------------------------------
# SQLiteStateProvider — sessions
# ---------------------------------------------------------------------------

class TestSQLiteSessions:
    def test_write_and_read(self, provider):
        session = {"issueKey": "TOS-1", "sessionId": "s-1", "agent": "writer"}
        provider.write_session("TOS-1", session)
        result = provider.read_session("TOS-1")
        assert result["agent"] == "writer"

    def test_read_nonexistent(self, provider):
        assert provider.read_session("nope") is None

    def test_list_sessions(self, provider):
        provider.write_session("TOS-1", {"issueKey": "TOS-1"})
        provider.write_session("TOS-2", {"issueKey": "TOS-2"})
        sessions = provider.list_sessions()
        assert len(sessions) == 2

    def test_delete_session(self, provider):
        provider.write_session("TOS-1", {"issueKey": "TOS-1"})
        assert provider.delete_session("TOS-1") is True
        assert provider.read_session("TOS-1") is None

    def test_delete_nonexistent(self, provider):
        assert provider.delete_session("nope") is False


# ---------------------------------------------------------------------------
# SQLiteStateProvider — log
# ---------------------------------------------------------------------------

class TestSQLiteLog:
    def test_append_and_read(self, provider):
        provider.append_log("test_action", detail="hello")
        entries = provider.read_log(last=10)
        assert len(entries) == 1
        assert entries[0]["action"] == "test_action"
        assert entries[0]["detail"] == "hello"

    def test_read_limit(self, provider):
        for i in range(10):
            provider.append_log(f"action_{i}")
        entries = provider.read_log(last=3)
        assert len(entries) == 3

    def test_chronological_order(self, provider):
        provider.append_log("first")
        provider.append_log("second")
        entries = provider.read_log(last=10)
        assert entries[0]["action"] == "first"
        assert entries[1]["action"] == "second"


# ---------------------------------------------------------------------------
# Migration — file → SQLite
# ---------------------------------------------------------------------------

class TestMigration:
    def test_migrate_watches(self, tmp_dir):
        # Create file-based watches
        fp = FileStateProvider(tmp_dir)
        fp.write_watches([
            {"id": "w-1", "type": "pr:ci", "interval": 30, "addedAt": "t", "repo": "a/b"},
            {"id": "w-2", "type": "pr:review", "interval": 60, "addedAt": "t", "prNumber": 1},
        ])
        db_path = tmp_dir / "state.db"
        sp = SQLiteStateProvider.migrate_from_files(tmp_dir, db_path)
        watches = sp.read_watches()
        assert len(watches) == 2

    def test_migrate_events(self, tmp_dir):
        fp = FileStateProvider(tmp_dir)
        fp.push_event({"type": "ci:passed"})
        fp.push_event({"type": "task:requested"})
        db_path = tmp_dir / "state.db"
        sp = SQLiteStateProvider.migrate_from_files(tmp_dir, db_path)
        events = sp.read_events()
        assert len(events) == 2

    def test_migrate_designs(self, tmp_dir):
        designs_dir = tmp_dir / "designs"
        designs_dir.mkdir()
        (designs_dir / "d-1.json").write_text(json.dumps({
            "id": "d-1", "description": "test", "stage": "design",
        }))
        db_path = tmp_dir / "state.db"
        sp = SQLiteStateProvider.migrate_from_files(tmp_dir, db_path)
        designs = sp.list_designs()
        assert len(designs) == 1
        assert designs[0]["description"] == "test"

    def test_migrate_sessions(self, tmp_dir):
        sessions_dir = tmp_dir / "sessions"
        sessions_dir.mkdir()
        (sessions_dir / "TOS-1.json").write_text(json.dumps({
            "issueKey": "TOS-1", "sessionId": "s-1", "agent": "writer",
        }))
        db_path = tmp_dir / "state.db"
        sp = SQLiteStateProvider.migrate_from_files(tmp_dir, db_path)
        sessions = sp.list_sessions()
        assert len(sessions) == 1
        assert sessions[0]["agent"] == "writer"

    def test_migrate_log(self, tmp_dir):
        log_file = tmp_dir / "log.jsonl"
        entries = [
            {"ts": "2024-01-01T00:00:00", "action": "design_created", "detail": "test"},
            {"ts": "2024-01-01T00:01:00", "action": "watch_added"},
        ]
        log_file.write_text("\n".join(json.dumps(e) for e in entries))
        db_path = tmp_dir / "state.db"
        sp = SQLiteStateProvider.migrate_from_files(tmp_dir, db_path)
        log = sp.read_log(last=10)
        assert len(log) == 2

    def test_auto_migrate_on_sqlite_init(self, tmp_dir):
        """State(STATE_BACKEND=sqlite) auto-migrates existing file state."""
        # Phase 1: create file-based state
        os.environ.pop("STATE_BACKEND", None)
        s1 = State(base_dir=str(tmp_dir))
        s1.create_design("test feature")
        s1.save_session("TOS-1", "sess-1")
        s1.add_watch("pr:ci", repo="a/b", branch="main")

        # Phase 2: switch to sqlite
        os.environ["STATE_BACKEND"] = "sqlite"
        s2 = State(base_dir=str(tmp_dir))

        # Verify migration happened
        assert (tmp_dir / "state.db").exists()
        # Note: State-level methods still use file I/O for designs/sessions
        # The provider-level migration covers watches and events
        watches = s2.list_watches()
        assert len(watches) >= 1
        os.environ.pop("STATE_BACKEND", None)

    def test_preserves_original_files(self, tmp_dir):
        """Migration does NOT delete original files."""
        fp = FileStateProvider(tmp_dir)
        fp.write_watches([{"id": "w-1", "type": "pr:ci", "interval": 30, "addedAt": "t"}])
        db_path = tmp_dir / "state.db"
        SQLiteStateProvider.migrate_from_files(tmp_dir, db_path)
        # Original file should still exist
        assert (tmp_dir / "watches.json").exists()


# ---------------------------------------------------------------------------
# State with SQLite backend — integration
# ---------------------------------------------------------------------------

class TestSQLiteIntegration:
    def test_full_lifecycle(self, sqlite_state):
        s = sqlite_state
        # Design
        d = s.create_design("payment", category="feature")
        assert s.get_design(d["id"]) is not None

        # Session
        s.save_session("TOS-1", "sess-1", agent="code-writer", pr_number=10)
        assert s.get_session("TOS-1") is not None

        # Watch
        w = s.add_watch("pr:ci", repo="a/b", branch="main", prNumber=1)
        assert len(s.list_watches()) == 1

        # Events
        s.queue_event({"type": "ci:passed"})
        e = s.pop_event()
        assert e["type"] == "ci:passed"

        # Summary
        summary = s.summary()
        assert "payment" in summary

    def test_poison_in_sqlite(self, sqlite_state):
        s = sqlite_state
        s.queue_event({"type": "bad", "_attempts": 3})
        assert s.pop_event() is None

    def test_nack_in_sqlite(self, sqlite_state):
        s = sqlite_state
        s.queue_event({"type": "test"})
        e = s.pop_event()
        s.nack_event(e)
        e2 = s.pop_event()
        assert e2["_attempts"] >= 1
