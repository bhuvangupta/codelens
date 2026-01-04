#!/bin/bash

# CodeLens Backend - Build and Deploy Script
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
BUILD_DIR="$PROJECT_DIR/target"
JAR_NAME="codelens-1.0.0-SNAPSHOT.jar"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/codelens}"

# Configuration
DOCKER_IMAGE="codelens-api"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-}"
SERVER_HOST="${SERVER_HOST:-}"
SERVER_USER="${SERVER_USER:-deploy}"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  CodeLens Backend - Build & Deploy${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "Environment: ${YELLOW}$ENVIRONMENT${NC}"
echo -e "Target: ${YELLOW}$TARGET${NC}"
echo ""

# Navigate to project directory
cd "$PROJECT_DIR"

# Check for Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Maven not found! Please install Maven.${NC}"
    exit 1
fi

# Check for Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Java not found! Please install Java 17+.${NC}"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo -e "${RED}Java 17+ required. Found: Java $JAVA_VERSION${NC}"
    exit 1
fi

# Step 1: Clean
echo -e "${BLUE}[1/5] Cleaning previous build...${NC}"
mvn clean -q

# Step 2: Run tests
echo -e "${BLUE}[2/5] Running tests...${NC}"
if [ "$ENVIRONMENT" == "prod" ]; then
    mvn test -q || {
        echo -e "${RED}Tests failed! Aborting deploy.${NC}"
        exit 1
    }
else
    echo -e "${YELLOW}Skipping tests for non-prod environment${NC}"
fi

# Step 3: Build
echo -e "${BLUE}[3/5] Building application...${NC}"
if [ "$ENVIRONMENT" == "prod" ]; then
    mvn package -DskipTests -Pprod -q
else
    mvn package -DskipTests -P$ENVIRONMENT -q
fi

# Verify build
if [ ! -f "$BUILD_DIR/$JAR_NAME" ]; then
    echo -e "${RED}Build failed! JAR not found.${NC}"
    exit 1
fi

echo -e "${GREEN}Build completed: $JAR_NAME${NC}"

# Step 4: Copy configuration
echo -e "${BLUE}[4/5] Preparing configuration...${NC}"
if [ -f "$PROJECT_DIR/.env.$ENVIRONMENT" ]; then
    cp "$PROJECT_DIR/.env.$ENVIRONMENT" "$BUILD_DIR/.env"
    echo "Using .env.$ENVIRONMENT"
elif [ -f "$PROJECT_DIR/.env" ]; then
    cp "$PROJECT_DIR/.env" "$BUILD_DIR/.env"
    echo "Using .env"
fi

# Step 5: Deploy based on target
echo -e "${BLUE}[5/5] Deploying to $TARGET...${NC}"

case $TARGET in
    local)
        echo -e "${GREEN}Build is ready at: $BUILD_DIR/$JAR_NAME${NC}"
        echo ""
        echo "To start the server locally:"
        echo "  java -jar $BUILD_DIR/$JAR_NAME --spring.profiles.active=$ENVIRONMENT"
        echo ""
        echo "Or use: ./scripts/run.sh $ENVIRONMENT"
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
            echo "  docker run -p 9292:9292 --env-file .env $DOCKER_IMAGE:$ENVIRONMENT"
        fi
        ;;

    server)
        if [ -z "$SERVER_HOST" ]; then
            echo -e "${RED}SERVER_HOST not set! Please set SERVER_HOST environment variable.${NC}"
            exit 1
        fi

        echo "Deploying to $SERVER_USER@$SERVER_HOST:$DEPLOY_DIR"

        # Copy JAR to server
        scp "$BUILD_DIR/$JAR_NAME" "$SERVER_USER@$SERVER_HOST:/tmp/"
        [ -f "$BUILD_DIR/.env" ] && scp "$BUILD_DIR/.env" "$SERVER_USER@$SERVER_HOST:/tmp/codelens.env"

        # Deploy on server
        ssh "$SERVER_USER@$SERVER_HOST" << EOF
            sudo mkdir -p $DEPLOY_DIR
            sudo systemctl stop codelens || true
            sudo cp /tmp/$JAR_NAME $DEPLOY_DIR/
            [ -f /tmp/codelens.env ] && sudo cp /tmp/codelens.env $DEPLOY_DIR/.env
            rm -f /tmp/$JAR_NAME /tmp/codelens.env

            # Create/update systemd service
            sudo tee /etc/systemd/system/codelens.service > /dev/null << 'SYSTEMD'
[Unit]
Description=CodeLens API Server
After=network.target mysql.service

[Service]
Type=simple
User=codelens
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java -jar $DEPLOY_DIR/$JAR_NAME --spring.profiles.active=$ENVIRONMENT
Restart=always
RestartSec=10
Environment=JAVA_OPTS=-Xmx512m

[Install]
WantedBy=multi-user.target
SYSTEMD

            sudo systemctl daemon-reload
            sudo systemctl enable codelens
            sudo systemctl start codelens

            # Wait for startup
            sleep 5
            sudo systemctl status codelens --no-pager
EOF

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
