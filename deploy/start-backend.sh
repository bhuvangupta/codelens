#!/bin/bash

# CodeLens Backend Startup Script
# Usage: ./start-backend.sh [dev|prod]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/codelens-be"
MODE="${1:-dev}"

cd "$BACKEND_DIR"

echo "=========================================="
echo "  CodeLens Backend"
echo "=========================================="

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Error: Java 17+ required, found Java $JAVA_VERSION"
    exit 1
fi

# Load environment variables if .env exists
if [ -f "$BACKEND_DIR/.env" ]; then
    echo "Loading environment from .env"
    set -a
    source "$BACKEND_DIR/.env"
    set +a
fi

# Build if JAR doesn't exist
JAR_FILE="$BACKEND_DIR/target/codelens-1.0.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Building backend..."
    mvn clean package -DskipTests -q
fi
export GLM_API_KEY=b1c97ffd4b744aa390fecb0a89e31b6a.3rERBPfg0OBcPDBF
echo "Starting backend in $MODE mode..."
echo ""

if [ "$MODE" == "prod" ]; then
    # Production mode
    java -jar \
        -Dspring.profiles.active=prod \
        -Xms512m -Xmx1024m \
        "$JAR_FILE"
else
    # Development mode with hot reload info
    java -jar \
        -Dspring.profiles.active=dev \
        "$JAR_FILE"
fi
