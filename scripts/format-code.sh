#!/bin/bash
#
# Hook: Format Code (Spotless)
# Trigger: PostToolUse on Write|Edit of Java/Groovy files
#

cd "$CLAUDE_PROJECT_DIR"

input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

# Only format Java/Groovy files
[[ ! "$file_path" =~ \.(java|groovy)$ ]] && exit 0

echo "Formatting code..."
./gradlew spotlessApply --quiet 2>&1 | tail -5

exit 0