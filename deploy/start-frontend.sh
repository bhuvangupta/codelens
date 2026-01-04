#!/bin/bash

# CodeLens Frontend Startup Script
# Usage: ./start-frontend.sh [dev|prod|build]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
FRONTEND_DIR="$ROOT_DIR/codelens-fe"
MODE="${1:-dev}"

echo "=========================================="
echo "  CodeLens Frontend"
echo "=========================================="

# Check if frontend directory exists
if [ ! -d "$FRONTEND_DIR" ]; then
    echo "Error: Frontend directory not found at $FRONTEND_DIR"
    exit 1
fi

cd "$FRONTEND_DIR"

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "Error: Node.js is not installed"
    exit 1
fi

# Check Node version
NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo "Error: Node.js 18+ required, found v$NODE_VERSION"
    exit 1
fi

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "Installing dependencies..."
    npm install
fi

# Check if .env exists
if [ ! -f ".env" ]; then
    echo ""
    echo "Warning: .env file not found!"
    echo "Copy .env.example to .env and configure:"
    echo "  cp .env.example .env"
    echo ""
    echo "Required variables:"
    echo "  AUTH_SECRET=<run: openssl rand -base64 32>"
    echo "  GOOGLE_CLIENT_ID=<from Google Cloud Console>"
    echo "  GOOGLE_CLIENT_SECRET=<from Google Cloud Console>"
    echo ""
fi

case "$MODE" in
    dev)
        echo "Starting frontend in development mode..."
        echo "Frontend will be available at http://localhost:5174"
        echo ""
        npm run dev
        ;;
    build)
        echo "Building frontend for production..."
        npm run build
        echo ""
        echo "Build complete! Output in 'build' directory"
        ;;
    prod)
        echo "Building and starting frontend in production mode..."
        npm run build
        echo "Starting production server..."
        echo "Frontend will be available at http://localhost:5174"
        echo ""
        npm run preview
        ;;
    *)
        echo "Usage: $0 [dev|prod|build]"
        echo ""
        echo "  dev   - Start development server with hot reload (default)"
        echo "  prod  - Build and start production server"
        echo "  build - Build for production only"
        exit 1
        ;;
esac
