#!/bin/bash
#
# Hook: Compile Check
# Trigger: PreToolUse on Write|Edit of Java/Groovy files
# Purpose: Verify code compiles before allowing file write (catches syntax errors early)
#

cd "$CLAUDE_PROJECT_DIR"

# Parse hook input from stdin
input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
tool_name=$(echo "$input" | jq -r '.tool_name // empty')
new_content=$(echo "$input" | jq -r '.tool_input.content // .tool_input.new_string // empty')

LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/compile.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Only check Java/Groovy files
if [[ ! "$file_path" =~ \.(java|groovy)$ ]]; then
    exit 0
fi

# For PreToolUse, we can't compile yet since file isn't written
# This hook is more useful as PostToolUse
# Exit 0 to allow the write to proceed
exit 0