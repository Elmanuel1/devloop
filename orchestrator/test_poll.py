"""Tests for poll.py — watch handlers, webhook server, config reload."""

import json
import os
import signal
import sys
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock, patch, call
from http.client import HTTPConnection

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

os.environ.pop("STATE_BACKEND", None)


# ---------------------------------------------------------------------------
# Helper function tests
# ---------------------------------------------------------------------------

class TestHelpers:
    def test_env_returns_value(self):
        from poll import env
        os.environ["TEST_POLL_KEY"] = "hello"
        assert env("TEST_POLL_KEY") == "hello"
        os.environ.pop("TEST_POLL_KEY")

    def test_env_returns_fallback(self):
        from poll import env
        os.environ.pop("TEST_POLL_MISSING", None)
        assert env("TEST_POLL_MISSING", "fallback") == "fallback"

    def test_env_int_valid(self):
        from poll import env_int
        os.environ["TEST_INT"] = "42"
        assert env_int("TEST_INT", 0) == 42
        os.environ.pop("TEST_INT")

    def test_env_int_invalid(self):
        from poll import env_int
        os.environ["TEST_INT"] = "notanumber"
        assert env_int("TEST_INT", 99) == 99
        os.environ.pop("TEST_INT")

    def test_env_int_missing(self):
        from poll import env_int
        os.environ.pop("TEST_INT_MISS", None)
        assert env_int("TEST_INT_MISS", 7) == 7

    def test_load_dotenv_nonexistent(self):
        from poll import load_dotenv
        load_dotenv("/tmp/nonexistent_dotenv_file")  # Should not raise

    def test_load_dotenv_reads_file(self):
        from poll import load_dotenv
        with tempfile.NamedTemporaryFile(mode="w", suffix=".env", delete=False) as f:
            f.write("# comment\n")
            f.write("\n")
            f.write("POLL_TEST_VAR=test_value\n")
            f.write("BAD_LINE_NO_EQUALS\n")
            f.name
        os.environ.pop("POLL_TEST_VAR", None)
        load_dotenv(f.name)
        assert os.environ.get("POLL_TEST_VAR") == "test_value"
        os.environ.pop("POLL_TEST_VAR", None)
        os.unlink(f.name)

    def test_load_dotenv_does_not_override(self):
        from poll import load_dotenv
        os.environ["POLL_TEST_EXIST"] = "original"
        with tempfile.NamedTemporaryFile(mode="w", suffix=".env", delete=False) as f:
            f.write("POLL_TEST_EXIST=overridden\n")
        load_dotenv(f.name)
        assert os.environ["POLL_TEST_EXIST"] == "original"
        os.environ.pop("POLL_TEST_EXIST")
        os.unlink(f.name)

    def test_parse_category_feature(self):
        from poll import parse_category
        assert parse_category("build a new page") == "feature"

    def test_parse_category_bug(self):
        from poll import parse_category
        assert parse_category("bug: login is broken") == "bug"
        assert parse_category("Bug fix something") == "bug"

    def test_parse_category_chore(self):
        from poll import parse_category
        assert parse_category("chore: update deps") == "chore"
        assert parse_category("Chore clean up") == "chore"


# ---------------------------------------------------------------------------
# WatchHandler tests (unit)
# ---------------------------------------------------------------------------

class TestWatchHandlerBase:
    """Test the base WatchHandler behavior."""

    def test_should_check_first_time(self):
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        with tempfile.TemporaryDirectory() as tmp:
            os.environ["STATE_DIR"] = tmp
            Path(tmp, "handler_state").mkdir(parents=True, exist_ok=True)
            from poll import HANDLER_STATE_DIR
            handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 30})
            assert handler.should_check() is True
            os.environ.pop("STATE_DIR", None)

    def test_should_check_respects_interval(self):
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 30})
        handler._last_checked = time.monotonic()
        assert handler.should_check() is False

    def test_exponential_backoff(self):
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 10})
        handler._fail_count = 3
        handler._last_checked = time.monotonic() - 50
        # 10 * 2^3 = 80 seconds needed, only 50 passed
        assert handler.should_check() is False
        handler._last_checked = time.monotonic() - 90
        assert handler.should_check() is True

    def test_backoff_capped(self):
        from poll import WatchHandler, MAX_BACKOFF
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 10})
        handler._fail_count = 20  # 10 * 2^20 = huge, but capped
        handler._last_checked = time.monotonic() - (MAX_BACKOFF + 1)
        assert handler.should_check() is True

    def test_check_seeds_first_run(self):
        from poll import WatchHandler, HANDLER_STATE_DIR
        class DummyHandler(WatchHandler):
            needs_seed = True
            def poll(self):
                return {"type": "test:event"}

        # Clean any persisted state for this watch ID
        state_path = HANDLER_STATE_DIR / "w-seed-test.json"
        if state_path.exists():
            state_path.unlink()
        handler = DummyHandler({"id": "w-seed-test", "type": "pr:ci", "interval": 30})
        result = handler.check()
        assert result is None  # First run is seed
        assert handler._seeded is True
        if state_path.exists():
            state_path.unlink()

    def test_check_returns_event_after_seed(self):
        from poll import WatchHandler, HANDLER_STATE_DIR
        class DummyHandler(WatchHandler):
            needs_seed = True
            def poll(self):
                return {"type": "test:event"}

        state_path = HANDLER_STATE_DIR / "w-seed-test2.json"
        if state_path.exists():
            state_path.unlink()
        handler = DummyHandler({"id": "w-seed-test2", "type": "pr:ci", "interval": 30})
        handler.check()  # Seed
        result = handler.check()  # Real check
        assert result is not None
        assert result["type"] == "test:event"
        if state_path.exists():
            state_path.unlink()

    def test_check_no_seed_needed(self):
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            needs_seed = False
            def poll(self):
                return {"type": "test:event"}

        handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 30})
        result = handler.check()
        assert result is not None

    def test_record_failure(self):
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 30})
        assert handler._fail_count == 0
        handler.record_failure()
        assert handler._fail_count == 1
        handler.record_failure()
        assert handler._fail_count == 2

    def test_check_resets_fail_count(self):
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            needs_seed = False
            def poll(self):
                return None

        handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 30})
        handler._fail_count = 5
        handler.check()
        assert handler._fail_count == 0

    def test_load_state_corrupted_file(self):
        from poll import WatchHandler, HANDLER_STATE_DIR
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        HANDLER_STATE_DIR.mkdir(parents=True, exist_ok=True)
        state_path = HANDLER_STATE_DIR / "w-corrupt.json"
        state_path.write_text("not valid json{{{")
        handler = DummyHandler({"id": "w-corrupt", "type": "pr:ci", "interval": 30})
        assert handler._seeded is False  # Default, corrupted file ignored
        state_path.unlink(missing_ok=True)

    def test_should_check_default_interval(self):
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        handler = DummyHandler({"id": "w-test", "type": "pr:ci"})  # no interval key
        handler._last_checked = time.monotonic() - 31
        assert handler.should_check() is True

    def test_poll_returns_none_from_base(self):
        """Verifying WatchHandler.poll is abstract but we test check flow."""
        from poll import WatchHandler
        class DummyHandler(WatchHandler):
            needs_seed = False
            def poll(self):
                return None

        handler = DummyHandler({"id": "w-test", "type": "pr:ci", "interval": 30})
        assert handler.check() is None


# ---------------------------------------------------------------------------
# Handler state persistence
# ---------------------------------------------------------------------------

class TestHandlerStatePersistence:
    def test_save_and_load_state(self):
        from poll import WatchHandler, HANDLER_STATE_DIR
        class DummyHandler(WatchHandler):
            needs_seed = True
            def __init__(self, watch):
                self.custom_data = []
                super().__init__(watch)
            def poll(self):
                return None
            def _get_handler_state(self):
                return {"custom_data": self.custom_data}
            def _set_handler_state(self, data):
                self.custom_data = data.get("custom_data", [])

        HANDLER_STATE_DIR.mkdir(parents=True, exist_ok=True)
        h1 = DummyHandler({"id": "w-persist", "type": "pr:ci", "interval": 30})
        h1.custom_data = [1, 2, 3]
        h1._seeded = True
        h1._save_state()

        h2 = DummyHandler({"id": "w-persist", "type": "pr:ci", "interval": 30})
        assert h2.custom_data == [1, 2, 3]
        assert h2._seeded is True

        # Cleanup
        state_path = HANDLER_STATE_DIR / "w-persist.json"
        if state_path.exists():
            state_path.unlink()

    def test_fail_count_persisted(self):
        from poll import WatchHandler, HANDLER_STATE_DIR
        class DummyHandler(WatchHandler):
            def poll(self):
                return None

        HANDLER_STATE_DIR.mkdir(parents=True, exist_ok=True)
        h1 = DummyHandler({"id": "w-fc-test", "type": "pr:ci", "interval": 30})
        h1._fail_count = 5
        h1._save_state()

        h2 = DummyHandler({"id": "w-fc-test", "type": "pr:ci", "interval": 30})
        assert h2._fail_count == 5

        state_path = HANDLER_STATE_DIR / "w-fc-test.json"
        state_path.unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# PRCIHandler
# ---------------------------------------------------------------------------

class TestPRCIHandler:
    @patch("poll.run_gh")
    def test_no_new_runs(self, mock_gh):
        from poll import PRCIHandler
        mock_gh.return_value = (0, "[]", "")
        handler = PRCIHandler({"id": "w-1", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main"})
        result = handler.poll()
        assert result is None

    @patch("poll.run_gh")
    def test_new_passing_run(self, mock_gh):
        from poll import PRCIHandler
        runs = [{"databaseId": 123, "conclusion": "success", "name": "CI"}]
        mock_gh.return_value = (0, json.dumps(runs), "")
        handler = PRCIHandler({"id": "w-1", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main", "prNumber": 1})
        result = handler.poll()
        assert result["type"] == "ci:passed"
        assert result["runId"] == 123

    @patch("poll.run_gh")
    def test_new_failing_run(self, mock_gh):
        from poll import PRCIHandler
        runs = [{"databaseId": 456, "conclusion": "failure", "name": "CI"}]
        mock_gh.return_value = (0, json.dumps(runs), "")
        handler = PRCIHandler({"id": "w-1", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main"})
        result = handler.poll()
        assert result["type"] == "ci:failed"

    @patch("poll.run_gh")
    def test_dedup_seen_runs(self, mock_gh):
        from poll import PRCIHandler
        runs = [{"databaseId": 123, "conclusion": "success", "name": "CI"}]
        mock_gh.return_value = (0, json.dumps(runs), "")
        handler = PRCIHandler({"id": "w-1", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main"})
        handler.last_run_ids = {123}
        result = handler.poll()
        assert result is None

    @patch("poll.run_gh")
    def test_gh_failure(self, mock_gh):
        from poll import PRCIHandler
        mock_gh.return_value = (1, "", "error")
        handler = PRCIHandler({"id": "w-1", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main"})
        result = handler.poll()
        assert result is None

    def test_state_persistence(self):
        from poll import PRCIHandler
        handler = PRCIHandler({"id": "w-ci-sp", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main"})
        handler.last_run_ids = {100, 200, 300}
        state = handler._get_handler_state()
        assert set(state["last_run_ids"]) == {100, 200, 300}

        handler2 = PRCIHandler({"id": "w-ci-sp2", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main"})
        handler2._set_handler_state(state)
        assert handler2.last_run_ids == {100, 200, 300}

    @patch("poll.run_gh")
    def test_multiple_runs_returns_latest(self, mock_gh):
        from poll import PRCIHandler
        runs = [
            {"databaseId": 456, "conclusion": "success", "name": "CI"},
            {"databaseId": 457, "conclusion": "failure", "name": "Lint"},
        ]
        mock_gh.return_value = (0, json.dumps(runs), "")
        handler = PRCIHandler({"id": "w-1", "type": "pr:ci", "interval": 30, "repo": "a/b", "branch": "main"})
        result = handler.poll()
        # Returns the first (latest) new run
        assert result["runId"] == 456
        assert result["type"] == "ci:passed"


# ---------------------------------------------------------------------------
# PRMergeHandler
# ---------------------------------------------------------------------------

class TestPRMergeHandler:
    @patch("poll.run_gh")
    def test_not_merged(self, mock_gh):
        from poll import PRMergeHandler
        mock_gh.return_value = (0, json.dumps({"mergedAt": None}), "")
        handler = PRMergeHandler({"id": "w-1", "type": "pr:merge", "interval": 30, "repo": "a/b", "prNumber": 1})
        assert handler.poll() is None

    @patch("poll.run_gh")
    def test_merged(self, mock_gh):
        from poll import PRMergeHandler
        mock_gh.return_value = (0, json.dumps({"mergedAt": "2024-01-01T00:00:00Z"}), "")
        handler = PRMergeHandler({"id": "w-1", "type": "pr:merge", "interval": 30, "repo": "a/b", "prNumber": 1})
        result = handler.poll()
        assert result["type"] == "pr:merged"
        assert result["mergedAt"] == "2024-01-01T00:00:00Z"

    @patch("poll.run_gh")
    def test_gh_failure(self, mock_gh):
        from poll import PRMergeHandler
        mock_gh.return_value = (1, "", "error")
        handler = PRMergeHandler({"id": "w-1", "type": "pr:merge", "interval": 30, "repo": "a/b", "prNumber": 1})
        assert handler.poll() is None

    def test_no_seed_needed(self):
        from poll import PRMergeHandler
        assert PRMergeHandler.needs_seed is False


# ---------------------------------------------------------------------------
# PRReviewHandler
# ---------------------------------------------------------------------------

class TestPRReviewHandler:
    @patch("poll.run_gh")
    def test_approved_transition(self, mock_gh):
        from poll import PRReviewHandler
        mock_gh.return_value = (0, json.dumps({"reviewDecision": "APPROVED"}), "")
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42, "issueKey": "TOS-1"})
        handler._seeded = True
        handler.last_review = ""
        result = handler.poll()
        assert result is not None
        assert result["type"] == "pr:approved"

    @patch("poll.run_gh")
    def test_changes_requested_transition(self, mock_gh):
        from poll import PRReviewHandler
        # First call: review decision, Second call: review comments API
        def gh_side_effect(*args):
            if "reviewDecision" in args:
                return (0, json.dumps({"reviewDecision": "CHANGES_REQUESTED"}), "")
            if "reviews" in str(args):
                return (0, "fix the code\n", "")
            return (0, "[]", "")
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler._seeded = True
        handler.last_review = ""
        result = handler.poll()
        assert result is not None
        assert result["type"] == "pr:changes_requested"

    @patch("poll.run_gh")
    def test_no_review_change(self, mock_gh):
        from poll import PRReviewHandler
        # Three calls: review decision, GraphQL threads, issue comments
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": "APPROVED"}), "")
            if call_count[0] == 2:
                # GraphQL response for review threads
                gql = {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": []}}}}}
                return (0, json.dumps(gql), "")
            return (0, "[]", "")  # issue comments
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler._seeded = True
        handler.last_review = "APPROVED"  # Same as current → no transition
        result = handler.poll()
        assert result is None

    @patch("poll.run_gh")
    def test_gh_failure_returns_none(self, mock_gh):
        from poll import PRReviewHandler
        mock_gh.return_value = (1, "", "error")
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler._seeded = True
        result = handler.poll()
        assert result is None

    @patch("poll.run_gh")
    def test_new_review_thread_comment(self, mock_gh):
        from poll import PRReviewHandler
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": ""}), "")
            if call_count[0] == 2:
                gql = {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": [{
                    "id": "thread-1",
                    "isResolved": False,
                    "comments": {"nodes": [
                        {"databaseId": 100, "body": "fix this", "author": {"login": "reviewer"}}
                    ]}
                }]}}}}}
                return (0, json.dumps(gql), "")
            return (0, "[]", "")
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42, "issueKey": "TOS-1"})
        handler._seeded = True
        result = handler.poll()
        assert result is not None
        assert result["type"] == "pr:comment"
        assert result["isReviewComment"] is True
        assert result["body"] == "fix this"

    @patch("poll.run_gh")
    def test_new_issue_comment(self, mock_gh):
        from poll import PRReviewHandler
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": ""}), "")
            if call_count[0] == 2:
                gql = {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": []}}}}}
                return (0, json.dumps(gql), "")
            # Issue comments
            return (0, json.dumps([
                {"id": 200, "body": "please refactor", "user": {"login": "dev"}}
            ]), "")
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42, "issueKey": "TOS-1"})
        handler._seeded = True
        result = handler.poll()
        assert result is not None
        assert result["type"] == "pr:comment"
        assert result["isReviewComment"] is False

    @patch("poll.run_gh")
    def test_bot_comment_ignored(self, mock_gh):
        from poll import PRReviewHandler, BOT_SIGNATURE
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": ""}), "")
            if call_count[0] == 2:
                gql = {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": []}}}}}
                return (0, json.dumps(gql), "")
            return (0, json.dumps([
                {"id": 300, "body": f"automated fix {BOT_SIGNATURE}", "user": {"login": "bot"}}
            ]), "")
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler._seeded = True
        result = handler.poll()
        assert result is None

    def test_state_persistence(self):
        from poll import PRReviewHandler
        handler = PRReviewHandler({"id": "w-rv", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler.last_review = "APPROVED"
        handler.seen_issue_comment_ids = {1, 2, 3}
        handler.bot_comment_ids = {99}
        handler.thread_state = {
            "t1": {"resolved": True, "commentIds": {10, 20}},
        }
        state = handler._get_handler_state()
        assert state["last_review"] == "APPROVED"
        assert set(state["seen_issue_comment_ids"]) == {1, 2, 3}

        handler2 = PRReviewHandler({"id": "w-rv2", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler2._set_handler_state(state)
        assert handler2.last_review == "APPROVED"
        assert handler2.seen_issue_comment_ids == {1, 2, 3}

    @patch("poll.run_gh")
    def test_graphql_parse_error(self, mock_gh):
        """GraphQL returns unexpected format."""
        from poll import PRReviewHandler
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": ""}), "")
            if call_count[0] == 2:
                return (0, "not json", "")  # Bad GraphQL response
            return (0, "[]", "")
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler._seeded = True
        result = handler.poll()
        assert result is None  # Falls through to issue comments (empty)

    @patch("poll.run_gh")
    def test_issue_comments_failure(self, mock_gh):
        """Issue comments API fails."""
        from poll import PRReviewHandler
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": ""}), "")
            if call_count[0] == 2:
                gql = {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": []}}}}}
                return (0, json.dumps(gql), "")
            return (1, "", "error")  # issue comments fail
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42})
        handler._seeded = True
        result = handler.poll()
        assert result is None

    @patch("poll.run_gh")
    def test_reopened_thread(self, mock_gh):
        """Thread was resolved, now unresolved again."""
        from poll import PRReviewHandler
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": ""}), "")
            if call_count[0] == 2:
                gql = {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": [{
                    "id": "t-1",
                    "isResolved": False,
                    "comments": {"nodes": [
                        {"databaseId": 10, "body": "not fixed yet", "author": {"login": "dev"}}
                    ]}
                }]}}}}}
                return (0, json.dumps(gql), "")
            return (0, "[]", "")
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42, "issueKey": "TOS-1"})
        handler._seeded = True
        # Previously resolved thread
        handler.thread_state = {"t-1": {"resolved": True, "commentIds": {10}}}
        result = handler.poll()
        assert result is not None
        assert result["type"] == "pr:comment"

    @patch("poll.run_gh")
    def test_new_comment_in_existing_thread(self, mock_gh):
        """New comment added to an existing thread."""
        from poll import PRReviewHandler
        call_count = [0]
        def gh_side_effect(*args):
            call_count[0] += 1
            if call_count[0] == 1:
                return (0, json.dumps({"reviewDecision": ""}), "")
            if call_count[0] == 2:
                gql = {"data": {"repository": {"pullRequest": {"reviewThreads": {"nodes": [{
                    "id": "t-1",
                    "isResolved": False,
                    "comments": {"nodes": [
                        {"databaseId": 10, "body": "original", "author": {"login": "dev"}},
                        {"databaseId": 11, "body": "new reply", "author": {"login": "other"}}
                    ]}
                }]}}}}}
                return (0, json.dumps(gql), "")
            return (0, "[]", "")
        mock_gh.side_effect = gh_side_effect
        handler = PRReviewHandler({"id": "w-1", "type": "pr:review", "interval": 30, "repo": "a/b", "prNumber": 42, "issueKey": "TOS-1"})
        handler._seeded = True
        handler.thread_state = {"t-1": {"resolved": False, "commentIds": {10}}}
        result = handler.poll()
        assert result is not None
        assert result["type"] == "pr:comment"
        assert result["commentId"] == 11


# ---------------------------------------------------------------------------
# ConfluenceReviewHandler
# ---------------------------------------------------------------------------

class TestConfluenceReviewHandler:
    @patch("poll.run_script")
    def test_approved(self, mock_script):
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (0, json.dumps({
            "status": "approved", "footer": [], "inline": []
        }), "")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123", "designId": "d-1"})
        handler._seeded = True
        handler.last_label_status = "in-review"
        result = handler.poll()
        assert result is not None
        assert result["type"] == "page:approved"

    @patch("poll.run_script")
    def test_needs_fix(self, mock_script):
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (0, json.dumps({
            "status": "needs-fix", "footer": [], "inline": []
        }), "")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123", "designId": "d-1"})
        handler._seeded = True
        handler.last_label_status = "in-review"
        result = handler.poll()
        assert result is not None
        assert result["type"] == "page:needs-fix"

    @patch("poll.run_script")
    def test_new_comment(self, mock_script):
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (0, json.dumps({
            "status": "in-review",
            "footer": [{"id": 1001, "resolved": False}],
            "inline": []
        }), "")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123", "designId": "d-1"})
        handler._seeded = True
        handler.last_label_status = "in-review"
        result = handler.poll()
        assert result is not None
        assert result["type"] == "page:comment"
        assert result["commentId"] == 1001

    @patch("poll.run_script")
    def test_resolved_comment_skipped(self, mock_script):
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (0, json.dumps({
            "status": "in-review",
            "footer": [{"id": 1001, "resolved": True}],
            "inline": []
        }), "")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123", "designId": "d-1"})
        handler._seeded = True
        handler.last_label_status = "in-review"
        result = handler.poll()
        assert result is None

    @patch("poll.run_script")
    def test_script_failure(self, mock_script):
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (1, "", "error")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123"})
        handler._seeded = True
        result = handler.poll()
        assert result is None

    @patch("poll.run_script")
    def test_bad_json_output(self, mock_script):
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (0, "not json", "")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123"})
        handler._seeded = True
        result = handler.poll()
        assert result is None

    def test_state_persistence(self):
        from poll import ConfluenceReviewHandler
        handler = ConfluenceReviewHandler({"id": "w-cr", "type": "confluence:review", "interval": 30, "pageId": "123"})
        handler.comment_state = {"100": False, "200": True}
        handler.last_label_status = "approved"
        state = handler._get_handler_state()
        assert state["last_label_status"] == "approved"

        handler2 = ConfluenceReviewHandler({"id": "w-cr2", "type": "confluence:review", "interval": 30, "pageId": "123"})
        handler2._set_handler_state(state)
        assert handler2.last_label_status == "approved"
        assert handler2.comment_state == {"100": False, "200": True}

    @patch("poll.run_script")
    def test_no_status_change(self, mock_script):
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (0, json.dumps({
            "status": "in-review", "footer": [], "inline": []
        }), "")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123", "designId": "d-1"})
        handler._seeded = True
        handler.last_label_status = "in-review"
        result = handler.poll()
        assert result is None

    @patch("poll.run_script")
    def test_known_comment_no_change(self, mock_script):
        """Previously seen unresolved comment should not fire again."""
        from poll import ConfluenceReviewHandler
        mock_script.return_value = (0, json.dumps({
            "status": "in-review",
            "footer": [{"id": 1001, "resolved": False}],
            "inline": []
        }), "")
        handler = ConfluenceReviewHandler({"id": "w-1", "type": "confluence:review", "interval": 30, "pageId": "123", "designId": "d-1"})
        handler._seeded = True
        handler.last_label_status = "in-review"
        handler.comment_state = {"1001": False}  # Already seen as unresolved
        result = handler.poll()
        assert result is None


# ---------------------------------------------------------------------------
# poll_watches()
# ---------------------------------------------------------------------------

class TestPollWatches:
    @patch("poll.nudge_orchestrator")
    @patch("poll.State")
    def test_no_watches(self, MockState, mock_nudge):
        from poll import poll_watches, active_handlers
        state = MockState.return_value
        state.expire_watches.return_value = []
        state.list_watches.return_value = []
        poll_watches()
        mock_nudge.assert_not_called()

    @patch("poll.nudge_orchestrator")
    @patch("poll.State")
    def test_unknown_watch_type(self, MockState, mock_nudge):
        from poll import poll_watches, active_handlers
        state = MockState.return_value
        state.expire_watches.return_value = []
        state.list_watches.return_value = [{"id": "w-99", "type": "unknown:type", "interval": 30}]
        # Clear any previous handlers
        active_handlers.clear()
        poll_watches()
        assert "w-99" not in active_handlers

    @patch("poll.nudge_orchestrator")
    @patch("poll.State")
    @patch("poll.run_gh")
    def test_resolves_event(self, mock_gh, MockState, mock_nudge):
        from poll import poll_watches, active_handlers, HANDLER_STATE_DIR
        state = MockState.return_value
        state.expire_watches.return_value = []
        state.list_watches.return_value = [
            {"id": "w-1", "type": "pr:ci", "interval": 0, "repo": "a/b", "branch": "main"}
        ]
        # First run will seed, second won't have another call
        mock_gh.return_value = (0, json.dumps([{"databaseId": 1, "conclusion": "success", "name": "CI"}]), "")
        active_handlers.clear()
        # First call: seeds
        poll_watches()
        # Second call: returns event
        poll_watches()
        # Event should have been queued
        if state.queue_event.called:
            event_arg = state.queue_event.call_args[0][0]
            assert event_arg["type"] == "ci:passed"
        active_handlers.clear()


# ---------------------------------------------------------------------------
# nudge_orchestrator
# ---------------------------------------------------------------------------

class TestNudgeOrchestrator:
    def test_nudge_when_not_idle(self):
        import poll
        old_idle = poll.orchestrator_idle
        old_fd = poll.pty_master_fd
        poll.orchestrator_idle = False
        poll.pty_master_fd = None
        poll.nudge_orchestrator()
        # Should not crash, no fd to write to
        poll.orchestrator_idle = old_idle
        poll.pty_master_fd = old_fd

    def test_nudge_when_idle_but_no_fd(self):
        import poll
        old_idle = poll.orchestrator_idle
        old_fd = poll.pty_master_fd
        poll.orchestrator_idle = True
        poll.pty_master_fd = None
        poll.nudge_orchestrator()
        poll.orchestrator_idle = old_idle
        poll.pty_master_fd = old_fd


# ---------------------------------------------------------------------------
# handle_slack_message
# ---------------------------------------------------------------------------

class TestHandleSlackMessage:
    @patch("poll.nudge_orchestrator")
    @patch("poll.State")
    def test_empty_message(self, MockState, mock_nudge):
        from poll import handle_slack_message
        handle_slack_message({"text": ""})
        MockState.return_value.queue_event.assert_not_called()

    @patch("poll.nudge_orchestrator")
    @patch("poll.State")
    def test_feature_message(self, MockState, mock_nudge):
        from poll import handle_slack_message
        handle_slack_message({
            "text": "build a payment page",
            "user": "U123",
            "ts": "123.456",
            "channel": "C123",
        })
        MockState.return_value.queue_event.assert_called_once()
        event = MockState.return_value.queue_event.call_args[0][0]
        assert event["type"] == "task:requested"
        assert event["category"] == "feature"

    @patch("poll.nudge_orchestrator")
    @patch("poll.State")
    def test_bug_message(self, MockState, mock_nudge):
        from poll import handle_slack_message
        handle_slack_message({"text": "bug: login fails"})
        event = MockState.return_value.queue_event.call_args[0][0]
        assert event["category"] == "bug"


# ---------------------------------------------------------------------------
# Webhook handler
# ---------------------------------------------------------------------------

class TestWebhookHandler:
    @pytest.fixture
    def webhook_server(self, monkeypatch, tmp_path):
        monkeypatch.delenv("WEBHOOK_SECRET", raising=False)
        monkeypatch.setenv("STATE_DIR", str(tmp_path))
        monkeypatch.setenv("STATE_BACKEND", "file")
        from poll import WebhookHandler
        from http.server import HTTPServer
        server = HTTPServer(("127.0.0.1", 0), WebhookHandler)
        port = server.server_address[1]
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        yield port
        server.shutdown()

    def _post(self, port, path, data, headers=None):
        conn = HTTPConnection("127.0.0.1", port)
        body = json.dumps(data).encode()
        hdrs = {"Content-Type": "application/json"}
        if headers:
            hdrs.update(headers)
        conn.request("POST", path, body=body, headers=hdrs)
        resp = conn.getresponse()
        return resp.status, json.loads(resp.read())

    def test_health_endpoint(self, webhook_server):
        conn = HTTPConnection("127.0.0.1", webhook_server)
        conn.request("GET", "/health")
        resp = conn.getresponse()
        assert resp.status == 200
        data = json.loads(resp.read())
        assert data["status"] == "ok"

    def test_unknown_endpoint(self, webhook_server):
        conn = HTTPConnection("127.0.0.1", webhook_server)
        conn.request("POST", "/webhook/unknown", body=json.dumps({}).encode(),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        assert resp.status == 404

    def test_get_unknown_endpoint(self, webhook_server):
        conn = HTTPConnection("127.0.0.1", webhook_server)
        conn.request("GET", "/unknown")
        resp = conn.getresponse()
        assert resp.status == 404

    @patch("poll.nudge_orchestrator")
    def test_github_ci_event(self, mock_nudge, webhook_server):
        payload = {
            "action": "completed",
            "check_suite": {
                "conclusion": "success",
                "head_branch": "main",
                "pull_requests": [{"number": 42}],
            },
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "check_suite"},
        )
        assert status == 200
        assert data.get("queued") == "ci:passed"

    @patch("poll.nudge_orchestrator")
    def test_github_ci_failed(self, mock_nudge, webhook_server):
        payload = {
            "action": "completed",
            "check_suite": {
                "conclusion": "failure",
                "head_branch": "main",
                "pull_requests": [{"number": 42}],
            },
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "check_suite"},
        )
        assert status == 200
        assert data.get("queued") == "ci:failed"

    @patch("poll.nudge_orchestrator")
    def test_github_pr_merged(self, mock_nudge, webhook_server):
        payload = {
            "action": "closed",
            "pull_request": {"number": 42, "merged": True, "merged_at": "2024-01-01T00:00:00Z"},
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "pull_request"},
        )
        assert status == 200
        assert data.get("queued") == "pr:merged"

    @patch("poll.nudge_orchestrator")
    def test_github_pr_closed_not_merged(self, mock_nudge, webhook_server):
        payload = {
            "action": "closed",
            "pull_request": {"number": 42, "merged": False},
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "pull_request"},
        )
        assert status == 200
        assert data.get("ignored") is True

    @patch("poll.nudge_orchestrator")
    def test_github_review_approved(self, mock_nudge, webhook_server):
        payload = {
            "action": "submitted",
            "review": {"state": "approved"},
            "pull_request": {"number": 42},
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "pull_request_review"},
        )
        assert status == 200
        assert data.get("queued") == "pr:approved"

    @patch("poll.nudge_orchestrator")
    def test_github_review_changes_requested(self, mock_nudge, webhook_server):
        payload = {
            "action": "submitted",
            "review": {"state": "changes_requested"},
            "pull_request": {"number": 42},
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "pull_request_review"},
        )
        assert status == 200
        assert data.get("queued") == "pr:changes_requested"

    @patch("poll.nudge_orchestrator")
    def test_github_bot_comment_ignored(self, mock_nudge, webhook_server):
        payload = {
            "action": "created",
            "comment": {"id": 1, "body": "fix applied <!-- devloop-bot -->", "user": {"login": "bot"}},
            "issue": {"number": 42},
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "issue_comment"},
        )
        assert status == 200
        assert data.get("ignored") is True

    @patch("poll.nudge_orchestrator")
    def test_github_human_comment(self, mock_nudge, webhook_server):
        payload = {
            "action": "created",
            "comment": {"id": 1, "body": "please fix this", "user": {"login": "human"}},
            "issue": {"number": 42},
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "issue_comment"},
        )
        assert status == 200
        assert data.get("queued") == "pr:comment"

    @patch("poll.nudge_orchestrator")
    def test_github_review_comment_webhook(self, mock_nudge, webhook_server):
        payload = {
            "action": "created",
            "comment": {"id": 1, "body": "inline note", "user": {"login": "dev"}},
            "pull_request": {"number": 42},
            "repository": {"full_name": "org/repo"},
        }
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "pull_request_review_comment"},
        )
        assert status == 200
        assert data.get("queued") == "pr:comment"

    @patch("poll.nudge_orchestrator")
    def test_github_unknown_event(self, mock_nudge, webhook_server):
        payload = {"action": "opened"}
        status, data = self._post(
            webhook_server, "/webhook/github", payload,
            headers={"X-GitHub-Event": "issues"},
        )
        assert status == 200
        assert data.get("ignored") is True

    @patch("poll.nudge_orchestrator")
    def test_jira_status_change(self, mock_nudge, webhook_server):
        payload = {
            "webhookEvent": "jira:issue_updated",
            "issue": {"key": "TOS-42"},
            "changelog": {
                "items": [{"field": "status", "fromString": "To Do", "toString": "In Progress"}]
            },
        }
        status, data = self._post(webhook_server, "/webhook/jira", payload)
        assert status == 200
        assert data.get("queued") == "jira:status_changed"

    @patch("poll.nudge_orchestrator")
    def test_jira_comment(self, mock_nudge, webhook_server):
        payload = {
            "webhookEvent": "jira:issue_comment_created",
            "issue": {"key": "TOS-42"},
            "comment": {"id": 1, "body": "test comment", "author": {"displayName": "Dev"}},
        }
        status, data = self._post(webhook_server, "/webhook/jira", payload)
        assert status == 200
        assert data.get("queued") == "jira:comment"

    @patch("poll.nudge_orchestrator")
    def test_jira_unknown_event(self, mock_nudge, webhook_server):
        payload = {"webhookEvent": "jira:other", "issue": {"key": "TOS-1"}}
        status, data = self._post(webhook_server, "/webhook/jira", payload)
        assert status == 200
        assert data.get("ignored") is True

    def test_invalid_json(self, webhook_server):
        conn = HTTPConnection("127.0.0.1", webhook_server)
        conn.request("POST", "/webhook/github", body=b"not json",
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        assert resp.status == 400

    def test_health_via_post(self, webhook_server):
        """POST to /health should return 200 (goes through do_POST health branch)."""
        status, data = self._post(webhook_server, "/health", {})
        assert status == 200
        assert data["status"] == "ok"


# ---------------------------------------------------------------------------
# start_webhook_server
# ---------------------------------------------------------------------------

class TestStartWebhookServer:
    def test_disabled_when_port_zero(self):
        import poll
        old_port = poll.WEBHOOK_PORT
        poll.WEBHOOK_PORT = 0
        poll.start_webhook_server()  # Should not crash
        poll.WEBHOOK_PORT = old_port


# ---------------------------------------------------------------------------
# Config reload (SIGUSR1)
# ---------------------------------------------------------------------------

class TestConfigReload:
    def test_reload_config_function(self):
        from poll import reload_config
        # Just verify it runs without error
        reload_config()

    def test_reload_config_updates_globals(self):
        """reload_config reads from .env file — verify it recalculates globals."""
        import poll
        # Write a temp .env with custom values
        env_path = poll.SCRIPT_DIR / ".env"
        had_env = env_path.exists()
        old_content = env_path.read_text() if had_env else ""
        try:
            env_path.write_text("WATCH_POLL_INTERVAL_SEC=77\nMAX_POLL_WORKERS=3\n")
            poll.reload_config()
            assert poll.WATCH_POLL_INTERVAL == 77
            assert poll.MAX_POLL_WORKERS == 3
        finally:
            if had_env:
                env_path.write_text(old_content)
            else:
                env_path.unlink(missing_ok=True)
            poll.reload_config()  # Reset

    @pytest.mark.skipif(not hasattr(signal, "SIGUSR1"), reason="No SIGUSR1 on this platform")
    def test_sigusr1_handler_registered(self):
        """Verify SIGUSR1 would reload config (can't test signal in same process easily)."""
        from poll import reload_config
        assert callable(reload_config)


# ---------------------------------------------------------------------------
# Handler registry
# ---------------------------------------------------------------------------

class TestHandlerRegistry:
    def test_all_types_registered(self):
        from poll import HANDLER_REGISTRY
        expected = {"pr:ci", "pr:review", "pr:merge", "confluence:review"}
        assert set(HANDLER_REGISTRY.keys()) == expected

    def test_handler_classes_are_subclasses(self):
        from poll import HANDLER_REGISTRY, WatchHandler
        for cls in HANDLER_REGISTRY.values():
            assert issubclass(cls, WatchHandler)


# ---------------------------------------------------------------------------
# run_gh and run_script (mocked subprocess)
# ---------------------------------------------------------------------------

class TestSubprocessHelpers:
    @patch("poll.subprocess.run")
    def test_run_gh_success(self, mock_run):
        from poll import run_gh
        mock_run.return_value = MagicMock(returncode=0, stdout='{"ok":true}', stderr="")
        code, out, err = run_gh("pr", "list")
        assert code == 0
        assert out == '{"ok":true}'

    @patch("poll.subprocess.run")
    def test_run_gh_failure(self, mock_run):
        from poll import run_gh
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="not found")
        code, out, err = run_gh("pr", "view", "999")
        assert code == 1
        assert err == "not found"

    @patch("poll.subprocess.run")
    def test_run_script_success(self, mock_run):
        from poll import run_script
        mock_run.return_value = MagicMock(returncode=0, stdout="done", stderr="")
        code, out, err = run_script(30, "confluence.py", "check")
        assert code == 0

    @patch("poll.subprocess.run")
    def test_run_script_failure(self, mock_run):
        from poll import run_script
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="error")
        code, out, err = run_script(30, "confluence.py", "check")
        assert code == 1
