#!/bin/bash
#
# Hook: Run Tests with JaCoCo Coverage
# Trigger: PostToolUse on Write|Edit of Java/Groovy source files
#

set -o pipefail

if [[ -z "$CLAUDE_PROJECT_DIR" ]]; then
    echo "Error: CLAUDE_PROJECT_DIR is not set"
    exit 1
fi

cd "$CLAUDE_PROJECT_DIR" || {
    echo "Error: Failed to change directory to $CLAUDE_PROJECT_DIR"
    exit 1
}

input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

# Only run for Java/Groovy source files (not test files)
[[ ! "$file_path" =~ \.(java|groovy)$ ]] && exit 0
[[ "$file_path" =~ /test/ ]] && exit 0

# Get module
module=""
[[ "$file_path" =~ libs/([^/]+)/ ]] && module="${BASH_REMATCH[1]}"

echo "Running tests with JaCoCo coverage..."

if [[ -n "$module" ]]; then
    ./gradlew ":libs:${module}:test" ":libs:${module}:jacocoTestReport" --quiet 2>&1 | tail -30
    result=${PIPESTATUS[0]}
    report="libs/${module}/build/reports/jacoco/test/html/index.html"
else
    ./gradlew test jacocoTestReport --quiet 2>&1 | tail -30
    result=${PIPESTATUS[0]}
    report="build/reports/jacoco/test/html/index.html"
fi

if [ $result -eq 0 ]; then
    echo "Tests PASSED. Coverage report: $report"
    exit 0
else
    echo "Tests FAILED!"
    exit 2
fi
