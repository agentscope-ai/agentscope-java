#!/usr/bin/env bash
# HiMarket 本地环境一键初始化脚本
# 功能：
#   1. 初始化管理员账号
#   2. 注册 Nacos 实例
#   3. 注册网关实例（支持 Higress 和阿里云 AI 网关）
#   4. 创建 Portal
#   5. 绑定域名到 Portal
#   6. 注册开发者账号并审批
#   7. 导入 MCP 到 Nacos（可选）
#   8. 在 HiMarket 中上架 MCP（可选）
#
# 用法：
#   ./init-himarket-local.sh
#
# 环境变量配置（可选，有默认值）：
#   HIMARKET_FRONTEND_URL=http://localhost:3000
#   ADMIN_USERNAME=admin
#   ADMIN_PASSWORD=admin
#   DEVELOPER_USERNAME=demo
#   DEVELOPER_PASSWORD=demo123
#
#   # Nacos 配置（REGISTER_NACOS=true 时需要）
#   REGISTER_NACOS=false
#   NACOS_NAME=nacos-demo
#   NACOS_URL=http://localhost:8848
#   # 认证方式 1（可选）：
#   NACOS_USERNAME=nacos
#   NACOS_PASSWORD=nacos
#   # 认证方式 2（可选）：
#   NACOS_ACCESS_KEY=LTAI5t...
#   NACOS_SECRET_KEY=xxx...
#
#   # 网关配置（REGISTER_GATEWAY=true 时需要，支持 HIGRESS 或 APIG_AI）
#   REGISTER_GATEWAY=false
#   GATEWAY_TYPE=HIGRESS  # 或 APIG_AI
#   GATEWAY_NAME=higress-demo
#   # Higress 配置：
#   GATEWAY_URL=http://localhost:8080
#   GATEWAY_USERNAME=admin
#   GATEWAY_PASSWORD=admin
#   # AI 网关配置：
#   APIG_REGION=cn-hangzhou
#   APIG_ACCESS_KEY=LTAI5t...
#   APIG_SECRET_KEY=xxx...
#
#   # MCP 导入配置（IMPORT_MCP_TO_NACOS=true 时需要）
#   IMPORT_MCP_TO_NACOS=false
#   MCP_JSON_FILE=/path/to/nacos-mcp.json
#
#   # MCP 上架配置（默认启用，需要先导入 MCP）
#   PUBLISH_MCP_TO_HIMARKET=true  # 将 MCP 上架到 HiMarket 开发者门户（默认 true）
#
#   PORTAL_NAME=demo

set -euo pipefail

########################################
# 配置参数
########################################

# HiMarket 服务地址（固定值，不可配置）
HIMARKET_HOST="localhost:8080"  # Server 端口，所有 API 请求
HIMARKET_FRONTEND_URL="${HIMARKET_FRONTEND_URL:-http://localhost:3000}"  # 前端访问地址，用于域名绑定

# 管理员凭据
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"

# 开发者凭据
DEVELOPER_USERNAME="${DEVELOPER_USERNAME:-demo}"
DEVELOPER_PASSWORD="${DEVELOPER_PASSWORD:-demo123}"

# 功能开关
REGISTER_NACOS="${REGISTER_NACOS:-false}"      # 是否注册 Nacos 实例
REGISTER_GATEWAY="${REGISTER_GATEWAY:-false}"  # 是否注册网关实例
IMPORT_MCP_TO_NACOS="${IMPORT_MCP_TO_NACOS:-false}"  # 是否导入 MCP 到 Nacos
PUBLISH_MCP_TO_HIMARKET="${PUBLISH_MCP_TO_HIMARKET:-true}"  # 是否在 HiMarket 中上架 MCP（默认启用）

# Nacos 配置（仅当 REGISTER_NACOS=true 时需要）
NACOS_NAME="${NACOS_NAME:-nacos-demo}"
NACOS_URL="${NACOS_URL:-http://localhost:8848}"
# 认证方式 1: 用户名密码（可选，开源 Nacos 常用）
NACOS_USERNAME="${NACOS_USERNAME:-}"
NACOS_PASSWORD="${NACOS_PASSWORD:-}"
# 认证方式 2: AccessKey/SecretKey（可选，商业化 Nacos）
NACOS_ACCESS_KEY="${NACOS_ACCESS_KEY:-}"
NACOS_SECRET_KEY="${NACOS_SECRET_KEY:-}"

# MCP 配置（仅当 IMPORT_MCP_TO_NACOS=true 时需要）
MCP_JSON_FILE="${MCP_JSON_FILE:-}"  # MCP 数据文件路径

# 网关配置（仅当 REGISTER_GATEWAY=true 时需要）
GATEWAY_TYPE="${GATEWAY_TYPE:-HIGRESS}"  # HIGRESS 或 APIG_AI
GATEWAY_NAME="${GATEWAY_NAME:-higress-demo}"

# Higress 网关配置（当 GATEWAY_TYPE=HIGRESS 时需要）
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
GATEWAY_USERNAME="${GATEWAY_USERNAME:-admin}"
GATEWAY_PASSWORD="${GATEWAY_PASSWORD:-admin}"

# AI 网关配置（当 GATEWAY_TYPE=APIG_AI 时需要）
APIG_REGION="${APIG_REGION:-cn-hangzhou}"
APIG_ACCESS_KEY="${APIG_ACCESS_KEY:-}"
APIG_SECRET_KEY="${APIG_SECRET_KEY:-}"

# Portal 配置
PORTAL_NAME="${PORTAL_NAME:-demo}"

# 最大重试次数
MAX_RETRIES=3

# 全局变量
ADMIN_TOKEN=""
DEVELOPER_TOKEN=""
NACOS_ACCESS_TOKEN=""  # Nacos 登录 Token（用于导入 MCP）
NACOS_ID=""
GATEWAY_ID=""
PORTAL_ID=""
DEVELOPER_ID=""
CONSUMER_ID=""

########################################
# 日志函数
########################################
log() { 
  echo "[$(date +'%H:%M:%S')] $*" 
}

err() { 
  echo "[ERROR] $*" >&2 
}

success() {
  echo "[✓] $*"
}

########################################
# URL 编码函数（用于 MCP 导入）
########################################
url_encode() {
  local input="$1"
  # 使用 jq 的 @uri 过滤器进行 URL 编码
  # jq 已经是脚本的必需依赖，无需额外安装
  echo -n "$input" | jq -sRr '@uri'
}

########################################
# 检查依赖
########################################
check_dependencies() {
  log "检查依赖..."
  
  if ! command -v curl &> /dev/null; then
    err "curl 未安装"
    exit 1
  fi
  
  if ! command -v jq &> /dev/null; then
    err "jq 未安装，请先安装: brew install jq (macOS) 或 apt-get install jq (Linux)"
    exit 1
  fi
  
  success "依赖检查通过"
}

########################################
# 调用 API 通用函数
########################################
call_api() {
  local api_name="$1"
  local method="$2"
  local path="$3"
  local body="${4:-}"
  local token="${5:-}"
  
  local url="http://${HIMARKET_HOST}${path}"
  
  log "调用 [${api_name}]: ${method} ${url}"
  
  local curl_cmd="curl -sS -w '\nHTTP_CODE:%{http_code}' -X ${method} '${url}'"
  curl_cmd="${curl_cmd} -H 'Content-Type: application/json'"
  curl_cmd="${curl_cmd} -H 'Accept: application/json, text/plain, */*'"
  
  if [[ -n "$token" ]]; then
    curl_cmd="${curl_cmd} -H 'Authorization: Bearer ${token}'"
  fi
  
  if [[ -n "$body" ]]; then
    curl_cmd="${curl_cmd} -d '${body}'"
  fi
  
  curl_cmd="${curl_cmd} --connect-timeout 10 --max-time 30"
  
  local result
  result=$(eval "$curl_cmd" 2>&1 || echo "HTTP_CODE:000")
  
  local http_code=""
  local response=""
  
  if [[ "$result" =~ HTTP_CODE:([0-9]{3}) ]]; then
    http_code="${BASH_REMATCH[1]}"
    response=$(echo "$result" | sed '/HTTP_CODE:/d')
  else
    http_code="000"
    response="$result"
  fi
  
  export API_RESPONSE="$response"
  export API_HTTP_CODE="$http_code"
  
  if [[ "$http_code" =~ ^2[0-9]{2}$ ]] || [[ "$http_code" == "409" ]]; then
    return 0
  else
    log "响应: ${response}"
    return 1
  fi
}

########################################
# 步骤 1: 注册管理员账号
########################################
step_1_register_admin() {
  log "=========================================="
  log "步骤 1: 注册管理员账号"
  log "=========================================="
  
  local body="{\"username\":\"${ADMIN_USERNAME}\",\"password\":\"${ADMIN_PASSWORD}\"}"
  
  local attempt=1
  while (( attempt <= MAX_RETRIES )); do
    if call_api "注册管理员" "POST" "/admins/init" "$body"; then
      if [[ "$API_HTTP_CODE" == "409" ]]; then
        success "管理员账号已存在（幂等）"
      else
        success "管理员账号注册成功"
      fi
      return 0
    fi
    
    # 检查是否是账号已存在的错误（即使返回 500）
    if echo "$API_RESPONSE" | grep -qi "Duplicate entry\|already exists\|已存在"; then
      success "管理员账号已存在（幂等）"
      return 0
    fi
    
    if (( attempt < MAX_RETRIES )); then
      log "重试 (${attempt}/${MAX_RETRIES})..."
      sleep 3
    fi
    attempt=$((attempt+1))
  done
  
  err "注册管理员账号失败"
  return 1
}

########################################
# 步骤 2: 管理员登录
########################################
step_2_admin_login() {
  log "=========================================="
  log "步骤 2: 管理员登录"
  log "=========================================="
  
  local body="{\"username\":\"${ADMIN_USERNAME}\",\"password\":\"${ADMIN_PASSWORD}\"}"
  
  if call_api "管理员登录" "POST" "/admins/login" "$body"; then
    # 尝试多种可能的 Token 字段路径
    ADMIN_TOKEN=$(echo "$API_RESPONSE" | jq -r '.data.access_token // .access_token // .data.token // .token // .data.accessToken // .accessToken // empty')
    
    if [[ -z "$ADMIN_TOKEN" ]]; then
      err "无法提取管理员 Token"
      log "API 响应: $API_RESPONSE"
      return 1
    fi
    
    success "管理员登录成功"
    log "Token: ${ADMIN_TOKEN:0:30}..."
    return 0
  fi
  
  err "管理员登录失败"
  return 1
}

########################################
# 步骤 3: 注册 Nacos 实例（可选）
########################################
step_3_register_nacos() {
  if [[ "$REGISTER_NACOS" != "true" ]]; then
    log "跳过 Nacos 实例注册（REGISTER_NACOS=false）"
    return 0
  fi
  
  log "=========================================="
  log "步骤 3: 注册 Nacos 实例"
  log "=========================================="
  
  # 构建请求体（支持两种认证方式）
  local body="{\"nacosName\":\"${NACOS_NAME}\",\"serverUrl\":\"${NACOS_URL}\""
  
  # 添加用户名密码认证（如果提供）
  if [[ -n "$NACOS_USERNAME" ]]; then
    body="${body},\"username\":\"${NACOS_USERNAME}\""
  fi
  
  if [[ -n "$NACOS_PASSWORD" ]]; then
    body="${body},\"password\":\"${NACOS_PASSWORD}\""
  fi
  
  # 添加商业化 Nacos 认证（如果提供）
  if [[ -n "$NACOS_ACCESS_KEY" ]]; then
    body="${body},\"accessKey\":\"${NACOS_ACCESS_KEY}\""
  fi
  
  if [[ -n "$NACOS_SECRET_KEY" ]]; then
    body="${body},\"secretKey\":\"${NACOS_SECRET_KEY}\""
  fi
  
  body="${body}}"
  
  log "Nacos 请求体: ${body}"
  
  # 创建 Nacos
  call_api "注册Nacos" "POST" "/nacos" "$body" "$ADMIN_TOKEN" || true
  
  # 查询 Nacos ID
  if call_api "查询Nacos" "GET" "/nacos" "" "$ADMIN_TOKEN"; then
    NACOS_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.nacosName==\"${NACOS_NAME}\") | .nacosId" | head -1)
    
    if [[ -z "$NACOS_ID" ]]; then
      err "无法获取 Nacos ID"
      return 1
    fi
    
    success "Nacos 实例注册成功"
    log "Nacos ID: ${NACOS_ID}"
    return 0
  fi
  
  err "注册 Nacos 实例失败"
  return 1
}

########################################
# 步骤 4: 注册网关实例（可选）
########################################
step_4_register_gateway() {
  if [[ "$REGISTER_GATEWAY" != "true" ]]; then
    log "跳过网关实例注册（REGISTER_GATEWAY=false）"
    return 0
  fi
  
  log "=========================================="
  log "步骤 4: 注册网关实例 (${GATEWAY_TYPE})"
  log "=========================================="
  
  # 根据网关类型构建不同的请求体
  local body=""
  
  if [[ "$GATEWAY_TYPE" == "HIGRESS" ]]; then
    # Higress 网关
    body="{\"gatewayName\":\"${GATEWAY_NAME}\",\"gatewayType\":\"HIGRESS\",\"higressConfig\":{\"address\":\"${GATEWAY_URL}\",\"username\":\"${GATEWAY_USERNAME}\",\"password\":\"${GATEWAY_PASSWORD}\"}}"
    log "注册 Higress 网关: ${GATEWAY_URL}"
  
  elif [[ "$GATEWAY_TYPE" == "APIG_AI" ]]; then
    # 阿里云 AI 网关
    body="{\"gatewayName\":\"${GATEWAY_NAME}\",\"gatewayType\":\"APIG_AI\",\"apigConfig\":{\"region\":\"${APIG_REGION}\",\"accessKey\":\"${APIG_ACCESS_KEY}\",\"secretKey\":\"${APIG_SECRET_KEY}\"}}"
    log "注册阿里云 AI 网关: ${APIG_REGION}"
  
  else
    err "不支持的网关类型: ${GATEWAY_TYPE}"
    err "支持的类型: HIGRESS, APIG_AI"
    return 1
  fi
  
  log "网关请求体: ${body}"
  
  # 创建网关
  call_api "注册网关" "POST" "/gateways" "$body" "$ADMIN_TOKEN" || true
  
  # 查询网关 ID
  if call_api "查询网关" "GET" "/gateways" "" "$ADMIN_TOKEN"; then
    GATEWAY_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.gatewayName==\"${GATEWAY_NAME}\") | .gatewayId" | head -1)
    
    if [[ -z "$GATEWAY_ID" ]]; then
      err "无法获取网关 ID"
      return 1
    fi
    
    success "网关实例注册成功"
    log "Gateway ID: ${GATEWAY_ID}"
    return 0
  fi
  
  err "注册网关实例失败"
  return 1
}

########################################
# 步骤 5: 创建 Portal
########################################
step_5_create_portal() {
  log "=========================================="
  log "步骤 5: 创建 Portal"
  log "=========================================="
  
  local body="{\"name\":\"${PORTAL_NAME}\"}"
  
  # 创建 Portal
  call_api "创建Portal" "POST" "/portals" "$body" "$ADMIN_TOKEN" || true
  
  # 查询 Portal ID
  if call_api "查询Portal" "GET" "/portals" "" "$ADMIN_TOKEN"; then
    PORTAL_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.name==\"${PORTAL_NAME}\") | .portalId" | head -1)
    
    if [[ -z "$PORTAL_ID" ]]; then
      err "无法获取 Portal ID"
      return 1
    fi
    
    success "Portal 创建成功"
    log "Portal ID: ${PORTAL_ID}"
    return 0
  fi
  
  err "创建 Portal 失败"
  return 1
}

########################################
# 步骤 6: 绑定域名到 Portal
########################################
step_6_bind_domain() {
  log "=========================================="
  log "步骤 6: 绑定域名到 Portal"
  log "=========================================="
  
  local body="{\"domain\":\"${HIMARKET_FRONTEND_URL}\",\"type\":\"CUSTOM\",\"protocol\":\"HTTP\"}"
  
  if call_api "绑定域名" "POST" "/portals/${PORTAL_ID}/domains" "$body" "$ADMIN_TOKEN"; then
    if [[ "$API_HTTP_CODE" == "409" ]]; then
      success "域名已绑定（幂等）"
    else
      success "域名绑定成功"
    fi
    return 0
  fi
  
  log "域名绑定失败，但继续执行"
  return 0
}


########################################
# 步骤 7: 注册开发者账号
########################################
step_7_register_developer() {
  log "=========================================="
  log "步骤 7: 注册开发者账号"
  log "=========================================="
  
  local body="{\"username\":\"${DEVELOPER_USERNAME}\",\"password\":\"${DEVELOPER_PASSWORD}\"}"
  
  if call_api "注册开发者" "POST" "/developers" "$body"; then
    if [[ "$API_HTTP_CODE" == "409" ]]; then
      success "开发者账号已存在（幂等）"
    else
      success "开发者账号注册成功"
    fi
    return 0
  fi
  
  err "注册开发者账号失败"
  return 1
}

########################################
# 步骤 8: 查询并审批开发者
########################################
step_8_approve_developer() {
  log "=========================================="
  log "步骤 9: 审批开发者账号"
  log "=========================================="
  
  # 查询开发者列表
  if ! call_api "查询开发者" "GET" "/developers?portalId=${PORTAL_ID}&page=1&size=100" "" "$ADMIN_TOKEN"; then
    err "查询开发者列表失败"
    return 1
  fi
  
  # 提取开发者 ID
  DEVELOPER_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.username==\"${DEVELOPER_USERNAME}\") | .developerId" | head -1)
  
  if [[ -z "$DEVELOPER_ID" ]]; then
    err "未找到开发者: ${DEVELOPER_USERNAME}"
    return 1
  fi
  
  log "Developer ID: ${DEVELOPER_ID}"
  
  # 审批开发者
  local body="{\"portalId\":\"${PORTAL_ID}\",\"status\":\"APPROVED\"}"
  
  if call_api "审批开发者" "PATCH" "/developers/${DEVELOPER_ID}/status" "$body" "$ADMIN_TOKEN"; then
    success "开发者账号审批成功"
    return 0
  fi
  
  # 检查是否已经是 APPROVED 状态
  if echo "$API_RESPONSE" | grep -q "APPROVED"; then
    success "开发者已处于审批状态（幂等）"
    return 0
  fi
  
  err "审批开发者失败"
  return 1
}

########################################
# 步骤 9: 开发者登录
########################################
step_9_developer_login() {
  log "=========================================="
  log "步骤 9: 开发者登录"
  log "=========================================="
  
  local body="{\"username\":\"${DEVELOPER_USERNAME}\",\"password\":\"${DEVELOPER_PASSWORD}\"}"
  
  if call_api "开发者登录" "POST" "/developers/login" "$body"; then
    # 尝试多种可能的 Token 字段路径
    DEVELOPER_TOKEN=$(echo "$API_RESPONSE" | jq -r '.data.access_token // .access_token // .data.token // .token // .data.accessToken // .accessToken // empty')
    
    if [[ -z "$DEVELOPER_TOKEN" ]]; then
      err "无法提取开发者 Token"
      log "API 响应: $API_RESPONSE"
      return 1
    fi
    
    success "开发者登录成功"
    log "Token: ${DEVELOPER_TOKEN:0:30}..."
    return 0
  fi
  
  err "开发者登录失败"
  return 1
}

########################################
# 步骤 10: 导入 MCP 到 Nacos（可选）
########################################
step_10_import_mcp_to_nacos() {
  if [[ "$IMPORT_MCP_TO_NACOS" != "true" ]]; then
    log "跳过 MCP 导入（IMPORT_MCP_TO_NACOS=false）"
    return 0
  fi
  
  # 必须先注册 Nacos
  if [[ "$REGISTER_NACOS" != "true" ]]; then
    err "导入 MCP 需要先注册 Nacos 实例（REGISTER_NACOS=true）"
    return 1
  fi
  
  # 检查 MCP JSON 文件
  if [[ -z "$MCP_JSON_FILE" ]]; then
    err "未指定 MCP JSON 文件路径（MCP_JSON_FILE）"
    return 1
  fi
  
  if [[ ! -f "$MCP_JSON_FILE" ]]; then
    err "MCP 数据文件不存在: $MCP_JSON_FILE"
    return 1
  fi
  
  # 必须有 Nacos 用户名密码（MCP API 需要）
  if [[ -z "$NACOS_USERNAME" ]] || [[ -z "$NACOS_PASSWORD" ]]; then
    err "导入 MCP 需要 Nacos 用户名密码（NACOS_USERNAME, NACOS_PASSWORD）"
    return 1
  fi
  
  log "=========================================="
  log "步骤 10: 导入 MCP 到 Nacos"
  log "=========================================="
  
  log "MCP 数据文件: $MCP_JSON_FILE"
  
  # 1. 登录 Nacos 获取 accessToken
  log "登录 Nacos 获取 accessToken..."
  
  # 从 NACOS_URL 中提取 host:port
  local nacos_host=""
  if [[ "$NACOS_URL" =~ ^https?://([^/]+) ]]; then
    nacos_host="${BASH_REMATCH[1]}"
  else
    nacos_host="$NACOS_URL"
  fi
  
  local login_url="http://${nacos_host}/nacos/v1/auth/login"
  
  log "Nacos 登录地址: $login_url"
  
  local login_resp=$(curl -sS -X POST "$login_url" \
    -d "username=${NACOS_USERNAME}" \
    -d "password=${NACOS_PASSWORD}" 2>&1 || echo "")
  
  if [[ -z "$login_resp" ]]; then
    err "Nacos 登录请求失败"
    return 1
  fi
  
  # 提取 accessToken
  NACOS_ACCESS_TOKEN=$(echo "$login_resp" | jq -r '.accessToken // empty' 2>/dev/null)
  
  if [[ -z "$NACOS_ACCESS_TOKEN" ]]; then
    err "无法从 Nacos 登录响应中提取 accessToken"
    log "Nacos 响应: $login_resp"
    return 1
  fi
  
  success "Nacos 登录成功"
  log "Access Token: ${NACOS_ACCESS_TOKEN:0:30}..."
  
  # 2. 解析 MCP JSON 文件
  log "解析 MCP JSON 文件..."
  
  local is_array=$(jq 'type == "array"' "$MCP_JSON_FILE" 2>/dev/null)
  
  if [[ "$is_array" != "true" && "$is_array" != "false" ]]; then
    err "无法解析 MCP JSON 文件格式"
    return 1
  fi
  
  local success_count=0
  local fail_count=0
  local skip_count=0
  
  if [[ "$is_array" == "true" ]]; then
    # 数组格式，批量导入
    local array_length=$(jq 'length' "$MCP_JSON_FILE")
    log "检测到数组格式，共 $array_length 个 MCP 配置"
    
    for ((i=0; i<array_length; i++)); do
      log ""
      log "---------- 处理第 $((i+1))/$array_length 个 MCP ----------"
      
      if import_single_mcp_from_array "$i"; then
        ((success_count++))
      else
        local exit_code=$?
        if [[ $exit_code -eq 2 ]]; then
          ((skip_count++))
        else
          ((fail_count++))
        fi
      fi
    done
  else
    # 单个对象格式
    log "检测到单个对象格式"
    
    if import_single_mcp_from_object; then
      ((success_count++))
    else
      local exit_code=$?
      if [[ $exit_code -eq 2 ]]; then
        ((skip_count++))
      else
        ((fail_count++))
      fi
    fi
  fi
  
  log ""
  log "=========================================="
  log "MCP 导入完成！"
  log "成功: $success_count, 跳过: $skip_count, 失败: $fail_count"
  log "=========================================="
  
  if [[ $fail_count -gt 0 ]]; then
    return 1
  fi
  
  return 0
}

########################################
# 从数组中导入单个 MCP
########################################
import_single_mcp_from_array() {
  local index=$1
  
  # 提取 serverSpecification
  local server_spec=$(jq -c ".[$index].serverSpecification" "$MCP_JSON_FILE" 2>/dev/null)
  if [[ "$server_spec" == "null" ]] || [[ -z "$server_spec" ]]; then
    err "第 $((index+1)) 个配置未找到 serverSpecification，跳过"
    return 1
  fi
  
  # 提取 MCP 名称
  local mcp_name=$(echo "$server_spec" | jq -r '.name // "unknown"')
  log "MCP 名称: $mcp_name"
  
  # 提取 toolSpecification (可选)
  local tool_spec=$(jq -c ".[$index].toolSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # 提取 endpointSpecification (可选)
  local endpoint_spec=$(jq -c ".[$index].endpointSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # 调用创建函数
  create_mcp_in_nacos "$mcp_name" "$server_spec" "$tool_spec" "$endpoint_spec"
}

########################################
# 从对象中导入单个 MCP
########################################
import_single_mcp_from_object() {
  # 提取 serverSpecification
  local server_spec=$(jq -c ".serverSpecification" "$MCP_JSON_FILE" 2>/dev/null)
  if [[ "$server_spec" == "null" ]] || [[ -z "$server_spec" ]]; then
    err "未找到 serverSpecification"
    return 1
  fi
  
  # 提取 MCP 名称
  local mcp_name=$(echo "$server_spec" | jq -r '.name // "unknown"')
  log "MCP 名称: $mcp_name"
  
  # 提取 toolSpecification (可选)
  local tool_spec=$(jq -c ".toolSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # 提取 endpointSpecification (可选)
  local endpoint_spec=$(jq -c ".endpointSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # 调用创建函数
  create_mcp_in_nacos "$mcp_name" "$server_spec" "$tool_spec" "$endpoint_spec"
}

########################################
# 在 Nacos 中创建单个 MCP
########################################
create_mcp_in_nacos() {
  local mcp_name="$1"
  local server_spec="$2"
  local tool_spec="$3"
  local endpoint_spec="$4"
  
  log "正在创建 MCP: $mcp_name"
  
  # 编码参数
  local enc_server_spec=$(url_encode "$server_spec")
  
  if [[ $? -ne 0 ]]; then
    err "URL 编码失败"
    return 1
  fi
  
  # 构建表单数据
  local form_body="serverSpecification=${enc_server_spec}"
  
  if [[ -n "$tool_spec" ]]; then
    local enc_tool_spec=$(url_encode "$tool_spec")
    form_body="${form_body}&toolSpecification=${enc_tool_spec}"
  fi
  
  if [[ -n "$endpoint_spec" ]]; then
    local enc_endpoint_spec=$(url_encode "$endpoint_spec")
    form_body="${form_body}&endpointSpecification=${enc_endpoint_spec}"
  fi
  
  # 调用 Nacos MCP API
  local nacos_host=""
  if [[ "$NACOS_URL" =~ ^https?://([^/]+) ]]; then
    nacos_host="${BASH_REMATCH[1]}"
  else
    nacos_host="$NACOS_URL"
  fi
  
  local create_url="http://${nacos_host}/nacos/v3/admin/ai/mcp"
  
  log "调用 Nacos MCP API: $create_url"
  
  local resp=$(curl -sS -w "\nHTTP_CODE:%{http_code}" -X POST "$create_url" \
    -H "accessToken: $NACOS_ACCESS_TOKEN" \
    -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
    -d "$form_body" 2>&1 || echo "HTTP_CODE:000")
  
  local http_code=""
  local body=""
  
  if [[ "$resp" =~ HTTP_CODE:([0-9]{3}) ]]; then
    http_code="${BASH_REMATCH[1]}"
    body=$(echo "$resp" | sed '/HTTP_CODE:/d')
  else
    http_code="000"
    body="$resp"
  fi
  
  log "HTTP 状态码: $http_code"
  
  # 幂等性处理：409 或 "已存在" 视为成功
  if [[ "$http_code" == "409" ]] || echo "$body" | grep -qi "has existed\|already exists\|已存在"; then
    success "MCP '$mcp_name' 已存在，跳过创建（幂等）"
    return 2  # 返回 2 表示跳过
  fi
  
  if [[ "$http_code" == "200" ]]; then
    success "MCP '$mcp_name' 创建成功"
    return 0
  fi
  
  err "创建 MCP '$mcp_name' 失败（HTTP $http_code）"
  log "响应: $body"
  return 1
}

########################################
# 步骤 11: 在 HiMarket 中上架 Nacos MCP（可选）
########################################
step_11_publish_mcp_to_himarket() {
  if [[ "$PUBLISH_MCP_TO_HIMARKET" != "true" ]]; then
    log "跳过 MCP 上架（PUBLISH_MCP_TO_HIMARKET=false）"
    return 0
  fi
  
  # 必须先导入 MCP 到 Nacos
  if [[ "$IMPORT_MCP_TO_NACOS" != "true" ]]; then
    err "上架 MCP 需要先导入到 Nacos（IMPORT_MCP_TO_NACOS=true）"
    return 1
  fi
  
  log "=========================================="
  log "步骤 11: 在 HiMarket 中上架 MCP"
  log "=========================================="
  
  # 解析 MCP JSON 文件，提取需要上架的 MCP
  local is_array=$(jq 'type == "array"' "$MCP_JSON_FILE" 2>/dev/null)
  
  if [[ "$is_array" != "true" ]]; then
    err "仅支持数组格式的 MCP JSON 文件"
    return 1
  fi
  
  local array_length=$(jq 'length' "$MCP_JSON_FILE")
  local success_count=0
  local skip_count=0
  local fail_count=0
  
  log "检测到 $array_length 个 MCP 配置"
  
  for ((i=0; i<array_length; i++)); do
    local mcp_config=$(jq ".[$i]" "$MCP_JSON_FILE")
    
    # 检查是否有 himarket 配置
    local himarket_config=$(echo "$mcp_config" | jq -r '.himarket // empty')
    if [[ -z "$himarket_config" ]] || [[ "$himarket_config" == "null" ]]; then
      ((skip_count++))
      continue
    fi
    
    log ""
    log "---------- 处理第 $((i+1))/$array_length 个 MCP ----------"
    
    if publish_single_mcp "$mcp_config"; then
      ((success_count++))
    else
      ((fail_count++))
    fi
  done
  
  log ""
  log "=========================================="
  log "MCP 上架完成！"
  log "成功: $success_count, 跳过: $skip_count, 失败: $fail_count"
  log "=========================================="
  
  return 0
}

########################################
# 上架单个 MCP 到 HiMarket
########################################
publish_single_mcp() {
  local mcp_config="$1"
  
  # 提取 MCP 基本信息
  local mcp_name=$(echo "$mcp_config" | jq -r '.serverSpecification.name // .name')
  
  # 提取 HiMarket 配置
  local product_name=$(echo "$mcp_config" | jq -r '.himarket.product.name')
  local product_desc=$(echo "$mcp_config" | jq -r '.himarket.product.description')
  local product_type=$(echo "$mcp_config" | jq -r '.himarket.product.type // "MCP_SERVER"')
  local publish_to_portal=$(echo "$mcp_config" | jq -r '.himarket.publishToPortal // false')
  local namespace_id=$(echo "$mcp_config" | jq -r '.himarket.namespaceId // "public"')
  
  log "[${mcp_name}] 开始上架到 HiMarket..."
  
  # 1. 创建 API 产品
  log "[${mcp_name}] 创建 API 产品..."
  local product_body="{\"name\":\"${product_name}\",\"description\":\"${product_desc}\",\"type\":\"${product_type}\"}"
  
  call_api "创建产品" "POST" "/products" "$product_body" "$ADMIN_TOKEN" || true
  
  # 查询产品 ID
  call_api "查询产品" "GET" "/products" "" "$ADMIN_TOKEN" || return 1
  
  local product_id=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.name==\"${product_name}\") | .productId" | head -1)
  
  if [[ -z "$product_id" ]]; then
    err "[${mcp_name}] 无法获取产品 ID"
    return 1
  fi
  
  log "[${mcp_name}] Product ID: ${product_id}"
  
  # 2. 关联产品到 Nacos MCP（核心步骤）
  log "[${mcp_name}] 关联产品到 Nacos MCP..."
  
  # 构造 type 字段：MCP Server (namespace_id)
  local ref_type="MCP Server (${namespace_id})"
  
  local ref_body="{\"nacosId\":\"${NACOS_ID}\",\"sourceType\":\"NACOS\",\"productId\":\"${product_id}\",\"nacosRefConfig\":{\"mcpServerName\":\"${mcp_name}\",\"fromGatewayType\":\"NACOS\",\"type\":\"${ref_type}\",\"namespaceId\":\"${namespace_id}\"}}"
  
  if call_api "关联产品到Nacos" "POST" "/products/${product_id}/ref" "$ref_body" "$ADMIN_TOKEN"; then
    if [[ "$API_HTTP_CODE" =~ ^2[0-9]{2}$ ]]; then
      success "[${mcp_name}] 产品关联成功"
    elif [[ "$API_HTTP_CODE" == "409" ]]; then
      success "[${mcp_name}] 产品已关联（幂等）"
    else
      err "[${mcp_name}] 产品关联失败: HTTP ${API_HTTP_CODE}"
      return 1
    fi
  else
    err "[${mcp_name}] 产品关联 API 调用失败"
    return 1
  fi
  
  # 3. 发布到 Portal（可选）
  if [[ "$publish_to_portal" == "true" ]]; then
    log "[${mcp_name}] 发布产品到 Portal..."
    
    if call_api "发布到Portal" "POST" "/products/${product_id}/publications/${PORTAL_ID}" "" "$ADMIN_TOKEN"; then
      success "[${mcp_name}] 发布到 Portal 成功"
    else
      log "[${mcp_name}] 发布到 Portal 失败（可能已发布）"
    fi
  fi
  
  success "[${mcp_name}] MCP 上架完成"
  return 0
}

########################################
# 打印总结信息
########################################
print_summary() {
  log ""
  log "=========================================="
  log "✓ HiMarket 初始化完成！"
  log "=========================================="
  log ""
  log "【服务地址】"
  log "  管理后台: http://${HIMARKET_HOST}"
  log "  开发者门户: ${HIMARKET_FRONTEND_URL}"
  log ""
  log "【管理员账号】"
  log "  用户名: ${ADMIN_USERNAME}"
  log "  密码: ${ADMIN_PASSWORD}"
  log ""
  log "【开发者账号】"
  log "  用户名: ${DEVELOPER_USERNAME}"
  log "  密码: ${DEVELOPER_PASSWORD}"
  log ""
  
  # 只有注册了才显示
  if [[ "$REGISTER_NACOS" == "true" && -n "$NACOS_ID" ]]; then
    log "【已注册 Nacos 实例】"
    log "  名称: ${NACOS_NAME}"
    log "  ID: ${NACOS_ID}"
    log "  地址: ${NACOS_URL}"
    log ""
  fi
  
  if [[ "$REGISTER_GATEWAY" == "true" && -n "$GATEWAY_ID" ]]; then
    log "【已注册网关实例】"
    log "  名称: ${GATEWAY_NAME}"
    log "  ID: ${GATEWAY_ID}"
    log "  类型: ${GATEWAY_TYPE}"
    log ""
  fi
  
  if [[ "$IMPORT_MCP_TO_NACOS" == "true" ]]; then
    log "【已导入 MCP 到 Nacos】"
    log "  数据文件: ${MCP_JSON_FILE}"
    if [[ "$PUBLISH_MCP_TO_HIMARKET" == "true" ]]; then
      log "  已上架到 HiMarket"
    fi
    log ""
  fi
  
  log "【Portal 信息】"
  log "  名称: ${PORTAL_NAME}"
  log "  ID: ${PORTAL_ID}"
  log "  绑定域名: ${HIMARKET_FRONTEND_URL}"
  log ""
  log "=========================================="
  log ""
  log "🎉 您现在可以："
  log "  1. 访问管理后台管理 API 产品和开发者"
  log "  2. 访问开发者门户浏览和订阅 API"
  
  if [[ "$REGISTER_NACOS" == "true" || "$REGISTER_GATEWAY" == "true" ]]; then
    log "  3. 在管理后台中配置和管理已注册的实例"
  fi
  
  log ""
}

########################################
# 主流程
########################################
main() {
  log "=========================================="
  log "HiMarket 本地环境一键初始化脚本"
  log "=========================================="
  log ""
  log "配置信息:"
  log "  前端访问: ${HIMARKET_FRONTEND_URL}"
  log "  注册 Nacos: ${REGISTER_NACOS}"
  log "  注册网关: ${REGISTER_GATEWAY}"
  log "  导入 MCP: ${IMPORT_MCP_TO_NACOS}"
  log "  上架 MCP: ${PUBLISH_MCP_TO_HIMARKET}"
  
  if [[ "$REGISTER_NACOS" == "true" ]]; then
    log "  Nacos 地址: ${NACOS_URL}"
  fi
  
  if [[ "$REGISTER_GATEWAY" == "true" ]]; then
    log "  网关类型: ${GATEWAY_TYPE}"
  fi
  
  if [[ "$IMPORT_MCP_TO_NACOS" == "true" ]]; then
    log "  MCP 文件: ${MCP_JSON_FILE}"
  fi
  
  log ""
  
  # 检查依赖
  check_dependencies
  
  # 执行初始化步骤
  step_1_register_admin || exit 1
  step_2_admin_login || exit 1
  step_3_register_nacos || exit 1  # 内部有开关判断
  step_4_register_gateway || exit 1  # 内部有开关判断
  step_5_create_portal || exit 1
  step_6_bind_domain || exit 1
  step_7_register_developer || exit 1
  step_8_approve_developer || exit 1
  step_9_developer_login || exit 1
  step_10_import_mcp_to_nacos || exit 1  # 内部有开关判断
  step_11_publish_mcp_to_himarket || exit 1  # 内部有开关判断
  
  # 打印总结
  print_summary
  
  log "初始化完成！"
}

main "$@"

