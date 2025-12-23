#!/bin/bash

# 前端项目重启脚本

# 颜色定义
BLUE='\033[0;34m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

# 主函数
main() {
    echo ""
    log_info "重启前端服务..."
    echo ""
    
    # 停止服务
    if [ -f "stop.sh" ]; then
        ./stop.sh
    else
        log_info "停止服务..."
        # 简单的停止逻辑
        if [ -f ".pid" ]; then
            kill $(cat .pid) 2>/dev/null || true
            rm -f .pid
        fi
        lsof -ti:3000 | xargs kill -9 2>/dev/null || true
    fi
    
    sleep 2
    
    # 启动服务
    if [ -f "start.sh" ]; then
        ./start.sh
    else
        log_info "启动服务..."
        npm run dev -- --port 3000 --host 0.0.0.0 &
        echo $! > .pid
    fi
    
    echo ""
    log_success "重启完成！"
}

# 执行主函数
main "$@"

