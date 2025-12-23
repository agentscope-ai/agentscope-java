#!/bin/bash

# 清理并重新安装依赖脚本

# 颜色定义
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

# 主函数
main() {
    echo ""
    log_info "清理并重新安装依赖..."
    echo ""
    
    # 检查 package.json
    if [ ! -f "package.json" ]; then
        log_error "package.json 文件不存在，请确保在 frontend 目录下运行此脚本"
        exit 1
    fi
    
    # 清理
    log_info "清理旧的依赖..."
    if [ -d "node_modules" ]; then
        rm -rf node_modules
        log_success "已删除 node_modules"
    fi
    
    if [ -f "package-lock.json" ]; then
        rm -f package-lock.json
        log_success "已删除 package-lock.json"
    fi
    
    echo ""
    log_info "开始安装依赖..."
    log_info "这可能需要几分钟时间，请耐心等待..."
    echo ""
    
    # 尝试安装
    if npm install; then
        log_success "依赖安装完成 ✓"
    else
        log_warning "正常安装失败，尝试使用 --legacy-peer-deps..."
        if npm install --legacy-peer-deps; then
            log_success "依赖安装完成（使用 --legacy-peer-deps）✓"
        else
            log_error "依赖安装失败"
            log_info "请检查错误信息并手动解决依赖冲突"
            exit 1
        fi
    fi
    
    echo ""
    log_success "清理和安装完成！"
    log_info "现在可以运行 ./start.sh 启动服务"
}

# 执行主函数
main "$@"

