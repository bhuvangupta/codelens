#!/bin/bash
# Setup script to create an admin user for CodeLens
# Usage: ./setup-admin.sh <email> [org_name]

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Load .env file if it exists
ENV_FILE="$PROJECT_ROOT/.env"
if [ -f "$ENV_FILE" ]; then
    echo -e "${YELLOW}Loading configuration from $ENV_FILE${NC}"
    # Export variables from .env file (ignore comments and empty lines)
    set -a
    source <(grep -v '^#' "$ENV_FILE" | grep -v '^$' | sed 's/\r$//')
    set +a
fi

# Parse DB_URL if provided (JDBC format: jdbc:mysql://host:port/database?params)
if [ -n "$DB_URL" ]; then
    # Extract host, port, database from JDBC URL
    # Example: jdbc:mysql://localhost:3306/codelens?useSSL=false
    DB_URL_PARSED=$(echo "$DB_URL" | sed -n 's|jdbc:mysql://\([^:]*\):\([0-9]*\)/\([^?]*\).*|\1 \2 \3|p')
    if [ -n "$DB_URL_PARSED" ]; then
        DB_HOST_FROM_URL=$(echo "$DB_URL_PARSED" | awk '{print $1}')
        DB_PORT_FROM_URL=$(echo "$DB_URL_PARSED" | awk '{print $2}')
        DB_NAME_FROM_URL=$(echo "$DB_URL_PARSED" | awk '{print $3}')
    fi
fi

# Database connection (from parsed URL, env vars, or defaults)
DB_HOST="${DB_HOST:-${DB_HOST_FROM_URL:-${MYSQL_HOST:-localhost}}}"
DB_PORT="${DB_PORT:-${DB_PORT_FROM_URL:-${MYSQL_PORT:-3306}}}"
DB_NAME="${DB_NAME:-${DB_NAME_FROM_URL:-${MYSQL_DATABASE:-codelens}}}"
DB_USER="${DB_USER:-${DB_USERNAME:-${MYSQL_USER:-root}}}"
DB_PASSWORD="${DB_PASSWORD:-${MYSQL_PASSWORD:-}}"

usage() {
    echo "Usage: $0 <email> [org_name]"
    echo ""
    echo "Creates or updates a user as ADMIN for the specified organization."
    echo ""
    echo "Arguments:"
    echo "  email      Email address of the admin user (required)"
    echo "  org_name   Organization name (optional, extracted from email domain if not provided)"
    echo ""
    echo "Configuration:"
    echo "  The script automatically loads database settings from .env file in the project root."
    echo "  Supported .env variables:"
    echo ""
    echo "  DB_URL        JDBC URL (e.g., jdbc:mysql://localhost:3306/codelens)"
    echo "  DB_USERNAME   Database user"
    echo "  DB_PASSWORD   Database password"
    echo ""
    echo "  Or override with environment variables:"
    echo "  DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD"
    echo ""
    echo "Examples:"
    echo "  $0 admin@acme.com                    # Uses .env for DB config"
    echo "  $0 admin@acme.com acme-corp          # With explicit org name"
    echo "  DB_PASSWORD=secret $0 admin@acme.com # Override password"
    exit 1
}

# Check arguments
if [ -z "$1" ]; then
    echo -e "${RED}Error: Email is required${NC}"
    usage
fi

EMAIL="$1"
ORG_NAME="$2"

# Extract org name from email domain if not provided
if [ -z "$ORG_NAME" ]; then
    # Get domain part and extract first segment (e.g., acme from acme.com)
    DOMAIN=$(echo "$EMAIL" | cut -d'@' -f2)
    ORG_NAME=$(echo "$DOMAIN" | cut -d'.' -f1)
    echo -e "${YELLOW}No org name provided, using '$ORG_NAME' from email domain${NC}"
fi

echo ""
echo "=== CodeLens Admin Setup ==="
echo "Email:        $EMAIL"
echo "Organization: $ORG_NAME"
echo "Database:     $DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
echo ""

# Build MySQL command
MYSQL_CMD="mysql -h $DB_HOST -P $DB_PORT -u $DB_USER"
if [ -n "$DB_PASSWORD" ]; then
    MYSQL_CMD="$MYSQL_CMD -p$DB_PASSWORD"
fi
MYSQL_CMD="$MYSQL_CMD $DB_NAME"

# Check if MySQL is accessible
echo "Checking database connection..."
if ! $MYSQL_CMD -e "SELECT 1" > /dev/null 2>&1; then
    echo -e "${RED}Error: Cannot connect to MySQL database${NC}"
    echo "Please check your database connection settings."
    exit 1
fi
echo -e "${GREEN}Database connection OK${NC}"

# Create or get organization
echo ""
echo "Setting up organization '$ORG_NAME'..."

ORG_EXISTS=$($MYSQL_CMD -N -e "SELECT COUNT(*) FROM organizations WHERE name = '$ORG_NAME'")

if [ "$ORG_EXISTS" -eq 0 ]; then
    echo "Creating new organization..."
    ORG_SLUG=$(echo "$ORG_NAME" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g')
    $MYSQL_CMD -e "
        INSERT INTO organizations (id, name, slug, auto_review_enabled, post_comments_enabled, security_scan_enabled, static_analysis_enabled, created_at, updated_at)
        VALUES (UUID_TO_BIN(UUID()), '$ORG_NAME', '$ORG_SLUG', true, true, true, true, NOW(), NOW())
    "
    echo -e "${GREEN}Organization '$ORG_NAME' created${NC}"
else
    echo -e "${YELLOW}Organization '$ORG_NAME' already exists${NC}"
fi

# Get organization ID (as hex for display, binary for queries)
ORG_ID_HEX=$($MYSQL_CMD -N -e "SELECT HEX(id) FROM organizations WHERE name = '$ORG_NAME'")
echo "Organization ID: $ORG_ID_HEX"

# Create or update user
echo ""
echo "Setting up admin user '$EMAIL'..."

USER_EXISTS=$($MYSQL_CMD -N -e "SELECT COUNT(*) FROM users WHERE email = '$EMAIL'")

if [ "$USER_EXISTS" -eq 0 ]; then
    echo "Creating new admin user..."
    USER_NAME=$(echo "$EMAIL" | cut -d'@' -f1 | sed 's/[._-]/ /g' | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) tolower(substr($i,2))}1')
    $MYSQL_CMD -e "
        INSERT INTO users (id, email, name, provider, provider_id, role, organization_id, created_at, updated_at)
        VALUES (UUID_TO_BIN(UUID()), '$EMAIL', '$USER_NAME', 'pending', CONCAT('pending-', UUID()), 'ADMIN', UNHEX('$ORG_ID_HEX'), NOW(), NOW())
    "
    echo -e "${GREEN}Admin user '$EMAIL' created${NC}"
else
    echo "Updating existing user to ADMIN..."
    $MYSQL_CMD -e "
        UPDATE users
        SET role = 'ADMIN', organization_id = UNHEX('$ORG_ID_HEX'), updated_at = NOW()
        WHERE email = '$EMAIL'
    "
    echo -e "${GREEN}User '$EMAIL' updated to ADMIN${NC}"
fi

# Verify setup
echo ""
echo "=== Verification ==="
$MYSQL_CMD -e "
    SELECT u.email, u.name, u.role, o.name as organization
    FROM users u
    LEFT JOIN organizations o ON u.organization_id = o.id
    WHERE u.email = '$EMAIL'
"

echo ""
echo -e "${GREEN}Setup complete!${NC}"
echo ""
echo "The user can now log in with Google OAuth using $EMAIL"
echo "and will have ADMIN access to the '$ORG_NAME' organization."
