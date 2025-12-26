#!/bin/bash
set -e

echo "=========================================="
echo "HiMarket Server with Auto-Init"
echo "=========================================="
echo ""

# Start HiMarket Server
# Use startup command and configuration from base image
echo "[$(date +'%H:%M:%S')] Starting HiMarket Server..."

# Switch to working directory
cd /app

# Start HiMarket Server in background
# Use JAVA_OPTS and logging configuration defined by base image
java $JAVA_OPTS -jar app.jar --logging.file.name=/app/logs/himarket-server.log &

SERVER_PID=$!
echo "[$(date +'%H:%M:%S')] HiMarket Server process PID: ${SERVER_PID}"

# Wait for service startup
echo "[$(date +'%H:%M:%S')] Waiting for HiMarket Server to start (${INIT_DELAY} seconds)..."
sleep ${INIT_DELAY}

# Check if service started successfully
MAX_WAIT=60
WAIT_COUNT=0
while ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; do
    if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
        echo "[ERROR] HiMarket Server startup timeout"
        exit 1
    fi
    echo "[$(date +'%H:%M:%S')] Waiting for HiMarket Server to be ready... ($WAIT_COUNT/$MAX_WAIT)"
    sleep 2
    WAIT_COUNT=$((WAIT_COUNT + 2))
done

echo "[✓] HiMarket Server is ready"
echo ""

# Execute initialization script (if enabled)
if [ "$AUTO_INIT" = "true" ]; then
    echo "[$(date +'%H:%M:%S')] Starting auto-initialization..."
    cd /opt/himarket
    
    # Export environment variables for script use
    export HIMARKET_HOST="${HIMARKET_HOST}"
    export HIMARKET_FRONTEND_URL="${HIMARKET_FRONTEND_URL}"
    export ADMIN_USERNAME="${ADMIN_USERNAME}"
    export ADMIN_PASSWORD="${ADMIN_PASSWORD}"
    export DEVELOPER_USERNAME="${DEVELOPER_USERNAME}"
    export DEVELOPER_PASSWORD="${DEVELOPER_PASSWORD}"
    export PORTAL_NAME="${PORTAL_NAME}"
    export REGISTER_NACOS="${REGISTER_NACOS}"
    export REGISTER_GATEWAY="${REGISTER_GATEWAY}"
    export IMPORT_MCP_TO_NACOS="${IMPORT_MCP_TO_NACOS}"
    export PUBLISH_MCP_TO_HIMARKET="${PUBLISH_MCP_TO_HIMARKET}"
    export MCP_JSON_FILE="${MCP_JSON_FILE}"
    
    # Nacos configuration
    export NACOS_NAME="${NACOS_NAME:-}"
    export NACOS_URL="${NACOS_URL:-}"
    export NACOS_USERNAME="${NACOS_USERNAME:-}"
    export NACOS_PASSWORD="${NACOS_PASSWORD:-}"
    export NACOS_ACCESS_KEY="${NACOS_ACCESS_KEY:-}"
    export NACOS_SECRET_KEY="${NACOS_SECRET_KEY:-}"
    
    # Gateway configuration
    export GATEWAY_NAME="${GATEWAY_NAME:-}"
    export GATEWAY_TYPE="${GATEWAY_TYPE}"
    export GATEWAY_URL="${GATEWAY_URL:-}"
    export GATEWAY_USERNAME="${GATEWAY_USERNAME:-}"
    export GATEWAY_PASSWORD="${GATEWAY_PASSWORD:-}"
    export APIG_REGION="${APIG_REGION:-}"
    export APIG_ACCESS_KEY="${APIG_ACCESS_KEY:-}"
    export APIG_SECRET_KEY="${APIG_SECRET_KEY:-}"
    
    # Execute initialization script
    if ./init-himarket.sh; then
        echo ""
        echo "[✓] Auto-initialization completed!"
    else
        echo ""
        echo "[WARNING] Auto-initialization failed, but service will continue running"
    fi
else
    echo "[$(date +'%H:%M:%S')] Skipping auto-initialization (AUTO_INIT=false)"
fi

echo ""
echo "=========================================="
echo "[$(date +'%H:%M:%S')] HiMarket Server running..."
echo "=========================================="

# Keep container running, monitor main process
wait $SERVER_PID

