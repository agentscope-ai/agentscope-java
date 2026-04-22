#!/bin/bash

set -e

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Default values
IMAGE_NAME="mysql"
VERSION="8.0.30"
DOCKER_REGISTRY=""
PUSH_IMAGE=false
PLATFORM=""

# Log functions
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Build the MySQL image with initialization SQL.

OPTIONS:
    -v, --version VERSION       Specify the image version tag (default: 8.0.30)
    -r, --registry REGISTRY     Specify the Docker registry
    -p, --platform PLATFORM     Specify target platform (e.g., linux/amd64, linux/arm64)
    --push                      Push image to registry after build
    -h, --help                  Display this help message

EXAMPLES:
    $0                                          # Build with defaults
    $0 -v 8.0.30                                # Build with custom version
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
        --push) PUSH_IMAGE=true; shift ;;
        -h|--help) usage ;;
        *) log_error "Unknown option: $1"; usage ;;
    esac
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

log_info "Starting MySQL image build process..."

# Build image tag
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

docker build \
    $PLATFORM_ARG \
    -f "$SCRIPT_DIR/Dockerfile" \
    -t "$IMAGE_TAG" \
    .

log_success "Docker image built: $IMAGE_TAG"

if [ "$PUSH_IMAGE" = true ]; then
    if [ -z "$DOCKER_REGISTRY" ]; then
        log_error "Cannot push: --push requires -r/--registry to be set"
        exit 1
    fi
    log_info "Pushing image to registry..."
    docker push "$IMAGE_TAG"
    log_success "Image pushed to registry"
fi

echo ""
log_success "Build completed!"
log_info "Image: $IMAGE_TAG"
log_success "Done!"
