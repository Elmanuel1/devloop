#!/bin/bash
#
# Hook: Check JaCoCo Code Coverage
# Trigger: PostToolUse on Write|Edit of Java/Groovy files
# Purpose: Run tests and generate JaCoCo coverage report, fail if below threshold
#

set -e

cd "$CLAUDE_PROJECT_DIR"

# Parse hook input from stdin
input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
tool_name=$(echo "$input" | jq -r '.tool_name // empty')

# Configuration
MIN_COVERAGE_PERCENT=70  # Minimum required coverage percentage
LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/coverage.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Only run for Java/Groovy source files (not test files)
if [[ ! "$file_path" =~ \.(java|groovy)$ ]]; then
    exit 0
fi

# Skip if it's a test file
if [[ "$file_path" =~ /test/ ]] || [[ "$file_path" =~ Test\.java$ ]] || [[ "$file_path" =~ Spec\.groovy$ ]]; then
    log "Skipping coverage check for test file: $file_path"
    exit 0
fi

log "File modified: $file_path"
echo "Running tests with JaCoCo coverage analysis..."

# Determine which module was modified (libs or services)
module=""
module_type=""
if [[ "$file_path" =~ libs/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
    module_type="libs"
elif [[ "$file_path" =~ services/([^/]+)/ ]]; then
    module="${BASH_REMATCH[1]}"
    module_type="services"
fi

# Run tests with JaCoCo (temporarily disable errexit to capture exit code)
set +e
if [[ -n "$module" ]]; then
    log "Running JaCoCo for ${module_type} module: $module"
    ./gradlew ":${module_type}:${module}:test" ":${module_type}:${module}:jacocoTestReport" --quiet 2>&1
    test_result=$?
    REPORT_PATH="${module_type}/${module}/build/reports/jacoco/test/html/index.html"
    XML_REPORT="${module_type}/${module}/build/reports/jacoco/test/jacocoTestReport.xml"
else
    log "Running JaCoCo for all modules"
    ./gradlew test jacocoTestReport --quiet 2>&1
    test_result=$?
    REPORT_PATH="build/reports/jacoco/test/html/index.html"
    XML_REPORT="build/reports/jacoco/test/jacocoTestReport.xml"
fi
set -e

if [ $test_result -ne 0 ]; then
    log "Tests FAILED"
    echo "Tests failed! Cannot generate coverage report."
    exit 2
fi

log "Tests PASSED"

# Parse coverage from XML report if it exists
if [[ -f "$XML_REPORT" ]]; then
    # Extract instruction coverage from JaCoCo XML
    covered=$(grep -o 'type="INSTRUCTION" missed="[0-9]*" covered="[0-9]*"' "$XML_REPORT" | head -1 | grep -o 'covered="[0-9]*"' | grep -o '[0-9]*')
    missed=$(grep -o 'type="INSTRUCTION" missed="[0-9]*" covered="[0-9]*"' "$XML_REPORT" | head -1 | grep -o 'missed="[0-9]*"' | grep -o '[0-9]*')

    if [[ -n "$covered" ]] && [[ -n "$missed" ]]; then
        total=$((covered + missed))
        if [ $total -gt 0 ]; then
            coverage_percent=$((covered * 100 / total))

            log "Coverage: ${coverage_percent}% (covered: $covered, missed: $missed)"
            echo ""
            echo "======================================"
            echo "  Code Coverage: ${coverage_percent}%"
            echo "======================================"
            echo "  Covered: $covered instructions"
            echo "  Missed:  $missed instructions"
            echo "  Report:  $REPORT_PATH"
            echo "======================================"

            if [ $coverage_percent -lt $MIN_COVERAGE_PERCENT ]; then
                echo ""
                echo "WARNING: Coverage ${coverage_percent}% is below minimum threshold of ${MIN_COVERAGE_PERCENT}%"
                log "Coverage below threshold: ${coverage_percent}% < ${MIN_COVERAGE_PERCENT}%"
                # Uncomment to enforce minimum coverage:
                # exit 2
            fi
        fi
    fi
else
    echo "Coverage report generated: $REPORT_PATH"
    log "XML report not found, HTML report at: $REPORT_PATH"
fi

exit 0
