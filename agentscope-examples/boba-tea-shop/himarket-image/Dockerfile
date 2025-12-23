# Copyright 2024-2025 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM --platform=linux/amd64 opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server:latest

# 安装必要的工具
USER root
RUN if command -v apt-get &> /dev/null; then \
        apt-get update && apt-get install -y curl jq && apt-get clean && rm -rf /var/lib/apt/lists/*; \
    elif command -v yum &> /dev/null; then \
        yum install -y curl jq && yum clean all; \
    elif command -v dnf &> /dev/null; then \
        dnf install -y curl jq && dnf clean all; \
    elif command -v apk &> /dev/null; then \
        apk add --no-cache curl jq; \
    else \
        echo "No package manager found!" && exit 1; \
    fi

# 创建初始化脚本目录
RUN mkdir -p /opt/himarket

# 复制初始化脚本
COPY init-himarket-local.sh /opt/himarket/init-himarket.sh
RUN chmod +x /opt/himarket/init-himarket.sh

# 复制启动脚本
COPY entrypoint.sh /opt/himarket/entrypoint.sh
RUN chmod +x /opt/himarket/entrypoint.sh

# 复制预置的 MCP 数据文件
COPY nacos-mcp.json /opt/himarket/data/nacos-mcp.json

# 设置自动初始化相关的环境变量
# 注意：JAVA_OPTS 已由基础镜像定义，不要覆盖
ENV HIMARKET_HOST="localhost:8080" \
    HIMARKET_FRONTEND_URL="http://localhost:3000" \
    ADMIN_USERNAME="admin" \
    ADMIN_PASSWORD="admin" \
    DEVELOPER_USERNAME="demo" \
    DEVELOPER_PASSWORD="demo123" \
    PORTAL_NAME="demo" \
    REGISTER_NACOS="false" \
    REGISTER_GATEWAY="false" \
    IMPORT_MCP_TO_NACOS="false" \
    PUBLISH_MCP_TO_HIMARKET="true" \
    MCP_JSON_FILE="/opt/himarket/data/nacos-mcp.json" \
    GATEWAY_TYPE="HIGRESS" \
    AUTO_INIT="true" \
    INIT_DELAY="10"

# 暴露端口（继承自基础镜像，这里显式声明）
EXPOSE 8080

# 覆盖基础镜像的 ENTRYPOINT，使用自定义启动脚本
# 自定义脚本会先启动 Java 应用，再执行初始化
ENTRYPOINT ["/opt/himarket/entrypoint.sh"]

