#!/bin/bash

# CodeLens - Stop Backend
# Usage: ./stop-backend.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Stopping CodeLens backend..."

# Try to read PID from file
if [ -f "$SCRIPT_DIR/.backend.pid" ]; then
    PID=$(cat "$SCRIPT_DIR/.backend.pid")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "Stopped backend (PID: $PID)"
        rm -f "$SCRIPT_DIR/.backend.pid"
        exit 0
    fi
    rm -f "$SCRIPT_DIR/.backend.pid"
fi

# Find and kill by port
PID=$(lsof -ti:9292 2>/dev/null || true)
if [ -n "$PID" ]; then
    kill $PID 2>/dev/null || true
    echo "Stopped backend on port 9292 (PID: $PID)"
else
    echo "Backend not running"
fi
