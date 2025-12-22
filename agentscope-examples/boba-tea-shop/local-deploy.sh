#!/bin/bash
# =============================================================================
# AgentScope Boba Tea Shop - 本地部署脚本
# =============================================================================
# 
# 本脚本用于在本地环境部署多智能体系统的各个服务
# 
# ============================= 前置依赖说明 =============================
# 
# 【MySQL】需要预先部署，并从中获取以下信息：
#   - 主机地址（DB_HOST）：如 localhost
#   - 端口（DB_PORT）：默认 3306
#   - 数据库名（DB_NAME）：如 multi_agent_demo
#   - 用户名（DB_USERNAME）
#   - 密码（DB_PASSWORD）
# 
# 【Nacos】需要预先部署，并从中获取以下信息：
#   - 服务地址（NACOS_SERVER_ADDR）：如 localhost:8848
#   - 命名空间（NACOS_NAMESPACE）：如 public
#   - 用户名（NACOS_USERNAME）
#   - 密码（NACOS_PASSWORD）
# 
# =============================================================================

set -e

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"

# 创建日志目录
mkdir -p "${LOG_DIR}"

# =============================================================================
# 颜色定义
# =============================================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# 环境变量配置（请根据实际情况修改）
# =============================================================================

# ------------ MySQL 配置（从已部署的 MySQL 获取）------------
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-3306}"
export DB_NAME="${DB_NAME:-multi_agent_demo}"
export DB_USERNAME="${DB_USERNAME:-multi_agent_demo}"
export DB_PASSWORD="${DB_PASSWORD:-multi_agent_demo@321}"

# ------------ Nacos 配置（从已部署的 Nacos 获取）------------
export NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR:-localhost:8848}"
export NACOS_NAMESPACE="${NACOS_NAMESPACE:-public}"
export NACOS_USERNAME="${NACOS_USERNAME:-nacos}"
export NACOS_PASSWORD="${NACOS_PASSWORD:-nacos}"
export NACOS_REGISTER_ENABLED="${NACOS_REGISTER_ENABLED:-true}"

# ------------ LLM 模型配置 ------------
export MODEL_PROVIDER="${MODEL_PROVIDER:-dashscope}"  # dashscope 或 openai
export MODEL_API_KEY="${MODEL_API_KEY:-}"             # ⚠️ 必须配置
export MODEL_NAME="${MODEL_NAME:-qwen-max}"
export MODEL_BASE_URL="${MODEL_BASE_URL:-}"           # OpenAI 兼容接口时配置

# ------------ DashScope 知识库配置（RAG）------------
export DASHSCOPE_API_KEY="${DASHSCOPE_API_KEY:-}"     # ⚠️ 必须配置
export DASHSCOPE_INDEX_ID="${DASHSCOPE_INDEX_ID:-}"   # ⚠️ 必须配置

# ------------ Mem0 记忆服务配置 ------------
export MEM0_API_KEY="${MEM0_API_KEY:-}"               # ⚠️ 必须配置

# ------------ XXL-JOB 任务调度配置（可选）------------
export XXL_JOB_ENABLED="${XXL_JOB_ENABLED:-false}"
export XXL_JOB_ADMIN="${XXL_JOB_ADMIN:-http://localhost:28080/xxl-job-admin}"
export XXL_JOB_ACCESS_TOKEN="${XXL_JOB_ACCESS_TOKEN:-default_token}"
export XXL_JOB_APPNAME="${XXL_JOB_APPNAME:-multi-agent-demo}"

# ------------ 服务端口配置 ------------
export BUSINESS_MCP_SERVER_PORT="${BUSINESS_MCP_SERVER_PORT:-10002}"
export CONSULT_SUB_AGENT_PORT="${CONSULT_SUB_AGENT_PORT:-10005}"
export BUSINESS_SUB_AGENT_PORT="${BUSINESS_SUB_AGENT_PORT:-10006}"
export SUPERVISOR_AGENT_PORT="${SUPERVISOR_AGENT_PORT:-10008}"

# =============================================================================
# 辅助函数
# =============================================================================

print_banner() {
    echo -e "${BLUE}"
    echo "=============================================="
    echo "   AgentScope Boba Tea Shop - 本地部署"
    echo "=============================================="
    echo -e "${NC}"
}

print_step() {
    echo -e "${GREEN}[步骤]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[信息]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[警告]${NC} $1"
}

print_error() {
    echo -e "${RED}[错误]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[成功]${NC} $1"
}

# 检查必要的环境变量
check_required_env() {
    local missing=()
    
    if [ -z "${MODEL_API_KEY}" ]; then
        missing+=("MODEL_API_KEY")
    fi
    
    if [ -z "${DASHSCOPE_API_KEY}" ]; then
        missing+=("DASHSCOPE_API_KEY")
    fi
    
    if [ -z "${DASHSCOPE_INDEX_ID}" ]; then
        missing+=("DASHSCOPE_INDEX_ID")
    fi
    
    if [ -z "${MEM0_API_KEY}" ]; then
        missing+=("MEM0_API_KEY")
    fi
    
    if [ ${#missing[@]} -gt 0 ]; then
        print_error "以下必需的环境变量未配置："
        for var in "${missing[@]}"; do
            echo "  - ${var}"
        done
        echo ""
        echo "请通过以下方式配置："
        echo "  export MODEL_API_KEY=your_api_key"
        echo "  export DASHSCOPE_API_KEY=your_dashscope_key"
        echo "  export DASHSCOPE_INDEX_ID=your_index_id"
        echo "  export MEM0_API_KEY=your_mem0_key"
        echo ""
        echo "或者创建 .env 文件后执行: source .env && ./local-deploy.sh"
        exit 1
    fi
}

# 检查 Java 环境
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "未找到 Java，请安装 JDK 17+"
        exit 1
    fi
    
    java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "${java_version}" -lt 17 ]; then
        print_error "Java 版本过低，需要 JDK 17+，当前版本: ${java_version}"
        exit 1
    fi
    print_info "Java 版本检查通过: $(java -version 2>&1 | head -n 1)"
}


# 检查服务依赖连通性
check_dependencies() {
    print_step "检查服务依赖连通性..."
    
    # 检查 MySQL
    print_info "检查 MySQL 连接 (${DB_HOST}:${DB_PORT})..."
    if ! nc -z "${DB_HOST}" "${DB_PORT}" 2>/dev/null; then
        print_warn "无法连接到 MySQL (${DB_HOST}:${DB_PORT})，请确保 MySQL 已启动"
    else
        print_success "MySQL 连接正常"
    fi
    
    # 检查 Nacos
    NACOS_HOST=$(echo "${NACOS_SERVER_ADDR}" | cut -d: -f1)
    NACOS_PORT=$(echo "${NACOS_SERVER_ADDR}" | cut -d: -f2)
    print_info "检查 Nacos 连接 (${NACOS_HOST}:${NACOS_PORT})..."
    if ! nc -z "${NACOS_HOST}" "${NACOS_PORT}" 2>/dev/null; then
        print_warn "无法连接到 Nacos (${NACOS_HOST}:${NACOS_PORT})，请确保 Nacos 已启动"
    else
        print_success "Nacos 连接正常"
    fi
}

# 构建前端
build_frontend() {
    print_step "构建前端..."
    
    cd "${SCRIPT_DIR}/frontend"
    
    if ! command -v node &> /dev/null; then
        print_warn "未找到 Node.js，跳过前端构建"
        print_info "前端将无法通过 supervisor-agent 访问"
        cd "${SCRIPT_DIR}"
        return 1
    fi
    
    if [ ! -d "node_modules" ]; then
        print_info "安装前端依赖..."
        npm install --legacy-peer-deps
    fi
    
    # 清理旧的构建产物
    print_info "清理旧的前端构建..."
    rm -rf dist
    
    print_info "构建前端应用..."
    npm run build-only
    
    # 清理并复制到 supervisor-agent 的静态资源目录
    local static_dir="${SCRIPT_DIR}/supervisor-agent/src/main/resources/static"
    print_info "清理旧的静态资源目录..."
    rm -rf "${static_dir}"
    mkdir -p "${static_dir}"
    cp -r dist/* "${static_dir}/"
    
    print_success "前端构建完成，已复制到 ${static_dir}"
    cd "${SCRIPT_DIR}"
    return 0
}

# 构建 Maven 项目
build_maven() {
    print_step "构建 Maven 项目..."
    cd "${SCRIPT_DIR}"
    
    if [ ! -f "pom.xml" ]; then
        print_error "未找到 pom.xml 文件"
        exit 1
    fi
    
    # 检查前端静态文件是否已复制
    local static_index="${SCRIPT_DIR}/supervisor-agent/src/main/resources/static/index.html"
    if [ ! -f "${static_index}" ]; then
        print_warn "前端静态文件不存在，JAR 将不包含前端"
    else
        print_info "前端静态文件已就绪: ${static_index}"
    fi
    
    # 从 boba-tea-shop 目录构建所有子模块
    print_info "构建所有子模块..."
    mvn clean package -DskipTests
    
    # 验证 JAR 文件是否生成
    local all_jars_found=true
    for module in supervisor-agent business-mcp-server business-sub-agent consult-sub-agent; do
        if [ ! -f "${SCRIPT_DIR}/${module}/target/${module}.jar" ]; then
            print_error "未找到 ${module}.jar"
            all_jars_found=false
        fi
    done
    
    if [ "$all_jars_found" = false ]; then
        print_error "Maven 构建失败，部分 JAR 文件未生成"
        exit 1
    fi
    
    # 验证 supervisor-agent JAR 中是否包含前端静态文件
    if unzip -l "${SCRIPT_DIR}/supervisor-agent/target/supervisor-agent.jar" | grep -q "static/index.html"; then
        print_success "Maven 项目构建完成（已包含前端静态文件）"
    else
        print_warn "Maven 项目构建完成，但 JAR 中未找到前端静态文件"
    fi
}

# 启动 Java 服务
start_java_service() {
    local service_name=$1
    local jar_path=$2
    local port=$3
    local extra_opts=${4:-""}
    
    print_step "启动 ${service_name} (端口: ${port})..."
    
    if [ ! -f "${jar_path}" ]; then
        print_error "未找到 JAR 文件: ${jar_path}"
        print_info "请先执行构建: ./local-deploy.sh build"
        return 1
    fi
    
    # 检查端口是否被占用
    if lsof -i ":${port}" &>/dev/null; then
        print_warn "端口 ${port} 已被占用，跳过启动 ${service_name}"
        return 0
    fi
    
    local log_file="${LOG_DIR}/${service_name}.log"
    
    # 构建 Java 启动参数
    local java_opts="-Xms512m -Xmx2048m"
    java_opts="${java_opts} -DSERVER_PORT=${port}"
    java_opts="${java_opts} -DMODEL_PROVIDER=${MODEL_PROVIDER}"
    java_opts="${java_opts} -DMODEL_API_KEY=${MODEL_API_KEY}"
    java_opts="${java_opts} -DMODEL_NAME=${MODEL_NAME}"
    [ -n "${MODEL_BASE_URL}" ] && java_opts="${java_opts} -DMODEL_BASE_URL=${MODEL_BASE_URL}"
    java_opts="${java_opts} -DDASHSCOPE_API_KEY=${DASHSCOPE_API_KEY}"
    java_opts="${java_opts} -DDASHSCOPE_INDEX_ID=${DASHSCOPE_INDEX_ID}"
    java_opts="${java_opts} -DDB_HOST=${DB_HOST}"
    java_opts="${java_opts} -DDB_PORT=${DB_PORT}"
    java_opts="${java_opts} -DDB_NAME=${DB_NAME}"
    java_opts="${java_opts} -DDB_USERNAME=${DB_USERNAME}"
    java_opts="${java_opts} -DDB_PASSWORD=${DB_PASSWORD}"
    java_opts="${java_opts} -DMEM0_API_KEY=${MEM0_API_KEY}"
    java_opts="${java_opts} -DNACOS_SERVER_ADDR=${NACOS_SERVER_ADDR}"
    java_opts="${java_opts} -DNACOS_NAMESPACE=${NACOS_NAMESPACE}"
    java_opts="${java_opts} -DNACOS_USERNAME=${NACOS_USERNAME}"
    java_opts="${java_opts} -DNACOS_PASSWORD=${NACOS_PASSWORD}"
    java_opts="${java_opts} -DNACOS_REGISTER_ENABLED=${NACOS_REGISTER_ENABLED}"
    java_opts="${java_opts} ${extra_opts}"
    
    # 启动服务
    nohup java ${java_opts} -jar "${jar_path}" > "${log_file}" 2>&1 &
    local pid=$!
    echo "${pid}" > "${LOG_DIR}/${service_name}.pid"
    
    print_success "${service_name} 已启动 (PID: ${pid})，日志: ${log_file}"
}

# 停止所有服务
stop_all() {
    print_step "停止所有服务..."
    
    local services=("business-mcp-server" "consult-sub-agent" "business-sub-agent" "supervisor-agent")
    
    for service in "${services[@]}"; do
        local pid_file="${LOG_DIR}/${service}.pid"
        if [ -f "${pid_file}" ]; then
            local pid=$(cat "${pid_file}")
            if kill -0 "${pid}" 2>/dev/null; then
                kill "${pid}" 2>/dev/null || true
                print_info "已停止 ${service} (PID: ${pid})"
            fi
            rm -f "${pid_file}"
        fi
    done
    
    print_success "所有服务已停止"
}

# 显示服务状态
show_status() {
    print_step "服务状态..."
    echo ""
    printf "%-25s %-10s %-15s\n" "服务名称" "端口" "状态"
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
        local status="未运行"
        
        local pid_file="${LOG_DIR}/${service}.pid"
        if [ -f "${pid_file}" ]; then
            local pid=$(cat "${pid_file}")
            if kill -0 "${pid}" 2>/dev/null; then
                status="${GREEN}运行中 (PID: ${pid})${NC}"
            fi
        fi
        
        printf "%-25s %-10s %b\n" "${service}" "${port}" "${status}"
    done
    echo ""
}

# 显示配置信息
show_config() {
    echo ""
    echo "=============================================="
    echo "当前配置信息"
    echo "=============================================="
    echo ""
    echo "【数据库配置】"
    echo "  DB_HOST:     ${DB_HOST}"
    echo "  DB_PORT:     ${DB_PORT}"
    echo "  DB_NAME:     ${DB_NAME}"
    echo "  DB_USERNAME: ${DB_USERNAME}"
    echo "  DB_PASSWORD: ******"
    echo ""
    echo "【Nacos 配置】"
    echo "  NACOS_SERVER_ADDR:     ${NACOS_SERVER_ADDR}"
    echo "  NACOS_NAMESPACE:       ${NACOS_NAMESPACE}"
    echo "  NACOS_USERNAME:        ${NACOS_USERNAME}"
    echo "  NACOS_REGISTER_ENABLED: ${NACOS_REGISTER_ENABLED}"
    echo ""
    echo "【模型配置】"
    echo "  MODEL_PROVIDER:  ${MODEL_PROVIDER}"
    echo "  MODEL_NAME:      ${MODEL_NAME}"
    echo "  MODEL_API_KEY:   ${MODEL_API_KEY:+******}"
    echo "  MODEL_BASE_URL:  ${MODEL_BASE_URL:-未配置}"
    echo ""
    echo "【DashScope 配置】"
    echo "  DASHSCOPE_API_KEY:  ${DASHSCOPE_API_KEY:+******}"
    echo "  DASHSCOPE_INDEX_ID: ${DASHSCOPE_INDEX_ID:-未配置}"
    echo ""
    echo "【Mem0 配置】"
    echo "  MEM0_API_KEY: ${MEM0_API_KEY:+******}"
    echo ""
    echo "【XXL-JOB 配置】"
    echo "  XXL_JOB_ENABLED: ${XXL_JOB_ENABLED}"
    if [ "${XXL_JOB_ENABLED}" = "true" ]; then
        echo "  XXL_JOB_ADMIN:        ${XXL_JOB_ADMIN}"
        echo "  XXL_JOB_ACCESS_TOKEN: ******"
        echo "  XXL_JOB_APPNAME:      ${XXL_JOB_APPNAME}"
    fi
    echo ""
    echo "【服务端口】"
    echo "  business-mcp-server: ${BUSINESS_MCP_SERVER_PORT}"
    echo "  consult-sub-agent:   ${CONSULT_SUB_AGENT_PORT}"
    echo "  business-sub-agent:  ${BUSINESS_SUB_AGENT_PORT}"
    echo "  supervisor-agent:    ${SUPERVISOR_AGENT_PORT} (含前端)"
    echo ""
}

# 显示帮助信息
show_help() {
    echo "用法: $0 [命令]"
    echo ""
    echo "命令:"
    echo "  start       构建并启动所有服务（默认）"
    echo "  build       仅构建项目"
    echo "  stop        停止所有服务"
    echo "  restart     重启所有服务"
    echo "  status      查看服务状态"
    echo "  config      显示当前配置"
    echo "  logs        查看日志目录"
    echo "  help        显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  # 配置环境变量后启动"
    echo "  export MODEL_API_KEY=your_key"
    echo "  export DASHSCOPE_API_KEY=your_key"
    echo "  export DASHSCOPE_INDEX_ID=your_id"
    echo "  export MEM0_API_KEY=your_key"
    echo "  ./local-deploy.sh start"
    echo ""
    echo "  # 或使用 .env 文件"
    echo "  source .env && ./local-deploy.sh start"
    echo ""
}

# 启动所有后端服务
start_backend_services() {
    # 构建 XXL-JOB 额外参数（仅 supervisor-agent 使用）
    local xxl_job_opts=""
    if [ "${XXL_JOB_ENABLED}" = "true" ]; then
        xxl_job_opts="-DXXL_JOB_ENABLED=true"
        xxl_job_opts="${xxl_job_opts} -DXXL_JOB_ADMIN=${XXL_JOB_ADMIN}"
        xxl_job_opts="${xxl_job_opts} -DXXL_JOB_ACCESS_TOKEN=${XXL_JOB_ACCESS_TOKEN}"
        xxl_job_opts="${xxl_job_opts} -DXXL_JOB_APPNAME=${XXL_JOB_APPNAME}"
    fi
    
    # 按依赖顺序启动服务
    # 1. 先启动 MCP Server（被其他服务依赖）
    start_java_service "business-mcp-server" \
        "${SCRIPT_DIR}/business-mcp-server/target/business-mcp-server.jar" \
        "${BUSINESS_MCP_SERVER_PORT}"
    
    sleep 3
    
    # 2. 启动子智能体
    start_java_service "consult-sub-agent" \
        "${SCRIPT_DIR}/consult-sub-agent/target/consult-sub-agent.jar" \
        "${CONSULT_SUB_AGENT_PORT}"
    
    start_java_service "business-sub-agent" \
        "${SCRIPT_DIR}/business-sub-agent/target/business-sub-agent.jar" \
        "${BUSINESS_SUB_AGENT_PORT}"
    
    sleep 3
    
    # 3. 启动主智能体（依赖子智能体）
    start_java_service "supervisor-agent" \
        "${SCRIPT_DIR}/supervisor-agent/target/supervisor-agent.jar" \
        "${SUPERVISOR_AGENT_PORT}" \
        "${xxl_job_opts}"
}

# =============================================================================
# 主逻辑
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
            print_success "所有服务启动完成！"
            echo ""
            echo "访问地址:"
            echo "  前端界面 + API: http://localhost:${SUPERVISOR_AGENT_PORT}"
            echo ""
            echo "日志目录: ${LOG_DIR}"
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
            echo "日志目录: ${LOG_DIR}"
            ls -la "${LOG_DIR}" 2>/dev/null || echo "日志目录为空"
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            print_error "未知命令: ${command}"
            show_help
            exit 1
            ;;
    esac
}

main "$@"

