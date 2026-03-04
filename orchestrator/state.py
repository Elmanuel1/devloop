"""
Orchestrator state management — persistent store for designs, sessions, action log,
watches, and events.

Importable as a module (from state import State) or usable as a CLI:

    python3 state.py design create  "build payment processing" --category feature
    python3 state.py design get     <id>
    python3 state.py design update  <id> --stage review --confluence-page 12345
    python3 state.py design update  <id> --set-in-flight-comments "id1,id2,id3"
    python3 state.py design update  <id> --merge-in-flight-comments "id4,id5"
    python3 state.py design update  <id> --clear-in-flight-comments
    python3 state.py design list
    python3 state.py session save   TOS-42 --session-id abc123 --agent code-writer --pr 42
    python3 state.py session get    TOS-42
    python3 state.py session delete TOS-42
    python3 state.py log append     "spawned_architect" --design-id abc --detail "for payments"
    python3 state.py log show       --last 20
    python3 state.py watch add      pr:ci --repo owner/repo --pr 42 --branch feature/TOS-42 --issue TOS-42
    python3 state.py watch add      confluence:review --page 3440642 --design <id>   # registers approval + comments together
    python3 state.py watch remove   w-1
    python3 state.py watch list     [--type pr:ci]
    python3 state.py events pop
    python3 state.py events list
    python3 state.py events nack          # pipe event JSON to stdin to re-queue with incremented attempts
    python3 state.py events dead          # list dead-lettered events (failed 3+ times)
    python3 state.py events retry-dead    # move all dead events back to live queue
    python3 state.py migrate sqlite       # migrate file-based state to SQLite (set STATE_BACKEND=sqlite after)
"""

import json
import os
import sys
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

SCRIPT_DIR = Path(__file__).resolve().parent


# ---------------------------------------------------------------------------
# Abstract provider interfaces (one per entity — ISP)
# ---------------------------------------------------------------------------

class WatchProvider(ABC):
    """Storage for watch entries."""

    @abstractmethod
    def read_watches(self) -> List[dict]:
        ...

    @abstractmethod
    def write_watches(self, watches: List[dict]) -> None:
        ...


class EventProvider(ABC):
    """Multi-channel event queue with round-robin pop."""

    @abstractmethod
    def push_event(self, event: dict, channel: str = "system") -> None:
        ...

    @abstractmethod
    def pop_event(self, channel: Optional[str] = None) -> Optional[dict]:
        """Pop oldest event. channel=None → round-robin across all channels."""
        ...

    @abstractmethod
    def read_events(self, channel: Optional[str] = None) -> List[dict]:
        ...

    @abstractmethod
    def nack_event(self, event: dict) -> None:
        """Re-queue a failed event with incremented attempts counter."""
        ...


class DeadEventProvider(ABC):
    """Dead-lettered events that exceeded max attempts."""

    @abstractmethod
    def list_dead_events(self) -> List[dict]:
        ...

    @abstractmethod
    def retry_dead_events(self) -> int:
        """Move all dead events back to live queue. Returns count retried."""
        ...


class DesignProvider(ABC):
    """Storage for design documents."""

    @abstractmethod
    def read_design(self, design_id: str) -> Optional[dict]:
        ...

    @abstractmethod
    def write_design(self, design: dict) -> None:
        ...

    @abstractmethod
    def delete_design(self, design_id: str) -> bool:
        ...

    @abstractmethod
    def list_designs(self) -> List[dict]:
        ...


class SessionProvider(ABC):
    """Storage for agent sessions."""

    @abstractmethod
    def read_session(self, issue_key: str) -> Optional[dict]:
        ...

    @abstractmethod
    def write_session(self, issue_key: str, session: dict) -> None:
        ...

    @abstractmethod
    def delete_session(self, issue_key: str) -> bool:
        ...

    @abstractmethod
    def list_sessions(self) -> List[dict]:
        ...


# ---------------------------------------------------------------------------
# Channel routing — maps event type prefix to channel name
# ---------------------------------------------------------------------------

CHANNELS = ("ci", "pr", "design", "task", "system")

_CHANNEL_MAP = {
    "ci":    "ci",
    "pr":    "pr",
    "page":  "design",
    "task":  "task",
    "watch": "system",
}


def _channel_for(event_type: str) -> str:
    """Derive channel from event type (e.g. 'ci:passed' → 'ci', 'page:comment' → 'design')."""
    prefix = event_type.split(":")[0] if ":" in event_type else event_type
    return _CHANNEL_MAP.get(prefix, "system")


def _dedup_key(event: dict) -> Optional[str]:
    """Compute a dedup key for an event. Returns None if no dedup applies."""
    etype = event.get("type", "")
    if etype == "pr:comment":
        cid = event.get("commentId")
        return f"pr:comment:{cid}" if cid else None
    if etype in ("ci:passed", "ci:failed"):
        sha = event.get("headSha")
        if sha:
            return f"{etype}:{event.get('repo', '')}:{sha}"
        return None
    if etype in ("pr:approved", "pr:changes_requested", "pr:merged"):
        pr = event.get("prNumber")
        return f"{etype}:{event.get('repo', '')}:{pr}" if pr else None
    return None


# ---------------------------------------------------------------------------
# File-based implementation
# ---------------------------------------------------------------------------

class FileStateProvider(WatchProvider, EventProvider, DeadEventProvider, DesignProvider, SessionProvider):
    """File-based implementation. watches.json + events/{channel}/ + designs/ + sessions/."""

    MAX_EVENT_ATTEMPTS = 3

    def __init__(self, base_dir: Path) -> None:
        self.base = base_dir
        self.watches_file = base_dir / "watches.json"
        self.events_dir = base_dir / "events"
        self.dead_events_dir = base_dir / "dead_events"
        self.designs_dir = base_dir / "designs"
        self.sessions_dir = base_dir / "sessions"
        for d in (self.dead_events_dir, self.designs_dir, self.sessions_dir):
            d.mkdir(parents=True, exist_ok=True)
        # Create channel subdirs
        for ch in CHANNELS:
            (self.events_dir / ch).mkdir(parents=True, exist_ok=True)
        # Migrate old flat events/ files into "system" channel
        self._migrate_flat_events()
        # Round-robin state
        self._rr_index = 0

    def _migrate_flat_events(self) -> None:
        """Move any .json files in the flat events/ dir into the system channel subdir."""
        for f in self.events_dir.glob("*.json"):
            dest = self.events_dir / "system" / f.name
            f.rename(dest)

    # --- Watches ---

    def read_watches(self) -> List[dict]:
        if not self.watches_file.exists():
            return []
        try:
            return json.loads(self.watches_file.read_text())
        except (json.JSONDecodeError, OSError):
            return []

    def write_watches(self, watches: List[dict]) -> None:
        self.watches_file.write_text(json.dumps(watches))

    # --- Events (multi-channel) ---

    def push_event(self, event: dict, channel: str = "system") -> None:
        ch_dir = self.events_dir / channel
        ch_dir.mkdir(parents=True, exist_ok=True)
        ts = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        path = ch_dir / f"{ts}.json"
        path.write_text(json.dumps(event))

    def _pop_from_channel(self, channel: str) -> Optional[dict]:
        """Pop oldest event from a specific channel. Dead-letters poison events."""
        ch_dir = self.events_dir / channel
        if not ch_dir.exists():
            return None
        files = sorted(ch_dir.glob("*.json"))
        for path in files:
            try:
                event = json.loads(path.read_text())
            except (json.JSONDecodeError, OSError):
                path.unlink(missing_ok=True)
                continue
            attempts = event.get("_attempts", 0)
            if attempts >= self.MAX_EVENT_ATTEMPTS:
                dest = self.dead_events_dir / path.name
                path.rename(dest)
                continue
            event["_attempts"] = attempts
            event["_channel"] = channel
            path.unlink()
            return event
        return None

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

    def nack_event(self, event: dict) -> None:
        """Re-queue with incremented attempts. Routes back to original channel."""
        event["_attempts"] = event.get("_attempts", 0) + 1
        channel = event.pop("_channel", "system")
        self.push_event(event, channel=channel)

    def read_events(self, channel: Optional[str] = None) -> List[dict]:
        channels = [channel] if channel else list(CHANNELS)
        result = []
        for ch in channels:
            ch_dir = self.events_dir / ch
            if not ch_dir.exists():
                continue
            for f in sorted(ch_dir.glob("*.json")):
                try:
                    event = json.loads(f.read_text())
                    event["_channel"] = ch
                    result.append(event)
                except (json.JSONDecodeError, OSError):
                    pass
        return result

    # --- Dead events ---

    def list_dead_events(self) -> List[dict]:
        dead = []
        for f in sorted(self.dead_events_dir.glob("*.json")):
            try:
                dead.append(json.loads(f.read_text()))
            except (json.JSONDecodeError, OSError):
                pass
        return dead

    def retry_dead_events(self) -> int:
        count = 0
        for f in sorted(self.dead_events_dir.glob("*.json")):
            try:
                event = json.loads(f.read_text())
                event["_attempts"] = 0
                channel = _channel_for(event.get("type", "unknown"))
                self.push_event(event, channel=channel)
                f.unlink()
                count += 1
            except (json.JSONDecodeError, OSError):
                pass
        return count

    # --- Designs ---

    def read_design(self, design_id: str) -> Optional[dict]:
        path = self.designs_dir / f"{design_id}.json"
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text())
        except (json.JSONDecodeError, OSError):
            return None

    def write_design(self, design: dict) -> None:
        path = self.designs_dir / f"{design['id']}.json"
        path.write_text(json.dumps(design))

    def delete_design(self, design_id: str) -> bool:
        path = self.designs_dir / f"{design_id}.json"
        if not path.exists():
            return False
        path.unlink()
        return True

    def list_designs(self) -> List[dict]:
        designs = []
        for path in sorted(self.designs_dir.glob("*.json")):
            try:
                designs.append(json.loads(path.read_text()))
            except (json.JSONDecodeError, OSError):
                pass
        return designs

    # --- Sessions ---

    def read_session(self, issue_key: str) -> Optional[dict]:
        path = self.sessions_dir / f"{issue_key}.json"
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text())
        except (json.JSONDecodeError, OSError):
            return None

    def write_session(self, issue_key: str, session: dict) -> None:
        path = self.sessions_dir / f"{issue_key}.json"
        path.write_text(json.dumps(session))

    def delete_session(self, issue_key: str) -> bool:
        path = self.sessions_dir / f"{issue_key}.json"
        if not path.exists():
            return False
        path.unlink()
        return True

    def list_sessions(self) -> List[dict]:
        sessions = []
        for path in sorted(self.sessions_dir.glob("*.json")):
            try:
                sessions.append(json.loads(path.read_text()))
            except (json.JSONDecodeError, OSError):
                pass
        return sessions


# ---------------------------------------------------------------------------
# Design stage machine — valid transitions
# ---------------------------------------------------------------------------

VALID_STAGE_TRANSITIONS: Dict[str, List[str]] = {
    "design":         ["review"],
    "review":         ["approved", "design"],       # design = sent back for rework
    "approved":       ["jira-breakdown"],
    "jira-breakdown": ["implementation"],
    "implementation": ["complete"],
}


class InvalidStageTransition(ValueError):
    """Raised when a design stage transition violates the allowed flow."""
    pass


# ---------------------------------------------------------------------------
# Design update handlers (plain functions for the handler registry)
# ---------------------------------------------------------------------------

def _apply_add_child_ticket(design, value, now):
    design.setdefault("childTickets", []).append(value)
    design.setdefault("history", []).append({"ts": now, "action": "child_ticket_added", "ticket": value})


def _apply_add_pr(design, value, now):
    design.setdefault("prs", []).append(value)
    design.setdefault("history", []).append({"ts": now, "action": "pr_added", "pr": value})


def _apply_add_artifact(design, value, now):
    if isinstance(value, dict):
        design.setdefault("artifacts", {}).update(value)
    design.setdefault("history", []).append({"ts": now, "action": "artifact_added"})


def _apply_add_history(design, value, now):
    design.setdefault("history", []).append(value)


def _apply_set_in_flight_comments(design, value, now):
    design["inFlightCommentIds"] = list(value) if value else []
    design.setdefault("history", []).append({"ts": now, "action": "architect_in_flight"})


def _apply_merge_in_flight_comments(design, value, now):
    existing = set(design.get("inFlightCommentIds", []))
    existing.update(value or [])
    design["inFlightCommentIds"] = list(existing)
    design.setdefault("history", []).append({"ts": now, "action": "in_flight_comments_merged"})


def _apply_clear_in_flight_comments(design, value, now):
    finished = design.get("inFlightCommentIds", [])
    handled = set(design.get("handledCommentIds", []))
    handled.update(finished)
    design["handledCommentIds"] = list(handled)
    design["inFlightCommentIds"] = []
    design.setdefault("history", []).append({"ts": now, "action": "architect_in_flight_cleared"})


def _apply_add_handled_comments(design, value, now):
    handled = set(design.get("handledCommentIds", []))
    handled.update(value or [])
    design["handledCommentIds"] = list(handled)
    design.setdefault("history", []).append({"ts": now, "action": "comments_marked_handled", "count": len(value or [])})


# ---------------------------------------------------------------------------
# Main state manager
# ---------------------------------------------------------------------------

class State:
    """Manages all orchestrator persistent state."""

    # TTL defaults (seconds) per watch type
    _DEFAULT_TTL: Dict[str, int] = {
        "pr:ci":              86400,    #  1 day
        "pr:review":          1209600,  # 14 days
        "pr:merge":           259200,   #  3 days
        "confluence:review":  2592000,  # 30 days
    }

    # Fields used to de-duplicate watches of the same type
    _DEDUP_KEYS: Dict[str, tuple] = {
        "pr:ci":               ("type", "repo", "branch"),
        "pr:review":           ("type", "repo", "prNumber"),
        "pr:merge":            ("type", "repo", "prNumber"),
        "confluence:review":   ("type", "pageId"),
        "confluence:approval": ("type", "pageId"),
        "confluence:comments": ("type", "pageId"),
    }

    def __init__(self, base_dir: Optional[str] = None, provider=None) -> None:
        default_base = os.environ.get("STATE_DIR", str(SCRIPT_DIR / ".orchestrator"))
        self.base = Path(base_dir) if base_dir else Path(default_base)
        self.base.mkdir(parents=True, exist_ok=True)

        if provider is None:
            backend = os.environ.get("STATE_BACKEND", "file").lower()
            if backend == "sqlite":
                provider = self._init_sqlite_provider()
            else:
                provider = FileStateProvider(self.base)

        # Typed refs — all point to the same composite provider object
        self.provider = provider  # kept for backward compat
        self.watches: WatchProvider = provider
        self.events: EventProvider = provider
        self.dead_events: DeadEventProvider = provider
        self.designs: DesignProvider = provider
        self.sessions: SessionProvider = provider

        # Log is always file-based (not abstracted into a provider)
        self.log_file = self.base / "log.jsonl"
        self.log_file.touch(exist_ok=True)

    def _init_sqlite_provider(self):
        """Initialize SQLite provider, auto-migrating from files if needed."""
        try:
            from store_sqlite import SQLiteStateProvider
        except ImportError:
            import sys
            print("[state] WARNING: store_sqlite.py not found, falling back to file provider", file=sys.stderr)
            return FileStateProvider(self.base)

        db_path = self.base / "state.db"
        if not db_path.exists():
            # Check if file-based state exists to migrate
            has_files = (
                (self.base / "watches.json").exists()
                or any((self.base / "designs").glob("*.json"))
                or any((self.base / "sessions").glob("*.json"))
                or any((self.base / "events").glob("*.json"))
            )
            if has_files:
                import sys
                print("[state] Migrating file-based state to SQLite...", file=sys.stderr)
                provider = SQLiteStateProvider.migrate_from_files(self.base, db_path)
                print("[state] Migration complete. File-based state preserved as backup.", file=sys.stderr)
                return provider
        return SQLiteStateProvider(db_path)

    # ------------------------------------------------------------------
    # Designs
    # ------------------------------------------------------------------

    def create_design(self, description: str, category: str = "feature", design_id: Optional[str] = None) -> dict:
        """Create a new design and return it."""
        did = design_id or str(uuid.uuid4())
        now = datetime.now(timezone.utc).isoformat()
        design = {
            "id":                 did,
            "description":        description,
            "category":           category,
            "stage":              "design",
            "createdAt":          now,
            "confluencePageId":   None,
            "jiraParentKey":      None,
            "childTickets":       [],
            "prs":                [],
            "artifacts":          {},
            "inFlightCommentIds": [],
            "handledCommentIds":  [],
            "history":            [{"ts": now, "action": "created"}],
        }
        self.designs.write_design(design)
        self.log("design_created", design_id=did, description=description, category=category)
        return design

    def get_design(self, design_id: str) -> Optional[dict]:
        """Read a design by ID."""
        return self.designs.read_design(design_id)

    _UPDATE_HANDLERS = {
        "add_child_ticket":         _apply_add_child_ticket,
        "add_pr":                   _apply_add_pr,
        "add_artifact":             _apply_add_artifact,
        "add_history":              _apply_add_history,
        "set_in_flight_comments":   _apply_set_in_flight_comments,
        "merge_in_flight_comments": _apply_merge_in_flight_comments,
        "clear_in_flight_comments": _apply_clear_in_flight_comments,
        "add_handled_comments":     _apply_add_handled_comments,
    }

    def update_design(self, design_id: str, updates: dict) -> Optional[dict]:
        """Update fields on a design. Automatically logs stage changes and appends to history."""
        design = self.get_design(design_id)
        if not design:
            return None
        now = datetime.now(timezone.utc).isoformat()
        old_stage = design.get("stage")

        # Validate stage transition before applying any changes
        new_stage = updates.get("stage")
        if new_stage and new_stage != old_stage:
            allowed = VALID_STAGE_TRANSITIONS.get(old_stage, [])
            if new_stage not in allowed:
                raise InvalidStageTransition(
                    f"Cannot transition design from '{old_stage}' to '{new_stage}'. "
                    f"Allowed: {allowed}"
                )

        for key, value in updates.items():
            handler = self._UPDATE_HANDLERS.get(key)
            if handler:
                handler(design, value, now)
            else:
                design[key] = value

        if new_stage and new_stage != old_stage:
            design.setdefault("history", []).append({
                "ts": now,
                "action": "stage_changed",
                "from": old_stage,
                "to": new_stage,
            })
            self.log(f"stage_{old_stage}_to_{new_stage}", design_id=design_id)

        self.designs.write_design(design)
        return design

    def list_designs(self, stage: Optional[str] = None, active_only: bool = False) -> List[dict]:
        """List designs, optionally filtered by stage. active_only excludes 'complete' stage."""
        designs = self.designs.list_designs()
        if active_only:
            designs = [d for d in designs if d.get("stage") != "complete"]
        if stage:
            designs = [d for d in designs if d.get("stage") == stage]
        return designs

    def complete_design(self, design_id: str) -> Optional[dict]:
        """Mark a design as complete. It won't be loaded on startup."""
        return self.update_design(design_id, {"stage": "complete"})

    def delete_design(self, design_id: str) -> bool:
        """Delete a design."""
        ok = self.designs.delete_design(design_id)
        if ok:
            self.log("design_deleted", design_id=design_id)
        return ok

    # ------------------------------------------------------------------
    # Sessions
    # ------------------------------------------------------------------

    def save_session(self, issue_key: str, session_id: str, agent: str = "unknown",
                     pr_number: Optional[int] = None, extra: Optional[dict] = None) -> dict:
        """Save an agent session for an issue."""
        now = datetime.now(timezone.utc).isoformat()
        session = {
            "issueKey":   issue_key,
            "sessionId":  session_id,
            "agent":      agent,
            "prNumber":   pr_number,
            "savedAt":    now,
            **(extra or {}),
        }
        self.sessions.write_session(issue_key, session)
        self.log("session_saved", issue_key=issue_key, session_id=session_id, agent=agent)
        return session

    def get_session(self, issue_key: str) -> Optional[dict]:
        """Read a session by issue key."""
        return self.sessions.read_session(issue_key)

    def delete_session(self, issue_key: str) -> bool:
        """Delete a session."""
        ok = self.sessions.delete_session(issue_key)
        if ok:
            self.log("session_deleted", issue_key=issue_key)
        return ok

    def list_sessions(self) -> List[dict]:
        """List all active sessions."""
        return self.sessions.list_sessions()

    # ------------------------------------------------------------------
    # Action log
    # ------------------------------------------------------------------

    def log(self, action: str, **data: Any) -> None:
        """Append an action to the log (always file-based JSONL)."""
        entry = {
            "ts":     datetime.now(timezone.utc).isoformat(),
            "action": action,
            **{k: v for k, v in data.items() if v is not None},
        }
        with open(self.log_file, "a") as f:
            f.write(json.dumps(entry) + "\n")

    def read_log(self, last: int = 50) -> List[dict]:
        """Read the last N log entries."""
        if not self.log_file.exists():
            return []
        lines = self.log_file.read_text().strip().split("\n")
        entries = []
        for line in lines[-last:]:
            try:
                entries.append(json.loads(line))
            except json.JSONDecodeError:
                pass
        return entries

    # ------------------------------------------------------------------
    # Watches
    # ------------------------------------------------------------------

    def add_watch(self, watch_type: str, interval: int = 30, **fields: Any) -> dict:
        """Add a watch entry. Deduplicates by type + key params."""
        watches = self.watches.read_watches()
        dedup_fields = self._DEDUP_KEYS.get(watch_type, ("type",))
        candidate = {"type": watch_type, **fields}

        # De-duplicate: return existing if all dedup keys match
        for w in watches:
            try:
                if all(w.get(k) == candidate.get(k) for k in dedup_fields):
                    return w
            except ValueError:
                continue

        # Generate next sequential ID
        max_id = 0
        for w in watches:
            wid = w.get("id", "")
            if not wid.startswith("w-"):
                continue
            try:
                max_id = max(max_id, int(wid[2:]))
            except ValueError:
                continue
        new_id = f"w-{max_id + 1}"

        watch = {
            "id":       new_id,
            "type":     watch_type,
            "interval": interval,
            "addedAt":  datetime.now(timezone.utc).isoformat(),
            **fields,
        }

        # Compute expiry if not already set
        if "expiresAt" not in watch:
            ttl = self._DEFAULT_TTL.get(watch_type, 86400)
            expires = datetime.now(timezone.utc) + timedelta(seconds=ttl)
            watch["expiresAt"] = expires.isoformat()

        watches.append(watch)
        self.watches.write_watches(watches)
        self.log("watch_added", watch_id=new_id, watch_type=watch_type, **fields)
        return watch

    def remove_watch(self, watch_id: str) -> bool:
        """Remove a watch by ID."""
        watches = self.watches.read_watches()
        before = len(watches)
        watches = [w for w in watches if w.get("id") != watch_id]
        if len(watches) == before:
            return False
        self.watches.write_watches(watches)
        self.log("watch_removed", watch_id=watch_id)
        return True

    def list_watches(self, watch_type: Optional[str] = None) -> List[dict]:
        """List watches, optionally filtered by type."""
        watches = self.watches.read_watches()
        if watch_type:
            watches = [w for w in watches if w.get("type") == watch_type]
        return watches

    def expire_watches(self) -> List[dict]:
        """Remove expired watches and queue watch:expired events. Returns expired list."""
        watches = self.watches.read_watches()
        now = datetime.now(timezone.utc)
        alive = []
        expired = []
        for w in watches:
            expires_str = w.get("expiresAt")
            if expires_str:
                try:
                    expires_at = datetime.fromisoformat(expires_str)
                    if expires_at < now:
                        expired.append(w)
                        continue
                except (ValueError, TypeError):
                    pass
            alive.append(w)
        if expired:
            self.watches.write_watches(alive)
            for w in expired:
                self.queue_event({"type": "watch:expired", **{k: v for k, v in w.items()}})
                self.log("watch_expired", watch_id=w.get("id"), watch_type=w.get("type"))
        return expired

    def queue_event(self, event: dict) -> None:
        """Push an event to the appropriate channel queue. Deduplicates by event-specific key."""
        channel = _channel_for(event.get("type", "unknown"))
        key = _dedup_key(event)
        if key:
            for existing in self.events.read_events(channel=channel):
                if _dedup_key(existing) == key:
                    self.log("event_deduped", type=event.get("type"), key=key)
                    return
        self.events.push_event(event, channel=channel)
        self.log("event_queued", type=event.get("type"), channel=channel)

    def nack_event(self, event: dict) -> None:
        """Re-queue a failed event. Increments _attempts; dead-lettered on next pop if over limit."""
        self.events.nack_event(event)
        attempts = event.get("_attempts", 0)
        self.log("event_nacked", type=event.get("type"), attempts=attempts)

    def pop_event(self, channel: Optional[str] = None) -> Optional[dict]:
        """Pop an event from the queue (round-robin across channels by default).

        - Poison protection: events with _attempts >= 3 are dead-lettered (skipped).
        - page:comment gating: if the target design has inFlightCommentIds set
          (architect is already working), the new comment IDs are merged and the event is deferred.
        """
        while True:
            event = self.events.pop_event(channel=channel)
            if event is None:
                return None

            # page:comment gating logic
            if event.get("type") == "page:comment":
                design_id = event.get("designId")
                if design_id:
                    design = self.get_design(design_id)
                    if design:
                        handled = set(design.get("handledCommentIds", []))
                        in_flight = set(design.get("inFlightCommentIds", []))
                        new_ids = [c for c in event.get("newCommentIds", []) if c not in handled]
                        if not new_ids:
                            self.log("event_dropped", type="page:comment", reason="all_comments_already_handled")
                            continue
                        if in_flight:
                            self.update_design(design_id, {"merge_in_flight_comments": new_ids})
                            self.log("event_deferred", type="page:comment", reason="architect_in_flight")
                            continue
                        event["newCommentIds"] = new_ids

            self.log("event_popped", type=event.get("type"), channel=event.get("_channel"))
            return event

    def list_events(self, channel: Optional[str] = None) -> List[dict]:
        """List all pending events without removing them."""
        return self.events.read_events(channel=channel)

    def list_dead_events(self) -> List[dict]:
        """List dead-lettered events (failed 3+ times)."""
        return self.dead_events.list_dead_events()

    def retry_dead_events(self) -> int:
        """Move all dead events back to live queue with attempts reset. Returns count retried."""
        count = self.dead_events.retry_dead_events()
        if count:
            self.log("dead_events_retried", count=count)
        return count

    # ------------------------------------------------------------------
    # Summary
    # ------------------------------------------------------------------

    def summary(self) -> str:
        """Generate a human-readable summary of current state (active only, not completed)."""
        designs  = self.list_designs(active_only=True)
        sessions = self.list_sessions()
        watches  = self.list_watches()
        events   = self.list_events()
        recent   = self.read_log(last=10)

        parts = []

        # Designs
        parts.append(f"## Active Designs ({len(designs)})")
        for d in designs:
            children  = len(d.get("childTickets", []))
            prs       = len(d.get("prs", []))
            jira      = d.get("jiraParentKey") or "no Jira yet"
            in_flight = d.get("inFlightCommentIds", [])
            handled   = d.get("handledCommentIds", [])
            in_flight_str = f", ⏳ {len(in_flight)} with architect" if in_flight else ""
            handled_str   = f", ✅ {len(handled)} handled"           if handled   else ""
            parts.append(
                f"- **{d.get('id')}** [{d.get('stage')}] {d.get('description')} "
                f"({d.get('category')}) — {jira} — "
                f"{children} tickets, {prs} PRs{in_flight_str}{handled_str}"
            )
        if not designs:
            parts.append("No active designs.")

        # Sessions
        parts.append(f"\n## Active Sessions ({len(sessions)})")
        for s in sessions:
            key   = s.get("issueKey", "?")
            agent = s.get("agent", "?")
            pr    = f"PR #{s.get('prNumber')}" if s.get("prNumber") else "no PR"
            parts.append(f"- **{key}** → {agent} ({pr})")
        if not sessions:
            parts.append("\nNo active sessions.")

        # Watches
        parts.append(f"\n## Active Watches ({len(watches)})")
        for w in watches:
            wid   = w.get("id", "?")
            wtype = w.get("type", "?")
            detail_keys = [k for k in w if k not in ("id", "type", "interval", "addedAt")]
            detail = ", ".join(f"{k}={w[k]}" for k in detail_keys)
            parts.append(f"- [{wid}] {wtype} — {detail}")
        if not watches:
            parts.append("\nNo active watches.")

        # Events
        parts.append(f"\n## Pending Events ({len(events)})")
        for e in events:
            etype = e.get("type", "?")
            detail = ", ".join(f"{k}={v}" for k, v in e.items() if k != "type")
            parts.append(f"- [{etype}] {detail}")

        # Recent log
        parts.append(f"\n## Recent Actions (last {len(recent)})")
        for entry in recent:
            ts     = entry.get("ts", "")[:19]
            action = entry.get("action", "?")
            detail_str = ", ".join(f"{k}={v}" for k, v in entry.items() if k not in ("ts", "action"))
            parts.append(f"- `{ts}` {action} — {detail_str}" if detail_str else f"- `{ts}` {action}")

        return "\n".join(parts)


# ---------------------------------------------------------------------------
# CLI helpers (re-exported for backward compat)
# ---------------------------------------------------------------------------

from cli_utils import flag as _flag


if __name__ == "__main__":
    from state_cli import cli
    cli()
