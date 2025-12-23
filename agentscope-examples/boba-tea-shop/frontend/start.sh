#!/bin/bash

# 前端项目启动脚本
# 功能：检查环境、安装依赖、启动开发服务器

set -e  # 遇到错误立即退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 配置
PORT=3000
PID_FILE=".pid"
LOG_FILE="nohup.out"
BACKEND_URL="http://localhost:10008"

# 日志函数
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

log_step() {
    echo -e "${CYAN}=== $1 ===${NC}"
}

# 检查命令是否存在
check_command() {
    if ! command -v $1 &> /dev/null; then
        log_error "$1 命令未找到，请先安装 $1"
        exit 1
    fi
}

# 检查 Node.js 版本
check_node_version() {
    log_info "检查 Node.js 版本..."
    
    if ! command -v node &> /dev/null; then
        log_error "Node.js 未安装，请先安装 Node.js >= 16.0.0"
        log_info "访问 https://nodejs.org/ 下载安装"
        exit 1
    fi
    
    local node_version=$(node -v | sed 's/v//')
    local major_version=$(echo $node_version | cut -d. -f1)
    
    if [ "$major_version" -lt 16 ]; then
        log_error "Node.js 版本过低: $node_version，需要 >= 16.0.0"
        exit 1
    fi
    
    log_success "Node.js 版本: $node_version ✓"
}

# 检查 npm 版本
check_npm_version() {
    log_info "检查 npm 版本..."
    
    if ! command -v npm &> /dev/null; then
        log_error "npm 未安装，请先安装 npm >= 8.0.0"
        exit 1
    fi
    
    local npm_version=$(npm -v)
    local major_version=$(echo $npm_version | cut -d. -f1)
    
    if [ "$major_version" -lt 6 ]; then
        log_error "npm 版本过低: $npm_version，需要 >= 8.0.0"
        exit 1
    fi
    
    log_success "npm 版本: $npm_version ✓"
}

# 检查 package.json 是否存在
check_package_json() {
    if [ ! -f "package.json" ]; then
        log_error "package.json 文件不存在，请确保在 frontend 目录下运行此脚本"
        exit 1
    fi
    log_success "package.json 存在 ✓"
}

# 检查并安装依赖
check_and_install_dependencies() {
    log_info "检查依赖..."
    
    if [ ! -d "node_modules" ] || [ ! -f "node_modules/.package-lock.json" ]; then
        log_warning "依赖未安装，开始安装依赖..."
        log_info "这可能需要几分钟时间，请耐心等待..."
        
        # 尝试正常安装
        if npm install; then
            log_success "依赖安装完成 ✓"
        else
            log_warning "依赖安装遇到冲突，尝试使用 --legacy-peer-deps 安装..."
            if npm install --legacy-peer-deps; then
                log_success "依赖安装完成（使用 --legacy-peer-deps）✓"
            else
                log_error "依赖安装失败"
                log_info "建议尝试以下步骤："
                log_info "  1. 删除 node_modules 和 package-lock.json"
                log_info "  2. 运行: npm install --legacy-peer-deps"
                log_info "  或运行: npm install --force"
                exit 1
            fi
        fi
    else
        log_success "依赖已存在 ✓"
        
        # 可选：检查是否需要更新依赖
        read -p "是否检查并更新依赖？(y/N): " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            log_info "更新依赖..."
            if npm update; then
                log_success "依赖更新完成 ✓"
            else
                log_warning "依赖更新遇到冲突，尝试使用 --legacy-peer-deps..."
                npm update --legacy-peer-deps || true
            fi
        fi
    fi
}

# 检查端口是否被占用
check_port() {
    log_info "检查端口 $PORT 是否可用..."
    
    if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
        log_warning "端口 $PORT 已被占用"
        
        # 检查是否是自己的进程
        if [ -f "$PID_FILE" ]; then
            local old_pid=$(cat "$PID_FILE" 2>/dev/null || echo "")
            if [ -n "$old_pid" ] && ps -p $old_pid > /dev/null 2>&1; then
                log_info "发现之前的进程 (PID: $old_pid)，正在停止..."
                kill $old_pid 2>/dev/null || true
                sleep 2
                
                # 再次检查端口
                if ! lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
                    log_success "端口已释放 ✓"
                    return 0
                fi
            fi
        fi
        
        log_error "端口 $PORT 被其他进程占用，请手动停止占用该端口的进程"
        log_info "可以使用以下命令查看占用端口的进程："
        log_info "  lsof -i :$PORT"
        exit 1
    fi
    
    log_success "端口 $PORT 可用 ✓"
}

# 检查后端服务
check_backend_service() {
    log_info "检查后端服务连接..."
    
    if command -v curl &> /dev/null; then
        if curl -s --connect-timeout 3 "$BACKEND_URL/health" > /dev/null 2>&1 || \
           curl -s --connect-timeout 3 "$BACKEND_URL" > /dev/null 2>&1; then
            log_success "后端服务连接正常 ✓"
        else
            log_warning "无法连接到后端服务: $BACKEND_URL"
            log_warning "请确保后端服务（supervisor-agent）已启动"
            log_info "后端服务默认地址: $BACKEND_URL"
            echo ""
            read -p "是否继续启动前端？(y/N): " -n 1 -r
            echo ""
            if [[ ! $REPLY =~ ^[Yy]$ ]]; then
                log_info "已取消启动"
                exit 0
            fi
        fi
    else
        log_warning "未安装 curl，跳过后端服务检查"
        log_warning "请确保后端服务（supervisor-agent）已启动在 $BACKEND_URL"
    fi
}

# 启动开发服务器
start_dev_server() {
    log_step "启动开发服务器"
    
    # 清理旧的日志文件（可选）
    if [ -f "$LOG_FILE" ]; then
        mv "$LOG_FILE" "${LOG_FILE}.$(date +%Y%m%d_%H%M%S).bak" 2>/dev/null || true
    fi
    
    log_info "启动 Vite 开发服务器..."
    log_info "端口: $PORT"
    log_info "访问地址: http://localhost:$PORT"
    log_info "后端 API: $BACKEND_URL"
    
    # 后台启动
    nohup npm run dev -- --port $PORT --host 0.0.0.0 > "$LOG_FILE" 2>&1 &
    local pid=$!
    
    # 保存 PID
    echo $pid > "$PID_FILE"
    
    # 等待服务启动
    log_info "等待服务启动..."
    local max_attempts=30
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
            log_success "开发服务器启动成功！"
            log_success "进程 ID: $pid"
            log_success "访问地址: http://localhost:$PORT"
            log_info "日志文件: $LOG_FILE"
            log_info "停止服务: ./stop.sh 或 kill $pid"
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    
    log_error "服务启动超时"
    log_info "请查看日志文件: $LOG_FILE"
    exit 1
}

# 显示服务状态
show_status() {
    echo ""
    log_step "服务状态"
    
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE" 2>/dev/null || echo "")
        if [ -n "$pid" ] && ps -p $pid > /dev/null 2>&1; then
            log_success "前端服务运行中 (PID: $pid)"
            log_info "访问地址: http://localhost:$PORT"
        else
            log_warning "PID 文件存在但进程未运行"
        fi
    else
        log_warning "服务未启动"
    fi
    
    if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
        local port_pid=$(lsof -Pi :$PORT -sTCP:LISTEN -t)
        log_info "端口 $PORT 占用进程: $port_pid"
    fi
}

# 主函数
main() {
    echo ""
    log_step "前端项目启动脚本"
    echo ""
    
    # 1. 检查环境
    log_step "步骤 1: 环境检查"
    check_command "node"
    check_command "npm"
    check_node_version
    check_npm_version
    check_package_json
    echo ""
    
    # 2. 检查并安装依赖
    log_step "步骤 2: 依赖管理"
    check_and_install_dependencies
    echo ""
    
    # 3. 检查端口
    log_step "步骤 3: 端口检查"
    check_port
    echo ""
    
    # 4. 检查后端服务（可选）
    log_step "步骤 4: 后端服务检查"
    check_backend_service
    echo ""
    
    # 5. 启动开发服务器
    start_dev_server
    echo ""
    
    # 6. 显示状态
    show_status
    echo ""
    
    log_success "启动流程完成！"
    echo ""
    log_info "提示："
    log_info "  - 查看日志: tail -f $LOG_FILE"
    log_info "  - 停止服务: ./stop.sh"
    log_info "  - 访问地址: http://localhost:$PORT"
}

# 错误处理
trap 'log_error "脚本执行失败，请检查错误信息"; exit 1' ERR

# 执行主函数
main "$@"

