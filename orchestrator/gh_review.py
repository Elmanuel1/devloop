#!/usr/bin/env python3
"""
gh_review.py — Submit formal GitHub PR reviews with inline comments.

Usage:
  # Fail review with inline comments from a JSON file
  python3 gh_review.py request-changes --repo owner/repo --pr 140 \
      --summary "3 violations found" --comments violations.json

  # Approve
  python3 gh_review.py approve --repo owner/repo --pr 140 \
      --summary "LGTM — all checks pass."

  # Comment only (no approval/rejection)
  python3 gh_review.py comment --repo owner/repo --pr 140 \
      --summary "Leaving notes for consideration."  --comments notes.json

violations.json format:
[
  {
    "path": "src/main/java/com/tosspaper/precon/ExtractionApplicationServiceImpl.java",
    "line": 55,
    "body": "Wrong error code — extraction was found, just wrong state. Use ExtractionNotApplicableException."
  }
]

If "line" is omitted the comment is posted on the overall review body instead.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cli_utils import ToolError


def run(cmd: list[str]) -> dict:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise ToolError(f"Command failed: {' '.join(cmd)}\n{r.stderr.strip()}")
    return json.loads(r.stdout) if r.stdout.strip() else {}


def get_head_sha(repo: str, pr: int) -> str:
    data = run(["gh", "pr", "view", str(pr), "--repo", repo, "--json", "headRefOid"])
    return data["headRefOid"]


def submit_review(repo: str, pr: int, event: str, summary: str, comments: list[dict]):
    """
    event: REQUEST_CHANGES | APPROVE | COMMENT
    comments: list of {path, line, body}
    """
    sha = get_head_sha(repo, pr)

    # Build the review payload
    payload = {
        "commit_id": sha,
        "body": summary,
        "event": event,
        "comments": [],
    }

    for c in comments:
        if "line" not in c:
            # No line — append to overall body instead
            payload["body"] += f"\n\n---\n**{c['path']}**\n{c['body']}"
            continue

        entry = {
            "path": c["path"],
            "line": c["line"],
            "side": c.get("side", "RIGHT"),
            "body": c["body"],
        }
        # Include start_line for multi-line comments
        if "start_line" in c:
            entry["start_line"] = c["start_line"]
            entry["start_side"] = c.get("start_side", "RIGHT")

        payload["comments"].append(entry)

    payload_str = json.dumps(payload)

    proc = subprocess.run(
        ["gh", "api", f"repos/{repo}/pulls/{pr}/reviews",
         "--method", "POST", "--input", "-"],
        input=payload_str,
        capture_output=True,
        text=True,
    )

    # GitHub blocks self-review (422) — fall back to COMMENT so feedback still appears
    if proc.returncode != 0 and "422" in proc.stderr and event != "COMMENT":
        print(f"[gh_review] WARN: {event} blocked (self-review?), falling back to COMMENT", file=sys.stderr)
        payload["event"] = "COMMENT"
        payload_str = json.dumps(payload)
        proc = subprocess.run(
            ["gh", "api", f"repos/{repo}/pulls/{pr}/reviews",
             "--method", "POST", "--input", "-"],
            input=payload_str,
            capture_output=True,
            text=True,
        )

    if proc.returncode != 0:
        raise ToolError(f"Error submitting review: {proc.stderr.strip()}")

    data = json.loads(proc.stdout)
    print(json.dumps({
        "status": "ok",
        "review_id": data.get("id"),
        "event": event,
        "html_url": data.get("html_url"),
        "inline_comments": len(payload["comments"]),
    }, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Submit GitHub PR reviews with inline comments")
    sub = parser.add_subparsers(dest="cmd", required=True)

    for name in ("request-changes", "approve", "comment"):
        p = sub.add_parser(name)
        p.add_argument("--repo", required=True, help="owner/repo")
        p.add_argument("--pr", required=True, type=int, help="PR number")
        p.add_argument("--summary", required=True, help="Overall review summary")
        p.add_argument("--comments", help="Path to JSON file with inline comments")

    args = parser.parse_args()

    comments = []
    if args.comments:
        with open(args.comments) as f:
            comments = json.load(f)

    event_map = {
        "request-changes": "REQUEST_CHANGES",
        "approve": "APPROVE",
        "comment": "COMMENT",
    }

    submit_review(
        repo=args.repo,
        pr=args.pr,
        event=event_map[args.cmd],
        summary=args.summary,
        comments=comments,
    )


if __name__ == "__main__":
    try:
        main()
    except ToolError as e:
        print(f"[gh_review] ERROR: {e}", file=sys.stderr)
        sys.exit(e.exit_code)
