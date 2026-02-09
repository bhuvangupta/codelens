#!/bin/bash

# CodeLens UI - Build and Deploy Script
# Usage: ./scripts/deploy.sh [environment] [target]
#
# Environments: dev, staging, prod (default: prod)
# Targets: local, docker, server (default: local)
#
# Examples:
#   ./scripts/deploy.sh                    # Build for prod, deploy locally
#   ./scripts/deploy.sh prod docker        # Build for prod, deploy via Docker
#   ./scripts/deploy.sh staging server     # Build for staging, deploy to server

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
ENVIRONMENT=${1:-prod}
TARGET=${2:-local}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_DIR/build"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/codelens-ui}"

# Configuration
DOCKER_IMAGE="codelens-ui"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-}"
SERVER_HOST="${SERVER_HOST:-}"
SERVER_USER="${SERVER_USER:-deploy}"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  CodeLens UI - Build & Deploy${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "Environment: ${YELLOW}$ENVIRONMENT${NC}"
echo -e "Target: ${YELLOW}$TARGET${NC}"
echo ""

# Navigate to project directory
cd "$PROJECT_DIR"

# Step 1: Install dependencies
echo -e "${BLUE}[1/5] Installing dependencies...${NC}"
npm ci --silent

# Step 2: Run checks
echo -e "${BLUE}[2/5] Running type checks...${NC}"
npm run check || {
    echo -e "${RED}Type check failed! Aborting deploy.${NC}"
    exit 1
}

# Step 3: Run linting
echo -e "${BLUE}[3/5] Running linter...${NC}"
npm run lint || {
    echo -e "${YELLOW}Lint warnings detected. Continuing...${NC}"
}

# Step 4: Build
echo -e "${BLUE}[4/5] Building for $ENVIRONMENT...${NC}"
if [ "$ENVIRONMENT" == "prod" ]; then
    NODE_ENV=production npm run build
else
    NODE_ENV=$ENVIRONMENT npm run build
fi

# Verify build
if [ ! -d "$BUILD_DIR" ]; then
    echo -e "${RED}Build failed! Build directory not found.${NC}"
    exit 1
fi

echo -e "${GREEN}Build completed successfully!${NC}"

# Step 5: Deploy based on target
echo -e "${BLUE}[5/5] Deploying to $TARGET...${NC}"

case $TARGET in
    local)
        echo -e "${GREEN}Build is ready at: $BUILD_DIR${NC}"
        echo ""
        echo "To start the server locally:"
        echo "  cd $BUILD_DIR && node ."
        echo ""
        echo "Or use: npm run start"
        ;;

    docker)
        echo "Building Docker image..."
        docker build -t "$DOCKER_IMAGE:$ENVIRONMENT" -t "$DOCKER_IMAGE:latest" .

        if [ -n "$DOCKER_REGISTRY" ]; then
            echo "Pushing to registry: $DOCKER_REGISTRY"
            docker tag "$DOCKER_IMAGE:$ENVIRONMENT" "$DOCKER_REGISTRY/$DOCKER_IMAGE:$ENVIRONMENT"
            docker tag "$DOCKER_IMAGE:latest" "$DOCKER_REGISTRY/$DOCKER_IMAGE:latest"
            docker push "$DOCKER_REGISTRY/$DOCKER_IMAGE:$ENVIRONMENT"
            docker push "$DOCKER_REGISTRY/$DOCKER_IMAGE:latest"
            echo -e "${GREEN}Pushed to $DOCKER_REGISTRY/$DOCKER_IMAGE${NC}"
        else
            echo -e "${GREEN}Docker image built: $DOCKER_IMAGE:$ENVIRONMENT${NC}"
            echo ""
            echo "To run:"
            echo "  docker run -p 5175:5175 --env-file .env $DOCKER_IMAGE:$ENVIRONMENT"
        fi
        ;;

    server)
        if [ -z "$SERVER_HOST" ]; then
            echo -e "${RED}SERVER_HOST not set! Please set SERVER_HOST environment variable.${NC}"
            exit 1
        fi

        echo "Deploying to $SERVER_USER@$SERVER_HOST:$DEPLOY_DIR"

        # Create tarball
        TARBALL="codelens-ui-$ENVIRONMENT.tar.gz"
        tar -czf "$TARBALL" -C "$BUILD_DIR" .

        # Copy to server
        scp "$TARBALL" "$SERVER_USER@$SERVER_HOST:/tmp/"

        # Deploy on server
        ssh "$SERVER_USER@$SERVER_HOST" << EOF
            sudo mkdir -p $DEPLOY_DIR
            sudo tar -xzf /tmp/$TARBALL -C $DEPLOY_DIR
            rm /tmp/$TARBALL

            # Restart service if systemd is available
            if command -v systemctl &> /dev/null; then
                sudo systemctl restart codelens-ui || echo "Service not configured"
            fi

            echo "Deployed successfully to $DEPLOY_DIR"
EOF

        # Cleanup local tarball
        rm "$TARBALL"

        echo -e "${GREEN}Deployed to $SERVER_HOST successfully!${NC}"
        ;;

    *)
        echo -e "${RED}Unknown target: $TARGET${NC}"
        echo "Valid targets: local, docker, server"
        exit 1
        ;;
esac

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Deployment Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
