# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
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

# Install required tools
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

# Create initialization script directory
RUN mkdir -p /opt/himarket

# Copy initialization script
COPY init-himarket-local.sh /opt/himarket/init-himarket.sh
RUN chmod +x /opt/himarket/init-himarket.sh

# Copy startup script
COPY entrypoint.sh /opt/himarket/entrypoint.sh
RUN chmod +x /opt/himarket/entrypoint.sh

# Copy preset MCP data file
COPY nacos-mcp.json /opt/himarket/data/nacos-mcp.json

# Set auto-initialization related environment variables
# Note: JAVA_OPTS is already defined by base image, do not override
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

# Expose port (inherited from base image, explicitly declared here)
EXPOSE 8080

# Override base image ENTRYPOINT, use custom startup script
# Custom script starts Java application first, then executes initialization
ENTRYPOINT ["/opt/himarket/entrypoint.sh"]

