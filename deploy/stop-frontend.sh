#!/bin/bash

# CodeLens - Stop Frontend
# Usage: ./stop-frontend.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Stopping CodeLens frontend..."

# Try to read PID from file
if [ -f "$SCRIPT_DIR/.frontend.pid" ]; then
    PID=$(cat "$SCRIPT_DIR/.frontend.pid")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "Stopped frontend (PID: $PID)"
        rm -f "$SCRIPT_DIR/.frontend.pid"
        exit 0
    fi
    rm -f "$SCRIPT_DIR/.frontend.pid"
fi

# Find and kill by port
for PORT in 5174; do
    PID=$(lsof -ti:$PORT 2>/dev/null || true)
    if [ -n "$PID" ]; then
        kill $PID 2>/dev/null || true
        echo "Stopped frontend on port $PORT (PID: $PID)"
        exit 0
    fi
done

echo "Frontend not running"
