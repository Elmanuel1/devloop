#!/bin/bash
# Script to export logs from tosspaper-everything container on remote server
# Usage:
#   ./export-logs.sh [ssh_user@host] [ssh_key] [container_name] [lines]
#   Example: ./export-logs.sh opc@192.168.1.100 ~/.ssh/server.key
#
# Environment variables (used as fallback if arguments not provided):
#   EXPORT_LOGS_SSH_HOST - SSH user@host
#   EXPORT_LOGS_SSH_KEY  - Path to SSH key file

SSH_HOST=${1:-${EXPORT_LOGS_SSH_HOST:?'SSH_HOST required as arg or EXPORT_LOGS_SSH_HOST env var'}}
SSH_KEY=${2:-${EXPORT_LOGS_SSH_KEY:?'SSH_KEY required as arg or EXPORT_LOGS_SSH_KEY env var'}}
CONTAINER_NAME=${3:-tosspaper-everything}
LINES=${4:-10000}
OUTPUT_FILE="tosspaper-everything-logs-$(date +%Y%m%d-%H%M%S).txt"

echo "Connecting to: $SSH_HOST"
echo "Exporting logs from container: $CONTAINER_NAME"
echo "Lines: $LINES"
echo "Output file: $OUTPUT_FILE"

# SSH to remote server and export logs
ssh -i "$SSH_KEY" "$SSH_HOST" "docker logs --tail $LINES $CONTAINER_NAME" > "$OUTPUT_FILE" 2>&1

if [ $? -eq 0 ]; then
    echo "✓ Logs exported successfully to: $OUTPUT_FILE"
    echo "File size: $(du -h "$OUTPUT_FILE" | cut -f1)"
    echo "First 10 lines:"
    head -n 10 "$OUTPUT_FILE"
else
    echo "✗ Failed to export logs. Check SSH connection and container name."
    exit 1
fi

