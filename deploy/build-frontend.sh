#!/bin/bash

# CodeLens - Build Frontend
# Usage: ./build-frontend.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
FRONTEND_DIR="$ROOT_DIR/codelens-fe"

echo "=========================================="
echo "  Building CodeLens Frontend"
echo "=========================================="

# Check if frontend directory exists
if [ ! -d "$FRONTEND_DIR" ]; then
    echo "Error: Frontend directory not found at $FRONTEND_DIR"
    exit 1
fi

cd "$FRONTEND_DIR"

# Check Node.js
if ! command -v node &> /dev/null; then
    echo "Error: Node.js is not installed"
    exit 1
fi

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "Installing dependencies..."
    npm install
fi

echo "Building frontend..."
npm run build

echo ""
echo "=========================================="
echo "  Build Complete"
echo "=========================================="
echo ""
echo "Output: $FRONTEND_DIR/build"
echo ""
echo "To preview: npm run preview"
