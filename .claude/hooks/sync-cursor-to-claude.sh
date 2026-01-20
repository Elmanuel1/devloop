#!/bin/bash

# Script to sync .cursor directory structure to .claude directory
# This ensures .claude has all the same skills and structure as .cursor
# Usage: ./sync-cursor-to-claude.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CURSOR_DIR="$PROJECT_ROOT/.cursor"
CLAUDE_DIR="$PROJECT_ROOT/.claude"

echo "🔄 Syncing .cursor to .claude..."
echo "   Source: $CURSOR_DIR"
echo "   Destination: $CLAUDE_DIR"
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
sync_directory "$CURSOR_DIR/skills" "$CLAUDE_DIR/skills" "skills"

# Summary
echo "✅ Sync complete!"
echo ""
echo "Synced directories:"
echo "  - .cursor/skills/ → .claude/skills/"
echo ""
echo "Note: This script preserves existing .claude files (hooks, agents, settings)"
echo "      and only syncs skills from .cursor"
