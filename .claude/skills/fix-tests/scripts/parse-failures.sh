#!/bin/bash
# Parse test failures from Gradle HTML report
# Usage: ./parse-failures.sh <path-to-test-report-dir>

REPORT_DIR="${1:-build/reports/tests/test}"

if [ ! -d "$REPORT_DIR" ]; then
    echo "Report directory not found: $REPORT_DIR"
    exit 1
fi

INDEX_FILE="$REPORT_DIR/index.html"

if [ ! -f "$INDEX_FILE" ]; then
    echo "No test report found at: $INDEX_FILE"
    exit 1
fi

# Check if there are failures
FAILURES=$(grep -o 'failures">[0-9]*' "$INDEX_FILE" | grep -o '[0-9]*' | head -1)

if [ "$FAILURES" = "0" ] || [ -z "$FAILURES" ]; then
    echo "No test failures found!"
    exit 0
fi

echo "Found $FAILURES test failure(s)"
echo ""
echo "Failed Tests:"
echo "============="

# Find failed class reports
CLASSES_DIR="$REPORT_DIR/classes"

if [ -d "$CLASSES_DIR" ]; then
    for class_file in "$CLASSES_DIR"/*.html; do
        if [ -f "$class_file" ]; then
            # Check if this class has failures
            if grep -q 'class="failures"' "$class_file" 2>/dev/null; then
                CLASS_NAME=$(basename "$class_file" .html)
                echo ""
                echo "Class: $CLASS_NAME"

                # Extract failed method names
                grep -o 'id="[^"]*" class="failures"' "$class_file" 2>/dev/null | \
                    sed 's/id="//g; s/" class="failures"//g' | \
                    while read method; do
                        echo "  - $method"
                    done
            fi
        fi
    done
fi

echo ""
echo "View full report: file://$PWD/$INDEX_FILE"