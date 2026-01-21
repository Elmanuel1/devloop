#!/bin/bash

# Script to copy missing environment variables from local to dev
# For QUICKBOOKS_REDIRECT_URI, replaces host with dev-api.tosspaper.com

LOCAL_ENV="springboot_local.env"
DEV_ENV="springboot_dev.env"
DEV_HOST="dev-api.tosspaper.com"

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

# Get the value for a key from a file (preserves all characters including +, =, /, etc.)
get_value() {
    grep "^[[:space:]]*${1}[[:space:]]*=" "$2" | head -1 | sed 's/^[^=]*=[[:space:]]*//' | sed 's/[[:space:]]*$//'
}

echo "Copying missing environment variables from local to dev..."
echo "=========================================================="
echo ""

# Get keys from both files
LOCAL_KEYS=$(extract_keys "$LOCAL_ENV" | sort)
DEV_KEYS=$(extract_keys "$DEV_ENV" | sort)

# Find keys in local but not in dev
MISSING_IN_DEV=$(comm -23 <(echo "$LOCAL_KEYS") <(echo "$DEV_KEYS"))

if [ -z "$MISSING_IN_DEV" ]; then
    echo "✅ No missing keys to copy!"
    exit 0
fi

# Backup dev file
cp "$DEV_ENV" "${DEV_ENV}.backup.$(date +%Y%m%d_%H%M%S)"
echo "Created backup: ${DEV_ENV}.backup.$(date +%Y%m%d_%H%M%S)"
echo ""

# Append missing keys to dev file
echo "$MISSING_IN_DEV" | while read -r key; do
    if [ -n "$key" ]; then
        VALUE=$(get_value "$key" "$LOCAL_ENV")
        
        # Special handling for QUICKBOOKS_REDIRECT_URI
        if [ "$key" = "QUICKBOOKS_REDIRECT_URI" ]; then
            # Extract path and query from the original URI
            # Example: https://62b4c9b28c44.ngrok-free.app/v1/integrations/quickbooks/callback -> https://dev-api.tosspaper.com/v1/integrations/quickbooks/callback
            if echo "$VALUE" | grep -qE "^https?://[^/]+(/.*)?$"; then
                # Extract everything after the host (path, query, fragment)
                PATH_AND_QUERY=$(echo "$VALUE" | sed -E 's|^https?://[^/]+||')
                # If no path, default to empty (but should have a path)
                if [ -z "$PATH_AND_QUERY" ]; then
                    PATH_AND_QUERY="/"
                fi
                NEW_VALUE="https://${DEV_HOST}${PATH_AND_QUERY}"
                echo "  $key=$NEW_VALUE (host replaced with $DEV_HOST)"
                echo "$key=$NEW_VALUE" >> "$DEV_ENV"
            else
                echo "  $key=$VALUE (could not parse URI, copying as-is)"
                echo "$key=$VALUE" >> "$DEV_ENV"
            fi
        else
            echo "  $key=$VALUE"
            echo "$key=$VALUE" >> "$DEV_ENV"
        fi
    fi
done

echo ""
echo "✅ Copied missing environment variables to $DEV_ENV"
echo ""

