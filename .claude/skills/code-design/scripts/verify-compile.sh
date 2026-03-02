#!/bin/bash
#
# Hook: Verify Compilation
# Trigger: PostToolUse on Write|Edit of Java/Groovy files
# Purpose: Verify code compiles after file modifications
#

set -e

cd "$CLAUDE_PROJECT_DIR"

# Parse hook input from stdin
input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
tool_name=$(echo "$input" | jq -r '.tool_name // empty')

LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/compile.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Only check Java/Groovy files
if [[ ! "$file_path" =~ \.(java|groovy)$ ]]; then
    exit 0
fi

log "Verifying compilation for: $file_path"
echo "Verifying compilation..."

# Determine which module was modified
module=""
if [[ "$file_path" =~ libs/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
elif [[ "$file_path" =~ services/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
fi

# Run compile task (temporarily disable set -e to capture exit code)
set +e
if [[ -n "$module" ]]; then
    if [[ "$file_path" =~ libs/ ]]; then
        compile_output=$(./gradlew ":libs:${module}:compileJava" ":libs:${module}:compileGroovy" --quiet 2>&1)
        compile_result=$?
    else
        compile_output=$(./gradlew ":services:${module}:compileJava" --quiet 2>&1)
        compile_result=$?
    fi
else
    compile_output=$(./gradlew compileJava compileGroovy --quiet 2>&1)
    compile_result=$?
fi
set -e

if [ $compile_result -eq 0 ]; then
    log "Compilation PASSED"
    echo "Compilation successful!"
    exit 0
else
    log "Compilation FAILED: $compile_output"
    echo "Compilation failed!"
    echo ""
    echo "$compile_output" | tail -20
    exit 2
fi