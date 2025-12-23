#!/bin/bash
set -e

# 默认配置
IMAGE_NAME="${IMAGE_NAME:-himarket-server-auto-init}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
REGISTRY="${REGISTRY:-registry.cn-hangzhou.aliyuncs.com/agentscope}"
PUSH_IMAGE=true  # 默认推送到镜像仓库

# 解析命令行参数
while [[ $# -gt 0 ]]; do
  case $1 in
    -r|--registry)
      REGISTRY="$2"
      shift 2
      ;;
    -n|--name)
      IMAGE_NAME="$2"
      shift 2
      ;;
    -t|--tag)
      IMAGE_TAG="$2"
      shift 2
      ;;
    -p|--push)
      PUSH_IMAGE=true
      shift
      ;;
    --no-push)
      PUSH_IMAGE=false
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  -r, --registry REGISTRY   指定镜像仓库 (默认: registry.cn-hangzhou.aliyuncs.com/agentscope)"
      echo "  -n, --name NAME           指定镜像名称 (默认: himarket-server-auto-init)"
      echo "  -t, --tag TAG             指定镜像标签 (默认: latest)"
      echo "  -p, --push                构建后推送镜像到仓库 (默认行为)"
      echo "  --no-push                 构建后不推送镜像"
      echo "  -h, --help                显示帮助信息"
      echo ""
      echo "默认行为: 构建完成后会自动推送镜像到仓库"
      echo ""
      echo "示例:"
      echo "  $0                                         # 使用默认配置并推送"
      echo "  $0 --no-push                               # 只构建不推送"
      echo "  $0 -r my-registry.com/mygroup              # 推送到指定仓库"
      echo "  $0 -t v1.0.0                               # 指定版本标签并推送"
      echo "  $0 -r my-registry.com/mygroup -t v1.0.0    # 完整配置"
      exit 0
      ;;
    *)
      echo "未知参数: $1"
      echo "使用 -h 或 --help 查看帮助"
      exit 1
      ;;
  esac
done

# 完整镜像名称
FULL_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

echo "=========================================="
echo "构建 HiMarket Server Auto-Init 镜像"
echo "=========================================="
echo ""
echo "镜像名称: ${FULL_IMAGE_NAME}"
echo "推送镜像: $([ "$PUSH_IMAGE" = true ] && echo "是 ✓" || echo "否")"
echo ""

# 检查文件是否存在
if [ ! -f "init-himarket-local.sh" ]; then
    echo "[ERROR] init-himarket-local.sh 不存在"
    exit 1
fi

if [ ! -f "Dockerfile" ]; then
    echo "[ERROR] Dockerfile 不存在"
    exit 1
fi

if [ ! -f "entrypoint.sh" ]; then
    echo "[ERROR] entrypoint.sh 不存在"
    exit 1
fi

# 构建镜像
echo "[$(date +'%H:%M:%S')] 开始构建镜像..."
docker build --platform linux/amd64 -t "${FULL_IMAGE_NAME}" .

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "[✓] 镜像构建成功！"
    echo "=========================================="
    echo ""
    echo "镜像信息:"
    echo "  - ${FULL_IMAGE_NAME}"
    
    # 如果不是 latest 标签，同时打一个 latest 标签
    if [ "${IMAGE_TAG}" != "latest" ]; then
        LATEST_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:latest"
        echo "  - ${LATEST_IMAGE_NAME}"
        docker tag "${FULL_IMAGE_NAME}" "${LATEST_IMAGE_NAME}"
        echo ""
        echo "[✓] 已创建 latest 标签"
    fi
    
    echo ""
    
    # 如果指定了推送参数，则推送镜像
    if [ "$PUSH_IMAGE" = true ]; then
        echo "=========================================="
        echo "推送镜像到仓库..."
        echo "=========================================="
        echo ""
        
        echo "[$(date +'%H:%M:%S')] 推送: ${FULL_IMAGE_NAME}"
        docker push "${FULL_IMAGE_NAME}"
        
        if [ $? -ne 0 ]; then
            echo ""
            echo "[ERROR] 推送镜像失败"
            exit 1
        fi
        
        # 如果创建了 latest 标签，也推送
        if [ "${IMAGE_TAG}" != "latest" ]; then
            echo "[$(date +'%H:%M:%S')] 推送: ${LATEST_IMAGE_NAME}"
            docker push "${LATEST_IMAGE_NAME}"
            
            if [ $? -ne 0 ]; then
                echo ""
                echo "[ERROR] 推送 latest 标签失败"
                exit 1
            fi
        fi
        
        echo ""
        echo "=========================================="
        echo "[✓] 镜像推送成功！"
        echo "=========================================="
        echo ""
    fi
    
    echo "使用方法："
    echo ""
    echo "1. 基础运行（不自动初始化）："
    echo "   docker run -p 8080:8080 -e AUTO_INIT=false ${FULL_IMAGE_NAME}"
    echo ""
    echo "2. 自动初始化（默认配置）："
    echo "   docker run -p 8080:8080 ${FULL_IMAGE_NAME}"
    echo ""
    echo "3. 自动初始化 + 注册 Nacos："
    echo "   docker run -p 8080:8080 \\"
    echo "     -e REGISTER_NACOS=true \\"
    echo "     -e NACOS_URL=http://nacos:8848 \\"
    echo "     -e NACOS_USERNAME=nacos \\"
    echo "     -e NACOS_PASSWORD=nacos \\"
    echo "     ${FULL_IMAGE_NAME}"
    echo ""
    echo "4. 商业化 Nacos（AccessKey/SecretKey）："
    echo "   docker run -p 8080:8080 \\"
    echo "     -e REGISTER_NACOS=true \\"
    echo "     -e NACOS_URL=mse-xxx.nacos-ans.mse.aliyuncs.com \\"
    echo "     -e NACOS_ACCESS_KEY=LTAI5t... \\"
    echo "     -e NACOS_SECRET_KEY=xxx... \\"
    echo "     ${FULL_IMAGE_NAME}"
    echo ""
    
    if [ "$PUSH_IMAGE" != true ]; then
        echo "💡 提示："
        echo ""
        echo "镜像已构建但未推送到仓库（使用了 --no-push 参数）"
        echo ""
        echo "如需推送，可以执行："
        echo "   docker push ${FULL_IMAGE_NAME}"
        if [ "${IMAGE_TAG}" != "latest" ]; then
            echo "   docker push ${LATEST_IMAGE_NAME}"
        fi
        echo ""
        echo "或重新运行脚本（默认会自动推送）："
        echo "   $0"
        echo ""
    fi
else
    echo ""
    echo "[ERROR] 镜像构建失败"
    exit 1
fi

