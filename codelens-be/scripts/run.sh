#!/bin/bash

# CodeLens Backend - Run Script
# Usage: ./scripts/run.sh [profile]
#
# Profiles: dev (default), staging, prod

set -e

PROFILE=${1:-dev}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_PATH="$PROJECT_DIR/target/codelens-1.0.0-SNAPSHOT.jar"

# Log directory configuration
LOG_DIR="${LOG_PATH:-$PROJECT_DIR/logs}"
mkdir -p "$LOG_DIR"

echo "Starting CodeLens API with profile: $PROFILE"
echo "Log directory: $LOG_DIR"

# Check if JAR exists, build if not
if [ ! -f "$JAR_PATH" ]; then
    echo "JAR not found. Building..."
    cd "$PROJECT_DIR"
    mvn package -DskipTests -q
fi

# Set Java options
JAVA_OPTS="${JAVA_OPTS:--Xmx512m -Xms256m}"

# Run the application with log path
cd "$PROJECT_DIR"
LOG_PATH="$LOG_DIR" java $JAVA_OPTS -jar "$JAR_PATH" --spring.profiles.active=$PROFILE
