#!/bin/bash
#
# CodeLens Environment Setup Script
# Creates the .env file with all required configuration
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/codelens-be"
FRONTEND_DIR="$ROOT_DIR/codelens-fe"

echo "========================================"
echo "  CodeLens Environment Setup"
echo "========================================"
echo ""

# Function to prompt for input with default
prompt_with_default() {
    local prompt="$1"
    local default="$2"
    local var_name="$3"
    local is_secret="$4"

    if [ "$is_secret" = "true" ]; then
        read -sp "$prompt [$default]: " value
        echo ""
    else
        read -p "$prompt [$default]: " value
    fi

    if [ -z "$value" ]; then
        value="$default"
    fi

    eval "$var_name='$value'"
}

# Database configuration
echo "=== Database Configuration ==="
prompt_with_default "MySQL Host" "localhost" DB_HOST
prompt_with_default "MySQL Port" "3306" DB_PORT
prompt_with_default "MySQL Database" "codelens" DB_NAME
prompt_with_default "MySQL Username" "codelens" DB_USERNAME
prompt_with_default "MySQL Password" "" DB_PASSWORD true

# LLM Providers
echo ""
echo "=== LLM Provider Configuration ==="
echo "Configure at least one LLM provider. Press Enter to skip."
echo ""

prompt_with_default "GLM API Key (ZhipuAI)" "" GLM_API_KEY true
prompt_with_default "Anthropic API Key (Claude)" "" ANTHROPIC_API_KEY true
prompt_with_default "Google API Key (Gemini)" "" GOOGLE_API_KEY true
prompt_with_default "OpenAI API Key" "" OPENAI_API_KEY true
prompt_with_default "Ollama Base URL" "http://localhost:11434" OLLAMA_BASE_URL

# Default LLM provider
echo ""
echo "Select default LLM provider:"
echo "  1) glm (ZhipuAI GLM)"
echo "  2) claude (Anthropic)"
echo "  3) gemini (Google)"
echo "  4) openai (OpenAI)"
echo "  5) ollama (Local)"
read -p "Enter choice [1]: " LLM_CHOICE

case $LLM_CHOICE in
    2) DEFAULT_LLM="claude" ;;
    3) DEFAULT_LLM="gemini" ;;
    4) DEFAULT_LLM="openai" ;;
    5) DEFAULT_LLM="ollama" ;;
    *) DEFAULT_LLM="glm" ;;
esac

# Git Providers
echo ""
echo "=== Git Provider Configuration ==="
echo "Configure at least one Git provider. Press Enter to skip."
echo ""

prompt_with_default "GitHub Personal Access Token" "" GITHUB_TOKEN true
prompt_with_default "GitHub Webhook Secret" "" GITHUB_WEBHOOK_SECRET true

prompt_with_default "GitLab Personal Access Token" "" GITLAB_TOKEN true
prompt_with_default "GitLab URL" "https://gitlab.com" GITLAB_URL
prompt_with_default "GitLab Webhook Secret" "" GITLAB_WEBHOOK_SECRET true

# Server configuration
echo ""
echo "=== Server Configuration ==="
prompt_with_default "Backend Port" "9292" SERVER_PORT
prompt_with_default "Frontend Port" "5174" FRONTEND_PORT

# OAuth configuration
echo ""
echo "=== OAuth Configuration ==="
prompt_with_default "Google OAuth Client ID" "" GOOGLE_CLIENT_ID
prompt_with_default "Google OAuth Client Secret" "" GOOGLE_CLIENT_SECRET true

# Generate random JWT_SECRET
JWT_SECRET=$(openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64)

# Generate backend .env file
cat > "$BACKEND_DIR/.env" << EOF
# CodeLens Backend Environment Configuration
# Generated on $(date)

# Database
DB_URL=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}

# LLM Providers
LLM_DEFAULT_PROVIDER=${DEFAULT_LLM}
ZAI_API_KEY=${GLM_API_KEY}
ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
GOOGLE_API_KEY=${GOOGLE_API_KEY}
OPENAI_API_KEY=${OPENAI_API_KEY}
OLLAMA_BASE_URL=${OLLAMA_BASE_URL}

# Git Providers
GITHUB_TOKEN=${GITHUB_TOKEN}
GITHUB_WEBHOOK_SECRET=${GITHUB_WEBHOOK_SECRET}
GITLAB_TOKEN=${GITLAB_TOKEN}
GITLAB_URL=${GITLAB_URL}
GITLAB_WEBHOOK_SECRET=${GITLAB_WEBHOOK_SECRET}

# OAuth
GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
JWT_SECRET=${JWT_SECRET}
OAUTH2_REDIRECT_URL=http://localhost:${FRONTEND_PORT}/auth/callback
OAUTH2_CALLBACK_URL=http://localhost:${FRONTEND_PORT}/auth/callback/google
GITHUB_OAUTH_CALLBACK_URL=http://localhost:${FRONTEND_PORT}/auth/callback/github

# Server
SERVER_PORT=${SERVER_PORT}
SPRING_PROFILES_ACTIVE=dev
EOF

echo ""
echo "Backend .env file created at: $BACKEND_DIR/.env"

# Frontend configuration
echo ""
echo "=== Frontend Backend URLs ==="
echo "For SSR (server-side), use localhost. For client (browser), use LAN IP if accessing externally."
prompt_with_default "Backend URL for SSR (internal)" "http://localhost:${SERVER_PORT}" SSR_BACKEND_URL
prompt_with_default "Backend URL for Client (external)" "http://localhost:${SERVER_PORT}" CLIENT_BACKEND_URL

if [ -d "$FRONTEND_DIR" ]; then
    cat > "$FRONTEND_DIR/.env" << EOF
# CodeLens Frontend Environment Configuration
# Generated on $(date)

# Server
PORT=${FRONTEND_PORT}
HOST=0.0.0.0
NODE_ENV=development

# Backend API - SSR (server-to-server, used by SvelteKit proxy)
BACKEND_URL=${SSR_BACKEND_URL}

# Backend API - Client (browser direct calls - WebSockets, uploads)
PUBLIC_API_URL=${CLIENT_BACKEND_URL}

# Login Providers
PUBLIC_GOOGLE_LOGIN_ENABLED=true
PUBLIC_GITHUB_LOGIN_ENABLED=false
EOF

    echo "Frontend .env file created at: $FRONTEND_DIR/.env"
fi

echo ""
echo "========================================"
echo "  Setup Complete!"
echo "========================================"
echo ""
echo "Next steps:"
echo "  1. Review and adjust the .env files as needed"
echo "  2. Create the MySQL database if not exists"
echo "  3. Run: ./deploy/start-backend.sh"
echo ""
