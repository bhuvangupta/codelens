#!/bin/bash
#
# CodeLens Backup Script
# Creates backups of database and configuration
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/codelens-be"
FRONTEND_DIR="$ROOT_DIR/codelens-fe"
BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}"
DATE=$(date +%Y%m%d_%H%M%S)

# Load environment
if [ -f "$BACKEND_DIR/.env" ]; then
    export $(grep -v '^#' "$BACKEND_DIR/.env" | xargs)
fi

echo "========================================"
echo "  CodeLens Backup Script"
echo "========================================"
echo ""

# Create backup directory
mkdir -p "$BACKUP_DIR"

# Parse database URL
DB_HOST=$(echo "$DB_URL" | sed -n 's/.*\/\/\([^:\/]*\).*/\1/p')
DB_PORT=$(echo "$DB_URL" | sed -n 's/.*:\([0-9]*\)\/.*/\1/p')
DB_NAME=$(echo "$DB_URL" | sed -n 's/.*\/\([^?]*\).*/\1/p')

# Backup database
echo "=== Backing up Database ==="
BACKUP_FILE="$BACKUP_DIR/codelens_db_$DATE.sql.gz"

if command -v mysqldump &> /dev/null; then
    mysqldump \
        -h "${DB_HOST:-localhost}" \
        -P "${DB_PORT:-3306}" \
        -u "$DB_USERNAME" \
        -p"$DB_PASSWORD" \
        "$DB_NAME" | gzip > "$BACKUP_FILE"

    echo "Database backup: $BACKUP_FILE"
else
    echo "⚠️  mysqldump not found, skipping database backup"
fi

# Backup configuration
echo ""
echo "=== Backing up Configuration ==="
CONFIG_BACKUP="$BACKUP_DIR/codelens_config_$DATE.tar.gz"

tar -czf "$CONFIG_BACKUP" \
    -C "$BACKEND_DIR" \
    .env \
    src/main/resources/application*.yaml \
    2>/dev/null || true

# Include frontend config if exists
if [ -f "$FRONTEND_DIR/.env" ]; then
    tar -rf "${CONFIG_BACKUP%.gz}" \
        -C "$ROOT_DIR" \
        codelens-fe/.env \
        2>/dev/null || true
    gzip -f "${CONFIG_BACKUP%.gz}"
fi

echo "Configuration backup: $CONFIG_BACKUP"

# Clean old backups (keep last 7 days)
echo ""
echo "=== Cleaning Old Backups ==="
find "$BACKUP_DIR" -name "codelens_*.gz" -mtime +7 -delete
echo "Removed backups older than 7 days"

# List backups
echo ""
echo "=== Current Backups ==="
ls -lh "$BACKUP_DIR"/*.gz 2>/dev/null || echo "No backups found"

echo ""
echo "Backup complete!"
