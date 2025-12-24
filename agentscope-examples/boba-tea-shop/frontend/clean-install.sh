#!/bin/bash

# Clean and reinstall dependencies script

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Main function
main() {
    echo ""
    log_info "Cleaning and reinstalling dependencies..."
    echo ""
    
    # Check package.json
    if [ ! -f "package.json" ]; then
        log_error "package.json file does not exist, please make sure to run this script in the frontend directory"
        exit 1
    fi
    
    # Clean up
    log_info "Cleaning old dependencies..."
    if [ -d "node_modules" ]; then
        rm -rf node_modules
        log_success "Deleted node_modules"
    fi
    
    if [ -f "package-lock.json" ]; then
        rm -f package-lock.json
        log_success "Deleted package-lock.json"
    fi
    
    echo ""
    log_info "Starting dependency installation..."
    log_info "This may take a few minutes, please wait..."
    echo ""
    
    # Try to install
    if npm install; then
        log_success "Dependencies installed successfully ✓"
    else
        log_warning "Normal installation failed, trying with --legacy-peer-deps..."
        if npm install --legacy-peer-deps; then
            log_success "Dependencies installed successfully (using --legacy-peer-deps) ✓"
        else
            log_error "Dependency installation failed"
            log_info "Please check the error message and manually resolve dependency conflicts"
            exit 1
        fi
    fi
    
    echo ""
    log_success "Clean and install completed!"
    log_info "You can now run ./start.sh to start the service"
}

# Execute main function
main "$@"

