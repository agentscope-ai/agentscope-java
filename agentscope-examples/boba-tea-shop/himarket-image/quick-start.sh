#!/bin/bash
# 快速启动 HiMarket Server（连接本地数据库）

# 配置数据库信息
DB_PASSWORD=""  # 修改为你的数据库密码

# 配置商业化 Nacos
NACOS_URL=""  # 修改为你的 Nacos 地址
NACOS_ACCESS_KEY=""  # 修改为你的 AccessKey
NACOS_SECRET_KEY=""     # 修改为你的 SecretKey

# 启动容器
docker run -d \
  --platform linux/amd64 \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=${DB_PASSWORD} \
  -e REGISTER_NACOS=true \
  -e NACOS_URL=${NACOS_URL} \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=a057f808-35bf-42b2-be85-be839cfc4369 \
  -e IMPORT_MCP_TO_NACOS=true \
  registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest

echo "容器已启动！"
echo "查看日志: docker logs -f himarket-server"
echo "访问地址: http://localhost:8080"

