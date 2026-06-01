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


Build and dockerize the supervisor-agent.

OPTIONS:
    -v, --version VERSION       Specify the image version tag (default: test)
    -r, --registry REGISTRY     Specify the Docker registry
    -p, --platform PLATFORM     Specify target platform (e.g., linux/amd64, linux/arm64)
    -t, --run-tests             Run tests during Maven build (default: skip tests)
    --skip-build                Skip Maven build (use existing JAR)
    --skip-docker               Skip Docker image build
    --push                      Push image to registry after build
    -h, --help                  Display this help message

EXAMPLES:
    $0                                          # Build with defaults
    $0 -v 1.0.1                                 # Build with custom version
    $0 -p linux/amd64                           # Build for specific platform
    $0 -r myregistry.com/myapp --push           # Build and push to registry

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

log_info "Build and dockerize the supervisor-agent..."

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
else
    log_warning "Skipping Docker image build"
fi

log_success "Done!"
