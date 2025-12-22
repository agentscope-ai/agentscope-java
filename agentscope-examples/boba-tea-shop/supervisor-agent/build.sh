#!/bin/bash

set -e

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Default values
IMAGE_NAME="supervisor-agent"
VERSION="test"
DOCKER_REGISTRY=""
SKIP_TESTS=true
SKIP_BUILD=false
SKIP_DOCKER=false
PUSH_IMAGE=false
PLATFORM=""

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

usage() {
    cat << EOF
Usage: $0 [OPTIONS]

构建 supervisor-agent 镜像（含前端）。
前端静态文件由 Spring Boot 直接托管，统一通过端口 10008 访问。

OPTIONS:
    -v, --version VERSION       镜像版本标签 (默认: test)
    -r, --registry REGISTRY     Docker 镜像仓库
    -p, --platform PLATFORM     目标平台 (如 linux/amd64, linux/arm64)
    -t, --run-tests             Maven 构建时运行测试
    --skip-build                跳过 Maven 构建（使用已有 JAR）
    --skip-docker               跳过 Docker 镜像构建
    --push                      构建后推送到仓库
    -h, --help                  显示帮助信息

EXAMPLES:
    $0                                  # 使用默认配置构建
    $0 -v 1.0.0                         # 指定版本
    $0 -p linux/amd64                   # 指定平台
    $0 -r myregistry.com/myapp --push   # 构建并推送

EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--version) VERSION="$2"; shift 2 ;;
        -r|--registry) DOCKER_REGISTRY="$2"; shift 2 ;;
        -p|--platform) PLATFORM="$2"; shift 2 ;;
        -t|--run-tests) SKIP_TESTS=false; shift ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --skip-docker) SKIP_DOCKER=true; shift ;;
        --push) PUSH_IMAGE=true; shift ;;
        -h|--help) usage ;;
        *) log_error "Unknown option: $1"; usage ;;
    esac
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"

log_info "构建 supervisor-agent 镜像（含前端）..."

# Phase 1: Maven Build
if [ "$SKIP_BUILD" = false ]; then
    log_info "=== Phase 1: Maven Build ==="
    cd "$SCRIPT_DIR"
    
    if [ "$SKIP_TESTS" = true ]; then
        log_info "Building with Maven (skipping tests)..."
        mvn clean package -DskipTests -B -U
    else
        log_info "Building with Maven (running tests)..."
        mvn clean package -B -U
    fi
    
    log_success "Maven build completed"
else
    log_warning "Skipping Maven build (using existing JAR)"
fi

# Phase 2: Docker Image Build
if [ "$SKIP_DOCKER" = false ]; then
    log_info "=== Phase 2: Docker Image Build ==="
    
    if [ -n "$DOCKER_REGISTRY" ]; then
        IMAGE_TAG="${DOCKER_REGISTRY}/${IMAGE_NAME}:${VERSION}"
    else
        IMAGE_TAG="${IMAGE_NAME}:${VERSION}"
    fi
    
    log_info "Building Docker image: $IMAGE_TAG"
    
    PLATFORM_ARG=""
    if [ -n "$PLATFORM" ]; then
        PLATFORM_ARG="--platform $PLATFORM"
        log_info "Target platform: $PLATFORM"
    fi
    
    # 从项目根目录构建（需要访问 frontend 目录）
    cd "$PROJECT_DIR"
    
    docker build \
        $PLATFORM_ARG \
        -f "$SCRIPT_DIR/Dockerfile" \
        -t "$IMAGE_TAG" \
        --build-arg VERSION="$VERSION" \
        .
    
    log_success "Docker image built: $IMAGE_TAG"
    
    if [ "$PUSH_IMAGE" = true ]; then
        if [ -z "$DOCKER_REGISTRY" ]; then
            log_error "Cannot push: --push requires -r/--registry"
            exit 1
        fi
        log_info "Pushing image to registry..."
        docker push "$IMAGE_TAG"
        log_success "Image pushed"
    fi
    
    echo ""
    log_success "Build completed!"
    log_info "Image: $IMAGE_TAG"
    log_info "前端和 API 统一通过端口 10008 访问"
else
    log_warning "Skipping Docker image build"
fi

log_success "Done!"
