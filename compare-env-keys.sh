#!/bin/bash

# Script to compare environment variable keys between local and dev
# Shows which keys exist in springboot_local.env but not in springboot_dev.env

LOCAL_ENV="springboot_local.env"
DEV_ENV="springboot_dev.env"

if [ ! -f "$LOCAL_ENV" ]; then
    echo "Error: $LOCAL_ENV not found"
    exit 1
fi

if [ ! -f "$DEV_ENV" ]; then
    echo "Error: $DEV_ENV not found"
    exit 1
fi

# Extract keys (variable names before = sign, ignoring comments and empty lines)
extract_keys() {
    grep -v '^#' "$1" | grep -v '^$' | grep '=' | sed 's/=.*//' | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//'
}

echo "Comparing environment variable keys..."
echo "======================================"
echo ""

# Get keys from both files
LOCAL_KEYS=$(extract_keys "$LOCAL_ENV" | sort)
DEV_KEYS=$(extract_keys "$DEV_ENV" | sort)

# Find keys in local but not in dev
MISSING_IN_DEV=$(comm -23 <(echo "$LOCAL_KEYS") <(echo "$DEV_KEYS"))

# Count
LOCAL_COUNT=$(echo "$LOCAL_KEYS" | wc -l | tr -d ' ')
DEV_COUNT=$(echo "$DEV_KEYS" | wc -l | tr -d ' ')
MISSING_COUNT=$(echo "$MISSING_IN_DEV" | grep -v '^$' | wc -l | tr -d ' ')

echo "Summary:"
echo "  Keys in $LOCAL_ENV: $LOCAL_COUNT"
echo "  Keys in $DEV_ENV: $DEV_COUNT"
echo "  Keys in local but NOT in dev: $MISSING_COUNT"
echo ""

if [ -z "$MISSING_IN_DEV" ] || [ "$MISSING_COUNT" -eq 0 ]; then
    echo "✅ All keys from local are present in dev!"
else
    echo "⚠️  Keys missing in dev:"
    echo "-------------------------------------------"
    echo "$MISSING_IN_DEV" | while read -r key; do
        if [ -n "$key" ]; then
            echo "  $key"
        fi
    done
fi

echo ""
echo "======================================"
echo "Done!"

