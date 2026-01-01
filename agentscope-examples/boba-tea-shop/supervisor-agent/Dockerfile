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

# Dockerfile for supervisor-agent (with frontend)
# Frontend static files are served directly by Spring Boot

# Stage 1: Build frontend
FROM node:20-alpine AS frontend-builder

WORKDIR /frontend

# Copy frontend dependency configuration
COPY frontend/package*.json ./

# Install dependencies
ARG HTTP_PROXY
ARG HTTPS_PROXY
RUN npm config set registry https://registry.npmmirror.com && \
    npm install --legacy-peer-deps

COPY frontend/ .

# Build frontend (output to dist/)
RUN npm run build-only

# Stage 2: Final image (Java + static files)
FROM eclipse-temurin:17-jre

LABEL maintainer="AgentScope Team"
LABEL description="Supervisor Agent + Frontend - Multi-Agent Demo"
LABEL version="1.0.0"

# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

# Copy pre-built JAR
COPY supervisor-agent/target/supervisor-agent.jar app.jar

# Copy frontend static files
RUN mkdir -p /app/static
COPY --from=frontend-builder /frontend/dist /app/static

# Change ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Only expose backend port (frontend accessed via same port)
EXPOSE 10008

ENV SERVER_PORT=10008 \
    DB_HOST=localhost \
    DB_PORT=3306 \
    DB_NAME=multi-agent-demo \
    DB_USERNAME=multi_agent_demo \
    DB_PASSWORD=multi_agent_demo@321 \
    NACOS_SERVER_ADDR=127.0.0.1:8848 \
    NACOS_NAMESPACE=public \
    DASHSCOPE_MODEL=qwen-plus \
    XXL_JOB_ENABLED=false \
    XXL_JOB_LOG_PATH=/app/applogs/xxl-job/jobhandler \
    JAVA_OPTS="-Xms512m -Xmx1024m" \
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS="file:/app/static/"

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:${SERVER_PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
