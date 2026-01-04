#!/bin/bash

# CodeLens - Start All Services
# Usage: ./start-all.sh [dev|prod]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-dev}"

echo "=========================================="
echo "  CodeLens - Starting All Services"
echo "=========================================="
echo ""

# Start backend in background
echo "Starting backend..."
"$SCRIPT_DIR/start-backend.sh" "$MODE" &
BACKEND_PID=$!
echo "Backend PID: $BACKEND_PID"

# Wait for backend to be ready
echo "Waiting for backend to start..."
sleep 5

# Check if backend is running
if ! kill -0 $BACKEND_PID 2>/dev/null; then
    echo "Error: Backend failed to start"
    exit 1
fi

# Start frontend in background
echo ""
echo "Starting frontend..."
"$SCRIPT_DIR/start-frontend.sh" "$MODE" &
FRONTEND_PID=$!
echo "Frontend PID: $FRONTEND_PID"

echo ""
echo "=========================================="
echo "  Services Started"
echo "=========================================="
echo ""
echo "Backend:  http://localhost:9292"
echo "Frontend: http://localhost:5174"
echo ""
echo "Press Ctrl+C to stop all services"
echo ""

# Save PIDs to file for stop script
echo "$BACKEND_PID" > "$SCRIPT_DIR/.backend.pid"
echo "$FRONTEND_PID" > "$SCRIPT_DIR/.frontend.pid"

# Wait for both processes
trap "kill $BACKEND_PID $FRONTEND_PID 2>/dev/null; exit" SIGINT SIGTERM
wait
