#!/bin/bash
#
# Hook: Session Start
# Trigger: SessionStart
# Purpose: Initialize session, verify environment, warm up Gradle daemon
#

cd "$CLAUDE_PROJECT_DIR"

LOG_FILE="$CLAUDE_PROJECT_DIR/.claude/logs/sessions.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

echo "Initializing development session..."
log "=== SESSION STARTED ==="

# Check Java version
if command -v java &> /dev/null; then
    java_version=$(java -version 2>&1 | head -1)
    log "Java: $java_version"
    echo "Java: $java_version"
else
    echo "WARNING: Java not found in PATH"
    log "WARNING: Java not found"
fi

# Check Gradle wrapper
if [[ -f "./gradlew" ]]; then
    log "Gradle wrapper found"

    # Warm up Gradle daemon in background (don't block session start)
    echo "Warming up Gradle daemon..."
    (./gradlew --status &>/dev/null || ./gradlew help --quiet &>/dev/null) &
else
    echo "WARNING: gradlew not found"
    log "WARNING: gradlew not found"
fi

# Check Docker if needed
if command -v docker &> /dev/null; then
    if docker info &>/dev/null; then
        log "Docker: running"
    else
        log "Docker: not running"
        echo "Note: Docker is not running"
    fi
fi

# Ensure logs directory exists
mkdir -p "$CLAUDE_PROJECT_DIR/.claude/logs"

# Report git branch
if command -v git &> /dev/null; then
    branch=$(git branch --show-current 2>/dev/null)
    if [[ -n "$branch" ]]; then
        log "Git branch: $branch"
        echo "Git branch: $branch"
    fi
fi

log "Session initialization complete"
echo "Session ready!"
echo ""

exit 0