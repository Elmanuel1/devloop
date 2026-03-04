"""
Orchestrator Poller — watch-driven event loop.

- Persistent Claude session via native PTY (no wrapper needed)
- Watch queue: sub-agents register watches, poller resolves and pushes events
- Slack Socket Mode (optional, via slack-bolt)
- caffeinate integration to prevent idle sleep on macOS
- Events written to .orchestrator/events/ — orchestrator reads via state.py

Run: uv run poll.py
"""

import json
import logging
import os
import pty
import re
import select
import shutil
import signal
import subprocess
import sys
import threading
import time
from abc import ABC, abstractmethod
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List, Optional, Tuple


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def env(key: str, fallback: str = None) -> str:
    return os.environ.get(key, fallback)


def env_int(key: str, fallback: int = 0) -> int:
    val = os.environ.get(key, '')
    try:
        return int(val)
    except ValueError:
        return fallback


SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from cli_utils import load_dotenv

load_dotenv(str(SCRIPT_DIR / '.env'))

SLACK_APP_TOKEN       = env('SLACK_APP_TOKEN', '')
SLACK_BOT_TOKEN       = env('SLACK_BOT_TOKEN', '')
WATCH_POLL_INTERVAL   = env_int('WATCH_POLL_INTERVAL_SEC', 10)
MAX_POLL_WORKERS      = env_int('MAX_POLL_WORKERS', 5)
WEBHOOK_PORT          = env_int('WEBHOOK_PORT', 9400)
MAX_RESTART_ATTEMPTS  = 3
BOT_SIGNATURE         = '<!-- devloop-bot -->'


def reload_config(signum=None, frame=None) -> None:
    """Reload .env and recalculate config vars. Called on SIGUSR1."""
    global SLACK_APP_TOKEN, SLACK_BOT_TOKEN, WATCH_POLL_INTERVAL, MAX_POLL_WORKERS, WEBHOOK_PORT
    # Re-read .env (only sets vars not already in os.environ, so clear first)
    for key in ('SLACK_APP_TOKEN', 'SLACK_BOT_TOKEN', 'WATCH_POLL_INTERVAL_SEC',
                'MAX_POLL_WORKERS', 'WEBHOOK_PORT'):
        os.environ.pop(key, None)
    load_dotenv(str(SCRIPT_DIR / '.env'))
    SLACK_APP_TOKEN     = env('SLACK_APP_TOKEN', '')
    SLACK_BOT_TOKEN     = env('SLACK_BOT_TOKEN', '')
    WATCH_POLL_INTERVAL = env_int('WATCH_POLL_INTERVAL_SEC', 10)
    MAX_POLL_WORKERS    = env_int('MAX_POLL_WORKERS', 5)
    WEBHOOK_PORT        = env_int('WEBHOOK_PORT', 9400)
    log.info('Config reloaded: poll_interval=%ds, workers=%d, webhook_port=%d',
             WATCH_POLL_INTERVAL, MAX_POLL_WORKERS, WEBHOOK_PORT)

CLAUDE_BIN = shutil.which('claude')

from state import State

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

STATE_BASE = Path(os.environ.get('STATE_DIR', str(SCRIPT_DIR / '.orchestrator')))
LOG_DIR    = STATE_BASE
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / 'poll.log'

log = logging.getLogger('poller')
log.setLevel(logging.INFO)

_fmt = logging.Formatter('%(asctime)s [%(levelname)s] %(message)s', datefmt='%H:%M:%S')
_fh  = logging.FileHandler(LOG_FILE)
_fh.setFormatter(_fmt)
log.addHandler(_fh)

# ---------------------------------------------------------------------------
# Global state
# ---------------------------------------------------------------------------

orchestrator_idle: bool   = False
idle_timer:        Optional[threading.Timer] = None
pty_master_fd:     Optional[int] = None
pty_child_pid:     Optional[int] = None
restart_attempts:  int    = 0
last_ctrl_c:       float  = 0.0
caffeinate_proc:   Optional[subprocess.Popen] = None

# ---------------------------------------------------------------------------
# Subprocess helpers
# ---------------------------------------------------------------------------

def run_gh(*args: str) -> Tuple[int, str, str]:
    """Run a gh CLI command, return (exit_code, stdout, stderr)."""
    cmd_str = ' '.join(args)
    log.info('[gh] START: gh %s', cmd_str)
    result = subprocess.run(
        ['gh'] + list(args),
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode == 0:
        log.info('[gh] OK: gh %s', cmd_str)
    else:
        log.warning('[gh] FAIL (exit %d): gh %s — %s', result.returncode, cmd_str, result.stderr.strip())
    return result.returncode, result.stdout, result.stderr


def run_script(timeout: int, *args: str) -> Tuple[int, str, str]:
    """Run a python3 script relative to SCRIPT_DIR, return (exit_code, stdout, stderr)."""
    cmd_str = ' '.join(args)
    log.debug('[script] START: python3 %s', cmd_str)
    result = subprocess.run(
        ['python3'] + list(args),
        capture_output=True,
        text=True,
        timeout=timeout,
        cwd=str(SCRIPT_DIR),
    )
    if result.returncode == 0:
        log.debug('[script] OK: python3 %s', cmd_str)
    else:
        log.warning('[script] FAIL (exit %d): python3 %s — %s', result.returncode, cmd_str, result.stderr.strip())
    return result.returncode, result.stdout, result.stderr


def parse_category(text: str) -> str:
    lower = text.lower()
    if lower.startswith('bug:') or lower.startswith('bug '):
        return 'bug'
    if lower.startswith('chore:') or lower.startswith('chore '):
        return 'chore'
    return 'feature'


# ---------------------------------------------------------------------------
# Handler state persistence
# ---------------------------------------------------------------------------

HANDLER_STATE_DIR = STATE_BASE / 'handler_state'
MAX_BACKOFF       = 300


# ---------------------------------------------------------------------------
# Base handler
# ---------------------------------------------------------------------------

class WatchHandler(ABC):
    """Base class for all watch type handlers. One subclass per watch type."""

    needs_seed: bool = True

    def __init__(self, watch: dict) -> None:
        self.watch        = watch
        self._seeded      = False
        self._last_checked: Optional[float] = None
        self._fail_count  = 0
        self._load_state()

    def _state_path(self) -> Path:
        return HANDLER_STATE_DIR / f"{self.watch['id']}.json"

    def _get_handler_state(self) -> dict:
        """Override in subclasses to return serializable state."""
        return {}

    def _set_handler_state(self, data: dict) -> None:
        """Override in subclasses to restore from persisted state."""
        pass

    def _load_state(self) -> None:
        path = self._state_path()
        if path.exists():
            try:
                data = json.loads(path.read_text())
                self._seeded     = data.get('_seeded', False)
                self._fail_count = data.get('_fail_count', 0)
                self._set_handler_state(data.get('handler', {}))
            except (json.JSONDecodeError, OSError):
                pass

    def _save_state(self) -> None:
        HANDLER_STATE_DIR.mkdir(parents=True, exist_ok=True)
        data = {
            '_seeded':     self._seeded,
            '_fail_count': self._fail_count,
            'handler':     self._get_handler_state(),
        }
        self._state_path().write_text(json.dumps(data, indent=2))

    def should_check(self) -> bool:
        """Respect per-watch interval with exponential backoff on failures."""
        if self._last_checked is None:
            return True
        interval = self.watch.get('interval', 30)
        if self._fail_count > 0:
            interval = min(interval * (2 ** self._fail_count), MAX_BACKOFF)
        return time.monotonic() - self._last_checked >= interval

    def record_failure(self) -> None:
        """Called by poll_watches on exception — increases backoff."""
        self._fail_count  += 1
        self._last_checked = time.monotonic()
        self._save_state()
        log.warning(
            'Watch %s fail_count=%d, next check in %ds',
            self.watch.get('id'),
            self._fail_count,
            min(self.watch.get('interval', 30) * (2 ** self._fail_count), MAX_BACKOFF),
        )

    @abstractmethod
    def poll(self) -> Optional[dict]:
        """Check the watched resource. Return event dict if criteria met, None otherwise."""
        return None

    def check(self) -> Optional[dict]:
        """Run poll. On first call (if needs_seed), seed baseline and return None."""
        self._last_checked = time.monotonic()
        result             = self.poll()
        self._fail_count   = 0
        if self.needs_seed and not self._seeded:
            self._seeded = True
            self._save_state()
            return None
        self._seeded = True
        self._save_state()
        return result


# ---------------------------------------------------------------------------
# PR CI handler
# ---------------------------------------------------------------------------

class PRCIHandler(WatchHandler):
    """Watches CI status on a PR branch."""

    def __init__(self, watch: dict) -> None:
        self.last_run_ids: set = set()
        super().__init__(watch)

    def _get_handler_state(self) -> dict:
        return {'last_run_ids': list(self.last_run_ids)}

    def _set_handler_state(self, data: dict) -> None:
        self.last_run_ids = set(data.get('last_run_ids', []))

    def poll(self) -> Optional[dict]:
        repo   = self.watch.get('repo', '')
        branch = self.watch.get('branch', '')
        code, stdout, _ = run_gh(
            'run', 'list',
            '--repo',   repo,
            '--branch', branch,
            '--status', 'completed',
            '--json',   'databaseId,conclusion,name,headSha',
            '--limit',  '5',
        )
        if code != 0:
            return None
        runs        = json.loads(stdout)
        current_ids = {r['databaseId'] for r in runs}
        new_runs    = [r for r in runs if r['databaseId'] not in self.last_run_ids]
        self.last_run_ids = current_ids
        if not new_runs:
            return None
        latest     = new_runs[0]
        conclusion = latest.get('conclusion', '')
        event_type = 'ci:passed' if conclusion == 'success' else 'ci:failed'
        return {
            'type':       event_type,
            'repo':       repo,
            'branch':     branch,
            'prNumber':   self.watch.get('prNumber'),
            'issueKey':   self.watch.get('issueKey'),
            'runName':    latest.get('name', ''),
            'runId':      latest['databaseId'],
            'headSha':    latest.get('headSha', ''),
            'conclusion': conclusion,
        }


# ---------------------------------------------------------------------------
# PR Review handler
# ---------------------------------------------------------------------------

class PRReviewHandler(WatchHandler):
    """Watches review decision on a PR and new PR comments.

    Fires:
      - pr:approved           — when reviewDecision transitions to APPROVED
      - pr:changes_requested  — when reviewDecision transitions to CHANGES_REQUESTED
      - pr:comment            — when new inline review comments or issue comments appear

    pr:comment payload:
      {
        "type":            "pr:comment",
        "repo":            "owner/repo",
        "prNumber":        140,
        "issueKey":        "TOS-37",
        "commentId":       123456789,
        "body":            "why is fieldsApplied always empty?",
        "author":          "tobi",
        "isReviewComment": true,    # true = /pulls/{pr}/comments endpoint
        "commentUrl":      "https://github.com/..."
      }

    Deduplication: seen comment IDs are persisted in handler state so restarts
    do not re-emit old comments.
    """

    def __init__(self, watch: dict) -> None:
        self.last_review:   str  = ''
        self.thread_state:  dict = {}
        self.seen_issue_comment_ids: set = set()
        self.bot_comment_ids: set = set()
        super().__init__(watch)

    def _get_handler_state(self) -> dict:
        serializable = {}
        for tid, ts in self.thread_state.items():
            serializable[tid] = {
                'resolved':   ts['resolved'],
                'commentIds': list(ts['commentIds']),
            }
        return {
            'last_review':            self.last_review,
            'thread_state':           serializable,
            'seen_issue_comment_ids': list(self.seen_issue_comment_ids),
            'bot_comment_ids':        list(self.bot_comment_ids),
        }

    def _set_handler_state(self, data: dict) -> None:
        self.last_review = data.get('last_review', '')
        self.seen_issue_comment_ids = set(data.get('seen_issue_comment_ids', []))
        self.bot_comment_ids = set(data.get('bot_comment_ids', []))
        raw = data.get('thread_state', {})
        self.thread_state = {}
        for tid, ts in raw.items():
            self.thread_state[tid] = {
                'resolved':   ts.get('resolved', False),
                'commentIds': set(ts.get('commentIds', [])),
            }

    def poll(self) -> Optional[dict]:
        repo      = self.watch.get('repo', '')
        pr_number = self.watch.get('prNumber')
        issue_key = self.watch.get('issueKey')

        # --- 1. Check review decision ---
        code, stdout, _ = run_gh(
            'pr', 'view', str(pr_number),
            '--repo', repo,
            '--json', 'reviewDecision',
        )
        if code != 0:
            return None
        data   = json.loads(stdout)
        review = data.get('reviewDecision', '') or ''
        old_review       = self.last_review
        self.last_review = review
        if review and review != old_review:
            event = {
                'repo':     repo,
                'prNumber': pr_number,
                'issueKey': issue_key,
            }
            if review == 'APPROVED':
                event['type'] = 'pr:approved'
                return event
            if review == 'CHANGES_REQUESTED':
                event['type']     = 'pr:changes_requested'
                event['comments'] = self._get_review_comments(repo, pr_number)
                return event

        # --- 2. Check review threads (comments + resolution via GraphQL) ---
        thread_event = self._poll_review_threads(repo, pr_number, issue_key)
        if thread_event:
            return thread_event

        # --- 3. Check general issue comments (not part of review threads) ---
        issue_event = self._poll_issue_comments(repo, pr_number, issue_key)
        if issue_event:
            return issue_event

        return None

    def _poll_review_threads(
        self, repo: str, pr_number: int, issue_key: Optional[str]
    ) -> Optional[dict]:
        owner, name = repo.split('/', 1) if '/' in repo else ('', repo)
        query = '''
          query($owner:String!,$name:String!,$pr:Int!) {
            repository(owner:$owner,name:$name) {
              pullRequest(number:$pr) {
                reviewThreads(first:100) {
                  nodes {
                    id
                    isResolved
                    comments(first:100) {
                      nodes { databaseId body author { login } }
                    }
                  }
                }
              }
            }
          }
        '''
        code, stdout, _ = run_gh(
            'api', 'graphql',
            '-f', f'query={query}',
            '-f', f'owner={owner}',
            '-f', f'name={name}',
            '-F', f'pr={pr_number}',
        )
        if code != 0:
            return None
        try:
            gql = json.loads(stdout)
            threads = gql['data']['repository']['pullRequest']['reviewThreads']['nodes']
        except (json.JSONDecodeError, TypeError, KeyError):
            return None

        for thread in threads:
            tid        = thread['id']
            resolved   = thread.get('isResolved', False)
            comments   = thread.get('comments', {}).get('nodes', [])
            comment_ids = {c['databaseId'] for c in comments if c.get('databaseId')}

            # Tag bot comments
            for c in comments:
                if BOT_SIGNATURE in c.get('body', ''):
                    self.bot_comment_ids.add(c['databaseId'])

            human_comments = [c for c in comments if c.get('databaseId') not in self.bot_comment_ids]

            prev = self.thread_state.get(tid)
            self.thread_state[tid] = {'resolved': resolved, 'commentIds': comment_ids}

            if prev is None:
                if not resolved and human_comments:
                    first = human_comments[0]
                    return {
                        'type':            'pr:comment',
                        'repo':            repo,
                        'prNumber':        pr_number,
                        'issueKey':        issue_key,
                        'commentId':       first.get('databaseId'),
                        'body':            first.get('body', ''),
                        'author':          first.get('author', {}).get('login', ''),
                        'isReviewComment': True,
                        'threadId':        tid,
                        'isResolved':      False,
                    }
                continue

            if prev['resolved'] and not resolved:
                first = human_comments[0] if human_comments else {}
                return {
                    'type':            'pr:comment',
                    'repo':            repo,
                    'prNumber':        pr_number,
                    'issueKey':        issue_key,
                    'commentId':       first.get('databaseId'),
                    'body':            first.get('body', ''),
                    'author':          first.get('author', {}).get('login', ''),
                    'isReviewComment': True,
                    'threadId':        tid,
                    'isResolved':      False,
                }

            new_ids = (comment_ids - prev['commentIds']) - self.bot_comment_ids
            if new_ids:
                new_cid = next(iter(new_ids))
                new_comment = next((c for c in comments if c.get('databaseId') == new_cid), {})
                return {
                    'type':            'pr:comment',
                    'repo':            repo,
                    'prNumber':        pr_number,
                    'issueKey':        issue_key,
                    'commentId':       new_cid,
                    'body':            new_comment.get('body', ''),
                    'author':          new_comment.get('author', {}).get('login', ''),
                    'isReviewComment': True,
                    'threadId':        tid,
                    'isResolved':      resolved,
                }

        return None

    def _poll_issue_comments(
        self, repo: str, pr_number: int, issue_key: Optional[str]
    ) -> Optional[dict]:
        code, stdout, _ = run_gh('api', f'repos/{repo}/issues/{pr_number}/comments')
        if code != 0:
            return None
        try:
            comments = json.loads(stdout)
        except (json.JSONDecodeError, TypeError):
            return None

        for comment in comments:
            cid = comment.get('id')
            if cid is None:
                continue
            if BOT_SIGNATURE in comment.get('body', ''):
                self.bot_comment_ids.add(cid)
            if cid in self.seen_issue_comment_ids or cid in self.bot_comment_ids:
                continue
            self.seen_issue_comment_ids.add(cid)
            return {
                'type':            'pr:comment',
                'repo':            repo,
                'prNumber':        pr_number,
                'issueKey':        issue_key,
                'commentId':       cid,
                'body':            comment.get('body', ''),
                'author':          comment.get('user', {}).get('login', ''),
                'isReviewComment': False,
            }

        return None

    @staticmethod
    def _get_review_comments(repo: str, pr_number: int) -> List[str]:
        code, stdout, _ = run_gh(
            'api', f'repos/{repo}/pulls/{pr_number}/reviews',
            '--jq', '.[] | select(.state == "CHANGES_REQUESTED") | .body',
        )
        if code != 0:
            return []
        return [line for line in stdout.split('\n') if line.strip()]


# ---------------------------------------------------------------------------
# PR Merge handler
# ---------------------------------------------------------------------------

class PRMergeHandler(WatchHandler):
    """Watches for PR merge."""

    needs_seed = False

    def poll(self) -> Optional[dict]:
        repo      = self.watch.get('repo', '')
        pr_number = self.watch.get('prNumber')
        code, stdout, _ = run_gh(
            'pr', 'view', str(pr_number),
            '--repo', repo,
            '--json', 'mergedAt',
        )
        if code != 0:
            return None
        data = json.loads(stdout)
        if not data.get('mergedAt'):
            return None
        return {
            'type':     'pr:merged',
            'repo':     repo,
            'prNumber': pr_number,
            'issueKey': self.watch.get('issueKey'),
            'mergedAt': data['mergedAt'],
        }


# ---------------------------------------------------------------------------
# Confluence Review handler (unified)
# ---------------------------------------------------------------------------

class ConfluenceReviewHandler(WatchHandler):
    """
    Unified Confluence review watcher — single watch per page for the full review lifecycle.

    Checks BOTH comments and label changes on every poll cycle. Fires:
      - page:comment   — when new comments appear (comment count increases)
      - page:needs-fix — when reviewer adds the needs-fix label
      - page:approved  — when reviewer adds the approved label

    The watch is NEVER auto-removed by the poller. The orchestrator is responsible
    for removing it explicitly when it handles page:approved.
    """

    needs_seed = True

    def __init__(self, watch: dict) -> None:
        self.comment_state: dict = {}
        self.last_label_status:  str = ''
        super().__init__(watch)

    def _get_handler_state(self) -> dict:
        return {
            'comment_state':     self.comment_state,
            'last_label_status': self.last_label_status,
        }

    def _set_handler_state(self, data: dict) -> None:
        self.comment_state     = data.get('comment_state', {})
        self.last_label_status = data.get('last_label_status', '')

    def poll(self) -> Optional[dict]:
        page_id   = self.watch.get('pageId', '')
        design_id = self.watch.get('designId')
        wid       = self.watch.get('id')

        code, stdout, _ = run_script(30, 'confluence.py', 'poll-page', page_id)
        if code != 0:
            return None

        try:
            data = json.loads(stdout)
        except (json.JSONDecodeError, TypeError):
            log.warning('Watch %s: could not parse poll-page output for page %s', wid, page_id)
            return None

        # --- label / status checks ---
        status = data.get('status', 'in-review')
        if status != self.last_label_status:
            old                    = self.last_label_status
            self.last_label_status = status
            if status == 'approved':
                log.info('Watch %s: page %s → approved', wid, page_id)
                return {
                    'type':     'page:approved',
                    'pageId':   page_id,
                    'designId': design_id,
                }
            if status == 'needs-fix':
                log.info('Watch %s: page %s → needs-fix (was %s)', wid, page_id, old)
                return {
                    'type':     'page:needs-fix',
                    'pageId':   page_id,
                    'designId': design_id,
                }

        # --- comment check (tracks resolved status per ID) ---
        # Fire only for unresolved comments that are new or were re-opened.
        all_comments = data.get('footer', []) + data.get('inline', [])
        for comment in all_comments:
            cid = comment.get('id')
            if cid is None:
                continue
            cid_str    = str(cid)
            resolved   = comment.get('resolved', False)
            was_resolved = self.comment_state.get(cid_str)
            self.comment_state[cid_str] = resolved

            # Skip resolved comments (architect finished work)
            if resolved:
                continue
            # Fire if: new unresolved comment, or previously resolved now unresolved
            if was_resolved is None or was_resolved is True:
                log.info('Watch %s: page %s unresolved comment %s', wid, page_id, cid)
                return {
                    'type':        'page:comment',
                    'pageId':      page_id,
                    'designId':    design_id,
                    'commentId':   cid,
                }
        return None


# ---------------------------------------------------------------------------
# Handler registry
# ---------------------------------------------------------------------------

HANDLER_REGISTRY: Dict[str, type] = {
    'pr:ci':             PRCIHandler,
    'pr:review':         PRReviewHandler,
    'pr:merge':          PRMergeHandler,
    'confluence:review': ConfluenceReviewHandler,
}

active_handlers: Dict[str, WatchHandler] = {}


# ---------------------------------------------------------------------------
# Poll loop
# ---------------------------------------------------------------------------

def poll_watches() -> None:
    """Read watches, run each handler concurrently, resolve + queue events."""
    state   = State()
    expired = state.expire_watches()

    # Clean up handlers + state files for expired watches
    for w in expired:
        wid        = w['id']
        if wid in active_handlers:
            del active_handlers[wid]
        state_path = HANDLER_STATE_DIR / f'{wid}.json'
        if state_path.exists():
            state_path.unlink()

    watches = state.list_watches()
    if not watches:
        return

    # Remove stale active_handlers that no longer have a matching watch
    active_ids = {w['id'] for w in watches}
    for wid in list(active_handlers):
        if wid not in active_ids:
            del active_handlers[wid]
            state_path = HANDLER_STATE_DIR / f'{wid}.json'
            if state_path.exists():
                state_path.unlink()

    # Build list of (wid, handler) that are due for checking
    due = []
    for watch in watches:
        wid   = watch['id']
        wtype = watch['type']
        if wid in active_handlers and active_handlers[wid].watch != watch:
            del active_handlers[wid]
            state_path = HANDLER_STATE_DIR / f'{wid}.json'
            if state_path.exists():
                state_path.unlink()
        if wid not in active_handlers:
            handler_cls = HANDLER_REGISTRY.get(wtype)
            if not handler_cls:
                log.warning('Unknown watch type: %s', wtype)
                continue
            active_handlers[wid] = handler_cls(watch)
        handler = active_handlers[wid]
        if handler.should_check():
            due.append((wid, handler))

    if not due:
        return

    new_events = False
    with ThreadPoolExecutor(max_workers=min(MAX_POLL_WORKERS, len(due))) as pool:
        futures: Dict = {pool.submit(handler.check): (wid, handler) for wid, handler in due}
        for future in as_completed(futures):
            wid, handler = futures[future]
            try:
                event = future.result()
            except Exception as e:
                log.error('Watch %s error: %s', wid, e)
                handler.record_failure()
                continue
            if event is None:
                continue
            log.info('Watch %s resolved: %s', wid, event.get('type'))
            keep_alive = event.get('type') in (
                'pr:comment', 'pr:changes_requested', 'pr:approved',
                'page:comment', 'page:needs-fix', 'page:approved',
            )
            if not keep_alive:
                state.remove_watch(wid)
                del active_handlers[wid]
                state_path = HANDLER_STATE_DIR / f'{wid}.json'
                if state_path.exists():
                    state_path.unlink()
            state.queue_event(event)
            new_events = True

    if new_events:
        nudge_orchestrator()


def nudge_orchestrator() -> None:
    """Send 'Events ready' to the orchestrator PTY when idle."""
    global orchestrator_idle
    if not orchestrator_idle or pty_master_fd is None:
        return
    orchestrator_idle = False
    try:
        os.write(pty_master_fd, b'Events ready')
        time.sleep(0.15)
        os.write(pty_master_fd, b'\r')
        log.info('Nudged orchestrator: Events ready')
    except OSError:
        pass


def watch_poll_loop() -> None:
    """Single poll loop — reads all watches, runs checks, resolves, nudges."""
    while True:
        try:
            time.sleep(WATCH_POLL_INTERVAL)
            poll_watches()
        except Exception as e:
            log.error('Watch poll loop error: %s', e)


# ---------------------------------------------------------------------------
# Orchestrator PTY management
# ---------------------------------------------------------------------------

def start_orchestrator() -> None:
    """Fork a child process with a real PTY running claude in interactive mode."""
    global pty_master_fd, pty_child_pid, orchestrator_idle
    if pty_master_fd is not None:
        return

    args = [CLAUDE_BIN, '--dangerously-skip-permissions', '--agent', 'orchestrator']

    log.info('Starting persistent orchestrator')
    pid, fd = pty.fork()

    if pid == 0:
        # child
        os.chdir(str(SCRIPT_DIR))
        cols  = os.environ.get('COLUMNS', '120')
        lines = os.environ.get('LINES', '40')
        os.environ['TERM']    = 'xterm-256color'
        os.environ['COLUMNS'] = cols
        os.environ['LINES']   = lines
        os.execvp(args[0], args)
        return

    # parent
    pty_master_fd     = fd
    pty_child_pid     = pid
    orchestrator_idle = False
    os.set_blocking(fd, False)
    log.info('Orchestrator started (pid=%d)', pid)


def stop_orchestrator() -> None:
    global pty_master_fd, pty_child_pid, orchestrator_idle
    if pty_child_pid:
        try:
            os.kill(pty_child_pid, signal.SIGTERM)
        except OSError:
            pass
    if pty_master_fd is not None:
        try:
            os.close(pty_master_fd)
        except OSError:
            pass
    pty_master_fd     = None
    pty_child_pid     = None
    orchestrator_idle = False


# ---------------------------------------------------------------------------
# Slack
# ---------------------------------------------------------------------------

def start_slack() -> None:
    if not SLACK_APP_TOKEN or not SLACK_BOT_TOKEN:
        log.info('Slack tokens not configured, skipping')
        return
    try:
        from slack_bolt import App
        from slack_bolt.adapter.socket_mode import SocketModeHandler
    except ImportError:
        log.warning('slack-bolt not installed, skipping Slack (pip install slack-bolt)')
        return

    app = App(token=SLACK_BOT_TOKEN)

    @app.event('app_mention')
    def handle_mention(event, say):
        handle_slack_message(event)

    @app.event('message')
    def handle_message(event, say):
        if event.get('channel_type') == 'im':
            handle_slack_message(event)

    handler = SocketModeHandler(app, SLACK_APP_TOKEN)

    def run():
        log.info('Slack Socket Mode connecting...')
        handler.start()

    t = threading.Thread(target=run, daemon=True)
    t.start()


def handle_slack_message(event: dict) -> None:
    text     = (event.get('text') or '').strip()
    if not text:
        return
    category = parse_category(text)
    message  = re.sub(r'^(bug|chore|feature):\s*', '', text, flags=re.IGNORECASE).strip()
    state    = State()
    state.queue_event({
        'type':     'task:requested',
        'category': category,
        'message':  message,
        'senderId': event.get('user', ''),
        'threadTs': event.get('ts'),
        'channel':  event.get('channel'),
    })
    nudge_orchestrator()


# ---------------------------------------------------------------------------
# Webhook HTTP server
# ---------------------------------------------------------------------------

from http.server import HTTPServer, BaseHTTPRequestHandler
import hmac
import hashlib


class WebhookHandler(BaseHTTPRequestHandler):
    """Lightweight HTTP handler for GitHub/Jira webhook payloads."""

    def log_message(self, format, *args):
        log.info('[webhook] %s', format % args)

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        if content_length > 1_000_000:  # 1MB limit
            self.send_error(413, 'Payload too large')
            return
        body = self.rfile.read(content_length)

        # Verify GitHub signature if secret is configured
        gh_secret = env('WEBHOOK_SECRET', '')
        if gh_secret and self.path.startswith('/webhook/github'):
            sig_header = self.headers.get('X-Hub-Signature-256', '')
            expected = 'sha256=' + hmac.new(
                gh_secret.encode(), body, hashlib.sha256
            ).hexdigest()
            if not hmac.compare_digest(sig_header, expected):
                self.send_error(403, 'Invalid signature')
                return

        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            self.send_error(400, 'Invalid JSON')
            return

        if self.path == '/webhook/github':
            self._handle_github(payload)
        elif self.path == '/webhook/jira':
            self._handle_jira(payload)
        elif self.path == '/health':
            self._respond(200, {'status': 'ok'})
        else:
            self.send_error(404, 'Unknown endpoint')

    def do_GET(self):
        if self.path == '/health':
            self._respond(200, {'status': 'ok'})
        else:
            self.send_error(404)

    def _respond(self, code: int, data: dict):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def _handle_github(self, payload: dict):
        """Convert GitHub webhook payload to an event and queue it."""
        gh_event = self.headers.get('X-GitHub-Event', '')
        state = State()
        event = None

        if gh_event == 'check_suite' and payload.get('action') == 'completed':
            suite = payload.get('check_suite', {})
            conclusion = suite.get('conclusion', '')
            branch = suite.get('head_branch', '')
            repo = payload.get('repository', {}).get('full_name', '')
            prs = suite.get('pull_requests', [])
            pr_number = prs[0].get('number') if prs else None
            event = {
                'type': 'ci:passed' if conclusion == 'success' else 'ci:failed',
                'repo': repo,
                'branch': branch,
                'prNumber': pr_number,
                'headSha': suite.get('head_sha', ''),
                'conclusion': conclusion,
                'source': 'webhook',
            }

        elif gh_event == 'pull_request_review':
            action = payload.get('action', '')
            review = payload.get('review', {})
            pr = payload.get('pull_request', {})
            repo = payload.get('repository', {}).get('full_name', '')
            review_state = review.get('state', '').upper()
            if action == 'submitted' and review_state in ('APPROVED', 'CHANGES_REQUESTED'):
                event = {
                    'type': 'pr:approved' if review_state == 'APPROVED' else 'pr:changes_requested',
                    'repo': repo,
                    'prNumber': pr.get('number'),
                    'source': 'webhook',
                }

        elif gh_event == 'pull_request' and payload.get('action') == 'closed':
            pr = payload.get('pull_request', {})
            if pr.get('merged'):
                repo = payload.get('repository', {}).get('full_name', '')
                event = {
                    'type': 'pr:merged',
                    'repo': repo,
                    'prNumber': pr.get('number'),
                    'mergedAt': pr.get('merged_at'),
                    'source': 'webhook',
                }

        elif gh_event in ('pull_request_review_comment', 'issue_comment'):
            comment = payload.get('comment', {})
            pr = payload.get('pull_request') or payload.get('issue', {})
            repo = payload.get('repository', {}).get('full_name', '')
            body = comment.get('body', '')
            # Skip bot comments
            if BOT_SIGNATURE not in body:
                event = {
                    'type': 'pr:comment',
                    'repo': repo,
                    'prNumber': pr.get('number'),
                    'commentId': comment.get('id'),
                    'body': body,
                    'author': comment.get('user', {}).get('login', ''),
                    'isReviewComment': gh_event == 'pull_request_review_comment',
                    'source': 'webhook',
                }

        if event:
            state.queue_event(event)
            nudge_orchestrator()
            log.info('[webhook] Queued %s from GitHub', event.get('type'))
            self._respond(200, {'queued': event.get('type')})
        else:
            self._respond(200, {'ignored': True, 'github_event': gh_event})

    def _handle_jira(self, payload: dict):
        """Convert Jira webhook payload to an event and queue it."""
        webhook_event = payload.get('webhookEvent', '')
        issue = payload.get('issue', {})
        state = State()
        event = None

        if 'comment' in webhook_event:
            comment = payload.get('comment', {})
            event = {
                'type': 'jira:comment',
                'issueKey': issue.get('key', ''),
                'commentId': comment.get('id'),
                'body': comment.get('body', ''),
                'author': comment.get('author', {}).get('displayName', ''),
                'source': 'webhook',
            }

        elif webhook_event == 'jira:issue_updated':
            changelog = payload.get('changelog', {})
            for item in changelog.get('items', []):
                if item.get('field') == 'status':
                    event = {
                        'type': 'jira:status_changed',
                        'issueKey': issue.get('key', ''),
                        'fromStatus': item.get('fromString', ''),
                        'toStatus': item.get('toString', ''),
                        'source': 'webhook',
                    }
                    break

        if event:
            state.queue_event(event)
            nudge_orchestrator()
            log.info('[webhook] Queued %s from Jira', event.get('type'))
            self._respond(200, {'queued': event.get('type')})
        else:
            self._respond(200, {'ignored': True, 'jira_event': webhook_event})


def start_webhook_server() -> None:
    """Start the webhook HTTP server in a daemon thread."""
    port = WEBHOOK_PORT
    if not port:
        log.info('Webhook server disabled (WEBHOOK_PORT=0)')
        return
    try:
        server = HTTPServer(('0.0.0.0', port), WebhookHandler)
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        log.info('Webhook server listening on port %d', port)
    except OSError as e:
        log.warning('Failed to start webhook server on port %d: %s', port, e)


# ---------------------------------------------------------------------------
# macOS caffeinate
# ---------------------------------------------------------------------------

def start_caffeinate() -> None:
    """Prevent macOS idle sleep while poller runs."""
    global caffeinate_proc
    if sys.platform != 'darwin':
        return
    if not shutil.which('caffeinate'):
        return
    try:
        caffeinate_proc = subprocess.Popen(
            ['caffeinate', '-d', '-i', '-s', '-w', str(os.getpid())],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        log.info('caffeinate active (prevents display + idle + system sleep)')
    except Exception as e:
        log.warning('Failed to start caffeinate: %s', e)


# ---------------------------------------------------------------------------
# Main PTY event loop
# ---------------------------------------------------------------------------

def main() -> None:
    global orchestrator_idle, idle_timer, last_ctrl_c, restart_attempts
    state_dir = STATE_BASE
    (state_dir / 'designs').mkdir(parents=True, exist_ok=True)
    (state_dir / 'sessions').mkdir(parents=True, exist_ok=True)
    (state_dir / 'events').mkdir(parents=True, exist_ok=True)
    (state_dir / 'dead_events').mkdir(parents=True, exist_ok=True)
    (state_dir / 'handler_state').mkdir(parents=True, exist_ok=True)
    (state_dir / 'log.jsonl').touch(exist_ok=True)

    log.info('Orchestrator poller starting (watch-driven)')

    # SIGUSR1 → reload config without restart
    if hasattr(signal, 'SIGUSR1'):
        signal.signal(signal.SIGUSR1, reload_config)
        log.info('SIGUSR1 handler registered (kill -USR1 %d to reload config)', os.getpid())

    start_caffeinate()
    start_slack()
    start_webhook_server()

    watch_thread = threading.Thread(target=watch_poll_loop, daemon=True)
    watch_thread.start()

    print(
        '\n'
        '╭────────────────────────────────────────────────────╮\n'
        '│  Orchestrator (persistent session)                  │\n'
        '│                                                     │\n'
        '│  This is a live Claude session. Chat naturally.     │\n'
        '│  Background events auto-inject when idle.           │\n'
        '│                                                     │\n'
        '│  Ctrl+C once  → interrupt current action            │\n'
        '│  Ctrl+C twice → quit poller                         │\n'
        '╰────────────────────────────────────────────────────╯\n',
        flush=True,
    )

    start_orchestrator()

    import tty
    import termios

    if not sys.stdin.isatty():
        log.error('stdin is not a terminal — run this directly in your terminal, not piped')
        # Non-interactive fallback: just keep the poll loop alive
        try:
            while pty_master_fd is not None:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
        stop_orchestrator()
        if caffeinate_proc:
            caffeinate_proc.terminate()
        return

    old_settings = termios.tcgetattr(sys.stdin)
    try:
        tty.setraw(sys.stdin.fileno())
        while True:
            rlist = []
            if pty_master_fd is not None:
                rlist.append(pty_master_fd)
            rlist.append(sys.stdin.fileno())

            try:
                readable, _, _ = select.select(rlist, [], [], 0.1)
            except (ValueError, OSError):
                # fd was closed, restart
                termios.tcsetattr(sys.stdin, termios.TCSADRAIN, old_settings)
                stop_orchestrator()
                if caffeinate_proc:
                    caffeinate_proc.terminate()
                print('\n[Poller stopped.]')
                return

            # Data from orchestrator PTY
            if pty_master_fd is not None and pty_master_fd in readable:
                try:
                    data = os.read(pty_master_fd, 4096)
                    if not data:
                        raise OSError('PTY closed')
                    os.write(sys.stdout.fileno(), data)
                    orchestrator_idle = False
                    if idle_timer:
                        idle_timer.cancel()
                    idle_timer        = threading.Timer(3.0, _mark_idle)
                    idle_timer.daemon = True
                    idle_timer.start()
                except OSError:
                    _handle_orchestrator_exit()
                    continue

            # Data from stdin (user keystrokes)
            if sys.stdin.fileno() in readable:
                data = os.read(sys.stdin.fileno(), 4096)
                if not data:
                    break
                char = data.decode('utf-8', errors='replace')
                if char == '\x03':
                    now = time.time()
                    if now - last_ctrl_c < 0.5:
                        print('\r\n[Quitting poller...]')
                        break
                    last_ctrl_c = now
                elif pty_master_fd is None and char in ('\r', '\n'):
                    # orchestrator exited, Enter to restart
                    restart_attempts = 0
                    start_orchestrator()
                    continue
                try:
                    if pty_master_fd is not None:
                        os.write(pty_master_fd, data)
                except OSError:
                    _handle_orchestrator_exit()

    finally:
        termios.tcsetattr(sys.stdin, termios.TCSADRAIN, old_settings)
        stop_orchestrator()
        if caffeinate_proc:
            caffeinate_proc.terminate()
        print('\n[Poller stopped.]')


def _mark_idle() -> None:
    global orchestrator_idle
    orchestrator_idle = True
    events_dir = STATE_BASE / 'events'
    if events_dir.exists() and any(events_dir.glob('*.json')):
        nudge_orchestrator()


def _handle_orchestrator_exit() -> None:
    global pty_master_fd, pty_child_pid, orchestrator_idle, restart_attempts
    if pty_child_pid:
        try:
            _, status = os.waitpid(pty_child_pid, os.WNOHANG)
        except ChildProcessError:
            status = 0
    if pty_master_fd is not None:
        try:
            os.close(pty_master_fd)
        except OSError:
            pass
    pty_master_fd     = None
    pty_child_pid     = None
    orchestrator_idle = False
    restart_attempts  += 1
    if restart_attempts >= MAX_RESTART_ATTEMPTS:
        os.write(
            sys.stdout.fileno(),
            f'\r\n[Orchestrator failed {restart_attempts} times. Press Enter to retry.]\r\n'.encode(),
        )
        restart_attempts = 0
        return
    os.write(
        sys.stdout.fileno(),
        f'\r\n[Orchestrator exited. Restarting in 2s... ({restart_attempts}/{MAX_RESTART_ATTEMPTS})]\r\n'.encode(),
    )
    time.sleep(2)
    start_orchestrator()


if __name__ == '__main__':
    main()
