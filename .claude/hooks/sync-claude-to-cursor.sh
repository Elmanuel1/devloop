#!/bin/bash

# Script to sync .claude directory structure to .cursor directory
# This ensures .cursor has all the same skills and structure as .claude
# Usage: ./sync-claude-to-cursor.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CURSOR_DIR="$PROJECT_ROOT/.cursor"
CLAUDE_DIR="$PROJECT_ROOT/.claude"

echo "🔄 Syncing .claude to .cursor..."
echo "   Source: $CLAUDE_DIR"
echo "   Destination: $CURSOR_DIR"
echo ""

# Function to sync a directory
sync_directory() {
    local source_dir="$1"
    local dest_dir="$2"
    local description="$3"
    
    if [ ! -d "$source_dir" ]; then
        echo "⚠️  Source directory does not exist: $source_dir"
        return
    fi
    
    echo "📁 Syncing $description..."
    echo "   From: $source_dir"
    echo "   To:   $dest_dir"
    
    # Create destination directory if it doesn't exist
    mkdir -p "$dest_dir"
    
    # Use rsync if available, otherwise use cp
    if command -v rsync &> /dev/null; then
        rsync -av --delete "$source_dir/" "$dest_dir/"
    else
        # Remove destination directory contents first (but keep the directory)
        if [ -d "$dest_dir" ]; then
            find "$dest_dir" -mindepth 1 -delete
        fi
        # Copy all files and directories
        cp -r "$source_dir"/* "$dest_dir/" 2>/dev/null || true
    fi
    
    echo "   ✓ Synced $description"
    echo ""
}

# Sync skills directory (includes scripts/, references/, etc.)
sync_directory "$CLAUDE_DIR/skills" "$CURSOR_DIR/skills" "skills"

# Summary
echo "✅ Sync complete!"
echo ""
echo "Synced directories:"
echo "  - .claude/skills/ → .cursor/skills/"
echo ""
echo "Note: This script preserves existing .cursor files (rules, settings)"
echo "      and only syncs skills from .claude"
