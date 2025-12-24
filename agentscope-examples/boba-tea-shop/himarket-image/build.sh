#!/bin/bash
set -e

# Default configuration
IMAGE_NAME="${IMAGE_NAME:-himarket-server-auto-init}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
REGISTRY="${REGISTRY:-registry.cn-hangzhou.aliyuncs.com/agentscope}"
PUSH_IMAGE=true  # Push to image registry by default

# Parse command line arguments
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
      echo "  -r, --registry REGISTRY   Specify image registry (default: registry.cn-hangzhou.aliyuncs.com/agentscope)"
      echo "  -n, --name NAME           Specify image name (default: himarket-server-auto-init)"
      echo "  -t, --tag TAG             Specify image tag (default: latest)"
      echo "  -p, --push                Push image to registry after build (default behavior)"
      echo "  --no-push                 Do not push image after build"
      echo "  -h, --help                Display help information"
      echo ""
      echo "Default behavior: Automatically push image to registry after build"
      echo ""
      echo "Examples:"
      echo "  $0                                         # Use default configuration and push"
      echo "  $0 --no-push                               # Build only without pushing"
      echo "  $0 -r my-registry.com/mygroup              # Push to specified registry"
      echo "  $0 -t v1.0.0                               # Specify version tag and push"
      echo "  $0 -r my-registry.com/mygroup -t v1.0.0    # Full configuration"
      exit 0
      ;;
    *)
      echo "Unknown parameter: $1"
      echo "Use -h or --help for help"
      exit 1
      ;;
  esac
done

# Full image name
FULL_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

echo "=========================================="
echo "Build HiMarket Server Auto-Init Image"
echo "=========================================="
echo ""
echo "Image name: ${FULL_IMAGE_NAME}"
echo "Push image: $([ "$PUSH_IMAGE" = true ] && echo "Yes ✓" || echo "No")"
echo ""

# Check if required files exist
if [ ! -f "init-himarket-local.sh" ]; then
    echo "[ERROR] init-himarket-local.sh does not exist"
    exit 1
fi

if [ ! -f "Dockerfile" ]; then
    echo "[ERROR] Dockerfile does not exist"
    exit 1
fi

if [ ! -f "entrypoint.sh" ]; then
    echo "[ERROR] entrypoint.sh does not exist"
    exit 1
fi

# Build image
echo "[$(date +'%H:%M:%S')] Starting image build..."
docker build --platform linux/amd64 -t "${FULL_IMAGE_NAME}" .

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "[✓] Image build successful!"
    echo "=========================================="
    echo ""
    echo "Image info:"
    echo "  - ${FULL_IMAGE_NAME}"
    
    # If not latest tag, also create a latest tag
    if [ "${IMAGE_TAG}" != "latest" ]; then
        LATEST_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:latest"
        echo "  - ${LATEST_IMAGE_NAME}"
        docker tag "${FULL_IMAGE_NAME}" "${LATEST_IMAGE_NAME}"
        echo ""
        echo "[✓] Created latest tag"
    fi
    
    echo ""
    
    # If push parameter is specified, push the image
    if [ "$PUSH_IMAGE" = true ]; then
        echo "=========================================="
        echo "Pushing image to registry..."
        echo "=========================================="
        echo ""
        
        echo "[$(date +'%H:%M:%S')] Pushing: ${FULL_IMAGE_NAME}"
        docker push "${FULL_IMAGE_NAME}"
        
        if [ $? -ne 0 ]; then
            echo ""
            echo "[ERROR] Failed to push image"
            exit 1
        fi
        
        # If latest tag was created, push it too
        if [ "${IMAGE_TAG}" != "latest" ]; then
            echo "[$(date +'%H:%M:%S')] Pushing: ${LATEST_IMAGE_NAME}"
            docker push "${LATEST_IMAGE_NAME}"
            
            if [ $? -ne 0 ]; then
                echo ""
                echo "[ERROR] Failed to push latest tag"
                exit 1
            fi
        fi
        
        echo ""
        echo "=========================================="
        echo "[✓] Image push successful!"
        echo "=========================================="
        echo ""
    fi
    
    echo "Usage:"
    echo ""
    echo "1. Basic run (without auto-init):"
    echo "   docker run -p 8080:8080 -e AUTO_INIT=false ${FULL_IMAGE_NAME}"
    echo ""
    echo "2. Auto-init (default configuration):"
    echo "   docker run -p 8080:8080 ${FULL_IMAGE_NAME}"
    echo ""
    echo "3. Auto-init + Register Nacos:"
    echo "   docker run -p 8080:8080 \\"
    echo "     -e REGISTER_NACOS=true \\"
    echo "     -e NACOS_URL=http://nacos:8848 \\"
    echo "     -e NACOS_USERNAME=nacos \\"
    echo "     -e NACOS_PASSWORD=nacos \\"
    echo "     ${FULL_IMAGE_NAME}"
    echo ""
    echo "4. Commercial Nacos (AccessKey/SecretKey):"
    echo "   docker run -p 8080:8080 \\"
    echo "     -e REGISTER_NACOS=true \\"
    echo "     -e NACOS_URL=mse-xxx.nacos-ans.mse.aliyuncs.com \\"
    echo "     -e NACOS_ACCESS_KEY=LTAI5t... \\"
    echo "     -e NACOS_SECRET_KEY=xxx... \\"
    echo "     ${FULL_IMAGE_NAME}"
    echo ""
    
    if [ "$PUSH_IMAGE" != true ]; then
        echo "💡 Tip:"
        echo ""
        echo "Image built but not pushed to registry (used --no-push parameter)"
        echo ""
        echo "To push, execute:"
        echo "   docker push ${FULL_IMAGE_NAME}"
        if [ "${IMAGE_TAG}" != "latest" ]; then
            echo "   docker push ${LATEST_IMAGE_NAME}"
        fi
        echo ""
        echo "Or re-run the script (will auto-push by default):"
        echo "   $0"
        echo ""
    fi
else
    echo ""
    echo "[ERROR] Image build failed"
    exit 1
fi

