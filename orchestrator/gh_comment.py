#!/usr/bin/env python3
"""
gh_comment.py — Post replies to GitHub PR comment threads.

Usage:
  # Acknowledge a comment immediately (before fix)
  python3 gh_comment.py ack --repo owner/repo --pr 140 --comment-id 123456789

  # Confirm fix after code-writer pushes
  python3 gh_comment.py fixed --repo owner/repo --pr 140 --comment-id 123456789 --sha abc1234

  # Custom reply (question answer, pushback, recommendation response)
  python3 gh_comment.py reply --repo owner/repo --pr 140 --comment-id 123456789 \
      --body "This is intentional — the design doc specifies X because Y."

  # General PR comment (not a thread reply)
  python3 gh_comment.py post --repo owner/repo --pr 140 --body "message here"
"""

import argparse
import json
import subprocess
import sys


def gh_api(endpoint: str, body: str) -> dict:
    proc = subprocess.run(
        ["gh", "api", endpoint, "--method", "POST", "--input", "-"],
        input=body,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        print(f"[gh_comment] ERROR: {endpoint}", file=sys.stderr)
        print(proc.stderr.strip(), file=sys.stderr)
        sys.exit(1)
    return json.loads(proc.stdout) if proc.stdout.strip() else {}


def reply_to_comment(repo: str, pr: int, comment_id: int, body: str):
    """
    Try replying to an inline review comment first.
    Fall back to a general issue comment if it fails (top-level PR comments).
    """
    payload = json.dumps({"body": body})

    # Try inline review comment reply
    proc = subprocess.run(
        ["gh", "api", f"repos/{repo}/pulls/{pr}/comments/{comment_id}/replies",
         "--method", "POST", "--input", "-"],
        input=payload,
        capture_output=True,
        text=True,
    )

    if proc.returncode == 0:
        data = json.loads(proc.stdout)
        print(json.dumps({"status": "ok", "type": "review_reply", "id": data.get("id"), "url": data.get("html_url")}, indent=2))
        return

    # Fall back to general issue comment
    data = gh_api(f"repos/{repo}/issues/{pr}/comments", payload)
    print(json.dumps({"status": "ok", "type": "issue_comment", "id": data.get("id"), "url": data.get("html_url")}, indent=2))


def post_comment(repo: str, pr: int, body: str):
    data = gh_api(f"repos/{repo}/issues/{pr}/comments", json.dumps({"body": body}))
    print(json.dumps({"status": "ok", "id": data.get("id"), "url": data.get("html_url")}, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Post replies to GitHub PR comment threads")
    sub = parser.add_subparsers(dest="cmd", required=True)

    # ack
    p = sub.add_parser("ack", help="Acknowledge a comment — posts '👀 On it — fix incoming'")
    p.add_argument("--repo", required=True)
    p.add_argument("--pr", required=True, type=int)
    p.add_argument("--comment-id", required=True, type=int)

    # fixed
    p = sub.add_parser("fixed", help="Confirm fix after code-writer pushes")
    p.add_argument("--repo", required=True)
    p.add_argument("--pr", required=True, type=int)
    p.add_argument("--comment-id", required=True, type=int)
    p.add_argument("--sha", required=True, help="Commit SHA of the fix")

    # reply
    p = sub.add_parser("reply", help="Custom reply (answer, pushback, recommendation)")
    p.add_argument("--repo", required=True)
    p.add_argument("--pr", required=True, type=int)
    p.add_argument("--comment-id", required=True, type=int)
    p.add_argument("--body", required=True)

    # post
    p = sub.add_parser("post", help="Post a general PR comment (not a thread reply)")
    p.add_argument("--repo", required=True)
    p.add_argument("--pr", required=True, type=int)
    p.add_argument("--body", required=True)

    args = parser.parse_args()

    if args.cmd == "ack":
        reply_to_comment(args.repo, args.pr, args.comment_id, "👀 On it — fix incoming")

    elif args.cmd == "fixed":
        short = args.sha[:7]
        repo_url = f"https://github.com/{args.repo}"
        body = f"✅ Fixed in [{short}]({repo_url}/commit/{args.sha})"
        reply_to_comment(args.repo, args.pr, args.comment_id, body)

    elif args.cmd == "reply":
        reply_to_comment(args.repo, args.pr, args.comment_id, args.body)

    elif args.cmd == "post":
        post_comment(args.repo, args.pr, args.body)


if __name__ == "__main__":
    main()
