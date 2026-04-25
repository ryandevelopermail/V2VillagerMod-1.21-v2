#!/usr/bin/env bash
# run-and-export.sh
# Runs the Minecraft dev server and exports the latest log to storage/private
# on exit — even if killed with Ctrl+C or SIGTERM.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_SRC="$SCRIPT_DIR/logs/latest.log"
LOG_DEST="/home/computer/storage/private/mc-latest-run.log"

export JAVA_HOME=/home/computer/.jdk/jdk-21.0.10+7
export PATH=$JAVA_HOME/bin:$PATH

export_log() {
    if [ -f "$LOG_SRC" ]; then
        cp "$LOG_SRC" "$LOG_DEST"
        echo "[run-and-export] Log exported to $LOG_DEST"
    else
        echo "[run-and-export] No log found at $LOG_SRC — nothing exported"
    fi
}

# Export on any exit: clean, Ctrl+C (SIGINT), or SIGTERM
trap export_log EXIT

echo "[run-and-export] Starting runServer... (log will export on exit)"
"$SCRIPT_DIR/gradlew" runServer --no-daemon "$@"
