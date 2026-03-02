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
# Abstract provider interface
# ---------------------------------------------------------------------------

class StateProvider(ABC):
    """Abstract interface for state storage. File-based now, swappable later."""

    @abstractmethod
    def read_watches(self) -> List[dict]:
        ...

    @abstractmethod
    def write_watches(self, watches: List[dict]) -> None:
        ...

    @abstractmethod
    def read_events(self) -> List[dict]:
        ...

    @abstractmethod
    def push_event(self, event: dict) -> None:
        ...

    @abstractmethod
    def pop_event(self) -> Optional[dict]:
        ...


# ---------------------------------------------------------------------------
# File-based implementation
# ---------------------------------------------------------------------------

class FileStateProvider(StateProvider):
    """File-based implementation. watches.json + events/ dir."""

    def __init__(self, base_dir: Path) -> None:
        self.base = base_dir
        self.watches_file = base_dir / "watches.json"
        self.events_dir = base_dir / "events"
        self.events_dir.mkdir(parents=True, exist_ok=True)

    def read_watches(self) -> List[dict]:
        if not self.watches_file.exists():
            return []
        try:
            return json.loads(self.watches_file.read_text())
        except (json.JSONDecodeError, OSError):
            return []

    def write_watches(self, watches: List[dict]) -> None:
        self.watches_file.write_text(json.dumps(watches))

    def push_event(self, event: dict) -> None:
        ts = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        path = self.events_dir / f"{ts}.json"
        path.write_text(json.dumps(event))

    def pop_event(self) -> Optional[dict]:
        files = sorted(self.events_dir.glob("*.json"))
        if not files:
            return None
        path = files[0]
        event = json.loads(path.read_text())
        path.unlink()
        return event

    def read_events(self) -> List[dict]:
        return [json.loads(f.read_text()) for f in sorted(self.events_dir.glob("*.json"))]


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

    def __init__(self, base_dir: Optional[str] = None, provider: Optional[StateProvider] = None) -> None:
        default_base = os.environ.get("STATE_DIR", str(SCRIPT_DIR / ".orchestrator"))
        self.base = Path(base_dir) if base_dir else Path(default_base)
        self.designs_dir = self.base / "designs"
        self.sessions_dir = self.base / "sessions"
        self.log_file = self.base / "log.jsonl"
        self.designs_dir.mkdir(parents=True, exist_ok=True)
        self.sessions_dir.mkdir(parents=True, exist_ok=True)
        self.log_file.touch(exist_ok=True)
        self.provider = provider or FileStateProvider(self.base)

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
        self._write_design(design)
        self.log("design_created", design_id=did, description=description, category=category)
        return design

    def get_design(self, design_id: str) -> Optional[dict]:
        """Read a design by ID."""
        path = self.designs_dir / f"{design_id}.json"
        if not path.exists():
            return None
        return json.loads(path.read_text())

    def update_design(self, design_id: str, updates: dict) -> Optional[dict]:
        """Update fields on a design. Automatically logs stage changes and appends to history."""
        design = self.get_design(design_id)
        if not design:
            return None
        now = datetime.now(timezone.utc).isoformat()
        old_stage = design.get("stage")

        for key, value in updates.items():
            if key == "add_child_ticket":
                design.setdefault("childTickets", []).append(value)
                design.setdefault("history", []).append({"ts": now, "action": "child_ticket_added", "ticket": value})
            elif key == "add_pr":
                design.setdefault("prs", []).append(value)
                design.setdefault("history", []).append({"ts": now, "action": "pr_added", "pr": value})
            elif key == "add_artifact":
                if isinstance(value, dict):
                    design.setdefault("artifacts", {}).update(value)
                design.setdefault("history", []).append({"ts": now, "action": "artifact_added"})
            elif key == "add_history":
                design.setdefault("history", []).append(value)
            elif key == "set_in_flight_comments":
                design["inFlightCommentIds"] = list(value) if value else []
                design.setdefault("history", []).append({"ts": now, "action": "architect_in_flight"})
            elif key == "merge_in_flight_comments":
                existing = set(design.get("inFlightCommentIds", []))
                existing.update(value or [])
                design["inFlightCommentIds"] = list(existing)
                design.setdefault("history", []).append({"ts": now, "action": "in_flight_comments_merged"})
            elif key == "clear_in_flight_comments":
                finished = design.get("inFlightCommentIds", [])
                handled = set(design.get("handledCommentIds", []))
                handled.update(finished)
                design["handledCommentIds"] = list(handled)
                design["inFlightCommentIds"] = []
                design.setdefault("history", []).append({"ts": now, "action": "architect_in_flight_cleared"})
            elif key == "add_handled_comments":
                handled = set(design.get("handledCommentIds", []))
                handled.update(value or [])
                design["handledCommentIds"] = list(handled)
                design.setdefault("history", []).append({"ts": now, "action": "comments_marked_handled", "count": len(value or [])})
            else:
                design[key] = value

        new_stage = design.get("stage")
        if new_stage != old_stage:
            design.setdefault("history", []).append({
                "ts": now,
                "action": "stage_changed",
                "from": old_stage,
                "to": new_stage,
            })
            self.log(f"stage_{old_stage}_to_{new_stage}", design_id=design_id)

        self._write_design(design)
        return design

    def list_designs(self, stage: Optional[str] = None, active_only: bool = False) -> List[dict]:
        """List designs, optionally filtered by stage. active_only excludes 'complete' stage."""
        designs = []
        for path in sorted(self.designs_dir.glob("*.json")):
            try:
                d = json.loads(path.read_text())
                if active_only and d.get("stage") == "complete":
                    continue
                if stage and d.get("stage") != stage:
                    continue
                designs.append(d)
            except (json.JSONDecodeError, OSError):
                pass
        return designs

    def complete_design(self, design_id: str) -> Optional[dict]:
        """Mark a design as complete. It won't be loaded on startup."""
        return self.update_design(design_id, {"stage": "complete"})

    def delete_design(self, design_id: str) -> bool:
        """Delete a design file."""
        path = self.designs_dir / f"{design_id}.json"
        if not path.exists():
            return False
        path.unlink()
        self.log("design_deleted", design_id=design_id)
        return True

    def _write_design(self, design: dict) -> None:
        path = self.designs_dir / f"{design['id']}.json"
        path.write_text(json.dumps(design))

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
        path = self.sessions_dir / f"{issue_key}.json"
        path.write_text(json.dumps(session))
        self.log("session_saved", issue_key=issue_key, session_id=session_id, agent=agent)
        return session

    def get_session(self, issue_key: str) -> Optional[dict]:
        """Read a session by issue key."""
        path = self.sessions_dir / f"{issue_key}.json"
        if not path.exists():
            return None
        return json.loads(path.read_text())

    def delete_session(self, issue_key: str) -> bool:
        """Delete a session file."""
        path = self.sessions_dir / f"{issue_key}.json"
        if not path.exists():
            return False
        path.unlink()
        self.log("session_deleted", issue_key=issue_key)
        return True

    def list_sessions(self) -> List[dict]:
        """List all active sessions."""
        sessions = []
        for path in sorted(self.sessions_dir.glob("*.json")):
            try:
                sessions.append(json.loads(path.read_text()))
            except (json.JSONDecodeError, OSError):
                pass
        return sessions

    # ------------------------------------------------------------------
    # Action log
    # ------------------------------------------------------------------

    def log(self, action: str, **data: Any) -> None:
        """Append an action to the log."""
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
        watches = self.provider.read_watches()
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
        self.provider.write_watches(watches)
        self.log("watch_added", watch_id=new_id, watch_type=watch_type, **fields)
        return watch

    def remove_watch(self, watch_id: str) -> bool:
        """Remove a watch by ID."""
        watches = self.provider.read_watches()
        before = len(watches)
        watches = [w for w in watches if w.get("id") != watch_id]
        if len(watches) == before:
            return False
        self.provider.write_watches(watches)
        self.log("watch_removed", watch_id=watch_id)
        return True

    def list_watches(self, watch_type: Optional[str] = None) -> List[dict]:
        """List watches, optionally filtered by type."""
        watches = self.provider.read_watches()
        if watch_type:
            watches = [w for w in watches if w.get("type") == watch_type]
        return watches

    def expire_watches(self) -> List[dict]:
        """Remove expired watches and queue watch:expired events. Returns expired list."""
        watches = self.provider.read_watches()
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
            self.provider.write_watches(alive)
            for w in expired:
                self.queue_event({"type": "watch:expired", **{k: v for k, v in w.items()}})
                self.log("watch_expired", watch_id=w.get("id"), watch_type=w.get("type"))
        return expired

    def queue_event(self, event: dict) -> None:
        """Push an event to the FIFO queue."""
        self.provider.push_event(event)
        self.log("event_queued", type=event.get("type"))

    def pop_event(self) -> Optional[dict]:
        """Pop the oldest event from the queue (read + delete).

        page:comment events are gated: if the target design has inFlightCommentIds set
        (architect is already working), the new comment IDs are merged into inFlightCommentIds
        and the event is deferred (not returned). Already-handled comments are silently dropped.
        """
        events = self.provider.read_events()
        files = sorted((self.base / "events").glob("*.json"))
        for f in files:
            try:
                event = json.loads(f.read_text())
            except (json.JSONDecodeError, OSError):
                f.unlink()
                continue

            if event.get("type") == "page:comment":
                design_id = event.get("designId")
                if design_id:
                    design = self.get_design(design_id)
                    if design:
                        handled = set(design.get("handledCommentIds", []))
                        in_flight = set(design.get("inFlightCommentIds", []))
                        new_ids = [c for c in event.get("newCommentIds", []) if c not in handled]
                        if not new_ids:
                            f.unlink()
                            self.log("event_dropped", type="page:comment", reason="all_comments_already_handled")
                            continue
                        if in_flight:
                            # architect is busy — merge IDs and defer
                            self.update_design(design_id, {"merge_in_flight_comments": new_ids})
                            f.unlink()
                            self.log("event_deferred", type="page:comment", reason="architect_in_flight")
                            continue
                        event["newCommentIds"] = new_ids

            f.unlink()
            return event
        return None

    def list_events(self) -> List[dict]:
        """List all pending events without removing them."""
        return self.provider.read_events()

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
# CLI helpers
# ---------------------------------------------------------------------------

def _flag(args: list, flag: str, default: str = None):
    """Return the value after a flag, or default if absent."""
    try:
        idx = args.index(flag)
        if idx + 1 < len(args):
            return args[idx + 1]
    except ValueError:
        pass
    return default


def cli() -> None:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(0)

    state = State()
    group = args[0]
    rest  = args[1:]

    # ------------------------------------------------------------------
    # summary
    # ------------------------------------------------------------------
    if group == "summary":
        print(state.summary())
        return

    # ------------------------------------------------------------------
    # design
    # ------------------------------------------------------------------
    if group == "design":
        if not rest:
            print("Usage: state.py design <create|get|update|list|complete|delete>")
            sys.exit(1)
        command = rest[0]
        rest = rest[1:]

        if command == "create":
            description = rest[0] if rest else ""
            category    = _flag(rest, "--category", "feature")
            design_id   = _flag(rest, "--id")
            d = state.create_design(description, category=category, design_id=design_id)
            print(json.dumps(d))

        elif command == "get":
            design_id = rest[0] if rest else ""
            d = state.get_design(design_id)
            if d:
                print(json.dumps(d))
            else:
                print(f"Design {design_id} not found")
                sys.exit(1)

        elif command == "update":
            design_id = rest[0] if rest else ""
            rest = rest[1:]
            updates: dict = {}
            idx = 0
            while idx < len(rest):
                key = rest[idx]
                if key == "--stage":
                    updates["stage"] = rest[idx + 1]; idx += 2
                elif key == "--confluence-page":
                    updates["confluencePageId"] = rest[idx + 1]; idx += 2
                elif key == "--jira-parent":
                    updates["jiraParentKey"] = rest[idx + 1]; idx += 2
                elif key == "--add-child":
                    updates["add_child_ticket"] = rest[idx + 1]; idx += 2
                elif key == "--add-pr":
                    val = rest[idx + 1]
                    try:
                        updates["add_pr"] = int(val)
                    except ValueError:
                        updates["add_pr"] = val
                    idx += 2
                elif key == "--add-artifact":
                    raw = rest[idx + 1]
                    try:
                        updates["add_artifact"] = json.loads(raw)
                    except json.JSONDecodeError:
                        updates["add_artifact"] = raw
                    idx += 2
                elif key == "--set-in-flight-comments":
                    raw = rest[idx + 1]
                    updates["set_in_flight_comments"] = [c.strip() for c in raw.split(",") if c.strip()]
                    idx += 2
                elif key == "--merge-in-flight-comments":
                    raw = rest[idx + 1]
                    updates["merge_in_flight_comments"] = [c.strip() for c in raw.split(",") if c.strip()]
                    idx += 2
                elif key == "--clear-in-flight-comments":
                    updates["clear_in_flight_comments"] = True
                    idx += 1
                elif key == "--add-handled-comments":
                    raw = rest[idx + 1]
                    updates["add_handled_comments"] = [c.strip() for c in raw.split(",") if c.strip()]
                    idx += 2
                else:
                    idx += 1
            d = state.update_design(design_id, updates)
            print(json.dumps(d))

        elif command == "list":
            active_only = "--active" in rest
            stage = _flag(rest, "--stage")
            designs = state.list_designs(stage=stage, active_only=active_only)
            print(json.dumps(designs))

        elif command == "complete":
            design_id = rest[0] if rest else ""
            state.complete_design(design_id)
            print(json.dumps({"ok": True}))

        elif command == "delete":
            design_id = rest[0] if rest else ""
            ok = state.delete_design(design_id)
            print("Deleted" if ok else "Not found")

        else:
            print(f"Unknown: design {command}")
            sys.exit(1)
        return

    # ------------------------------------------------------------------
    # session
    # ------------------------------------------------------------------
    if group == "session":
        if not rest:
            print("Usage: state.py session <save|get|delete|list>")
            sys.exit(1)
        command = rest[0]
        rest = rest[1:]

        if command == "save":
            issue_key  = rest[0] if rest else ""
            session_id = _flag(rest, "--session-id", str(uuid.uuid4()))
            agent      = _flag(rest, "--agent", "unknown")
            pr_raw     = _flag(rest, "--pr")
            pr         = int(pr_raw) if pr_raw else None
            s = state.save_session(issue_key, session_id, agent=agent, pr_number=pr)
            print(json.dumps(s))

        elif command == "get":
            issue_key = rest[0] if rest else ""
            s = state.get_session(issue_key)
            if s:
                print(json.dumps(s))
            else:
                print(f"Session {issue_key} not found")
                sys.exit(1)

        elif command == "delete":
            issue_key = rest[0] if rest else ""
            ok = state.delete_session(issue_key)
            print("Deleted" if ok else "Not found")

        elif command == "list":
            sessions = state.list_sessions()
            print(json.dumps(sessions))

        else:
            print(f"Unknown: session {command}")
            sys.exit(1)
        return

    # ------------------------------------------------------------------
    # watch
    # ------------------------------------------------------------------
    if group == "watch":
        if not rest:
            print("Usage: state.py watch <add|remove|list>")
            sys.exit(1)
        command = rest[0]
        rest = rest[1:]

        if command == "add":
            watch_type = rest[0] if rest else ""
            rest = rest[1:]
            interval_raw = _flag(rest, "--interval")
            interval = int(interval_raw) if interval_raw else 30

            fields: dict = {}

            # Convenience: --repo, --pr, --branch, --page, --issue, --design
            repo   = _flag(rest, "--repo")
            if repo:   fields["repo"] = repo
            pr_raw = _flag(rest, "--pr")
            if pr_raw: fields["prNumber"] = int(pr_raw)
            branch = _flag(rest, "--branch")
            if branch: fields["branch"] = branch
            page   = _flag(rest, "--page")
            if page:   fields["pageId"] = page
            issue  = _flag(rest, "--issue")
            if issue:  fields["issueKey"] = issue
            design = _flag(rest, "--design")
            if design: fields["designId"] = design

            # TTL override
            ttl_raw = _flag(rest, "--ttl")
            if ttl_raw:
                ttl_sec = int(ttl_raw)
                expires = datetime.now(timezone.utc) + timedelta(seconds=ttl_sec)
                fields["expiresAt"] = expires.isoformat()

            # confluence:review registers a single unified watch
            if watch_type == "confluence:review":
                w = state.add_watch("confluence:review", interval=interval, **fields)
            else:
                w = state.add_watch(watch_type, interval=interval, **fields)
            print(json.dumps(w))

        elif command == "remove":
            watch_id = rest[0] if rest else ""
            ok = state.remove_watch(watch_id)
            print("Removed" if ok else "Not found")

        elif command == "list":
            wtype   = _flag(rest, "--type")
            watches = state.list_watches(watch_type=wtype)
            print(json.dumps(watches))

        else:
            print(f"Unknown: watch {command}")
            sys.exit(1)
        return

    # ------------------------------------------------------------------
    # events
    # ------------------------------------------------------------------
    if group == "events":
        if not rest:
            print("Usage: state.py events <pop|list>")
            sys.exit(1)
        command = rest[0]

        if command == "pop":
            event = state.pop_event()
            print(json.dumps(event) if event else json.dumps({"type": "empty"}))

        elif command == "list":
            events = state.list_events()
            print(json.dumps(events))

        else:
            print(f"Unknown: events {command}")
            sys.exit(1)
        return

    # ------------------------------------------------------------------
    # log
    # ------------------------------------------------------------------
    if group == "log":
        if not rest:
            print("Usage: state.py log <append|show>")
            sys.exit(1)
        command = rest[0]
        rest = rest[1:]

        if command == "append":
            action     = rest[0] if rest else "action"
            data: dict = {}
            design_id  = _flag(rest, "--design-id")
            issue_key  = _flag(rest, "--issue-key")
            detail     = _flag(rest, "--detail")
            if design_id: data["design_id"]  = design_id
            if issue_key: data["issue_key"]  = issue_key
            if detail:    data["detail"]      = detail
            state.log(action, **data)
            print("Logged")

        elif command == "show":
            last_raw = _flag(rest, "--last", "20")
            last     = int(last_raw)
            for entry in state.read_log(last=last):
                print(json.dumps(entry))

        else:
            print(f"Unknown: log {command}")
            sys.exit(1)
        return

    print(f"Unknown group: {group}")
    sys.exit(1)


if __name__ == "__main__":
    cli()
