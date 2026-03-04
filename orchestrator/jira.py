"""
Jira CLI wrapper — wraps `acli jira workitem` commands and outputs JSON.

Auth: uses ATLASSIAN_SITE + ATLASSIAN_EMAIL + ATLASSIAN_TOKEN from .env.
      acli must already be authenticated (`acli jira auth`).

Usage:
    python3 jira.py create <projectKey> <issueType> "<summary>" [--description "..."] [--parent KEY-36] [--labels "l1,l2"]
    python3 jira.py get    <issueKey>
    python3 jira.py update <issueKey> [--summary "..."] [--description "..."] [--labels "..."] [--type "..."]
    python3 jira.py transition <issueKey> "<status>"
    python3 jira.py transitions <issueKey>
    python3 jira.py search "<JQL query>" [--limit 50]
    python3 jira.py comment <issueKey> "<message>"
"""

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Optional
import base64
import urllib.request
from urllib.request import Request, urlopen
import urllib.error
from urllib.error import HTTPError, URLError

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from cli_utils import load_dotenv, flag as _flag, ToolError

# Lazy-init: env is loaded on first use, not at import time
_env_loaded = False
SITE  = ""
EMAIL = ""
TOKEN = ""


def _ensure_env() -> None:
    """Load .env and set module globals on first call. No-op after that."""
    global _env_loaded, SITE, EMAIL, TOKEN
    if _env_loaded:
        return
    load_dotenv(str(SCRIPT_DIR / ".env"))
    SITE  = os.environ.get("ATLASSIAN_SITE", "")
    EMAIL = os.environ.get("ATLASSIAN_EMAIL", "")
    TOKEN = os.environ.get("ATLASSIAN_TOKEN", "")
    _env_loaded = True


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _run_acli(*args: str) -> dict:
    """Run an acli command, capture output, return parsed JSON.

    Appends --json to every call so we always get machine-readable output.
    Raises ToolError on failure.
    """
    _ensure_env()
    cmd = ["acli", "jira", "workitem"] + list(args) + ["--json"]
    print(f"[jira] RUN: {' '.join(cmd)}", file=sys.stderr)
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except FileNotFoundError:
        raise ToolError("`acli` not found in PATH")
    except subprocess.TimeoutExpired:
        raise ToolError("acli timed out")

    if result.returncode != 0:
        raise ToolError(f"acli failed (exit {result.returncode}): {result.stderr.strip()}", result.returncode)

    stdout = result.stdout.strip()
    if not stdout:
        return {"ok": True}

    try:
        return json.loads(stdout)
    except json.JSONDecodeError:
        # acli sometimes returns non-JSON on success for mutations — wrap it
        return {"ok": True, "raw": stdout}


def _jira_rest(path: str) -> dict:
    """GET from the Jira REST API v3 (for operations acli doesn't expose)."""
    _ensure_env()
    url = f"https://{SITE}/rest/api/3{path}"
    creds = base64.b64encode(f"{EMAIL}:{TOKEN}".encode()).decode()
    headers = {
        "Content-Type":  "application/json",
        "Authorization": f"Basic {creds}",
    }
    req = Request(url, headers=headers, method="GET")
    print(f"[jira] REST GET {path}", file=sys.stderr)
    try:
        resp = urlopen(req, timeout=30)
        return json.loads(resp.read().decode())
    except HTTPError as e:
        body = e.fp.read().decode()
        raise ToolError(f"REST GET {path} failed — HTTP {e.code}: {body[:200]}")
    except URLError as e:
        raise ToolError(f"REST GET {path} failed — {e.reason}")


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_create(project: str, issue_type: str, summary: str,
               description: Optional[str] = None,
               parent: Optional[str] = None,
               labels: Optional[str] = None) -> None:
    """Create a Jira work item and print its JSON representation."""
    args = [
        "create",
        "--project",  project,
        "--type",     issue_type,
        "--summary",  summary,
    ]
    if description:
        args += ["--description", description]
    if parent:
        args += ["--parent", parent]
    if labels:
        args += ["--label", labels]

    result = _run_acli(*args)
    print(json.dumps(result, indent=2))


def cmd_get(issue_key: str) -> None:
    """Fetch a Jira issue and print its JSON representation."""
    result = _run_acli("view", issue_key, "--fields", "*all")
    print(json.dumps(result, indent=2))


def cmd_update(issue_key: str,
               summary: Optional[str] = None,
               description: Optional[str] = None,
               labels: Optional[str] = None,
               issue_type: Optional[str] = None) -> None:
    """Edit fields on an existing Jira issue."""
    args = ["edit", "--key", issue_key, "--yes"]
    if summary:
        args += ["--summary", summary]
    if description:
        args += ["--description", description]
    if labels:
        args += ["--labels", labels]
    if issue_type:
        args += ["--type", issue_type]

    result = _run_acli(*args)
    print(json.dumps(result, indent=2))


def cmd_transition(issue_key: str, status: str) -> None:
    """Transition a Jira issue to the given status name."""
    result = _run_acli("transition", "--key", issue_key, "--status", status, "--yes")
    print(json.dumps(result, indent=2))


def cmd_transitions(issue_key: str) -> None:
    """List the available transitions for a Jira issue (uses REST API directly)."""
    data        = _jira_rest(f"/issue/{issue_key}/transitions")
    transitions = [
        {"id": t.get("id"), "name": t.get("name"), "to": t.get("to", {}).get("name")}
        for t in data.get("transitions", [])
    ]
    print(json.dumps(transitions, indent=2))


def cmd_search(jql: str, limit: int = 50) -> None:
    """Search Jira with JQL and print matching issues as JSON."""
    args = [
        "search",
        "--jql",   jql,
        "--limit", str(limit),
        "--fields", "key,issuetype,summary,status,assignee,priority,parent,labels",
    ]
    result = _run_acli(*args)
    print(json.dumps(result, indent=2))


def cmd_comment(issue_key: str, message: str) -> None:
    """Add a comment to a Jira issue."""
    result = _run_acli("comment", "create", "--key", issue_key, "--body", message)
    print(json.dumps(result, indent=2))


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main() -> None:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(0)

    cmd = args[0]
    rest = args[1:]

    # ------------------------------------------------------------------
    # create <projectKey> <issueType> "<summary>" [options]
    # ------------------------------------------------------------------
    if cmd == "create":
        if len(rest) < 3:
            print("Usage: jira.py create <projectKey> <issueType> \"<summary>\" [--description ...] [--parent KEY-36] [--labels ...]")
            sys.exit(1)
        project     = rest[0]
        issue_type  = rest[1]
        summary     = rest[2]
        description = _flag(rest, "--description")
        parent      = _flag(rest, "--parent")
        labels      = _flag(rest, "--labels")
        cmd_create(project, issue_type, summary,
                   description=description, parent=parent, labels=labels)

    # ------------------------------------------------------------------
    # get <issueKey>
    # ------------------------------------------------------------------
    elif cmd == "get":
        if not rest:
            print("Usage: jira.py get <issueKey>"); sys.exit(1)
        cmd_get(rest[0])

    # ------------------------------------------------------------------
    # update <issueKey> [--summary ...] [--description ...] [--labels ...] [--type ...]
    # ------------------------------------------------------------------
    elif cmd == "update":
        if not rest:
            print("Usage: jira.py update <issueKey> [--summary ...] [--description ...] [--labels ...] [--type ...]")
            sys.exit(1)
        issue_key   = rest[0]
        summary     = _flag(rest, "--summary")
        description = _flag(rest, "--description")
        labels      = _flag(rest, "--labels")
        issue_type  = _flag(rest, "--type")
        cmd_update(issue_key, summary=summary, description=description,
                   labels=labels, issue_type=issue_type)

    # ------------------------------------------------------------------
    # transition <issueKey> "<status>"
    # ------------------------------------------------------------------
    elif cmd == "transition":
        if len(rest) < 2:
            print("Usage: jira.py transition <issueKey> \"<status>\""); sys.exit(1)
        cmd_transition(rest[0], rest[1])

    # ------------------------------------------------------------------
    # transitions <issueKey>   — list available transitions
    # ------------------------------------------------------------------
    elif cmd == "transitions":
        if not rest:
            print("Usage: jira.py transitions <issueKey>"); sys.exit(1)
        cmd_transitions(rest[0])

    # ------------------------------------------------------------------
    # search "<JQL>" [--limit N]
    # ------------------------------------------------------------------
    elif cmd == "search":
        if not rest:
            print("Usage: jira.py search \"<JQL>\" [--limit N]"); sys.exit(1)
        jql       = rest[0]
        limit_raw = _flag(rest, "--limit", "50")
        try:
            limit = int(limit_raw)
        except (TypeError, ValueError):
            limit = 50
        cmd_search(jql, limit=limit)

    # ------------------------------------------------------------------
    # comment <issueKey> "<message>"
    # ------------------------------------------------------------------
    elif cmd == "comment":
        if len(rest) < 2:
            print("Usage: jira.py comment <issueKey> \"<message>\""); sys.exit(1)
        cmd_comment(rest[0], rest[1])

    else:
        print(f"Unknown command: {cmd}")
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except ToolError as e:
        print(f"[jira] ERROR: {e}", file=sys.stderr)
        sys.exit(e.exit_code)
