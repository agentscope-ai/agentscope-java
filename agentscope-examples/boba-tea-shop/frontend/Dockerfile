# Multi-stage Dockerfile for frontend Vue.js application
# 后端已配置 CORS，无需 Nginx 反向代理，使用最简单的静态服务器

# Stage 1: Build the Vue.js application
FROM node:20-alpine AS builder

# Set working directory
WORKDIR /app

# Copy package files
COPY package*.json ./

# Install dependencies with proxy support
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG http_proxy
ARG https_proxy
RUN npm config set registry https://registry.npmmirror.com && \
    npm install --legacy-peer-deps

# Copy source code
COPY . .

# Build the application (skip type-check to avoid TypeScript version issues)
RUN npm run build-only

# Stage 2: Serve with a lightweight static server
FROM node:20-alpine

# Set metadata labels
LABEL maintainer="AgentScope Team"
LABEL description="Frontend - Multi-Agent Demo"
LABEL version="1.0.0"

# Set working directory
WORKDIR /app

# Install serve globally (lightweight static file server)
RUN npm install -g serve@14.2.1

# Copy built files from builder stage
COPY --from=builder /app/dist ./dist

# Expose port
EXPOSE 3000

# Environment variable for API URL (can be overridden at runtime)
ENV API_BASE_URL=http://localhost:10008

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:3000 || exit 1

# Start serve
# -s: Single page application mode (handles SPA routing)
# -l: Port to listen on
# --no-port-switching: Don't try alternative ports
CMD ["serve", "-s", "dist", "-l", "3000", "--no-port-switching"]

