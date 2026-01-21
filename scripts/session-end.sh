#!/bin/bash
#
# Hook: Session End / Stop
# Trigger: Stop (when Claude finishes responding)
# Purpose: Log session activity, optionally run final checks
#

cd "$CLAUDE_PROJECT_DIR"

# Parse hook input from stdin
input=$(cat)
session_id=$(echo "$input" | jq -r '.session_id // empty')

LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/sessions.log"
ACTIVITY_LOG="$CLAUDE_PROJECT_DIR/.claude/logs/activity.jsonl"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Log session activity
log "Claude response completed (session: ${session_id:-unknown})"

# Append to activity log as JSON
echo "{\"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\", \"event\": \"response_complete\", \"session_id\": \"${session_id:-unknown}\"}" >> "$ACTIVITY_LOG"

# Optional: Run a quick sanity check after Claude's response
# Uncomment if you want to verify compilation after each response
# ./gradlew compileJava --quiet 2>/dev/null

exit 0