#!/bin/bash
# =============================================
# MySQL 初始化脚本
# 使用 envsubst 处理环境变量替换
# =============================================

set -e

# 设置默认值（如果环境变量未设置）
export DB_NAME="${DB_NAME:-multi-agent-demo}"
export DB_USERNAME="${DB_USERNAME:-multi_agent_demo}"
export DB_PASSWORD="${DB_PASSWORD:-multi_agent_demo@321}"

echo "Initializing database with:"
echo "  DB_NAME: ${DB_NAME}"
echo "  DB_USERNAME: ${DB_USERNAME}"
echo "  DB_PASSWORD: ********"

# 使用 envsubst 处理模板并执行 SQL
envsubst '${DB_NAME} ${DB_USERNAME} ${DB_PASSWORD}' < /docker-entrypoint-initdb.d/init.sql.template > /tmp/init.sql

# 执行生成的 SQL 文件
mysql -u root -p"${MYSQL_ROOT_PASSWORD}" < /tmp/init.sql

# 清理临时文件
rm -f /tmp/init.sql

echo "Database initialization completed successfully!"

