"""
Confluence REST API wrapper — all responses go to files, never into context.

Auth: uses ATLASSIAN_SITE + ATLASSIAN_EMAIL + ATLASSIAN_TOKEN from .env (Basic auth).

Usage:
    python3 confluence.py poll-page <pageId>        # labels + comments in one call (used by poller)
    python3 confluence.py get-comments <pageId>
    python3 confluence.py check-approval <pageId>
    python3 confluence.py add-label <pageId> <label>
    python3 confluence.py remove-label <pageId> <label>
    python3 confluence.py add-reaction <commentId> <emoji>
    python3 confluence.py reply-comment <commentId> <message>
    python3 confluence.py get-page <pageId> <outputPath>
    python3 confluence.py create-page <spaceKey> <title> <bodyFile>
    python3 confluence.py update-page <pageId> <title> <bodyFile>
    python3 confluence.py search <cql>
    python3 confluence.py list-spaces
"""

import base64
import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional
import urllib.request
from urllib.request import Request, urlopen
import urllib.error
from urllib.error import HTTPError, URLError
import urllib.parse
from urllib.parse import quote

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from cli_utils import load_dotenv, ToolError

# Lazy-init: env is loaded on first use, not at import time
_env_loaded = False
SITE  = ""
EMAIL = ""
TOKEN = ""
CACHE_DIR = SCRIPT_DIR / ".orchestrator" / "confluence_cache"


def _ensure_env() -> None:
    """Load .env and set module globals on first call. No-op after that."""
    global _env_loaded, SITE, EMAIL, TOKEN
    if _env_loaded:
        return
    load_dotenv(str(SCRIPT_DIR / ".env"))
    SITE  = os.environ.get("ATLASSIAN_SITE", "")
    EMAIL = os.environ.get("ATLASSIAN_EMAIL", "")
    TOKEN = os.environ.get("ATLASSIAN_TOKEN", "")
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    _env_loaded = True

# Bot label prefix used on all replies/reactions posted by the orchestrator
BOT_LABEL = "🤖 <strong>Orchestrator:</strong>"


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def _base_url() -> str:
    _ensure_env()
    return f"https://{SITE}/wiki"


def _headers() -> dict:
    creds = base64.b64encode(f"{EMAIL}:{TOKEN}".encode()).decode()
    return {
        "Content-Type": "application/json",
        "Authorization": f"Basic {creds}",
    }


def api_get(path: str) -> dict:
    url = _base_url() + path
    print(f"[confluence] START GET {path}", file=sys.stderr)
    req = Request(url, headers=_headers(), method="GET")
    try:
        resp = urlopen(req, timeout=30)
        data = json.loads(resp.read().decode())
        print(f"[confluence] OK GET {path}", file=sys.stderr)
        return data
    except HTTPError as e:
        body = e.fp.read().decode()
        raise ToolError(f"GET {path} failed — HTTP {e.code}")
    except URLError as e:
        raise ToolError(f"GET {path} failed — {e.reason}")


def api_post(path: str, data: dict) -> dict:
    url = _base_url() + path
    print(f"[confluence] START POST {path}", file=sys.stderr)
    body = json.dumps(data).encode()
    req = Request(url, data=body, headers=_headers(), method="POST")
    try:
        resp = urlopen(req, timeout=30)
        result = json.loads(resp.read().decode())
        print(f"[confluence] OK POST {path}", file=sys.stderr)
        return result
    except HTTPError as e:
        body_text = e.fp.read().decode()
        raise ToolError(f"POST {path} failed — HTTP {e.code}")


def api_put(path: str, data: dict) -> dict:
    url = _base_url() + path
    print(f"[confluence] START PUT {path}", file=sys.stderr)
    body = json.dumps(data).encode()
    req = Request(url, data=body, headers=_headers(), method="PUT")
    try:
        resp = urlopen(req, timeout=30)
        result = json.loads(resp.read().decode())
        print(f"[confluence] OK PUT {path}", file=sys.stderr)
        return result
    except HTTPError as e:
        body_text = e.fp.read().decode()
        raise ToolError(f"PUT {path} failed — HTTP {e.code}")


def save_and_print(data: dict, cache_name: str) -> None:
    """Save raw response to cache file, print compact version."""
    _ensure_env()
    cache_path = CACHE_DIR / cache_name
    cache_path.write_text(json.dumps(data))
    print(json.dumps(data, indent=2))


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

def _extract_comments(results: list, unresolved_only: bool = False) -> list:
    """Parse Confluence comment API results into compact dicts."""
    out = []
    for c in results:
        inline_props = c.get("extensions", {}).get("inlineProperties", {})
        resolution   = c.get("extensions", {}).get("resolution", {})
        is_resolved  = bool(resolution.get("lastModifier"))
        if unresolved_only and is_resolved:
            continue
        out.append({
            "id":        c.get("id"),
            "resolved":  is_resolved,
            "author":    c.get("history", {}).get("createdBy", {}).get("displayName", "unknown"),
            "body":      c.get("body", {}).get("storage", {}).get("value", ""),
            "created":   c.get("createdDate"),
            "selection": inline_props.get("originalSelection"),
        })
    return out


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_get_comments(page_id: str, unresolved_only: bool = False) -> None:
    """Get footer + inline comments, output compact JSON.

    Each comment includes a `resolved` boolean. Pass --unresolved-only to
    filter out comments that have already been resolved by the reviewer.
    """
    expand = "body.storage,extensions.inlineProperties,extensions.resolution"
    footer_raw = api_get(f"/rest/api/content/{page_id}/child/comment?expand={expand}&depth=all&limit=50")
    inline_raw = api_get(f"/rest/api/content/{page_id}/child/comment?expand={expand}&depth=all&limit=50&location=inline")

    result = {
        "footer": _extract_comments(footer_raw.get("results", []), unresolved_only),
        "inline": _extract_comments(inline_raw.get("results", []), unresolved_only),
    }
    save_and_print(result, f"comments_{page_id}.json")


def cmd_check_approval(page_id: str) -> None:
    """Check if page has 'approved' or 'needs-fix' label."""
    data   = api_get(f"/rest/api/content/{page_id}/label")
    labels = [l.get("name") for l in data.get("results", [])]
    if "approved" in labels:
        print("approved")
    elif "needs-fix" in labels:
        print("needs-fix")
    else:
        print("not_approved")


def cmd_poll_page(page_id: str) -> None:
    """Single-call page poll — returns labels + footer + inline comments as one JSON blob.

    Output shape:
      {
        "labels":  ["in-review", ...],           // all label names on the page
        "status":  "approved"|"needs-fix"|"in-review",  // derived from labels
        "footer":  [ {id, resolved, author, body, created, selection}, ... ],
        "inline":  [ {id, resolved, author, body, created, selection}, ... ]
      }

    Used by ConfluenceReviewHandler so the poller makes ONE subprocess call per poll cycle.
    """
    expand     = "body.storage,extensions.inlineProperties,extensions.resolution"
    label_data = api_get(f"/rest/api/content/{page_id}/label")
    footer_raw = api_get(f"/rest/api/content/{page_id}/child/comment?expand={expand}&depth=all&limit=50")
    inline_raw = api_get(f"/rest/api/content/{page_id}/child/comment?expand={expand}&depth=all&limit=50&location=inline")

    labels = [l.get("name") for l in label_data.get("results", [])]
    if "approved" in labels:
        status = "approved"
    elif "needs-fix" in labels:
        status = "needs-fix"
    else:
        status = "in-review"

    result = {
        "labels": labels,
        "status": status,
        "footer": _extract_comments(footer_raw.get("results", [])),
        "inline": _extract_comments(inline_raw.get("results", [])),
    }
    save_and_print(result, f"poll_{page_id}.json")


def cmd_add_label(page_id: str, label: str) -> None:
    """Add a label to a page."""
    api_post(f"/rest/api/content/{page_id}/label", [{"prefix": "global", "name": label}])
    print(f"added:{label}")


def cmd_remove_label(page_id: str, label: str) -> None:
    """Remove a label from a page."""
    _ensure_env()
    url = f"https://{SITE}/wiki/rest/api/content/{page_id}/label?name={label}"
    req = Request(url, headers=_headers(), method="DELETE")
    print(f"[confluence] START DELETE /rest/api/content/{page_id}/label?name={label}", file=sys.stderr)
    try:
        resp = urlopen(req, timeout=30)
        print(f"[confluence] OK DELETE label={label}", file=sys.stderr)
    except HTTPError as e:
        body = e.fp.read().decode()
        raise ToolError(f"DELETE label={label} failed — HTTP {e.code}")
    print(f"removed:{label}")


def cmd_get_page(page_id: str, output_path: str) -> None:
    """Fetch page content, save to file. Skips if already cached."""
    if Path(output_path).exists():
        print(f"Already cached: {output_path}")
        return
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)
    data  = api_get(f"/rest/api/content/{page_id}?expand=body.storage,version")
    body  = data.get("body", {}).get("storage", {}).get("value", "")
    title = data.get("title", "")
    meta  = {
        "title":   title,
        "version": data.get("version", {}).get("number", 1),
    }
    Path(output_path).write_text(body)
    meta_path = Path(output_path).with_suffix(".meta.json")
    meta_path.write_text(json.dumps(meta, indent=2))
    print(f"Saved to {output_path} (title: {title})")


def cmd_create_page(space_key: str, title: str, body_file: str) -> None:
    """Create a Confluence page from a local file."""
    if not Path(body_file).exists():
        raise ToolError(f"File not found: {body_file}")
    body = Path(body_file).read_text()
    data = {
        "type":  "page",
        "title": title,
        "space": {"key": space_key},
        "body":  {"storage": {"value": body, "representation": "storage"}},
    }
    result = api_post("/rest/api/content", data)
    url = result.get("_links", {}).get("webui", "")
    save_and_print(result, "create_result.json")


def cmd_update_page(page_id: str, title: str, body_file: str) -> None:
    """Update an existing Confluence page from a local file."""
    if not Path(body_file).exists():
        raise ToolError(f"File not found: {body_file}")
    body    = Path(body_file).read_text()
    current = api_get(f"/rest/api/content/{page_id}?expand=version")
    version = current.get("version", {}).get("number", 1)
    data = {
        "type":    "page",
        "title":   title,
        "version": {"number": version + 1},
        "body":    {"storage": {"value": body, "representation": "storage"}},
    }
    result = api_put(f"/rest/api/content/{page_id}", data)
    save_and_print(result, "update_result.json")


def cmd_search(cql: str) -> None:
    """Search Confluence with CQL."""
    data    = api_get(f"/rest/api/content/search?cql={quote(cql)}&limit=20")
    results = []
    for r in data.get("results", []):
        results.append({
            "id":     r.get("id"),
            "title":  r.get("title"),
            "type":   r.get("type"),
            "status": r.get("status"),
        })
    save_and_print(results, "search_result.json")


def _post_comment_reply(container_id: str, container_type: str, html_body: str) -> str:
    """Internal helper: post a comment reply. Returns the new comment ID."""
    payload = {
        "type":      "comment",
        "container": {"id": container_id, "type": container_type},
        "body":      {"storage": {"value": html_body, "representation": "storage"}},
    }
    result = api_post("/rest/api/content", payload)
    return result.get("id")


def cmd_add_reaction(comment_id: str, emoji: str) -> None:
    """Acknowledge a comment by replying to it directly with the emoji.

    Confluence Cloud has no public REST API for native emoji reactions (confirmed by
    Atlassian as of 2025). This command posts a direct reply on the comment itself
    so it appears threaded under the comment, not as an unrelated footer note.
    All replies are prefixed with the bot label so they are distinguishable from
    human comments on the same account.

    comment_id : Confluence content ID of the comment (from get-comments)
    emoji      : any Unicode emoji, e.g. 👍
    """
    html_body = f"<p>{BOT_LABEL} {emoji}</p>"
    new_id    = _post_comment_reply(comment_id, "comment", html_body)
    print(f"reaction_added:{emoji} (reply id={new_id} on comment {comment_id})")


def cmd_reply_comment(comment_id: str, message: str) -> None:
    """Post a reply directly on a comment (threaded under it).

    All replies are prefixed with the bot label so they are distinguishable from
    human comments posted on the same Atlassian account.

    comment_id : Confluence content ID of the comment (from get-comments 'id' field)
    message    : plain-text reply body; use \\n to separate paragraphs
    """
    paragraphs = [f"<p>{line.strip()}</p>" for line in message.split("\\n") if line.strip()]
    if not paragraphs:
        paragraphs = ["<p></p>"]
    first = paragraphs[0]
    rest  = paragraphs[1:]
    html_body = f"<p>{BOT_LABEL} {first[3:-4]}</p>" + "".join(rest)
    new_id    = _post_comment_reply(comment_id, "comment", html_body)
    print(f"reply_posted:{new_id}")


def cmd_list_spaces() -> None:
    """List all Confluence spaces."""
    data   = api_get("/rest/api/space?limit=50")
    spaces = []
    for s in data.get("results", []):
        spaces.append({
            "key":  s.get("key"),
            "name": s.get("name"),
            "type": s.get("type"),
        })
    save_and_print(spaces, "spaces.json")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    _ensure_env()
    missing = [k for k in ("ATLASSIAN_SITE", "ATLASSIAN_EMAIL", "ATLASSIAN_TOKEN")
               if not os.environ.get(k)]
    if missing:
        raise ToolError(f"Missing in .env: {', '.join(missing)}")

    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(0)

    cmd = args[0]

    if cmd == "poll-page":
        if len(args) < 2:
            print("Usage: confluence.py poll-page <pageId>"); sys.exit(1)
        cmd_poll_page(args[1])

    elif cmd == "get-comments":
        if len(args) < 2:
            print("Usage: confluence.py get-comments <pageId>"); sys.exit(1)
        unresolved_only = "--unresolved-only" in args
        cmd_get_comments(args[1], unresolved_only=unresolved_only)

    elif cmd == "check-approval":
        if len(args) < 2:
            print("Usage: confluence.py check-approval <pageId>"); sys.exit(1)
        cmd_check_approval(args[1])

    elif cmd == "add-label":
        if len(args) < 3:
            print("Usage: confluence.py add-label <pageId> <label>"); sys.exit(1)
        cmd_add_label(args[1], args[2])

    elif cmd == "remove-label":
        if len(args) < 3:
            print("Usage: confluence.py remove-label <pageId> <label>"); sys.exit(1)
        cmd_remove_label(args[1], args[2])

    elif cmd == "add-reaction":
        if len(args) < 3:
            print("Usage: confluence.py add-reaction <commentId> <emoji>"); sys.exit(1)
        cmd_add_reaction(args[1], args[2])

    elif cmd == "reply-comment":
        if len(args) < 3:
            print("Usage: confluence.py reply-comment <commentId> <message>"); sys.exit(1)
        cmd_reply_comment(args[1], args[2])

    elif cmd == "get-page":
        if len(args) < 3:
            print("Usage: confluence.py get-page <pageId> <outputPath>"); sys.exit(1)
        cmd_get_page(args[1], args[2])

    elif cmd == "create-page":
        if len(args) < 4:
            print("Usage: confluence.py create-page <spaceKey> <title> <bodyFile>"); sys.exit(1)
        cmd_create_page(args[1], args[2], args[3])

    elif cmd == "update-page":
        if len(args) < 4:
            print("Usage: confluence.py update-page <pageId> <title> <bodyFile>"); sys.exit(1)
        cmd_update_page(args[1], args[2], args[3])

    elif cmd == "search":
        if len(args) < 2:
            print("Usage: confluence.py search <cql>"); sys.exit(1)
        cmd_search(args[1])

    elif cmd == "list-spaces":
        cmd_list_spaces()

    else:
        print(f"Unknown command: {cmd}")
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except ToolError as e:
        print(f"[confluence] ERROR: {e}", file=sys.stderr)
        sys.exit(e.exit_code)
