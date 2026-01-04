#!/bin/bash
#
# CodeLens Production Deployment Script
# Deploys CodeLens to a production server
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "========================================"
echo "  CodeLens Production Deployment"
echo "========================================"
echo ""

# Configuration
DEPLOY_USER="${DEPLOY_USER:-codelens}"
DEPLOY_HOST="${DEPLOY_HOST:-}"
DEPLOY_PATH="${DEPLOY_PATH:-/opt/codelens}"

# Parse arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --host) DEPLOY_HOST="$2"; shift ;;
        --user) DEPLOY_USER="$2"; shift ;;
        --path) DEPLOY_PATH="$2"; shift ;;
        --local) LOCAL_DEPLOY=true ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  --host HOST   Remote host to deploy to"
            echo "  --user USER   SSH user (default: codelens)"
            echo "  --path PATH   Deployment path (default: /opt/codelens)"
            echo "  --local       Deploy locally instead of remote"
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
    shift
done

# Build first
echo "=== Building Application ==="
"$SCRIPT_DIR/build.sh" --skip-tests

if [ "$LOCAL_DEPLOY" = true ]; then
    # Local deployment
    echo ""
    echo "=== Local Deployment ==="

    # Stop existing services
    "$SCRIPT_DIR/stop.sh" 2>/dev/null || true

    # Start in production mode
    "$SCRIPT_DIR/start.sh" --production

else
    # Remote deployment
    if [ -z "$DEPLOY_HOST" ]; then
        echo "Error: --host is required for remote deployment"
        echo "Use --local for local deployment"
        exit 1
    fi

    echo ""
    echo "=== Deploying to $DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_PATH ==="

    # Create deployment package
    echo "Creating deployment package..."
    DEPLOY_PACKAGE="/tmp/codelens-deploy-$(date +%Y%m%d%H%M%S).tar.gz"

    tar -czf "$DEPLOY_PACKAGE" \
        -C "$ROOT_DIR" \
        dist/ \
        deploy/ \
        .env \
        --exclude='*.log' \
        --exclude='pids/*'

    # Upload to server
    echo "Uploading to server..."
    scp "$DEPLOY_PACKAGE" "$DEPLOY_USER@$DEPLOY_HOST:/tmp/"

    # Deploy on server
    echo "Deploying on server..."
    ssh "$DEPLOY_USER@$DEPLOY_HOST" << EOF
        set -e

        # Create deployment directory
        sudo mkdir -p $DEPLOY_PATH
        sudo chown $DEPLOY_USER:$DEPLOY_USER $DEPLOY_PATH

        # Stop existing services
        if [ -f "$DEPLOY_PATH/deploy/stop.sh" ]; then
            $DEPLOY_PATH/deploy/stop.sh || true
        fi

        # Backup current deployment
        if [ -d "$DEPLOY_PATH/dist" ]; then
            mv $DEPLOY_PATH/dist $DEPLOY_PATH/dist.backup.\$(date +%Y%m%d%H%M%S)
        fi

        # Extract new deployment
        tar -xzf /tmp/$(basename $DEPLOY_PACKAGE) -C $DEPLOY_PATH

        # Clean up old backups (keep last 3)
        ls -dt $DEPLOY_PATH/dist.backup.* 2>/dev/null | tail -n +4 | xargs rm -rf 2>/dev/null || true

        # Make scripts executable
        chmod +x $DEPLOY_PATH/deploy/*.sh

        # Start services
        $DEPLOY_PATH/deploy/start.sh --production

        # Clean up
        rm /tmp/$(basename $DEPLOY_PACKAGE)
EOF

    # Clean up local package
    rm "$DEPLOY_PACKAGE"

    echo ""
    echo "Deployment complete!"
fi

echo ""
echo "========================================"
echo "  Deployment Successful!"
echo "========================================"
