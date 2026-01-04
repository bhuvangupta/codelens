#!/bin/bash
# =============================================================================
# CodeLens Database Setup Script
# =============================================================================
# This script creates the CodeLens database from scratch.
#
# Usage:
#   ./scripts/setup_database.sh                    # Uses default connection
#   ./scripts/setup_database.sh -h localhost -P 3306 -u root
#   DB_HOST=localhost DB_USER=root ./scripts/setup_database.sh
#
# Environment variables:
#   DB_HOST     - MySQL host (default: localhost)
#   DB_PORT     - MySQL port (default: 3306)
#   DB_USER     - MySQL user (default: root)
#   DB_PASSWORD - MySQL password (will prompt if not set)
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/create_database.sql"

# Default values
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"

# Parse command line arguments
while getopts "h:P:u:p:" opt; do
    case $opt in
        h) DB_HOST="$OPTARG" ;;
        P) DB_PORT="$OPTARG" ;;
        u) DB_USER="$OPTARG" ;;
        p) DB_PASSWORD="$OPTARG" ;;
        \?) echo "Invalid option: -$OPTARG" >&2; exit 1 ;;
    esac
done

echo "======================================"
echo "CodeLens Database Setup"
echo "======================================"
echo "Host: $DB_HOST:$DB_PORT"
echo "User: $DB_USER"
echo ""

# Check if SQL file exists
if [ ! -f "$SQL_FILE" ]; then
    echo "Error: SQL file not found: $SQL_FILE"
    exit 1
fi

# Warning
echo "WARNING: This will DROP all existing CodeLens tables and recreate them!"
echo ""
read -p "Are you sure you want to continue? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Aborted."
    exit 0
fi

echo ""
echo "Creating database..."

# Build mysql command
MYSQL_CMD="mysql -h $DB_HOST -P $DB_PORT -u $DB_USER"

# Add password if provided
if [ -n "$DB_PASSWORD" ]; then
    MYSQL_CMD="$MYSQL_CMD -p$DB_PASSWORD"
else
    MYSQL_CMD="$MYSQL_CMD -p"
fi

# Execute SQL file
$MYSQL_CMD < "$SQL_FILE"

echo ""
echo "======================================"
echo "Database setup complete!"
echo "======================================"
echo ""
echo "Next steps:"
echo "  1. Update application.yaml with your database credentials"
echo "  2. Start the application: ./mvnw spring-boot:run"
echo ""
