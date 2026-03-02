#!/bin/bash
#
# Hook: Check Code Style (Checkstyle/SpotBugs)
# Trigger: PostToolUse on Write|Edit of Java files
# Purpose: Run static analysis and code style checks
#

cd "$CLAUDE_PROJECT_DIR" || exit 0

# Parse hook input from stdin
input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/style.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Only check Java files (not test files)
if [[ ! "$file_path" =~ \.java$ ]]; then
    exit 0
fi

# Skip test files
if [[ "$file_path" =~ /test/ ]]; then
    exit 0
fi

log "Checking style for: $file_path"

# Determine module
module=""
if [[ "$file_path" =~ libs/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
elif [[ "$file_path" =~ services/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
fi

# Check if checkstyle task exists
if ./gradlew tasks --all 2>/dev/null | grep -q "checkstyleMain"; then
    echo "Running Checkstyle..."

    if [[ -n "$module" ]]; then
        if [[ "$file_path" =~ libs/ ]]; then
            ./gradlew ":libs:${module}:checkstyleMain" --quiet 2>&1 | tail -20; style_result=${PIPESTATUS[0]}
        else
            ./gradlew ":services:${module}:checkstyleMain" --quiet 2>&1 | tail -20; style_result=${PIPESTATUS[0]}
        fi
    else
        ./gradlew checkstyleMain --quiet 2>&1 | tail -20; style_result=${PIPESTATUS[0]}
    fi

    if [ $style_result -ne 0 ]; then
        log "Checkstyle violations found"
        echo "Checkstyle violations found! See output above."
        # Non-blocking - just warn
        exit 0
    fi

    log "Checkstyle passed"
    echo "Code style check passed!"
fi

exit 0