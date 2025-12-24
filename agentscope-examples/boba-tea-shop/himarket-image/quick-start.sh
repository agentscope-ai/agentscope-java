#!/bin/bash
# Quick start HiMarket Server (connect to local database)

# Database configuration
DB_PASSWORD=""  # Change to your database password

# Commercial Nacos configuration
NACOS_URL=""  # Change to your Nacos URL
NACOS_ACCESS_KEY=""  # Change to your AccessKey
NACOS_SECRET_KEY=""     # Change to your SecretKey

# Start container
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

echo "Container started!"
echo "View logs: docker logs -f himarket-server"
echo "Access URL: http://localhost:8080"

