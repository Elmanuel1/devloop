"""Tests for state.py — FileStateProvider + State manager."""

import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

# Ensure we import from the orchestrator directory
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))

os.environ.pop("STATE_BACKEND", None)

from state import FileStateProvider, State


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def tmp_dir():
    with tempfile.TemporaryDirectory() as d:
        yield Path(d)


@pytest.fixture
def state(tmp_dir):
    return State(base_dir=str(tmp_dir))


@pytest.fixture
def file_provider(tmp_dir):
    return FileStateProvider(tmp_dir)


# ---------------------------------------------------------------------------
# FileStateProvider — watches
# ---------------------------------------------------------------------------

class TestFileProviderWatches:
    def test_read_empty(self, file_provider):
        assert file_provider.read_watches() == []

    def test_write_and_read(self, file_provider):
        watches = [{"id": "w-1", "type": "pr:ci", "interval": 30}]
        file_provider.write_watches(watches)
        assert file_provider.read_watches() == watches

    def test_overwrite(self, file_provider):
        file_provider.write_watches([{"id": "w-1", "type": "pr:ci"}])
        file_provider.write_watches([{"id": "w-2", "type": "pr:review"}])
        result = file_provider.read_watches()
        assert len(result) == 1
        assert result[0]["id"] == "w-2"


# ---------------------------------------------------------------------------
# FileStateProvider — events
# ---------------------------------------------------------------------------

class TestFileProviderEvents:
    def test_push_and_pop(self, file_provider):
        file_provider.push_event({"type": "ci:passed", "repo": "a/b"})
        event = file_provider.pop_event()
        assert event["type"] == "ci:passed"
        assert event["repo"] == "a/b"
        assert event["_attempts"] == 0

    def test_pop_empty(self, file_provider):
        assert file_provider.pop_event() is None

    def test_fifo_order(self, file_provider):
        import time
        file_provider.push_event({"type": "first"})
        time.sleep(0.01)  # Ensure different timestamps
        file_provider.push_event({"type": "second"})
        assert file_provider.pop_event()["type"] == "first"
        assert file_provider.pop_event()["type"] == "second"

    def test_read_events(self, file_provider):
        file_provider.push_event({"type": "a"})
        file_provider.push_event({"type": "b"})
        events = file_provider.read_events()
        assert len(events) == 2

    def test_poison_event_dead_lettered(self, file_provider):
        file_provider.push_event({"type": "bad", "_attempts": 3})
        event = file_provider.pop_event()
        assert event is None
        # Verify it's in dead_events
        dead = list(file_provider.dead_events_dir.glob("*.json"))
        assert len(dead) == 1
        dead_event = json.loads(dead[0].read_text())
        assert dead_event["type"] == "bad"

    def test_nack_increments_attempts(self, file_provider):
        file_provider.push_event({"type": "retry"})
        event = file_provider.pop_event()
        assert event["_attempts"] == 0
        file_provider.nack_event(event)
        event2 = file_provider.pop_event()
        assert event2["_attempts"] == 1

    def test_nack_three_times_dead_letters(self, file_provider):
        file_provider.push_event({"type": "doomed"})
        for i in range(3):
            event = file_provider.pop_event()
            assert event is not None, f"Round {i+1}: should not be None"
            file_provider.nack_event(event)
        # 4th pop should get None (dead-lettered)
        assert file_provider.pop_event() is None
        dead = list(file_provider.dead_events_dir.glob("*.json"))
        assert len(dead) == 1

    def test_corrupted_event_skipped(self, file_provider):
        # Write a corrupted file
        bad_file = file_provider.events_dir / "00000000000000000000.json"
        bad_file.write_text("not json{{{")
        file_provider.push_event({"type": "good"})
        event = file_provider.pop_event()
        assert event["type"] == "good"
        assert not bad_file.exists()  # Corrupted file removed

    def test_backward_compatible_no_attempts_field(self, file_provider):
        # Simulate old event without _attempts
        ts = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        path = file_provider.events_dir / f"{ts}.json"
        path.write_text(json.dumps({"type": "legacy", "data": "old"}))
        event = file_provider.pop_event()
        assert event["type"] == "legacy"
        assert event["_attempts"] == 0


# ---------------------------------------------------------------------------
# State — designs
# ---------------------------------------------------------------------------

class TestStateDesigns:
    def test_create_and_get(self, state):
        d = state.create_design("build payments", category="feature")
        assert d["description"] == "build payments"
        assert d["stage"] == "design"
        retrieved = state.get_design(d["id"])
        assert retrieved["id"] == d["id"]

    def test_get_nonexistent(self, state):
        assert state.get_design("nonexistent") is None

    def test_update_stage(self, state):
        d = state.create_design("test")
        updated = state.update_design(d["id"], {"stage": "review"})
        assert updated["stage"] == "review"
        history = updated["history"]
        assert any(h["action"] == "stage_changed" for h in history)

    def test_add_child_ticket(self, state):
        d = state.create_design("test")
        updated = state.update_design(d["id"], {"add_child_ticket": "TOS-42"})
        assert "TOS-42" in updated["childTickets"]

    def test_add_pr(self, state):
        d = state.create_design("test")
        updated = state.update_design(d["id"], {"add_pr": 42})
        assert 42 in updated["prs"]

    def test_in_flight_comments(self, state):
        d = state.create_design("test")
        state.update_design(d["id"], {"set_in_flight_comments": ["c1", "c2"]})
        d2 = state.get_design(d["id"])
        assert set(d2["inFlightCommentIds"]) == {"c1", "c2"}

        state.update_design(d["id"], {"merge_in_flight_comments": ["c3"]})
        d3 = state.get_design(d["id"])
        assert "c3" in d3["inFlightCommentIds"]

        state.update_design(d["id"], {"clear_in_flight_comments": True})
        d4 = state.get_design(d["id"])
        assert d4["inFlightCommentIds"] == []
        assert set(d4["handledCommentIds"]) == {"c1", "c2", "c3"}

    def test_list_designs(self, state):
        state.create_design("a", category="feature")
        state.create_design("b", category="bug")
        designs = state.list_designs()
        assert len(designs) == 2

    def test_list_active_only(self, state):
        d = state.create_design("done")
        state.complete_design(d["id"])
        state.create_design("active")
        active = state.list_designs(active_only=True)
        assert len(active) == 1

    def test_list_by_stage(self, state):
        state.create_design("a")
        d2 = state.create_design("b")
        state.update_design(d2["id"], {"stage": "review"})
        review = state.list_designs(stage="review")
        assert len(review) == 1

    def test_delete_design(self, state):
        d = state.create_design("delete me")
        assert state.delete_design(d["id"]) is True
        assert state.get_design(d["id"]) is None

    def test_delete_nonexistent(self, state):
        assert state.delete_design("nope") is False

    def test_complete_design(self, state):
        d = state.create_design("finish")
        result = state.complete_design(d["id"])
        assert result["stage"] == "complete"


# ---------------------------------------------------------------------------
# State — sessions
# ---------------------------------------------------------------------------

class TestStateSessions:
    def test_save_and_get(self, state):
        s = state.save_session("TOS-1", "sess-abc", agent="code-writer", pr_number=10)
        assert s["issueKey"] == "TOS-1"
        assert s["sessionId"] == "sess-abc"
        retrieved = state.get_session("TOS-1")
        assert retrieved["agent"] == "code-writer"

    def test_get_nonexistent(self, state):
        assert state.get_session("nope") is None

    def test_delete_session(self, state):
        state.save_session("TOS-1", "sess-1")
        assert state.delete_session("TOS-1") is True
        assert state.get_session("TOS-1") is None

    def test_delete_nonexistent(self, state):
        assert state.delete_session("nope") is False

    def test_list_sessions(self, state):
        state.save_session("TOS-1", "s1")
        state.save_session("TOS-2", "s2")
        sessions = state.list_sessions()
        assert len(sessions) == 2


# ---------------------------------------------------------------------------
# State — watches
# ---------------------------------------------------------------------------

class TestStateWatches:
    def test_add_watch(self, state):
        w = state.add_watch("pr:ci", repo="org/repo", branch="main", prNumber=1)
        assert w["id"] == "w-1"
        assert w["type"] == "pr:ci"
        assert "expiresAt" in w

    def test_deduplication(self, state):
        w1 = state.add_watch("pr:ci", repo="org/repo", branch="main")
        w2 = state.add_watch("pr:ci", repo="org/repo", branch="main")
        assert w1["id"] == w2["id"]

    def test_no_dedup_different_key(self, state):
        w1 = state.add_watch("pr:ci", repo="org/repo", branch="main")
        w2 = state.add_watch("pr:ci", repo="org/repo", branch="feat")
        assert w1["id"] != w2["id"]

    def test_sequential_ids(self, state):
        w1 = state.add_watch("pr:ci", repo="a/b", branch="b1")
        w2 = state.add_watch("pr:ci", repo="a/b", branch="b2")
        assert w1["id"] == "w-1"
        assert w2["id"] == "w-2"

    def test_remove_watch(self, state):
        w = state.add_watch("pr:ci", repo="a/b", branch="main")
        assert state.remove_watch(w["id"]) is True
        assert state.list_watches() == []

    def test_remove_nonexistent(self, state):
        assert state.remove_watch("w-99") is False

    def test_list_watches_by_type(self, state):
        state.add_watch("pr:ci", repo="a/b", branch="main")
        state.add_watch("pr:review", repo="a/b", prNumber=1)
        ci_watches = state.list_watches(watch_type="pr:ci")
        assert len(ci_watches) == 1

    def test_ttl_default(self, state):
        w = state.add_watch("pr:ci", repo="a/b", branch="main")
        assert "expiresAt" in w

    def test_ttl_custom(self, state):
        expires = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
        w = state.add_watch("pr:ci", repo="a/b", branch="main", expiresAt=expires)
        assert w["expiresAt"] == expires

    def test_expire_watches(self, state):
        # Add an already-expired watch
        past = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
        w = state.add_watch("pr:ci", repo="a/b", branch="main", expiresAt=past)
        expired = state.expire_watches()
        assert len(expired) == 1
        assert expired[0]["id"] == w["id"]
        assert state.list_watches() == []

    def test_expire_keeps_alive_watches(self, state):
        future = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
        state.add_watch("pr:ci", repo="a/b", branch="main", expiresAt=future)
        expired = state.expire_watches()
        assert len(expired) == 0
        assert len(state.list_watches()) == 1


# ---------------------------------------------------------------------------
# State — events + poison protection
# ---------------------------------------------------------------------------

class TestStateEvents:
    def test_queue_and_pop(self, state):
        state.queue_event({"type": "ci:passed"})
        event = state.pop_event()
        assert event["type"] == "ci:passed"

    def test_pop_empty(self, state):
        assert state.pop_event() is None

    def test_nack_and_retry(self, state):
        state.queue_event({"type": "test"})
        e = state.pop_event()
        state.nack_event(e)
        e2 = state.pop_event()
        assert e2["_attempts"] == 1

    def test_poison_dead_lettered(self, state):
        state.queue_event({"type": "poison", "_attempts": 3})
        e = state.pop_event()
        assert e is None

    def test_list_events(self, state):
        state.queue_event({"type": "a"})
        state.queue_event({"type": "b"})
        events = state.list_events()
        assert len(events) == 2

    def test_page_comment_gating_handled(self, state):
        """page:comment with all handled comments should be dropped."""
        d = state.create_design("test")
        state.update_design(d["id"], {"add_handled_comments": ["c1", "c2"]})
        state.queue_event({
            "type": "page:comment",
            "designId": d["id"],
            "newCommentIds": ["c1", "c2"],
        })
        event = state.pop_event()
        assert event is None  # All handled → dropped

    def test_page_comment_gating_in_flight(self, state):
        """page:comment while architect busy should be deferred (merged)."""
        d = state.create_design("test")
        state.update_design(d["id"], {"set_in_flight_comments": ["c1"]})
        state.queue_event({
            "type": "page:comment",
            "designId": d["id"],
            "newCommentIds": ["c2"],
        })
        event = state.pop_event()
        assert event is None  # Deferred
        d2 = state.get_design(d["id"])
        assert "c2" in d2["inFlightCommentIds"]

    def test_page_comment_passes_through(self, state):
        """page:comment with new unhandled comments should pass through."""
        d = state.create_design("test")
        state.queue_event({
            "type": "page:comment",
            "designId": d["id"],
            "newCommentIds": ["c1"],
        })
        event = state.pop_event()
        assert event is not None
        assert event["type"] == "page:comment"


# ---------------------------------------------------------------------------
# State — log
# ---------------------------------------------------------------------------

class TestStateLog:
    def test_log_and_read(self, state):
        state.log("test_action", detail="hello")
        entries = state.read_log(last=10)
        assert len(entries) >= 1
        assert any(e["action"] == "test_action" for e in entries)

    def test_read_log_limit(self, state):
        for i in range(10):
            state.log(f"action_{i}")
        entries = state.read_log(last=3)
        assert len(entries) == 3

    def test_read_empty_log(self, state):
        # Clear the log file (it was touched on init, may have entries from create)
        state.log_file.write_text("")
        entries = state.read_log()
        assert entries == []


# ---------------------------------------------------------------------------
# State — summary
# ---------------------------------------------------------------------------

class TestStateSummary:
    def test_summary_empty(self, state):
        summary = state.summary()
        assert "Active Designs (0)" in summary
        assert "No active designs" in summary

    def test_summary_with_data(self, state):
        state.create_design("payment feature", category="feature")
        state.add_watch("pr:ci", repo="a/b", branch="main")
        state.queue_event({"type": "ci:passed"})
        summary = state.summary()
        assert "Active Designs (1)" in summary
        assert "Active Watches (1)" in summary
        assert "Pending Events (1)" in summary
        assert "payment feature" in summary
