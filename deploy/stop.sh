#!/bin/bash
#
# CodeLens Stop Script
# Stops the backend and frontend services
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/codelens-be"
PID_DIR="$BACKEND_DIR/pids"

echo "========================================"
echo "  CodeLens Stop Script"
echo "========================================"

# Function to stop a service
stop_service() {
    local name="$1"
    local pid_file="$2"

    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            echo "Stopping $name (PID: $pid)..."
            kill "$pid"

            # Wait for graceful shutdown
            for i in {1..10}; do
                if ! kill -0 "$pid" 2>/dev/null; then
                    echo "$name stopped."
                    rm -f "$pid_file"
                    return 0
                fi
                sleep 1
            done

            # Force kill if still running
            echo "Force stopping $name..."
            kill -9 "$pid" 2>/dev/null
            rm -f "$pid_file"
        else
            echo "$name is not running."
            rm -f "$pid_file"
        fi
    else
        echo "$name is not running (no PID file)."
    fi
}

# Stop services
stop_service "Backend" "$PID_DIR/backend.pid"
stop_service "Frontend" "$PID_DIR/frontend.pid"

echo ""
echo "All services stopped."
