#!/bin/bash
# HiMarket Server Auto-Init with MCP Import
# Quick start script using local database and Nacos MCP import

set -e

echo "=========================================="
echo "HiMarket Server Auto-Init with MCP"
echo "=========================================="
echo ""

# Configuration variables
CONTAINER_NAME="himarket-server"
IMAGE_NAME="registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest"

# Check if container already exists
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Container ${CONTAINER_NAME} already exists, removing..."
    docker rm -f ${CONTAINER_NAME}
fi

echo "Using built-in MCP file (contains 5 preset MCP Servers)"
echo ""

# Start container
echo "Starting HiMarket Server container..."
docker run -d \
  --name ${CONTAINER_NAME} \
  -p 8080:8080 \
  \
  `# Database configuration (using local MySQL)` \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USER=root \
  -e DB_PASSWORD=yourpassword \
  \
  `# HiMarket basic configuration` \
  -e HIMARKET_HOST=localhost:8080 \
  -e HIMARKET_FRONTEND_URL=http://localhost:3000 \
  \
  `# Admin and developer accounts` \
  -e ADMIN_USERNAME=admin \
  -e ADMIN_PASSWORD=admin \
  -e DEVELOPER_USERNAME=demo \
  -e DEVELOPER_PASSWORD=demo123 \
  \
  `# Nacos configuration` \
  -e REGISTER_NACOS=true \
  -e NACOS_NAME=nacos-demo \
  -e NACOS_URL=http://your-nacos-host:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  \
  `# MCP import and publish configuration (using built-in file)` \
  -e IMPORT_MCP_TO_NACOS=true \
  -e PUBLISH_MCP_TO_HIMARKET=true \
  \
  ${IMAGE_NAME}

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ Container started successfully!"
    echo "=========================================="
    echo ""
    echo "Container name: ${CONTAINER_NAME}"
    echo "Service URL: http://localhost:8080"
    echo ""
    echo "[Admin account]"
    echo "  Username: admin"
    echo "  Password: admin"
    echo ""
    echo "[Developer account]"
    echo "  Username: demo"
    echo "  Password: demo123"
    echo ""
    echo "[Nacos configuration]"
    echo "  Name: nacos-demo"
    echo "  URL: http://your-nacos-host:8848"
    echo ""
    echo "[MCP configuration]"
    echo "  Using built-in file: /opt/himarket/data/nacos-mcp.json"
    echo "  Contains MCP Server: context7, git, Time, memory, fetch"
    echo "  Auto-published to HiMarket developer portal"
    echo ""
    echo "💡 Tip: To use custom MCP file, mount to override:"
    echo "  -v /path/to/your-mcp.json:/opt/himarket/data/nacos-mcp.json:ro"
    echo ""
    echo "=========================================="
    echo ""
    echo "📝 View logs:"
    echo "  docker logs -f ${CONTAINER_NAME}"
    echo ""
    echo "🛑 Stop container:"
    echo "  docker stop ${CONTAINER_NAME}"
    echo ""
    echo "🗑️  Remove container:"
    echo "  docker rm -f ${CONTAINER_NAME}"
    echo ""
else
    echo ""
    echo "❌ Container startup failed!"
    exit 1
fi

