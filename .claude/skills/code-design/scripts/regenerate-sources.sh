#!/bin/bash
#
# Hook: Regenerate Sources (JOOQ, OpenAPI, JSON Schema)
# Trigger: PostToolUse on Write|Edit of spec/migration files
#

cd "$CLAUDE_PROJECT_DIR"

input=$(cat)
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

# OpenAPI spec changed
if [[ "$file_path" =~ openapi\.yaml$ ]]; then
    echo "Regenerating OpenAPI sources..."
    ./gradlew openApiGenerate --quiet 2>&1 | tail -10
    echo "OpenAPI regenerated!"
    exit 0
fi

# JSON Schema changed
if [[ "$file_path" =~ \.schema\.json$ ]] || [[ "$file_path" =~ /schemas/.*\.json$ ]]; then
    echo "Regenerating JSON Schema sources..."
    ./gradlew generateJsonSchema2Pojo --quiet 2>&1 | tail -10
    echo "JSON Schema regenerated!"
    exit 0
fi

# Flyway migration changed
if [[ "$file_path" =~ flyway/V.*\.sql$ ]]; then
    echo "Regenerating JOOQ sources..."
    ./gradlew flywayMigrate generateJooq --quiet 2>&1 | tail -10
    echo "JOOQ regenerated!"
    exit 0
fi

exit 0