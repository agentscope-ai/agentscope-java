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

# Single-stage Dockerfile for business-mcp-server
# Using pre-built JAR from local Maven build
# Using a multi-arch compatible base image (supports amd64 and arm64)
FROM eclipse-temurin:17-jre

# Set metadata labels
LABEL maintainer="AgentScope Team"
LABEL description="Business MCP Server - Multi-Agent Demo"
LABEL version="1.0.0"

# Create a non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Set working directory
WORKDIR /app

# Copy pre-built JAR from local target directory
# JAR is built by Maven before Docker build
COPY target/business-mcp-server.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 10002

# Define environment variables with defaults
ENV SERVER_PORT=10002 \
    DB_HOST=localhost \
    DB_PORT=3306 \
    DB_NAME=multi-agent-demo \
    DB_USERNAME=multi_agent_demo \
    DB_PASSWORD=multi_agent_demo@321 \
    NACOS_SERVER_ADDR=127.0.0.1:8848 \
    NACOS_NAMESPACE=public \
    NACOS_REGISTER_ENABLED=true \
    JAVA_OPTS="-Xms512m -Xmx1024m"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:${SERVER_PORT}/actuator/health || exit 1

# Set entrypoint to run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
