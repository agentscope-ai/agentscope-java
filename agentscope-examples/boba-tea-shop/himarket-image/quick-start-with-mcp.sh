#!/bin/bash
# HiMarket Server Auto-Init with MCP Import
# 使用本地数据库和 Nacos MCP 导入的快速启动脚本

set -e

echo "=========================================="
echo "HiMarket Server Auto-Init with MCP"
echo "=========================================="
echo ""

# 配置变量
CONTAINER_NAME="himarket-server"
IMAGE_NAME="registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest"

# 检查是否已存在容器
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "容器 ${CONTAINER_NAME} 已存在，正在删除..."
    docker rm -f ${CONTAINER_NAME}
fi

echo "使用内置的 MCP 文件（包含 5 个预置 MCP Server）"
echo ""

# 启动容器
echo "启动 HiMarket Server 容器..."
docker run -d \
  --name ${CONTAINER_NAME} \
  -p 8080:8080 \
  \
  `# 数据库配置（使用本地 MySQL）` \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USER=root \
  -e DB_PASSWORD=yourpassword \
  \
  `# HiMarket 基础配置` \
  -e HIMARKET_HOST=localhost:8080 \
  -e HIMARKET_FRONTEND_URL=http://localhost:3000 \
  \
  `# 管理员和开发者账号` \
  -e ADMIN_USERNAME=admin \
  -e ADMIN_PASSWORD=admin \
  -e DEVELOPER_USERNAME=demo \
  -e DEVELOPER_PASSWORD=demo123 \
  \
  `# Nacos 配置` \
  -e REGISTER_NACOS=true \
  -e NACOS_NAME=nacos-demo \
  -e NACOS_URL=http://your-nacos-host:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  \
  `# MCP 导入和上架配置（使用内置文件）` \
  -e IMPORT_MCP_TO_NACOS=true \
  -e PUBLISH_MCP_TO_HIMARKET=true \
  \
  ${IMAGE_NAME}

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ 容器启动成功！"
    echo "=========================================="
    echo ""
    echo "容器名称: ${CONTAINER_NAME}"
    echo "服务地址: http://localhost:8080"
    echo ""
    echo "【管理员账号】"
    echo "  用户名: admin"
    echo "  密码: admin"
    echo ""
    echo "【开发者账号】"
    echo "  用户名: demo"
    echo "  密码: demo123"
    echo ""
    echo "【Nacos 配置】"
    echo "  名称: nacos-demo"
    echo "  地址: http://your-nacos-host:8848"
    echo ""
    echo "【MCP 配置】"
    echo "  使用内置文件: /opt/himarket/data/nacos-mcp.json"
    echo "  包含 MCP Server: context7, git, Time, memory, fetch"
    echo "  自动上架到 HiMarket 开发者门户"
    echo ""
    echo "💡 提示: 如需使用自定义 MCP 文件，可挂载覆盖:"
    echo "  -v /path/to/your-mcp.json:/opt/himarket/data/nacos-mcp.json:ro"
    echo ""
    echo "=========================================="
    echo ""
    echo "📝 查看日志:"
    echo "  docker logs -f ${CONTAINER_NAME}"
    echo ""
    echo "🛑 停止容器:"
    echo "  docker stop ${CONTAINER_NAME}"
    echo ""
    echo "🗑️  删除容器:"
    echo "  docker rm -f ${CONTAINER_NAME}"
    echo ""
else
    echo ""
    echo "❌ 容器启动失败！"
    exit 1
fi

