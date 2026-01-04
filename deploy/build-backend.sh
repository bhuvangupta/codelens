#!/bin/bash

# CodeLens - Build Backend
# Usage: ./build-backend.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/codelens-be"

cd "$BACKEND_DIR"

echo "=========================================="
echo "  Building CodeLens Backend"
echo "=========================================="

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven is not installed"
    exit 1
fi

echo "Running Maven build..."
mvn clean package -DskipTests

echo ""
echo "=========================================="
echo "  Build Complete"
echo "=========================================="
echo ""
echo "JAR file: $BACKEND_DIR/target/codelens-1.0.0-SNAPSHOT.jar"
echo ""
echo "To run: java -jar target/codelens-1.0.0-SNAPSHOT.jar"
