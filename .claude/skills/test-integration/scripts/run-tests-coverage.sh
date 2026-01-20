#!/bin/bash
#
# Hook: Run Tests with JaCoCo Coverage
# Trigger: PostToolUse on Write|Edit of Java/Groovy source files
#

set -e

cd "$CLAUDE_PROJECT_DIR" || {
    echo "ERROR: Failed to change to project directory: $CLAUDE_PROJECT_DIR"
    exit 1
}

input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

# Only run for Java/Groovy source files (not test files)
[[ ! "$file_path" =~ \.(java|groovy)$ ]] && exit 0
[[ "$file_path" =~ /test/ ]] && exit 0

# Get module (libs or services)
module=""
module_type=""
if [[ "$file_path" =~ libs/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
    module_type="libs"
elif [[ "$file_path" =~ services/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
    module_type="services"
fi

echo "Running tests with JaCoCo coverage..."

# Run tests (temporarily disable errexit to capture exit code)
set +e
if [[ -n "$module" ]]; then
    ./gradlew ":${module_type}:${module}:test" ":${module_type}:${module}:jacocoTestReport" --quiet 2>&1
    result=$?
    report="${module_type}/${module}/build/reports/jacoco/test/html/index.html"
else
    ./gradlew test jacocoTestReport --quiet 2>&1
    result=$?
    report="build/reports/jacoco/test/html/index.html"
fi
set -e

if [ $result -eq 0 ]; then
    echo "Tests PASSED. Coverage report: $report"
    exit 0
else
    echo "Tests FAILED!"
    exit 2
fi
