#!/bin/bash
#
# Hook: Run Gradle Tests
# Trigger: PostToolUse on Write|Edit of Java/Groovy files
# Purpose: Automatically run tests when source files are modified
#

set -e

cd "$CLAUDE_PROJECT_DIR"

# Parse hook input from stdin
input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
tool_name=$(echo "$input" | jq -r '.tool_name // empty')

# Log file for debugging
LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/test-runs.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Only run for Java/Groovy source files
if [[ ! "$file_path" =~ \.(java|groovy)$ ]]; then
    exit 0
fi

# Skip test files for now (we'll run all tests anyway)
log "File modified: $file_path (tool: $tool_name)"

# Determine which module was modified
module=""
if [[ "$file_path" =~ libs/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
fi

echo "Running Gradle tests..."
log "Starting test run for module: ${module:-all}"

# Run tests with Gradle
# Use --quiet for less verbose output, remove if you want full output
if [[ -n "$module" ]]; then
    # Run tests for specific module
    ./gradlew ":libs:${module}:test" --quiet 2>&1 | tail -30
    test_result=${PIPESTATUS[0]}
else
    # Run all tests
    ./gradlew test --quiet 2>&1 | tail -30
    test_result=${PIPESTATUS[0]}
fi

if [ $test_result -eq 0 ]; then
    log "Tests PASSED"
    echo "Tests passed!"
    exit 0
else
    log "Tests FAILED"
    echo "Tests failed! Check output above."
    # Exit 2 blocks the action and shows error to Claude
    exit 2
fi
