#!/bin/bash

# 前端项目停止脚本

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置
PORT=3000
PID_FILE=".pid"

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

# 停止服务
stop_service() {
    log_info "正在停止前端服务..."
    
    # 方法1: 通过 PID 文件停止
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE" 2>/dev/null || echo "")
        if [ -n "$pid" ] && ps -p $pid > /dev/null 2>&1; then
            log_info "发现进程 (PID: $pid)，正在停止..."
            kill $pid 2>/dev/null || true
            sleep 2
            
            # 检查是否还在运行
            if ps -p $pid > /dev/null 2>&1; then
                log_warning "进程未正常退出，强制停止..."
                kill -9 $pid 2>/dev/null || true
                sleep 1
            fi
            
            if ! ps -p $pid > /dev/null 2>&1; then
                log_success "进程已停止 (PID: $pid)"
                rm -f "$PID_FILE"
            else
                log_error "无法停止进程 (PID: $pid)"
                return 1
            fi
        else
            log_warning "PID 文件存在但进程未运行，清理 PID 文件"
            rm -f "$PID_FILE"
        fi
    fi
    
    # 方法2: 通过端口停止
    if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
        local port_pid=$(lsof -Pi :$PORT -sTCP:LISTEN -t | head -n 1)
        if [ -n "$port_pid" ]; then
            log_info "发现占用端口 $PORT 的进程 (PID: $port_pid)，正在停止..."
            kill $port_pid 2>/dev/null || true
            sleep 2
            
            if ps -p $port_pid > /dev/null 2>&1; then
                log_warning "进程未正常退出，强制停止..."
                kill -9 $port_pid 2>/dev/null || true
                sleep 1
            fi
            
            if ! ps -p $port_pid > /dev/null 2>&1; then
                log_success "端口 $PORT 已释放"
            else
                log_error "无法停止占用端口的进程"
                return 1
            fi
        fi
    else
        log_info "端口 $PORT 未被占用"
    fi
    
    log_success "前端服务已停止"
}

# 主函数
main() {
    echo ""
    log_info "停止前端服务..."
    echo ""
    
    stop_service
    
    echo ""
    log_success "停止完成！"
}

# 执行主函数
main "$@"

