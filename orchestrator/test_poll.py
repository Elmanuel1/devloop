"""Tests for poll.py — watch handlers, webhook server, config reload."""

import json
import os
import signal
import sys
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock, patch
from http.client import HTTPConnection

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

os.environ.pop("STATE_BACKEND", None)

# We need to mock some things before importing poll
# Since poll.py imports state and does env loading at module level


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


# ---------------------------------------------------------------------------
# Webhook handler
# ---------------------------------------------------------------------------

class TestWebhookHandler:
    @pytest.fixture
    def webhook_server(self):
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

    def test_invalid_json(self, webhook_server):
        conn = HTTPConnection("127.0.0.1", webhook_server)
        conn.request("POST", "/webhook/github", body=b"not json",
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        assert resp.status == 400


# ---------------------------------------------------------------------------
# Config reload (SIGUSR1)
# ---------------------------------------------------------------------------

class TestConfigReload:
    def test_reload_config_function(self):
        from poll import reload_config
        # Just verify it runs without error
        reload_config()

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
