#!/bin/bash
# =============================================================================
# AgentScope Boba Tea Shop - Local Deployment Script
# =============================================================================
# 
# This script deploys the multi-agent system services in local environment
# 
# ============================= Prerequisites =============================
# 
# [MySQL] Must be deployed beforehand, obtain the following info:
#   - Host address (DB_HOST): e.g., localhost
#   - Port (DB_PORT): default 3306
#   - Database name (DB_NAME): e.g., multi_agent_demo
#   - Username (DB_USERNAME)
#   - Password (DB_PASSWORD)
# 
# [Nacos] Must be deployed beforehand, obtain the following info:
#   - Server address (NACOS_SERVER_ADDR): e.g., localhost:8848
#   - Namespace (NACOS_NAMESPACE): e.g., public
# 
# =============================================================================

set -e

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"

# Create log directory
mkdir -p "${LOG_DIR}"

# =============================================================================
# Color Definitions
# =============================================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# Environment Variables Configuration (modify according to your setup)
# =============================================================================

# ------------ MySQL Configuration (from deployed MySQL) ------------
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-3306}"
export DB_NAME="${DB_NAME:-multi_agent_demo}"
export DB_USERNAME="${DB_USERNAME:-multi_agent_demo}"
export DB_PASSWORD="${DB_PASSWORD:-multi_agent_demo@321}"

# ------------ Nacos Configuration (from deployed Nacos) ------------
export NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR:-localhost:8848}"
export NACOS_NAMESPACE="${NACOS_NAMESPACE:-public}"
export NACOS_REGISTER_ENABLED="${NACOS_REGISTER_ENABLED:-true}"

# ------------ LLM Model Configuration ------------
export MODEL_PROVIDER="${MODEL_PROVIDER:-dashscope}"  # dashscope or openai
export MODEL_API_KEY="${MODEL_API_KEY:-}"             # ⚠️ Required
export MODEL_NAME="${MODEL_NAME:-qwen-max}"
export MODEL_BASE_URL="${MODEL_BASE_URL:-}"           # For OpenAI-compatible API

# ------------ DashScope RAG Configuration ------------
export DASHSCOPE_ACCESS_KEY_ID="${DASHSCOPE_ACCESS_KEY_ID:-}"           # ⚠️ Required
export DASHSCOPE_ACCESS_KEY_SECRET="${DASHSCOPE_ACCESS_KEY_SECRET:-}"   # ⚠️ Required
export DASHSCOPE_WORKSPACE_ID="${DASHSCOPE_WORKSPACE_ID:-}"             # ⚠️ Required
export DASHSCOPE_INDEX_ID="${DASHSCOPE_INDEX_ID:-}"                     # ⚠️ Required

# ------------ Mem0 Memory Service Configuration ------------
export MEM0_API_KEY="${MEM0_API_KEY:-}"               # ⚠️ Required

# ------------ XXL-JOB Task Scheduling Configuration (optional) ------------
export XXL_JOB_ENABLED="${XXL_JOB_ENABLED:-false}"
export XXL_JOB_ADMIN="${XXL_JOB_ADMIN:-http://localhost:28080/xxl-job-admin}"
export XXL_JOB_ACCESS_TOKEN="${XXL_JOB_ACCESS_TOKEN:-default_token}"
export XXL_JOB_APPNAME="${XXL_JOB_APPNAME:-multi-agent-demo}"

# ------------ Service Port Configuration ------------
export BUSINESS_MCP_SERVER_PORT="${BUSINESS_MCP_SERVER_PORT:-10002}"
export CONSULT_SUB_AGENT_PORT="${CONSULT_SUB_AGENT_PORT:-10005}"
export BUSINESS_SUB_AGENT_PORT="${BUSINESS_SUB_AGENT_PORT:-10006}"
export SUPERVISOR_AGENT_PORT="${SUPERVISOR_AGENT_PORT:-10008}"

# =============================================================================
# Helper Functions
# =============================================================================

print_banner() {
    echo -e "${BLUE}"
    echo "=============================================="
    echo "   AgentScope Boba Tea Shop - Local Deploy"
    echo "=============================================="
    echo -e "${NC}"
}

print_step() {
    echo -e "${GREEN}[STEP]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

# Check required environment variables
check_required_env() {
    local missing=()
    
    if [ -z "${MODEL_API_KEY}" ]; then
        missing+=("MODEL_API_KEY")
    fi
    
    if [ -z "${DASHSCOPE_ACCESS_KEY_ID}" ]; then
        missing+=("DASHSCOPE_ACCESS_KEY_ID")
    fi
    
    if [ -z "${DASHSCOPE_ACCESS_KEY_SECRET}" ]; then
        missing+=("DASHSCOPE_ACCESS_KEY_SECRET")
    fi
    
    if [ -z "${DASHSCOPE_WORKSPACE_ID}" ]; then
        missing+=("DASHSCOPE_WORKSPACE_ID")
    fi
    
    if [ -z "${DASHSCOPE_INDEX_ID}" ]; then
        missing+=("DASHSCOPE_INDEX_ID")
    fi
    
    if [ -z "${MEM0_API_KEY}" ]; then
        missing+=("MEM0_API_KEY")
    fi
    
    if [ ${#missing[@]} -gt 0 ]; then
        print_error "The following required environment variables are not configured:"
        for var in "${missing[@]}"; do
            echo "  - ${var}"
        done
        echo ""
        echo "Please configure them as follows:"
        echo "  export MODEL_API_KEY=your_api_key"
        echo "  export DASHSCOPE_ACCESS_KEY_ID=your_access_key_id"
        echo "  export DASHSCOPE_ACCESS_KEY_SECRET=your_access_key_secret"
        echo "  export DASHSCOPE_WORKSPACE_ID=your_workspace_id"
        echo "  export DASHSCOPE_INDEX_ID=your_index_id"
        echo "  export MEM0_API_KEY=your_mem0_key"
        echo ""
        echo "Or create a .env file and run: source .env && ./local-deploy.sh"
        exit 1
    fi
}

# Check Java environment
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java not found, please install JDK 17+"
        exit 1
    fi
    
    java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "${java_version}" -lt 17 ]; then
        print_error "Java version too low, requires JDK 17+, current version: ${java_version}"
        exit 1
    fi
    print_info "Java version check passed: $(java -version 2>&1 | head -n 1)"
}


# Check service dependencies connectivity
check_dependencies() {
    print_step "Checking service dependencies connectivity..."
    
    # Check MySQL
    print_info "Checking MySQL connection (${DB_HOST}:${DB_PORT})..."
    if ! nc -z "${DB_HOST}" "${DB_PORT}" 2>/dev/null; then
        print_warn "Cannot connect to MySQL (${DB_HOST}:${DB_PORT}), please ensure MySQL is running"
    else
        print_success "MySQL connection OK"
    fi
    
    # Check Nacos
    NACOS_HOST=$(echo "${NACOS_SERVER_ADDR}" | cut -d: -f1)
    NACOS_PORT=$(echo "${NACOS_SERVER_ADDR}" | cut -d: -f2)
    print_info "Checking Nacos connection (${NACOS_HOST}:${NACOS_PORT})..."
    if ! nc -z "${NACOS_HOST}" "${NACOS_PORT}" 2>/dev/null; then
        print_warn "Cannot connect to Nacos (${NACOS_HOST}:${NACOS_PORT}), please ensure Nacos is running"
    else
        print_success "Nacos connection OK"
    fi
}

# Build frontend
build_frontend() {
    print_step "Building frontend..."
    
    cd "${SCRIPT_DIR}/frontend"
    
    if ! command -v node &> /dev/null; then
        print_warn "Node.js not found, skipping frontend build"
        print_info "Frontend will not be accessible via supervisor-agent"
        cd "${SCRIPT_DIR}"
        return 1
    fi
    
    if [ ! -d "node_modules" ]; then
        print_info "Installing frontend dependencies..."
        npm install --legacy-peer-deps
    fi
    
    # Clean old build artifacts
    print_info "Cleaning old frontend build..."
    rm -rf dist
    
    print_info "Building frontend application..."
    npm run build-only
    
    # Clean and copy to supervisor-agent static resources directory
    local static_dir="${SCRIPT_DIR}/supervisor-agent/src/main/resources/static"
    print_info "Cleaning old static resources directory..."
    rm -rf "${static_dir}"
    mkdir -p "${static_dir}"
    cp -r dist/* "${static_dir}/"
    
    print_success "Frontend build complete, copied to ${static_dir}"
    cd "${SCRIPT_DIR}"
    return 0
}

# Build Maven project
build_maven() {
    print_step "Building Maven project..."
    cd "${SCRIPT_DIR}"
    
    if [ ! -f "pom.xml" ]; then
        print_error "pom.xml file not found"
        exit 1
    fi
    
    # Check if frontend static files are copied
    local static_index="${SCRIPT_DIR}/supervisor-agent/src/main/resources/static/index.html"
    if [ ! -f "${static_index}" ]; then
        print_warn "Frontend static files not found, JAR will not include frontend"
    else
        print_info "Frontend static files ready: ${static_index}"
    fi
    
    # Build all submodules from boba-tea-shop directory
    print_info "Building all submodules..."
    mvn clean package -DskipTests
    
    # Verify JAR files are generated
    local all_jars_found=true
    for module in supervisor-agent business-mcp-server business-sub-agent consult-sub-agent; do
        if [ ! -f "${SCRIPT_DIR}/${module}/target/${module}.jar" ]; then
            print_error "${module}.jar not found"
            all_jars_found=false
        fi
    done
    
    if [ "$all_jars_found" = false ]; then
        print_error "Maven build failed, some JAR files not generated"
        exit 1
    fi
    
    # Verify supervisor-agent JAR contains frontend static files
    if unzip -l "${SCRIPT_DIR}/supervisor-agent/target/supervisor-agent.jar" | grep -q "static/index.html"; then
        print_success "Maven project build complete (includes frontend static files)"
    else
        print_warn "Maven project build complete, but frontend static files not found in JAR"
    fi
}

# Start Java service
start_java_service() {
    local service_name=$1
    local jar_path=$2
    local port=$3
    local extra_opts=${4:-""}
    
    print_step "Starting ${service_name} (port: ${port})..."
    
    if [ ! -f "${jar_path}" ]; then
        print_error "JAR file not found: ${jar_path}"
        print_info "Please build first: ./local-deploy.sh build"
        return 1
    fi
    
    # Check if port is in use
    if lsof -i ":${port}" &>/dev/null; then
        print_warn "Port ${port} is already in use, skipping ${service_name}"
        return 0
    fi
    
    local log_file="${LOG_DIR}/${service_name}.log"
    
    # Build Java startup parameters
    local java_opts="-Xms512m -Xmx2048m"
    java_opts="${java_opts} -DSERVER_PORT=${port}"
    java_opts="${java_opts} -DMODEL_PROVIDER=${MODEL_PROVIDER}"
    java_opts="${java_opts} -DMODEL_API_KEY=${MODEL_API_KEY}"
    java_opts="${java_opts} -DMODEL_NAME=${MODEL_NAME}"
    [ -n "${MODEL_BASE_URL}" ] && java_opts="${java_opts} -DMODEL_BASE_URL=${MODEL_BASE_URL}"
    java_opts="${java_opts} -DDASHSCOPE_ACCESS_KEY_ID=${DASHSCOPE_ACCESS_KEY_ID}"
    java_opts="${java_opts} -DDASHSCOPE_ACCESS_KEY_SECRET=${DASHSCOPE_ACCESS_KEY_SECRET}"
    java_opts="${java_opts} -DDASHSCOPE_WORKSPACE_ID=${DASHSCOPE_WORKSPACE_ID}"
    java_opts="${java_opts} -DDASHSCOPE_INDEX_ID=${DASHSCOPE_INDEX_ID}"
    java_opts="${java_opts} -DDB_HOST=${DB_HOST}"
    java_opts="${java_opts} -DDB_PORT=${DB_PORT}"
    java_opts="${java_opts} -DDB_NAME=${DB_NAME}"
    java_opts="${java_opts} -DDB_USERNAME=${DB_USERNAME}"
    java_opts="${java_opts} -DDB_PASSWORD=${DB_PASSWORD}"
    java_opts="${java_opts} -DMEM0_API_KEY=${MEM0_API_KEY}"
    java_opts="${java_opts} -DNACOS_SERVER_ADDR=${NACOS_SERVER_ADDR}"
    java_opts="${java_opts} -DNACOS_NAMESPACE=${NACOS_NAMESPACE}"
    java_opts="${java_opts} -DNACOS_REGISTER_ENABLED=${NACOS_REGISTER_ENABLED}"
    java_opts="${java_opts} ${extra_opts}"
    
    # Start service
    nohup java ${java_opts} -jar "${jar_path}" > "${log_file}" 2>&1 &
    local pid=$!
    echo "${pid}" > "${LOG_DIR}/${service_name}.pid"
    
    print_success "${service_name} started (PID: ${pid}), log: ${log_file}"
}

# Stop all services
stop_all() {
    print_step "Stopping all services..."
    
    local services=("business-mcp-server" "consult-sub-agent" "business-sub-agent" "supervisor-agent")
    
    for service in "${services[@]}"; do
        local pid_file="${LOG_DIR}/${service}.pid"
        if [ -f "${pid_file}" ]; then
            local pid=$(cat "${pid_file}")
            if kill -0 "${pid}" 2>/dev/null; then
                kill "${pid}" 2>/dev/null || true
                print_info "Stopped ${service} (PID: ${pid})"
            fi
            rm -f "${pid_file}"
        fi
    done
    
    print_success "All services stopped"
}

# Show service status
show_status() {
    print_step "Service status..."
    echo ""
    printf "%-25s %-10s %-15s\n" "Service Name" "Port" "Status"
    echo "------------------------------------------------"
    
    local services=(
        "business-mcp-server:${BUSINESS_MCP_SERVER_PORT}"
        "consult-sub-agent:${CONSULT_SUB_AGENT_PORT}"
        "business-sub-agent:${BUSINESS_SUB_AGENT_PORT}"
        "supervisor-agent:${SUPERVISOR_AGENT_PORT}"
    )
    
    for service_info in "${services[@]}"; do
        local service=$(echo "${service_info}" | cut -d: -f1)
        local port=$(echo "${service_info}" | cut -d: -f2)
        local status="Not running"
        
        local pid_file="${LOG_DIR}/${service}.pid"
        if [ -f "${pid_file}" ]; then
            local pid=$(cat "${pid_file}")
            if kill -0 "${pid}" 2>/dev/null; then
                status="${GREEN}Running (PID: ${pid})${NC}"
            fi
        fi
        
        printf "%-25s %-10s %b\n" "${service}" "${port}" "${status}"
    done
    echo ""
}

# Show configuration info
show_config() {
    echo ""
    echo "=============================================="
    echo "Current Configuration"
    echo "=============================================="
    echo ""
    echo "[Database Configuration]"
    echo "  DB_HOST:     ${DB_HOST}"
    echo "  DB_PORT:     ${DB_PORT}"
    echo "  DB_NAME:     ${DB_NAME}"
    echo "  DB_USERNAME: ${DB_USERNAME}"
    echo "  DB_PASSWORD: ******"
    echo ""
    echo "[Nacos Configuration]"
    echo "  NACOS_SERVER_ADDR:      ${NACOS_SERVER_ADDR}"
    echo "  NACOS_NAMESPACE:        ${NACOS_NAMESPACE}"
    echo "  NACOS_REGISTER_ENABLED: ${NACOS_REGISTER_ENABLED}"
    echo ""
    echo "[Model Configuration]"
    echo "  MODEL_PROVIDER:  ${MODEL_PROVIDER}"
    echo "  MODEL_NAME:      ${MODEL_NAME}"
    echo "  MODEL_API_KEY:   ${MODEL_API_KEY:+******}"
    echo "  MODEL_BASE_URL:  ${MODEL_BASE_URL:-Not configured}"
    echo ""
    echo "[DashScope RAG Configuration]"
    echo "  DASHSCOPE_ACCESS_KEY_ID:     ${DASHSCOPE_ACCESS_KEY_ID:+******}"
    echo "  DASHSCOPE_ACCESS_KEY_SECRET: ${DASHSCOPE_ACCESS_KEY_SECRET:+******}"
    echo "  DASHSCOPE_WORKSPACE_ID:      ${DASHSCOPE_WORKSPACE_ID:-Not configured}"
    echo "  DASHSCOPE_INDEX_ID:          ${DASHSCOPE_INDEX_ID:-Not configured}"
    echo ""
    echo "[Mem0 Configuration]"
    echo "  MEM0_API_KEY: ${MEM0_API_KEY:+******}"
    echo ""
    echo "[XXL-JOB Configuration]"
    echo "  XXL_JOB_ENABLED: ${XXL_JOB_ENABLED}"
    if [ "${XXL_JOB_ENABLED}" = "true" ]; then
        echo "  XXL_JOB_ADMIN:        ${XXL_JOB_ADMIN}"
        echo "  XXL_JOB_ACCESS_TOKEN: ******"
        echo "  XXL_JOB_APPNAME:      ${XXL_JOB_APPNAME}"
    fi
    echo ""
    echo "[Service Ports]"
    echo "  business-mcp-server: ${BUSINESS_MCP_SERVER_PORT}"
    echo "  consult-sub-agent:   ${CONSULT_SUB_AGENT_PORT}"
    echo "  business-sub-agent:  ${BUSINESS_SUB_AGENT_PORT}"
    echo "  supervisor-agent:    ${SUPERVISOR_AGENT_PORT} (with frontend)"
    echo ""
}

# Show help information
show_help() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  start       Build and start all services (default)"
    echo "  build       Build project only"
    echo "  stop        Stop all services"
    echo "  restart     Restart all services"
    echo "  status      Show service status"
    echo "  config      Show current configuration"
    echo "  logs        Show log directory"
    echo "  help        Show this help message"
    echo ""
    echo "Examples:"
    echo "  # Configure environment variables and start"
    echo "  export MODEL_API_KEY=your_key"
    echo "  export DASHSCOPE_ACCESS_KEY_ID=your_access_key_id"
    echo "  export DASHSCOPE_ACCESS_KEY_SECRET=your_access_key_secret"
    echo "  export DASHSCOPE_WORKSPACE_ID=your_workspace_id"
    echo "  export DASHSCOPE_INDEX_ID=your_index_id"
    echo "  export MEM0_API_KEY=your_key"
    echo "  ./local-deploy.sh start"
    echo ""
    echo "  # Or use .env file"
    echo "  source .env && ./local-deploy.sh start"
    echo ""
}

# Start all backend services
start_backend_services() {
    # Build XXL-JOB extra parameters (only for supervisor-agent)
    local xxl_job_opts=""
    if [ "${XXL_JOB_ENABLED}" = "true" ]; then
        xxl_job_opts="-DXXL_JOB_ENABLED=true"
        xxl_job_opts="${xxl_job_opts} -DXXL_JOB_ADMIN=${XXL_JOB_ADMIN}"
        xxl_job_opts="${xxl_job_opts} -DXXL_JOB_ACCESS_TOKEN=${XXL_JOB_ACCESS_TOKEN}"
        xxl_job_opts="${xxl_job_opts} -DXXL_JOB_APPNAME=${XXL_JOB_APPNAME}"
    fi
    
    # Start services in dependency order
    # 1. Start MCP Server first (other services depend on it)
    start_java_service "business-mcp-server" \
        "${SCRIPT_DIR}/business-mcp-server/target/business-mcp-server.jar" \
        "${BUSINESS_MCP_SERVER_PORT}"
    
    sleep 3
    
    # 2. Start sub-agents
    start_java_service "consult-sub-agent" \
        "${SCRIPT_DIR}/consult-sub-agent/target/consult-sub-agent.jar" \
        "${CONSULT_SUB_AGENT_PORT}"
    
    start_java_service "business-sub-agent" \
        "${SCRIPT_DIR}/business-sub-agent/target/business-sub-agent.jar" \
        "${BUSINESS_SUB_AGENT_PORT}"
    
    sleep 3
    
    # 3. Start supervisor agent (depends on sub-agents)
    start_java_service "supervisor-agent" \
        "${SCRIPT_DIR}/supervisor-agent/target/supervisor-agent.jar" \
        "${SUPERVISOR_AGENT_PORT}" \
        "${xxl_job_opts}"
}

# =============================================================================
# Main Logic
# =============================================================================

main() {
    local command=${1:-start}
    
    case "${command}" in
        start)
            print_banner
            check_required_env
            check_java
            check_dependencies
            build_frontend
            build_maven
            start_backend_services
            
            echo ""
            print_success "All services started successfully!"
            echo ""
            echo "Access URL:"
            echo "  Frontend + API: http://localhost:${SUPERVISOR_AGENT_PORT}"
            echo ""
            echo "Log directory: ${LOG_DIR}"
            echo ""
            show_status
            ;;
        build)
            print_banner
            check_java
            build_maven
            ;;
        stop)
            stop_all
            ;;
        restart)
            stop_all
            sleep 2
            main start
            ;;
        status)
            show_status
            ;;
        config)
            show_config
            ;;
        logs)
            echo "Log directory: ${LOG_DIR}"
            ls -la "${LOG_DIR}" 2>/dev/null || echo "Log directory is empty"
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            print_error "Unknown command: ${command}"
            show_help
            exit 1
            ;;
    esac
}

main "$@"

