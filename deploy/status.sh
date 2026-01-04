#!/bin/bash
#
# CodeLens Status Script
# Shows the status of backend and frontend services
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/codelens-be"
PID_DIR="$BACKEND_DIR/pids"
LOG_DIR="$BACKEND_DIR/logs"

# Load environment for ports
if [ -f "$BACKEND_DIR/.env" ]; then
    export $(grep -v '^#' "$BACKEND_DIR/.env" | xargs)
fi

echo "========================================"
echo "  CodeLens Status"
echo "========================================"
echo ""

# Function to check service status
check_service() {
    local name="$1"
    local pid_file="$2"
    local port="$3"
    local health_endpoint="$4"

    echo "=== $name ==="

    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            echo "  Status:  ✅ Running (PID: $pid)"

            # Check memory usage
            if command -v ps &> /dev/null; then
                local mem=$(ps -o rss= -p "$pid" 2>/dev/null | awk '{printf "%.1f MB", $1/1024}')
                echo "  Memory:  $mem"
            fi

            # Check health endpoint
            if [ -n "$health_endpoint" ]; then
                if curl -s "$health_endpoint" > /dev/null 2>&1; then
                    echo "  Health:  ✅ Healthy"
                else
                    echo "  Health:  ⚠️  Not responding"
                fi
            fi

            echo "  URL:     http://localhost:$port"
        else
            echo "  Status:  ❌ Not running (stale PID file)"
        fi
    else
        echo "  Status:  ❌ Not running"
    fi

    # Show recent log entries
    local log_file="$LOG_DIR/${name,,}.log"
    if [ -f "$log_file" ]; then
        echo "  Log:     $log_file"
        echo "  Recent:"
        tail -3 "$log_file" 2>/dev/null | sed 's/^/           /'
    fi

    echo ""
}

# Check Backend
check_service "Backend" "$PID_DIR/backend.pid" "${SERVER_PORT:-9292}" "http://localhost:${SERVER_PORT:-9292}/actuator/health"

# Check Frontend
check_service "Frontend" "$PID_DIR/frontend.pid" "${FRONTEND_PORT:-5174}" "http://localhost:${FRONTEND_PORT:-5174}"

# System Info
echo "=== System ==="
echo "  Java:    $(java -version 2>&1 | head -1)"
echo "  Node:    $(node -v 2>/dev/null || echo 'Not installed')"
echo "  MySQL:   $(mysql --version 2>/dev/null || echo 'Not in PATH')"

# Check MySQL connection
if [ -n "$DB_URL" ]; then
    DB_HOST=$(echo "$DB_URL" | sed -n 's/.*\/\/\([^:\/]*\).*/\1/p')
    DB_PORT=$(echo "$DB_URL" | sed -n 's/.*:\([0-9]*\)\/.*/\1/p')
    if nc -z "$DB_HOST" "${DB_PORT:-3306}" 2>/dev/null; then
        echo "  DB:      ✅ MySQL reachable at $DB_HOST:${DB_PORT:-3306}"
    else
        echo "  DB:      ❌ MySQL not reachable"
    fi
fi

echo ""
