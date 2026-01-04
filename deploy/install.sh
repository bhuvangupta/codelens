#!/bin/bash
#
# CodeLens Installation Script
# Sets up CodeLens on a fresh server
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/codelens-be"
FRONTEND_DIR="$ROOT_DIR/codelens-fe"

echo "========================================"
echo "  CodeLens Installation Script"
echo "========================================"
echo ""

# Detect OS
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
else
    OS=$(uname -s)
fi

echo "Detected OS: $OS"
echo ""

# Function to check if a command exists
command_exists() {
    command -v "$1" &> /dev/null
}

# Check and install Java
echo "=== Checking Java ==="
if command_exists java; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -ge 17 ]; then
        echo "✅ Java $JAVA_VERSION found"
    else
        echo "❌ Java 17+ required, found Java $JAVA_VERSION"
        exit 1
    fi
else
    echo "❌ Java not found"
    echo ""
    echo "Install Java 17+:"
    case $OS in
        ubuntu|debian)
            echo "  sudo apt update && sudo apt install openjdk-17-jdk"
            ;;
        centos|rhel|fedora)
            echo "  sudo dnf install java-17-openjdk-devel"
            ;;
        darwin)
            echo "  brew install openjdk@17"
            ;;
        *)
            echo "  Please install Java 17+ manually"
            ;;
    esac
    exit 1
fi

# Check and install Node.js
echo ""
echo "=== Checking Node.js ==="
if command_exists node; then
    NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
    if [ "$NODE_VERSION" -ge 18 ]; then
        echo "✅ Node.js $NODE_VERSION found"
    else
        echo "❌ Node.js 18+ required, found Node.js $NODE_VERSION"
        exit 1
    fi
else
    echo "❌ Node.js not found"
    echo ""
    echo "Install Node.js 18+:"
    echo "  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -"
    echo "  sudo apt install -y nodejs"
    exit 1
fi

# Check MySQL
echo ""
echo "=== Checking MySQL ==="
if command_exists mysql; then
    echo "✅ MySQL client found"
else
    echo "⚠️  MySQL client not found (optional for remote DB)"
fi

# Install backend dependencies
echo ""
echo "=== Installing Backend Dependencies ==="
cd "$BACKEND_DIR"
if [ ! -f "mvnw" ]; then
    echo "❌ Maven wrapper not found"
    exit 1
fi
chmod +x mvnw
./mvnw dependency:resolve -q
echo "✅ Backend dependencies installed"

# Install frontend dependencies
echo ""
echo "=== Installing Frontend Dependencies ==="
if [ -d "$FRONTEND_DIR" ]; then
    cd "$FRONTEND_DIR"
    npm install
    echo "✅ Frontend dependencies installed"
else
    echo "⚠️  Frontend directory not found at $FRONTEND_DIR"
fi

# Create directories
echo ""
echo "=== Creating Directories ==="
mkdir -p "$BACKEND_DIR/logs"
mkdir -p "$BACKEND_DIR/pids"
mkdir -p "$BACKEND_DIR/dist"
echo "✅ Directories created"

# Make scripts executable
echo ""
echo "=== Setting Permissions ==="
chmod +x "$SCRIPT_DIR"/*.sh
echo "✅ Scripts are executable"

echo ""
echo "========================================"
echo "  Installation Complete!"
echo "========================================"
echo ""
echo "Next steps:"
echo "  1. Run environment setup: ./deploy/setup-env.sh"
echo "  2. Create MySQL database (see ONBOARDING.md)"
echo "  3. Start services: ./deploy/start.sh"
echo ""
