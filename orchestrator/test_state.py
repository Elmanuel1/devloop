"""Tests for state.py — FileStateProvider + State manager + CLI."""

import json
import os
import subprocess
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

# Ensure we import from the orchestrator directory
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))

os.environ.pop("STATE_BACKEND", None)

from state import FileStateProvider, InvalidStageTransition, State, VALID_STAGE_TRANSITIONS, _flag


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
# _flag helper
# ---------------------------------------------------------------------------

class TestFlagHelper:
    def test_flag_present(self):
        assert _flag(["--name", "value", "--other"], "--name") == "value"

    def test_flag_absent(self):
        assert _flag(["--other", "val"], "--name", "default") == "default"

    def test_flag_at_end(self):
        assert _flag(["--name"], "--name", "fallback") == "fallback"

    def test_flag_empty_args(self):
        assert _flag([], "--name") is None


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

    def test_read_corrupted_file(self, file_provider):
        file_provider.watches_file.write_text("not valid json{{{")
        assert file_provider.read_watches() == []


# ---------------------------------------------------------------------------
# FileStateProvider — events
# ---------------------------------------------------------------------------

class TestFileProviderEvents:
    def test_push_and_pop(self, file_provider):
        file_provider.push_event({"type": "ci:passed", "repo": "a/b"}, channel="ci")
        event = file_provider.pop_event(channel="ci")
        assert event["type"] == "ci:passed"
        assert event["repo"] == "a/b"
        assert event["_attempts"] == 0
        assert event["_channel"] == "ci"

    def test_pop_empty(self, file_provider):
        assert file_provider.pop_event() is None

    def test_fifo_order(self, file_provider):
        import time
        file_provider.push_event({"type": "first"}, channel="system")
        time.sleep(0.01)  # Ensure different timestamps
        file_provider.push_event({"type": "second"}, channel="system")
        assert file_provider.pop_event(channel="system")["type"] == "first"
        assert file_provider.pop_event(channel="system")["type"] == "second"

    def test_read_events(self, file_provider):
        file_provider.push_event({"type": "a"}, channel="ci")
        file_provider.push_event({"type": "b"}, channel="pr")
        events = file_provider.read_events()
        assert len(events) == 2

    def test_read_events_by_channel(self, file_provider):
        file_provider.push_event({"type": "ci:passed"}, channel="ci")
        file_provider.push_event({"type": "pr:comment"}, channel="pr")
        assert len(file_provider.read_events(channel="ci")) == 1
        assert len(file_provider.read_events(channel="pr")) == 1

    def test_poison_event_dead_lettered(self, file_provider):
        file_provider.push_event({"type": "bad", "_attempts": 3}, channel="system")
        event = file_provider.pop_event(channel="system")
        assert event is None
        dead = list(file_provider.dead_events_dir.glob("*.json"))
        assert len(dead) == 1
        dead_event = json.loads(dead[0].read_text())
        assert dead_event["type"] == "bad"

    def test_nack_increments_attempts(self, file_provider):
        file_provider.push_event({"type": "retry"}, channel="system")
        event = file_provider.pop_event(channel="system")
        assert event["_attempts"] == 0
        file_provider.nack_event(event)
        event2 = file_provider.pop_event(channel="system")
        assert event2["_attempts"] == 1

    def test_nack_three_times_dead_letters(self, file_provider):
        file_provider.push_event({"type": "doomed"}, channel="system")
        for i in range(3):
            event = file_provider.pop_event(channel="system")
            assert event is not None, f"Round {i+1}: should not be None"
            file_provider.nack_event(event)
        # 4th pop should get None (dead-lettered)
        assert file_provider.pop_event(channel="system") is None
        dead = list(file_provider.dead_events_dir.glob("*.json"))
        assert len(dead) == 1

    def test_corrupted_event_skipped(self, file_provider):
        # Write a corrupted file into the system channel dir
        bad_file = file_provider.events_dir / "system" / "00000000000000000000.json"
        bad_file.write_text("not json{{{")
        file_provider.push_event({"type": "good"}, channel="system")
        event = file_provider.pop_event(channel="system")
        assert event["type"] == "good"
        assert not bad_file.exists()  # Corrupted file removed

    def test_backward_compatible_no_attempts_field(self, file_provider):
        # Simulate old event without _attempts in a channel dir
        ts = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        path = file_provider.events_dir / "system" / f"{ts}.json"
        path.write_text(json.dumps({"type": "legacy", "data": "old"}))
        event = file_provider.pop_event(channel="system")
        assert event["type"] == "legacy"
        assert event["_attempts"] == 0

    def test_round_robin_pop(self, file_provider):
        file_provider.push_event({"type": "ci:passed"}, channel="ci")
        file_provider.push_event({"type": "pr:comment"}, channel="pr")
        types = set()
        e1 = file_provider.pop_event()
        if e1:
            types.add(e1["_channel"])
        e2 = file_provider.pop_event()
        if e2:
            types.add(e2["_channel"])
        assert len(types) == 2


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

    def test_add_artifact(self, state):
        d = state.create_design("test")
        updated = state.update_design(d["id"], {"add_artifact": {"design_doc": "/path/to/doc"}})
        assert updated["artifacts"]["design_doc"] == "/path/to/doc"

    def test_add_artifact_non_dict(self, state):
        d = state.create_design("test")
        updated = state.update_design(d["id"], {"add_artifact": "just a string"})
        # Should not crash, artifact not updated as dict
        assert any(h["action"] == "artifact_added" for h in updated["history"])

    def test_add_history(self, state):
        d = state.create_design("test")
        entry = {"ts": "now", "action": "custom_event", "detail": "test"}
        updated = state.update_design(d["id"], {"add_history": entry})
        assert any(h["action"] == "custom_event" for h in updated["history"])

    def test_add_handled_comments(self, state):
        d = state.create_design("test")
        updated = state.update_design(d["id"], {"add_handled_comments": ["c1", "c2"]})
        assert set(updated["handledCommentIds"]) == {"c1", "c2"}

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

    def test_update_nonexistent(self, state):
        assert state.update_design("nonexistent", {"stage": "review"}) is None

    def test_update_generic_field(self, state):
        d = state.create_design("test")
        updated = state.update_design(d["id"], {"confluencePageId": "12345"})
        assert updated["confluencePageId"] == "12345"

    def test_list_designs(self, state):
        state.create_design("a", category="feature")
        state.create_design("b", category="bug")
        designs = state.list_designs()
        assert len(designs) == 2

    def test_list_active_only(self, state):
        d = state.create_design("done")
        # Walk through full lifecycle to reach complete
        for stage in ("review", "approved", "jira-breakdown", "implementation"):
            state.update_design(d["id"], {"stage": stage})
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
        for stage in ("review", "approved", "jira-breakdown", "implementation"):
            state.update_design(d["id"], {"stage": stage})
        result = state.complete_design(d["id"])
        assert result["stage"] == "complete"

    def test_create_with_custom_id(self, state):
        d = state.create_design("test", design_id="custom-id")
        assert d["id"] == "custom-id"
        assert state.get_design("custom-id") is not None


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

    def test_save_with_extra(self, state):
        s = state.save_session("TOS-1", "s1", extra={"worktree": "/tmp/wt"})
        assert s["worktree"] == "/tmp/wt"


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

    def test_expire_bad_date_format(self, state):
        """Watch with invalid expiresAt format should be kept alive."""
        watches = [{"id": "w-1", "type": "pr:ci", "interval": 30, "expiresAt": "not-a-date"}]
        state.provider.write_watches(watches)
        expired = state.expire_watches()
        assert len(expired) == 0  # Bad date → kept alive
        assert len(state.list_watches()) == 1

    def test_add_watch_unknown_type_dedup(self, state):
        """Unknown type falls back to ('type',) dedup key."""
        w1 = state.add_watch("custom:type", repo="a/b")
        w2 = state.add_watch("custom:type", repo="c/d")
        # Same type but different unknown fields — dedup only on type
        assert w1["id"] == w2["id"]

    def test_add_watch_non_numeric_id(self, state):
        """Handles existing watches with non-standard IDs."""
        watches = [{"id": "w-abc", "type": "pr:ci", "interval": 30}]
        state.provider.write_watches(watches)
        w = state.add_watch("pr:merge", repo="a/b", prNumber=1)
        assert w["id"] == "w-1"  # Starts from 1 since w-abc is not numeric


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

    def test_page_comment_no_design_id(self, state):
        """page:comment without designId should pass through."""
        state.queue_event({"type": "page:comment", "newCommentIds": ["c1"]})
        event = state.pop_event()
        assert event is not None

    def test_page_comment_missing_design(self, state):
        """page:comment referencing non-existent design should pass through."""
        state.queue_event({
            "type": "page:comment",
            "designId": "nonexistent",
            "newCommentIds": ["c1"],
        })
        event = state.pop_event()
        assert event is not None


# ---------------------------------------------------------------------------
# State — event dedup
# ---------------------------------------------------------------------------

class TestDedupKey:
    def test_pr_comment(self):
        from state import _dedup_key
        assert _dedup_key({"type": "pr:comment", "commentId": 123}) == "pr:comment:123"

    def test_pr_comment_no_id(self):
        from state import _dedup_key
        assert _dedup_key({"type": "pr:comment"}) is None

    def test_ci_with_sha(self):
        from state import _dedup_key
        e = {"type": "ci:failed", "repo": "o/r", "headSha": "abc123"}
        assert _dedup_key(e) == "ci:failed:o/r:abc123"

    def test_ci_no_sha(self):
        from state import _dedup_key
        assert _dedup_key({"type": "ci:passed", "repo": "o/r"}) is None

    def test_pr_approved(self):
        from state import _dedup_key
        e = {"type": "pr:approved", "repo": "o/r", "prNumber": 42}
        assert _dedup_key(e) == "pr:approved:o/r:42"

    def test_pr_merged(self):
        from state import _dedup_key
        e = {"type": "pr:merged", "repo": "o/r", "prNumber": 42}
        assert _dedup_key(e) == "pr:merged:o/r:42"

    def test_unkeyed_event(self):
        from state import _dedup_key
        assert _dedup_key({"type": "task:requested"}) is None
        assert _dedup_key({"type": "page:comment"}) is None


class TestEventDedup:
    def test_pr_comment_deduped(self, state):
        state.queue_event({"type": "pr:comment", "repo": "o/r", "prNumber": 1, "commentId": 100})
        state.queue_event({"type": "pr:comment", "repo": "o/r", "prNumber": 1, "commentId": 100})
        assert len(state.list_events(channel="pr")) == 1

    def test_pr_comment_different_ids(self, state):
        state.queue_event({"type": "pr:comment", "repo": "o/r", "prNumber": 1, "commentId": 100})
        state.queue_event({"type": "pr:comment", "repo": "o/r", "prNumber": 1, "commentId": 200})
        assert len(state.list_events(channel="pr")) == 2

    def test_ci_same_sha_deduped(self, state):
        state.queue_event({"type": "ci:failed", "repo": "o/r", "headSha": "abc"})
        state.queue_event({"type": "ci:failed", "repo": "o/r", "headSha": "abc"})
        assert len(state.list_events(channel="ci")) == 1

    def test_ci_different_sha_not_deduped(self, state):
        state.queue_event({"type": "ci:failed", "repo": "o/r", "headSha": "abc"})
        state.queue_event({"type": "ci:failed", "repo": "o/r", "headSha": "def"})
        assert len(state.list_events(channel="ci")) == 2

    def test_ci_no_sha_not_deduped(self, state):
        state.queue_event({"type": "ci:passed", "repo": "o/r"})
        state.queue_event({"type": "ci:passed", "repo": "o/r"})
        assert len(state.list_events(channel="ci")) == 2

    def test_pr_approved_deduped(self, state):
        state.queue_event({"type": "pr:approved", "repo": "o/r", "prNumber": 42})
        state.queue_event({"type": "pr:approved", "repo": "o/r", "prNumber": 42})
        assert len(state.list_events(channel="pr")) == 1

    def test_unkeyed_never_deduped(self, state):
        state.queue_event({"type": "task:requested", "message": "a"})
        state.queue_event({"type": "task:requested", "message": "b"})
        assert len(state.list_events(channel="task")) == 2


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

    def test_read_log_with_corrupted_lines(self, state):
        state.log_file.write_text('{"action":"good"}\nnot json\n{"action":"also_good"}\n')
        entries = state.read_log()
        assert len(entries) == 2

    def test_log_filters_none_values(self, state):
        state.log("test_action", detail="hello", empty_field=None)
        entries = state.read_log(last=1)
        assert "empty_field" not in entries[0]


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

    def test_summary_with_sessions(self, state):
        state.save_session("TOS-1", "sess-1", agent="code-writer", pr_number=42)
        summary = state.summary()
        assert "Active Sessions (1)" in summary
        assert "TOS-1" in summary
        assert "PR #42" in summary

    def test_summary_in_flight_comments(self, state):
        d = state.create_design("test")
        state.update_design(d["id"], {"set_in_flight_comments": ["c1", "c2"]})
        summary = state.summary()
        assert "2 with architect" in summary


# ---------------------------------------------------------------------------
# State — SQLite backend initialization
# ---------------------------------------------------------------------------

class TestSQLiteInit:
    def test_sqlite_backend_auto_creates(self, tmp_dir):
        os.environ["STATE_BACKEND"] = "sqlite"
        try:
            s = State(base_dir=str(tmp_dir))
            assert (tmp_dir / "state.db").exists()
        finally:
            os.environ.pop("STATE_BACKEND", None)

    def test_sqlite_backend_migrates_from_files(self, tmp_dir):
        # Create file-based state first
        os.environ.pop("STATE_BACKEND", None)
        s1 = State(base_dir=str(tmp_dir))
        s1.create_design("test design")

        # Switch to sqlite
        os.environ["STATE_BACKEND"] = "sqlite"
        try:
            s2 = State(base_dir=str(tmp_dir))
            assert (tmp_dir / "state.db").exists()
        finally:
            os.environ.pop("STATE_BACKEND", None)


# ---------------------------------------------------------------------------
# CLI tests (subprocess-based)
# ---------------------------------------------------------------------------

SCRIPT = str(Path(__file__).resolve().parent / "state_cli.py")


def run_cli(*args, input_data=None, tmp_dir=None):
    """Run state.py CLI and return (exit_code, stdout, stderr)."""
    env = os.environ.copy()
    env.pop("STATE_BACKEND", None)
    if tmp_dir:
        env["STATE_DIR"] = str(tmp_dir)
    result = subprocess.run(
        [sys.executable, SCRIPT] + list(args),
        capture_output=True, text=True, timeout=10,
        cwd=str(Path(SCRIPT).parent),
        env=env,
        input=input_data,
    )
    return result.returncode, result.stdout, result.stderr


class TestCLI:
    def test_no_args_prints_help(self, tmp_dir):
        code, out, err = run_cli(tmp_dir=tmp_dir)
        assert code == 0
        assert "state.py" in out.lower() or "design" in out.lower()

    def test_unknown_group(self, tmp_dir):
        code, out, err = run_cli("unknown", tmp_dir=tmp_dir)
        assert code == 1

    # --- design CLI ---

    def test_design_no_subcommand(self, tmp_dir):
        code, out, err = run_cli("design", tmp_dir=tmp_dir)
        assert code == 1

    def test_design_create(self, tmp_dir):
        code, out, err = run_cli("design", "create", "test feature", "--category", "feature", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["description"] == "test feature"
        assert d["category"] == "feature"

    def test_design_create_with_id(self, tmp_dir):
        code, out, err = run_cli("design", "create", "test", "--id", "custom-id", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["id"] == "custom-id"

    def test_design_get(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "get", "d-1", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["id"] == "d-1"

    def test_design_get_not_found(self, tmp_dir):
        code, out, err = run_cli("design", "get", "nonexistent", tmp_dir=tmp_dir)
        assert code == 1
        assert "not found" in out.lower()

    def test_design_update_stage(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--stage", "review", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["stage"] == "review"

    def test_design_update_confluence_page(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--confluence-page", "12345", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["confluencePageId"] == "12345"

    def test_design_update_jira_parent(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--jira-parent", "TOS-40", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["jiraParentKey"] == "TOS-40"

    def test_design_update_add_child(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--add-child", "TOS-41", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert "TOS-41" in d["childTickets"]

    def test_design_update_add_pr(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--add-pr", "42", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert 42 in d["prs"]

    def test_design_update_add_artifact(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--add-artifact", '{"doc":"/path"}', tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["artifacts"]["doc"] == "/path"

    def test_design_update_in_flight_comments(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--set-in-flight-comments", "c1,c2", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert set(d["inFlightCommentIds"]) == {"c1", "c2"}

    def test_design_update_merge_in_flight(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        run_cli("design", "update", "d-1", "--set-in-flight-comments", "c1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--merge-in-flight-comments", "c2,c3", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert "c2" in d["inFlightCommentIds"]

    def test_design_update_clear_in_flight(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        run_cli("design", "update", "d-1", "--set-in-flight-comments", "c1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--clear-in-flight-comments", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert d["inFlightCommentIds"] == []

    def test_design_update_handled_comments(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "update", "d-1", "--add-handled-comments", "c1,c2", tmp_dir=tmp_dir)
        assert code == 0
        d = json.loads(out)
        assert set(d["handledCommentIds"]) == {"c1", "c2"}

    def test_design_list(self, tmp_dir):
        run_cli("design", "create", "a", tmp_dir=tmp_dir)
        run_cli("design", "create", "b", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "list", tmp_dir=tmp_dir)
        assert code == 0
        designs = json.loads(out)
        assert len(designs) == 2

    def test_design_list_active(self, tmp_dir):
        run_cli("design", "create", "a", "--id", "d-1", tmp_dir=tmp_dir)
        for stage in ("review", "approved", "jira-breakdown", "implementation"):
            run_cli("design", "update", "d-1", "--stage", stage, tmp_dir=tmp_dir)
        run_cli("design", "complete", "d-1", tmp_dir=tmp_dir)
        run_cli("design", "create", "b", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "list", "--active", tmp_dir=tmp_dir)
        assert code == 0
        designs = json.loads(out)
        assert len(designs) == 1

    def test_design_list_by_stage(self, tmp_dir):
        run_cli("design", "create", "a", "--id", "d-1", tmp_dir=tmp_dir)
        run_cli("design", "update", "d-1", "--stage", "review", tmp_dir=tmp_dir)
        run_cli("design", "create", "b", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "list", "--stage", "review", tmp_dir=tmp_dir)
        assert code == 0
        designs = json.loads(out)
        assert len(designs) == 1

    def test_design_complete(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        for stage in ("review", "approved", "jira-breakdown", "implementation"):
            run_cli("design", "update", "d-1", "--stage", stage, tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "complete", "d-1", tmp_dir=tmp_dir)
        assert code == 0

    def test_design_delete(self, tmp_dir):
        run_cli("design", "create", "test", "--id", "d-1", tmp_dir=tmp_dir)
        code, out, err = run_cli("design", "delete", "d-1", tmp_dir=tmp_dir)
        assert code == 0
        assert "Deleted" in out

    def test_design_delete_not_found(self, tmp_dir):
        code, out, err = run_cli("design", "delete", "nope", tmp_dir=tmp_dir)
        assert "Not found" in out

    def test_design_unknown_command(self, tmp_dir):
        code, out, err = run_cli("design", "unknown", tmp_dir=tmp_dir)
        assert code == 1

    # --- session CLI ---

    def test_session_no_subcommand(self, tmp_dir):
        code, out, err = run_cli("session", tmp_dir=tmp_dir)
        assert code == 1

    def test_session_save(self, tmp_dir):
        code, out, err = run_cli("session", "save", "TOS-1", "--session-id", "s1", "--agent", "writer", "--pr", "42", tmp_dir=tmp_dir)
        assert code == 0
        s = json.loads(out)
        assert s["issueKey"] == "TOS-1"
        assert s["agent"] == "writer"

    def test_session_get(self, tmp_dir):
        run_cli("session", "save", "TOS-1", "--session-id", "s1", tmp_dir=tmp_dir)
        code, out, err = run_cli("session", "get", "TOS-1", tmp_dir=tmp_dir)
        assert code == 0
        s = json.loads(out)
        assert s["sessionId"] == "s1"

    def test_session_get_not_found(self, tmp_dir):
        code, out, err = run_cli("session", "get", "nope", tmp_dir=tmp_dir)
        assert code == 1

    def test_session_delete(self, tmp_dir):
        run_cli("session", "save", "TOS-1", "--session-id", "s1", tmp_dir=tmp_dir)
        code, out, err = run_cli("session", "delete", "TOS-1", tmp_dir=tmp_dir)
        assert code == 0
        assert "Deleted" in out

    def test_session_list(self, tmp_dir):
        run_cli("session", "save", "TOS-1", "--session-id", "s1", tmp_dir=tmp_dir)
        code, out, err = run_cli("session", "list", tmp_dir=tmp_dir)
        assert code == 0
        sessions = json.loads(out)
        assert len(sessions) == 1

    def test_session_unknown_command(self, tmp_dir):
        code, out, err = run_cli("session", "unknown", tmp_dir=tmp_dir)
        assert code == 1

    # --- watch CLI ---

    def test_watch_no_subcommand(self, tmp_dir):
        code, out, err = run_cli("watch", tmp_dir=tmp_dir)
        assert code == 1

    def test_watch_add(self, tmp_dir):
        code, out, err = run_cli("watch", "add", "pr:ci", "--repo", "a/b", "--branch", "main", "--pr", "1", "--issue", "TOS-1", tmp_dir=tmp_dir)
        assert code == 0
        w = json.loads(out)
        assert w["type"] == "pr:ci"
        assert w["repo"] == "a/b"

    def test_watch_add_with_ttl(self, tmp_dir):
        code, out, err = run_cli("watch", "add", "pr:ci", "--repo", "a/b", "--branch", "main", "--ttl", "3600", tmp_dir=tmp_dir)
        assert code == 0
        w = json.loads(out)
        assert "expiresAt" in w

    def test_watch_add_confluence_review(self, tmp_dir):
        code, out, err = run_cli("watch", "add", "confluence:review", "--page", "12345", "--design", "d-1", tmp_dir=tmp_dir)
        assert code == 0
        w = json.loads(out)
        assert w["type"] == "confluence:review"

    def test_watch_add_with_interval(self, tmp_dir):
        code, out, err = run_cli("watch", "add", "pr:ci", "--repo", "a/b", "--branch", "main", "--interval", "60", tmp_dir=tmp_dir)
        assert code == 0
        w = json.loads(out)
        assert w["interval"] == 60

    def test_watch_remove(self, tmp_dir):
        run_cli("watch", "add", "pr:ci", "--repo", "a/b", "--branch", "main", tmp_dir=tmp_dir)
        code, out, err = run_cli("watch", "remove", "w-1", tmp_dir=tmp_dir)
        assert code == 0
        assert "Removed" in out

    def test_watch_remove_not_found(self, tmp_dir):
        code, out, err = run_cli("watch", "remove", "w-99", tmp_dir=tmp_dir)
        assert "Not found" in out

    def test_watch_list(self, tmp_dir):
        run_cli("watch", "add", "pr:ci", "--repo", "a/b", "--branch", "main", tmp_dir=tmp_dir)
        code, out, err = run_cli("watch", "list", tmp_dir=tmp_dir)
        assert code == 0
        watches = json.loads(out)
        assert len(watches) == 1

    def test_watch_list_by_type(self, tmp_dir):
        run_cli("watch", "add", "pr:ci", "--repo", "a/b", "--branch", "main", tmp_dir=tmp_dir)
        run_cli("watch", "add", "pr:review", "--repo", "a/b", "--pr", "1", tmp_dir=tmp_dir)
        code, out, err = run_cli("watch", "list", "--type", "pr:ci", tmp_dir=tmp_dir)
        assert code == 0
        watches = json.loads(out)
        assert len(watches) == 1

    def test_watch_unknown_command(self, tmp_dir):
        code, out, err = run_cli("watch", "unknown", tmp_dir=tmp_dir)
        assert code == 1

    # --- events CLI ---

    def test_events_no_subcommand(self, tmp_dir):
        code, out, err = run_cli("events", tmp_dir=tmp_dir)
        assert code == 1

    def test_events_pop_empty(self, tmp_dir):
        code, out, err = run_cli("events", "pop", tmp_dir=tmp_dir)
        assert code == 0
        data = json.loads(out)
        assert data["type"] == "empty"

    def test_events_list(self, tmp_dir):
        # First queue an event
        env = os.environ.copy()
        env.pop("STATE_BACKEND", None)
        env["STATE_DIR"] = str(tmp_dir)
        s = State(base_dir=str(tmp_dir))
        s.queue_event({"type": "test"})
        code, out, err = run_cli("events", "list", tmp_dir=tmp_dir)
        assert code == 0
        events = json.loads(out)
        assert len(events) == 1

    def test_events_nack(self, tmp_dir):
        event = json.dumps({"type": "retry", "_attempts": 0})
        code, out, err = run_cli("events", "nack", tmp_dir=tmp_dir, input_data=event)
        assert code == 0
        data = json.loads(out)
        assert data["ok"] is True

    def test_events_nack_empty_stdin(self, tmp_dir):
        code, out, err = run_cli("events", "nack", tmp_dir=tmp_dir, input_data="")
        assert code == 1

    def test_events_dead(self, tmp_dir):
        code, out, err = run_cli("events", "dead", tmp_dir=tmp_dir)
        assert code == 0
        dead = json.loads(out)
        assert dead == []

    def test_events_retry_dead(self, tmp_dir):
        # Create a dead event
        dead_dir = Path(tmp_dir) / "dead_events"
        dead_dir.mkdir(parents=True, exist_ok=True)
        (dead_dir / "test.json").write_text(json.dumps({"type": "dead", "_attempts": 3}))
        code, out, err = run_cli("events", "retry-dead", tmp_dir=tmp_dir)
        assert code == 0
        data = json.loads(out)
        assert data["retried"] == 1

    def test_events_unknown_command(self, tmp_dir):
        code, out, err = run_cli("events", "unknown", tmp_dir=tmp_dir)
        assert code == 1

    # --- log CLI ---

    def test_log_no_subcommand(self, tmp_dir):
        code, out, err = run_cli("log", tmp_dir=tmp_dir)
        assert code == 1

    def test_log_append(self, tmp_dir):
        code, out, err = run_cli("log", "append", "test_action", "--detail", "hello", "--design-id", "d-1", "--issue-key", "TOS-1", tmp_dir=tmp_dir)
        assert code == 0
        assert "Logged" in out

    def test_log_show(self, tmp_dir):
        run_cli("log", "append", "test_action", tmp_dir=tmp_dir)
        code, out, err = run_cli("log", "show", "--last", "5", tmp_dir=tmp_dir)
        assert code == 0
        assert "test_action" in out

    def test_log_unknown_command(self, tmp_dir):
        code, out, err = run_cli("log", "unknown", tmp_dir=tmp_dir)
        assert code == 1

    # --- summary CLI ---

    def test_summary(self, tmp_dir):
        code, out, err = run_cli("summary", tmp_dir=tmp_dir)
        assert code == 0
        assert "Active Designs" in out

    # --- migrate CLI ---

    def test_migrate_wrong_target(self, tmp_dir):
        code, out, err = run_cli("migrate", "wrong", tmp_dir=tmp_dir)
        assert code == 1

    def test_migrate_sqlite(self, tmp_dir):
        code, out, err = run_cli("migrate", "sqlite", tmp_dir=tmp_dir)
        assert code == 0
        assert "Migrated" in out
        assert (Path(tmp_dir) / "state.db").exists()

    def test_migrate_sqlite_already_exists(self, tmp_dir):
        # First migration
        run_cli("migrate", "sqlite", tmp_dir=tmp_dir)
        # Second should fail
        code, out, err = run_cli("migrate", "sqlite", tmp_dir=tmp_dir)
        assert code == 1
        assert "already exists" in out


# ---------------------------------------------------------------------------
# Stage transition guards
# ---------------------------------------------------------------------------

class TestStageTransitions:
    """Verify the design stage machine enforces valid transitions."""

    def test_valid_full_lifecycle(self, state):
        """design → review → approved → jira-breakdown → implementation → complete"""
        d = state.create_design("lifecycle test")
        assert d["stage"] == "design"

        d = state.update_design(d["id"], {"stage": "review"})
        assert d["stage"] == "review"

        d = state.update_design(d["id"], {"stage": "approved"})
        assert d["stage"] == "approved"

        d = state.update_design(d["id"], {"stage": "jira-breakdown"})
        assert d["stage"] == "jira-breakdown"

        d = state.update_design(d["id"], {"stage": "implementation"})
        assert d["stage"] == "implementation"

        d = state.complete_design(d["id"])
        assert d["stage"] == "complete"

    def test_review_to_design_rework(self, state):
        """review → design is allowed (sent back for rework)."""
        d = state.create_design("rework test")
        state.update_design(d["id"], {"stage": "review"})
        d = state.update_design(d["id"], {"stage": "design"})
        assert d["stage"] == "design"

    def test_cannot_skip_review(self, state):
        """design → approved is blocked (must go through review)."""
        d = state.create_design("skip test")
        with pytest.raises(InvalidStageTransition, match="design.*approved"):
            state.update_design(d["id"], {"stage": "approved"})

    def test_cannot_skip_to_jira_from_review(self, state):
        """review → jira-breakdown is blocked (must be approved first)."""
        d = state.create_design("skip jira test")
        state.update_design(d["id"], {"stage": "review"})
        with pytest.raises(InvalidStageTransition, match="review.*jira-breakdown"):
            state.update_design(d["id"], {"stage": "jira-breakdown"})

    def test_cannot_complete_from_design(self, state):
        """design → complete is blocked."""
        d = state.create_design("premature complete")
        with pytest.raises(InvalidStageTransition):
            state.complete_design(d["id"])

    def test_cannot_complete_from_approved(self, state):
        """approved → complete is blocked (must go through implementation)."""
        d = state.create_design("premature complete")
        state.update_design(d["id"], {"stage": "review"})
        state.update_design(d["id"], {"stage": "approved"})
        with pytest.raises(InvalidStageTransition):
            state.complete_design(d["id"])

    def test_design_not_mutated_on_invalid_transition(self, state):
        """Failed transition must not modify the persisted design."""
        d = state.create_design("no mutation test")
        with pytest.raises(InvalidStageTransition):
            state.update_design(d["id"], {"stage": "approved"})
        persisted = state.get_design(d["id"])
        assert persisted["stage"] == "design"

    def test_non_stage_update_always_allowed(self, state):
        """Updating non-stage fields has no transition check."""
        d = state.create_design("metadata test")
        d = state.update_design(d["id"], {"confluencePageId": "12345"})
        assert d["confluencePageId"] == "12345"
        assert d["stage"] == "design"

    def test_stage_transition_logged(self, state):
        """Valid transitions are logged with from/to."""
        d = state.create_design("log test")
        state.update_design(d["id"], {"stage": "review"})
        log = state.read_log(last=5)
        stage_logs = [e for e in log if e["action"] == "stage_design_to_review"]
        assert len(stage_logs) == 1
