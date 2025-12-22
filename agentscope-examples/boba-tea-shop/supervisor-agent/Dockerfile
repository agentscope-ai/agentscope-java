# Dockerfile for supervisor-agent (含前端)
# 前端静态文件由 Spring Boot 直接托管

# Stage 1: 构建前端
FROM node:20-alpine AS frontend-builder

WORKDIR /frontend

# 复制前端依赖配置
COPY frontend/package*.json ./

# 安装依赖
ARG HTTP_PROXY
ARG HTTPS_PROXY
RUN npm config set registry https://registry.npmmirror.com && \
    npm install --legacy-peer-deps

COPY frontend/ .

# 构建前端（输出到 dist/）
RUN npm run build-only

# Stage 2: 最终镜像（Java + 静态文件）
FROM eclipse-temurin:17-jre

LABEL maintainer="AgentScope Team"
LABEL description="Supervisor Agent + Frontend - Multi-Agent Demo"
LABEL version="1.0.0"

# 创建非 root 用户
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

# 复制预构建的 JAR
COPY supervisor-agent/target/supervisor-agent.jar app.jar

# 复制前端静态文件
RUN mkdir -p /app/static
COPY --from=frontend-builder /frontend/dist /app/static

# 修改所有权
RUN chown -R appuser:appgroup /app

USER appuser

# 只暴露后端端口（前端通过同一端口访问）
EXPOSE 10008

ENV SERVER_PORT=10008 \
    DB_HOST=localhost \
    DB_PORT=3306 \
    DB_NAME=multi-agent-demo \
    DB_USERNAME=multi_agent_demo \
    DB_PASSWORD=multi_agent_demo@321 \
    NACOS_SERVER_ADDR=127.0.0.1:8848 \
    NACOS_USERNAME=nacos \
    NACOS_PASSWORD=nacos \
    DASHSCOPE_MODEL=qwen-plus \
    XXL_JOB_ENABLED=false \
    XXL_JOB_LOG_PATH=/app/applogs/xxl-job/jobhandler \
    JAVA_OPTS="-Xms512m -Xmx1024m" \
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS="file:/app/static/"

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:${SERVER_PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
