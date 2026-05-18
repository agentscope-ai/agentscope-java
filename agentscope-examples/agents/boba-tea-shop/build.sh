#!/bin/bash

set -e

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Default values
VERSION="test"
DOCKER_REGISTRY=""
PLATFORM=""
SKIP_BUILD=false
SKIP_DOCKER=false
PUSH_IMAGE=false
SKIP_TESTS=true
MODULES="default"

# Module definitions
DEFAULT_MODULES="supervisor-agent business-mcp-server business-sub-agent consult-sub-agent"
EXTRA_MODULES="mysql-image nacos-image"
ALL_MODULES="$DEFAULT_MODULES $EXTRA_MODULES"

# Log functions
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Build boba-tea-shop modules.

DEFAULT MODULES (built by default):
    supervisor-agent (含前端), business-mcp-server, business-sub-agent, 
    consult-sub-agent

EXTRA MODULES (must be specified explicitly):
    mysql-image, nacos-image

OPTIONS:
    -v, --version VERSION       Specify the image version tag (default: test)
    -r, --registry REGISTRY     Specify the Docker registry
    -p, --platform PLATFORM     Specify target platform (e.g., linux/amd64, linux/arm64)
    -t, --run-tests             Run tests during Maven build (default: skip tests)
    --skip-build                Skip source code build (Maven/npm)
    --skip-docker               Skip Docker image build
    --push                      Push images to registry after build
    -m, --modules MODULES       Comma-separated list of modules to build
                                Special values: 
                                  default - build default modules only (default behavior)
                                  all     - build all modules including extras
                                Or specify individual modules
    -h, --help                  Display this help message

EXAMPLES:
    $0                                          # Build default modules
    $0 -v 1.0.0                                 # Build default modules with version 1.0.0
    $0 -m all                                   # Build all modules including extras
    $0 -m supervisor-agent,frontend             # Build only specific modules
    $0 -m default,mysql-image                   # Build default modules + mysql-image
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
        -m|--modules) MODULES="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) log_error "Unknown option: $1"; usage ;;
    esac
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Determine which modules to build
BUILD_MODULES=""
for MOD in $(echo "$MODULES" | tr ',' ' '); do
    case "$MOD" in
        default) BUILD_MODULES="$BUILD_MODULES $DEFAULT_MODULES" ;;
        all) BUILD_MODULES="$BUILD_MODULES $ALL_MODULES" ;;
        *) BUILD_MODULES="$BUILD_MODULES $MOD" ;;
    esac
done
# Remove leading space and deduplicate
BUILD_MODULES=$(echo "$BUILD_MODULES" | xargs -n1 | sort -u | xargs)

# Build common arguments (for all modules)
COMMON_ARGS=""
[ -n "$VERSION" ] && COMMON_ARGS="$COMMON_ARGS -v $VERSION"
[ -n "$DOCKER_REGISTRY" ] && COMMON_ARGS="$COMMON_ARGS -r $DOCKER_REGISTRY"
[ -n "$PLATFORM" ] && COMMON_ARGS="$COMMON_ARGS -p $PLATFORM"
[ "$PUSH_IMAGE" = true ] && COMMON_ARGS="$COMMON_ARGS --push"

# Extra arguments for app modules (with source build)
APP_EXTRA_ARGS=""
[ "$SKIP_BUILD" = true ] && APP_EXTRA_ARGS="$APP_EXTRA_ARGS --skip-build"
[ "$SKIP_DOCKER" = true ] && APP_EXTRA_ARGS="$APP_EXTRA_ARGS --skip-docker"
[ "$SKIP_TESTS" = false ] && APP_EXTRA_ARGS="$APP_EXTRA_ARGS -t"

# Modules that are Docker-only (no source build)
DOCKER_ONLY_MODULES="mysql-image nacos-image"

echo ""
log_info "========================================="
log_info "Boba Tea Shop - Build All Modules"
log_info "========================================="
echo ""
log_info "Version: $VERSION"
log_info "Registry: ${DOCKER_REGISTRY:-<none>}"
log_info "Platform: ${PLATFORM:-<default>}"
log_info "Modules: $BUILD_MODULES"
log_info "Skip Build: $SKIP_BUILD"
log_info "Skip Docker: $SKIP_DOCKER"
log_info "Push: $PUSH_IMAGE"
log_info "Run Tests: $([ "$SKIP_TESTS" = false ] && echo "yes" || echo "no")"
echo ""

# Track build results
FAILED_MODULES=""
SUCCESS_MODULES=""

# Build each module
for MODULE in $BUILD_MODULES; do
    MODULE_DIR="$SCRIPT_DIR/$MODULE"
    
    if [ ! -d "$MODULE_DIR" ]; then
        log_warning "Module directory not found: $MODULE_DIR, skipping..."
        continue
    fi
    
    if [ ! -f "$MODULE_DIR/build.sh" ]; then
        log_warning "build.sh not found in $MODULE_DIR, skipping..."
        continue
    fi
    
    echo ""
    log_info "========================================="
    log_info "Building: $MODULE"
    log_info "========================================="
    echo ""
    
    cd "$MODULE_DIR"
    
    # Determine build arguments based on module type
    BUILD_ARGS="$COMMON_ARGS"
    if ! echo "$DOCKER_ONLY_MODULES" | grep -qw "$MODULE"; then
        # App module with source build support
        BUILD_ARGS="$BUILD_ARGS $APP_EXTRA_ARGS"
    fi
    
    if bash build.sh $BUILD_ARGS; then
        SUCCESS_MODULES="$SUCCESS_MODULES $MODULE"
        log_success "[$MODULE] Build completed successfully"
    else
        FAILED_MODULES="$FAILED_MODULES $MODULE"
        log_error "[$MODULE] Build failed"
    fi
    
    cd "$SCRIPT_DIR"
done

# Print summary
echo ""
log_info "========================================="
log_info "Build Summary"
log_info "========================================="
echo ""

if [ -n "$SUCCESS_MODULES" ]; then
    log_success "Successful builds:$SUCCESS_MODULES"
fi

if [ -n "$FAILED_MODULES" ]; then
    log_error "Failed builds:$FAILED_MODULES"
    echo ""
    exit 1
fi

echo ""
log_success "All modules built successfully!"
log_success "Done!"

