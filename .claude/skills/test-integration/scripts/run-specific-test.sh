#!/bin/bash
#
# Hook: Run Specific Test
# Trigger: PostToolUse on Write|Edit of test files
# Purpose: Run only the modified test file for faster feedback
#

set -e

cd "$CLAUDE_PROJECT_DIR"

# Parse hook input from stdin
input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
tool_name=$(echo "$input" | jq -r '.tool_name // empty')

LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/test-runs.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Only run for test files
if [[ ! "$file_path" =~ Spec\.groovy$ ]] && [[ ! "$file_path" =~ Test\.java$ ]]; then
    exit 0
fi

# Skip if not in test directory
if [[ ! "$file_path" =~ /test/ ]]; then
    exit 0
fi

log "Test file modified: $file_path"

# Extract test class name from file path
# e.g., /path/to/ContactServiceSpec.groovy -> ContactServiceSpec
filename=$(basename "$file_path")
test_class="${filename%.*}"

# Determine module
module=""
if [[ "$file_path" =~ libs/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
elif [[ "$file_path" =~ services/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
fi

echo "Running test: $test_class"
log "Running specific test: $test_class in module: ${module:-root}"

# Run specific test class
if [[ -n "$module" ]]; then
    if [[ "$file_path" =~ libs/ ]]; then
        ./gradlew ":libs:${module}:test" --tests "*${test_class}" 2>&1 | tail -40
        test_result=${PIPESTATUS[0]}
    else
        ./gradlew ":services:${module}:test" --tests "*${test_class}" 2>&1 | tail -40
        test_result=${PIPESTATUS[0]}
    fi
else
    ./gradlew test --tests "*${test_class}" 2>&1 | tail -40
    test_result=${PIPESTATUS[0]}
fi

if [ $test_result -eq 0 ]; then
    log "Test PASSED: $test_class"
    echo ""
    echo "Test $test_class PASSED!"
    exit 0
else
    log "Test FAILED: $test_class"
    echo ""
    echo "Test $test_class FAILED!"
    exit 2
fi