#!/bin/bash
set -e

echo "=========================================="
echo "HiMarket Server with Auto-Init"
echo "=========================================="
echo ""

# 启动 HiMarket Server
# 使用基础镜像的启动命令和配置
echo "[$(date +'%H:%M:%S')] 启动 HiMarket Server..."

# 切换到工作目录
cd /app

# 在后台启动 HiMarket Server
# 使用基础镜像定义的 JAVA_OPTS 和日志配置
java $JAVA_OPTS -jar app.jar --logging.file.name=/app/logs/himarket-server.log &

SERVER_PID=$!
echo "[$(date +'%H:%M:%S')] HiMarket Server 进程 PID: ${SERVER_PID}"

# 等待服务启动
echo "[$(date +'%H:%M:%S')] 等待 HiMarket Server 启动（${INIT_DELAY}秒）..."
sleep ${INIT_DELAY}

# 检查服务是否启动成功
MAX_WAIT=60
WAIT_COUNT=0
while ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; do
    if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
        echo "[ERROR] HiMarket Server 启动超时"
        exit 1
    fi
    echo "[$(date +'%H:%M:%S')] 等待 HiMarket Server 就绪... ($WAIT_COUNT/$MAX_WAIT)"
    sleep 2
    WAIT_COUNT=$((WAIT_COUNT + 2))
done

echo "[✓] HiMarket Server 已就绪"
echo ""

# 执行初始化脚本（如果启用）
if [ "$AUTO_INIT" = "true" ]; then
    echo "[$(date +'%H:%M:%S')] 开始执行自动初始化..."
    cd /opt/himarket
    
    # 导出环境变量供脚本使用
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
    
    # Nacos 配置
    export NACOS_NAME="${NACOS_NAME:-}"
    export NACOS_URL="${NACOS_URL:-}"
    export NACOS_USERNAME="${NACOS_USERNAME:-}"
    export NACOS_PASSWORD="${NACOS_PASSWORD:-}"
    export NACOS_ACCESS_KEY="${NACOS_ACCESS_KEY:-}"
    export NACOS_SECRET_KEY="${NACOS_SECRET_KEY:-}"
    
    # 网关配置
    export GATEWAY_NAME="${GATEWAY_NAME:-}"
    export GATEWAY_TYPE="${GATEWAY_TYPE}"
    export GATEWAY_URL="${GATEWAY_URL:-}"
    export GATEWAY_USERNAME="${GATEWAY_USERNAME:-}"
    export GATEWAY_PASSWORD="${GATEWAY_PASSWORD:-}"
    export APIG_REGION="${APIG_REGION:-}"
    export APIG_ACCESS_KEY="${APIG_ACCESS_KEY:-}"
    export APIG_SECRET_KEY="${APIG_SECRET_KEY:-}"
    
    # 执行初始化脚本
    if ./init-himarket.sh; then
        echo ""
        echo "[✓] 自动初始化完成！"
    else
        echo ""
        echo "[WARNING] 自动初始化失败，但服务将继续运行"
    fi
else
    echo "[$(date +'%H:%M:%S')] 跳过自动初始化 (AUTO_INIT=false)"
fi

echo ""
echo "=========================================="
echo "[$(date +'%H:%M:%S')] HiMarket Server 运行中..."
echo "=========================================="

# 保持容器运行，监控主进程
wait $SERVER_PID

