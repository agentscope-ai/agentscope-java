#!/usr/bin/env bash
# HiMarket Local Environment One-Click Initialization Script
# Features:
#   1. Initialize admin account
#   2. Register Nacos instance
#   3. Register gateway instance (supports Higress and Alibaba Cloud AI Gateway)
#   4. Create Portal
#   5. Bind domain to Portal
#   6. Register developer account and approve
#   7. Import MCP to Nacos (optional)
#   8. Publish MCP in HiMarket (optional)
#
# Usage:
#   ./init-himarket-local.sh
#
# Environment variable configuration (optional, with defaults):
#   HIMARKET_FRONTEND_URL=http://localhost:3000
#   ADMIN_USERNAME=admin
#   ADMIN_PASSWORD=admin
#   DEVELOPER_USERNAME=demo
#   DEVELOPER_PASSWORD=demo123
#
#   # Nacos configuration (required when REGISTER_NACOS=true)
#   REGISTER_NACOS=false
#   NACOS_NAME=nacos-demo
#   NACOS_URL=http://localhost:8848
#   # Authentication method 1 (optional):
#   NACOS_USERNAME=nacos
#   NACOS_PASSWORD=nacos
#   # Authentication method 2 (optional):
#   NACOS_ACCESS_KEY=LTAI5t...
#   NACOS_SECRET_KEY=xxx...
#
#   # Gateway configuration (required when REGISTER_GATEWAY=true, supports HIGRESS or APIG_AI)
#   REGISTER_GATEWAY=false
#   GATEWAY_TYPE=HIGRESS  # or APIG_AI
#   GATEWAY_NAME=higress-demo
#   # Higress configuration:
#   GATEWAY_URL=http://localhost:8080
#   GATEWAY_USERNAME=admin
#   GATEWAY_PASSWORD=admin
#   # AI Gateway configuration:
#   APIG_REGION=cn-hangzhou
#   APIG_ACCESS_KEY=LTAI5t...
#   APIG_SECRET_KEY=xxx...
#
#   # MCP import configuration (required when IMPORT_MCP_TO_NACOS=true)
#   IMPORT_MCP_TO_NACOS=false
#   MCP_JSON_FILE=/path/to/nacos-mcp.json
#
#   # MCP publish configuration (enabled by default, requires MCP import first)
#   PUBLISH_MCP_TO_HIMARKET=true  # Publish MCP to HiMarket developer portal (default true)
#
#   PORTAL_NAME=demo

set -euo pipefail

########################################
# Configuration Parameters
########################################

# HiMarket service address (fixed value, not configurable)
HIMARKET_HOST="localhost:8080"  # Server port, for all API requests
HIMARKET_FRONTEND_URL="${HIMARKET_FRONTEND_URL:-http://localhost:3000}"  # Frontend access URL, used for domain binding

# Admin credentials
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"

# Developer credentials
DEVELOPER_USERNAME="${DEVELOPER_USERNAME:-demo}"
DEVELOPER_PASSWORD="${DEVELOPER_PASSWORD:-demo123}"

# Feature switches
REGISTER_NACOS="${REGISTER_NACOS:-false}"      # Whether to register Nacos instance
REGISTER_GATEWAY="${REGISTER_GATEWAY:-false}"  # Whether to register gateway instance
IMPORT_MCP_TO_NACOS="${IMPORT_MCP_TO_NACOS:-false}"  # Whether to import MCP to Nacos
PUBLISH_MCP_TO_HIMARKET="${PUBLISH_MCP_TO_HIMARKET:-true}"  # Whether to publish MCP in HiMarket (enabled by default)

# Nacos configuration (required only when REGISTER_NACOS=true)
NACOS_NAME="${NACOS_NAME:-nacos-demo}"
NACOS_URL="${NACOS_URL:-http://localhost:8848}"
# Authentication method 1: username/password (optional, common for open source Nacos)
NACOS_USERNAME="${NACOS_USERNAME:-}"
NACOS_PASSWORD="${NACOS_PASSWORD:-}"
# Authentication method 2: AccessKey/SecretKey (optional, for commercial Nacos)
NACOS_ACCESS_KEY="${NACOS_ACCESS_KEY:-}"
NACOS_SECRET_KEY="${NACOS_SECRET_KEY:-}"

# MCP configuration (required only when IMPORT_MCP_TO_NACOS=true)
MCP_JSON_FILE="${MCP_JSON_FILE:-}"  # MCP data file path

# Gateway configuration (required only when REGISTER_GATEWAY=true)
GATEWAY_TYPE="${GATEWAY_TYPE:-HIGRESS}"  # HIGRESS or APIG_AI
GATEWAY_NAME="${GATEWAY_NAME:-higress-demo}"

# Higress gateway configuration (required when GATEWAY_TYPE=HIGRESS)
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
GATEWAY_USERNAME="${GATEWAY_USERNAME:-admin}"
GATEWAY_PASSWORD="${GATEWAY_PASSWORD:-admin}"

# AI gateway configuration (required when GATEWAY_TYPE=APIG_AI)
APIG_REGION="${APIG_REGION:-cn-hangzhou}"
APIG_ACCESS_KEY="${APIG_ACCESS_KEY:-}"
APIG_SECRET_KEY="${APIG_SECRET_KEY:-}"

# Portal configuration
PORTAL_NAME="${PORTAL_NAME:-demo}"

# Maximum retry count
MAX_RETRIES=3

# Global variables
ADMIN_TOKEN=""
DEVELOPER_TOKEN=""
NACOS_ACCESS_TOKEN=""  # Nacos login Token (used for MCP import)
NACOS_ID=""
GATEWAY_ID=""
PORTAL_ID=""
DEVELOPER_ID=""
CONSUMER_ID=""

########################################
# Logging functions
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
# URL encoding function (for MCP import)
########################################
url_encode() {
  local input="$1"
  # Use jq's @uri filter for URL encoding
  # jq is already a required dependency for the script, no additional installation needed
  echo -n "$input" | jq -sRr '@uri'
}

########################################
# Check dependencies
########################################
check_dependencies() {
  log "Checking dependencies..."
  
  if ! command -v curl &> /dev/null; then
    err "curl is not installed"
    exit 1
  fi
  
  if ! command -v jq &> /dev/null; then
    err "jq is not installed, please install first: brew install jq (macOS) or apt-get install jq (Linux)"
    exit 1
  fi
  
  success "Dependency check passed"
}

########################################
# Generic API call function
########################################
call_api() {
  local api_name="$1"
  local method="$2"
  local path="$3"
  local body="${4:-}"
  local token="${5:-}"
  
  local url="http://${HIMARKET_HOST}${path}"
  
  log "Calling [${api_name}]: ${method} ${url}"
  
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
    log "Response: ${response}"
    return 1
  fi
}

########################################
# Step 1: Register admin account
########################################
step_1_register_admin() {
  log "=========================================="
  log "Step 1: Register admin account"
  log "=========================================="
  
  local body="{\"username\":\"${ADMIN_USERNAME}\",\"password\":\"${ADMIN_PASSWORD}\"}"
  
  local attempt=1
  while (( attempt <= MAX_RETRIES )); do
    if call_api "Register admin" "POST" "/admins/init" "$body"; then
      if [[ "$API_HTTP_CODE" == "409" ]]; then
        success "Admin account already exists (idempotent)"
      else
        success "Admin account registered successfully"
      fi
      return 0
    fi
    
    # Check if error is due to account already existing (even if returns 500)
    if echo "$API_RESPONSE" | grep -qi "Duplicate entry\|already exists\|已存在"; then
      success "Admin account already exists (idempotent)"
      return 0
    fi
    
    if (( attempt < MAX_RETRIES )); then
      log "Retrying (${attempt}/${MAX_RETRIES})..."
      sleep 3
    fi
    attempt=$((attempt+1))
  done
  
  err "Failed to register admin account"
  return 1
}

########################################
# Step 2: Admin login
########################################
step_2_admin_login() {
  log "=========================================="
  log "Step 2: Admin login"
  log "=========================================="
  
  local body="{\"username\":\"${ADMIN_USERNAME}\",\"password\":\"${ADMIN_PASSWORD}\"}"
  
  if call_api "Admin login" "POST" "/admins/login" "$body"; then
    # Try multiple possible Token field paths
    ADMIN_TOKEN=$(echo "$API_RESPONSE" | jq -r '.data.access_token // .access_token // .data.token // .token // .data.accessToken // .accessToken // empty')
    
    if [[ -z "$ADMIN_TOKEN" ]]; then
      err "Unable to extract admin Token"
      log "API response: $API_RESPONSE"
      return 1
    fi
    
    success "Admin login successful"
    log "Token: ${ADMIN_TOKEN:0:30}..."
    return 0
  fi
  
  err "Admin login failed"
  return 1
}

########################################
# Step 3: Register Nacos instance (optional)
########################################
step_3_register_nacos() {
  if [[ "$REGISTER_NACOS" != "true" ]]; then
    log "Skipping Nacos instance registration (REGISTER_NACOS=false)"
    return 0
  fi
  
  log "=========================================="
  log "Step 3: Register Nacos instance"
  log "=========================================="
  
  # Build request body (supports two authentication methods)
  local body="{\"nacosName\":\"${NACOS_NAME}\",\"serverUrl\":\"${NACOS_URL}\""
  
  # Add username/password authentication (if provided)
  if [[ -n "$NACOS_USERNAME" ]]; then
    body="${body},\"username\":\"${NACOS_USERNAME}\""
  fi
  
  if [[ -n "$NACOS_PASSWORD" ]]; then
    body="${body},\"password\":\"${NACOS_PASSWORD}\""
  fi
  
  # Add commercial Nacos authentication (if provided)
  if [[ -n "$NACOS_ACCESS_KEY" ]]; then
    body="${body},\"accessKey\":\"${NACOS_ACCESS_KEY}\""
  fi
  
  if [[ -n "$NACOS_SECRET_KEY" ]]; then
    body="${body},\"secretKey\":\"${NACOS_SECRET_KEY}\""
  fi
  
  body="${body}}"
  
  log "Nacos request body: ${body}"
  
  # Create Nacos
  call_api "Register Nacos" "POST" "/nacos" "$body" "$ADMIN_TOKEN" || true
  
  # Query Nacos ID
  if call_api "Query Nacos" "GET" "/nacos" "" "$ADMIN_TOKEN"; then
    NACOS_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.nacosName==\"${NACOS_NAME}\") | .nacosId" | head -1)
    
    if [[ -z "$NACOS_ID" ]]; then
      err "Unable to get Nacos ID"
      return 1
    fi
    
    success "Nacos instance registered successfully"
    log "Nacos ID: ${NACOS_ID}"
    return 0
  fi
  
  err "Failed to register Nacos instance"
  return 1
}

########################################
# Step 4: Register gateway instance (optional)
########################################
step_4_register_gateway() {
  if [[ "$REGISTER_GATEWAY" != "true" ]]; then
    log "Skipping gateway instance registration (REGISTER_GATEWAY=false)"
    return 0
  fi
  
  log "=========================================="
  log "Step 4: Register gateway instance (${GATEWAY_TYPE})"
  log "=========================================="
  
  # Build different request bodies based on gateway type
  local body=""
  
  if [[ "$GATEWAY_TYPE" == "HIGRESS" ]]; then
    # Higress gateway
    body="{\"gatewayName\":\"${GATEWAY_NAME}\",\"gatewayType\":\"HIGRESS\",\"higressConfig\":{\"address\":\"${GATEWAY_URL}\",\"username\":\"${GATEWAY_USERNAME}\",\"password\":\"${GATEWAY_PASSWORD}\"}}"
    log "Registering Higress gateway: ${GATEWAY_URL}"
  
  elif [[ "$GATEWAY_TYPE" == "APIG_AI" ]]; then
    # Alibaba Cloud AI gateway
    body="{\"gatewayName\":\"${GATEWAY_NAME}\",\"gatewayType\":\"APIG_AI\",\"apigConfig\":{\"region\":\"${APIG_REGION}\",\"accessKey\":\"${APIG_ACCESS_KEY}\",\"secretKey\":\"${APIG_SECRET_KEY}\"}}"
    log "Registering Alibaba Cloud AI gateway: ${APIG_REGION}"
  
  else
    err "Unsupported gateway type: ${GATEWAY_TYPE}"
    err "Supported types: HIGRESS, APIG_AI"
    return 1
  fi
  
  log "Gateway request body: ${body}"
  
  # Create gateway
  call_api "Register gateway" "POST" "/gateways" "$body" "$ADMIN_TOKEN" || true
  
  # Query gateway ID
  if call_api "Query gateway" "GET" "/gateways" "" "$ADMIN_TOKEN"; then
    GATEWAY_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.gatewayName==\"${GATEWAY_NAME}\") | .gatewayId" | head -1)
    
    if [[ -z "$GATEWAY_ID" ]]; then
      err "Unable to get gateway ID"
      return 1
    fi
    
    success "Gateway instance registered successfully"
    log "Gateway ID: ${GATEWAY_ID}"
    return 0
  fi
  
  err "Failed to register gateway instance"
  return 1
}

########################################
# Step 5: Create Portal
########################################
step_5_create_portal() {
  log "=========================================="
  log "Step 5: Create Portal"
  log "=========================================="
  
  local body="{\"name\":\"${PORTAL_NAME}\"}"
  
  # Create Portal
  call_api "Create Portal" "POST" "/portals" "$body" "$ADMIN_TOKEN" || true
  
  # Query Portal ID
  if call_api "Query Portal" "GET" "/portals" "" "$ADMIN_TOKEN"; then
    PORTAL_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.name==\"${PORTAL_NAME}\") | .portalId" | head -1)
    
    if [[ -z "$PORTAL_ID" ]]; then
      err "Unable to get Portal ID"
      return 1
    fi
    
    success "Portal created successfully"
    log "Portal ID: ${PORTAL_ID}"
    return 0
  fi
  
  err "Failed to create Portal"
  return 1
}

########################################
# Step 6: Bind domain to Portal
########################################
step_6_bind_domain() {
  log "=========================================="
  log "Step 6: Bind domain to Portal"
  log "=========================================="
  
  local body="{\"domain\":\"${HIMARKET_FRONTEND_URL}\",\"type\":\"CUSTOM\",\"protocol\":\"HTTP\"}"
  
  if call_api "Bind domain" "POST" "/portals/${PORTAL_ID}/domains" "$body" "$ADMIN_TOKEN"; then
    if [[ "$API_HTTP_CODE" == "409" ]]; then
      success "Domain already bound (idempotent)"
    else
      success "Domain bound successfully"
    fi
    return 0
  fi
  
  log "Domain binding failed, but continuing execution"
  return 0
}


########################################
# Step 7: Register developer account
########################################
step_7_register_developer() {
  log "=========================================="
  log "Step 7: Register developer account"
  log "=========================================="
  
  local body="{\"username\":\"${DEVELOPER_USERNAME}\",\"password\":\"${DEVELOPER_PASSWORD}\"}"
  
  if call_api "Register developer" "POST" "/developers" "$body"; then
    if [[ "$API_HTTP_CODE" == "409" ]]; then
      success "Developer account already exists (idempotent)"
    else
      success "Developer account registered successfully"
    fi
    return 0
  fi
  
  err "Failed to register developer account"
  return 1
}

########################################
# Step 8: Query and approve developer
########################################
step_8_approve_developer() {
  log "=========================================="
  log "Step 8: Approve developer account"
  log "=========================================="
  
  # Query developer list
  if ! call_api "Query developers" "GET" "/developers?portalId=${PORTAL_ID}&page=1&size=100" "" "$ADMIN_TOKEN"; then
    err "Failed to query developer list"
    return 1
  fi
  
  # Extract developer ID
  DEVELOPER_ID=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.username==\"${DEVELOPER_USERNAME}\") | .developerId" | head -1)
  
  if [[ -z "$DEVELOPER_ID" ]]; then
    err "Developer not found: ${DEVELOPER_USERNAME}"
    return 1
  fi
  
  log "Developer ID: ${DEVELOPER_ID}"
  
  # Approve developer
  local body="{\"portalId\":\"${PORTAL_ID}\",\"status\":\"APPROVED\"}"
  
  if call_api "Approve developer" "PATCH" "/developers/${DEVELOPER_ID}/status" "$body" "$ADMIN_TOKEN"; then
    success "Developer account approved successfully"
    return 0
  fi
  
  # Check if already in APPROVED status
  if echo "$API_RESPONSE" | grep -q "APPROVED"; then
    success "Developer already in approved status (idempotent)"
    return 0
  fi
  
  err "Failed to approve developer"
  return 1
}

########################################
# Step 9: Developer login
########################################
step_9_developer_login() {
  log "=========================================="
  log "Step 9: Developer login"
  log "=========================================="
  
  local body="{\"username\":\"${DEVELOPER_USERNAME}\",\"password\":\"${DEVELOPER_PASSWORD}\"}"
  
  if call_api "Developer login" "POST" "/developers/login" "$body"; then
    # Try multiple possible Token field paths
    DEVELOPER_TOKEN=$(echo "$API_RESPONSE" | jq -r '.data.access_token // .access_token // .data.token // .token // .data.accessToken // .accessToken // empty')
    
    if [[ -z "$DEVELOPER_TOKEN" ]]; then
      err "Unable to extract developer Token"
      log "API response: $API_RESPONSE"
      return 1
    fi
    
    success "Developer login successful"
    log "Token: ${DEVELOPER_TOKEN:0:30}..."
    return 0
  fi
  
  err "Developer login failed"
  return 1
}

########################################
# Step 10: Import MCP to Nacos (optional)
########################################
step_10_import_mcp_to_nacos() {
  if [[ "$IMPORT_MCP_TO_NACOS" != "true" ]]; then
    log "Skipping MCP import (IMPORT_MCP_TO_NACOS=false)"
    return 0
  fi
  
  # Must register Nacos first
  if [[ "$REGISTER_NACOS" != "true" ]]; then
    err "Importing MCP requires Nacos instance registration first (REGISTER_NACOS=true)"
    return 1
  fi
  
  # Check MCP JSON file
  if [[ -z "$MCP_JSON_FILE" ]]; then
    err "MCP JSON file path not specified (MCP_JSON_FILE)"
    return 1
  fi
  
  if [[ ! -f "$MCP_JSON_FILE" ]]; then
    err "MCP data file does not exist: $MCP_JSON_FILE"
    return 1
  fi
  
  # Must have Nacos username/password (required by MCP API)
  if [[ -z "$NACOS_USERNAME" ]] || [[ -z "$NACOS_PASSWORD" ]]; then
    err "Importing MCP requires Nacos username/password (NACOS_USERNAME, NACOS_PASSWORD)"
    return 1
  fi
  
  log "=========================================="
  log "Step 10: Import MCP to Nacos"
  log "=========================================="
  
  log "MCP data file: $MCP_JSON_FILE"
  
  # 1. Login to Nacos to get accessToken
  log "Logging into Nacos to get accessToken..."
  
  # Extract host:port from NACOS_URL
  local nacos_host=""
  if [[ "$NACOS_URL" =~ ^https?://([^/]+) ]]; then
    nacos_host="${BASH_REMATCH[1]}"
  else
    nacos_host="$NACOS_URL"
  fi
  
  local login_url="http://${nacos_host}/nacos/v1/auth/login"
  
  log "Nacos login URL: $login_url"
  
  local login_resp=$(curl -sS -X POST "$login_url" \
    -d "username=${NACOS_USERNAME}" \
    -d "password=${NACOS_PASSWORD}" 2>&1 || echo "")
  
  if [[ -z "$login_resp" ]]; then
    err "Nacos login request failed"
    return 1
  fi
  
  # Extract accessToken
  NACOS_ACCESS_TOKEN=$(echo "$login_resp" | jq -r '.accessToken // empty' 2>/dev/null)
  
  if [[ -z "$NACOS_ACCESS_TOKEN" ]]; then
    err "Unable to extract accessToken from Nacos login response"
    log "Nacos response: $login_resp"
    return 1
  fi
  
  success "Nacos login successful"
  log "Access Token: ${NACOS_ACCESS_TOKEN:0:30}..."
  
  # 2. Parse MCP JSON file
  log "Parsing MCP JSON file..."
  
  local is_array=$(jq 'type == "array"' "$MCP_JSON_FILE" 2>/dev/null)
  
  if [[ "$is_array" != "true" && "$is_array" != "false" ]]; then
    err "Unable to parse MCP JSON file format"
    return 1
  fi
  
  local success_count=0
  local fail_count=0
  local skip_count=0
  
  if [[ "$is_array" == "true" ]]; then
    # Array format, batch import
    local array_length=$(jq 'length' "$MCP_JSON_FILE")
    log "Detected array format, $array_length MCP configurations in total"
    
    for ((i=0; i<array_length; i++)); do
      log ""
      log "---------- Processing MCP $((i+1))/$array_length ----------"
      
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
    # Single object format
    log "Detected single object format"
    
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
  log "MCP import completed!"
  log "Success: $success_count, Skipped: $skip_count, Failed: $fail_count"
  log "=========================================="
  
  if [[ $fail_count -gt 0 ]]; then
    return 1
  fi
  
  return 0
}

########################################
# Import single MCP from array
########################################
import_single_mcp_from_array() {
  local index=$1
  
  # Extract serverSpecification
  local server_spec=$(jq -c ".[$index].serverSpecification" "$MCP_JSON_FILE" 2>/dev/null)
  if [[ "$server_spec" == "null" ]] || [[ -z "$server_spec" ]]; then
    err "Configuration $((index+1)) missing serverSpecification, skipping"
    return 1
  fi
  
  # Extract MCP name
  local mcp_name=$(echo "$server_spec" | jq -r '.name // "unknown"')
  log "MCP name: $mcp_name"
  
  # Extract toolSpecification (optional)
  local tool_spec=$(jq -c ".[$index].toolSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # Extract endpointSpecification (optional)
  local endpoint_spec=$(jq -c ".[$index].endpointSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # Call create function
  create_mcp_in_nacos "$mcp_name" "$server_spec" "$tool_spec" "$endpoint_spec"
}

########################################
# Import single MCP from object
########################################
import_single_mcp_from_object() {
  # Extract serverSpecification
  local server_spec=$(jq -c ".serverSpecification" "$MCP_JSON_FILE" 2>/dev/null)
  if [[ "$server_spec" == "null" ]] || [[ -z "$server_spec" ]]; then
    err "serverSpecification not found"
    return 1
  fi
  
  # Extract MCP name
  local mcp_name=$(echo "$server_spec" | jq -r '.name // "unknown"')
  log "MCP name: $mcp_name"
  
  # Extract toolSpecification (optional)
  local tool_spec=$(jq -c ".toolSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # Extract endpointSpecification (optional)
  local endpoint_spec=$(jq -c ".endpointSpecification // empty" "$MCP_JSON_FILE" 2>/dev/null || echo "")
  
  # Call create function
  create_mcp_in_nacos "$mcp_name" "$server_spec" "$tool_spec" "$endpoint_spec"
}

########################################
# Create single MCP in Nacos
########################################
create_mcp_in_nacos() {
  local mcp_name="$1"
  local server_spec="$2"
  local tool_spec="$3"
  local endpoint_spec="$4"
  
  log "Creating MCP: $mcp_name"
  
  # Encode parameters
  local enc_server_spec=$(url_encode "$server_spec")
  
  if [[ $? -ne 0 ]]; then
    err "URL encoding failed"
    return 1
  fi
  
  # Build form data
  local form_body="serverSpecification=${enc_server_spec}"
  
  if [[ -n "$tool_spec" ]]; then
    local enc_tool_spec=$(url_encode "$tool_spec")
    form_body="${form_body}&toolSpecification=${enc_tool_spec}"
  fi
  
  if [[ -n "$endpoint_spec" ]]; then
    local enc_endpoint_spec=$(url_encode "$endpoint_spec")
    form_body="${form_body}&endpointSpecification=${enc_endpoint_spec}"
  fi
  
  # Call Nacos MCP API
  local nacos_host=""
  if [[ "$NACOS_URL" =~ ^https?://([^/]+) ]]; then
    nacos_host="${BASH_REMATCH[1]}"
  else
    nacos_host="$NACOS_URL"
  fi
  
  local create_url="http://${nacos_host}/nacos/v3/admin/ai/mcp"
  
  log "Calling Nacos MCP API: $create_url"
  
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
  
  log "HTTP status code: $http_code"
  
  # Idempotency handling: 409 or "already exists" treated as success
  if [[ "$http_code" == "409" ]] || echo "$body" | grep -qi "has existed\|already exists\|已存在"; then
    success "MCP '$mcp_name' already exists, skipping creation (idempotent)"
    return 2  # Return 2 indicates skipped
  fi
  
  if [[ "$http_code" == "200" ]]; then
    success "MCP '$mcp_name' created successfully"
    return 0
  fi
  
  err "Failed to create MCP '$mcp_name' (HTTP $http_code)"
  log "Response: $body"
  return 1
}

########################################
# Step 11: Publish Nacos MCP in HiMarket (optional)
########################################
step_11_publish_mcp_to_himarket() {
  if [[ "$PUBLISH_MCP_TO_HIMARKET" != "true" ]]; then
    log "Skipping MCP publish (PUBLISH_MCP_TO_HIMARKET=false)"
    return 0
  fi
  
  # Must import MCP to Nacos first
  if [[ "$IMPORT_MCP_TO_NACOS" != "true" ]]; then
    err "Publishing MCP requires importing to Nacos first (IMPORT_MCP_TO_NACOS=true)"
    return 1
  fi
  
  log "=========================================="
  log "Step 11: Publish MCP in HiMarket"
  log "=========================================="
  
  # Parse MCP JSON file, extract MCPs to publish
  local is_array=$(jq 'type == "array"' "$MCP_JSON_FILE" 2>/dev/null)
  
  if [[ "$is_array" != "true" ]]; then
    err "Only array format MCP JSON files are supported"
    return 1
  fi
  
  local array_length=$(jq 'length' "$MCP_JSON_FILE")
  local success_count=0
  local skip_count=0
  local fail_count=0
  
  log "Detected $array_length MCP configurations"
  
  for ((i=0; i<array_length; i++)); do
    local mcp_config=$(jq ".[$i]" "$MCP_JSON_FILE")
    
    # Check if himarket configuration exists
    local himarket_config=$(echo "$mcp_config" | jq -r '.himarket // empty')
    if [[ -z "$himarket_config" ]] || [[ "$himarket_config" == "null" ]]; then
      ((skip_count++))
      continue
    fi
    
    log ""
    log "---------- Processing MCP $((i+1))/$array_length ----------"
    
    if publish_single_mcp "$mcp_config"; then
      ((success_count++))
    else
      ((fail_count++))
    fi
  done
  
  log ""
  log "=========================================="
  log "MCP publish completed!"
  log "Success: $success_count, Skipped: $skip_count, Failed: $fail_count"
  log "=========================================="
  
  return 0
}

########################################
# Publish single MCP to HiMarket
########################################
publish_single_mcp() {
  local mcp_config="$1"
  
  # Extract MCP basic information
  local mcp_name=$(echo "$mcp_config" | jq -r '.serverSpecification.name // .name')
  
  # Extract HiMarket configuration
  local product_name=$(echo "$mcp_config" | jq -r '.himarket.product.name')
  local product_desc=$(echo "$mcp_config" | jq -r '.himarket.product.description')
  local product_type=$(echo "$mcp_config" | jq -r '.himarket.product.type // "MCP_SERVER"')
  local publish_to_portal=$(echo "$mcp_config" | jq -r '.himarket.publishToPortal // false')
  local namespace_id=$(echo "$mcp_config" | jq -r '.himarket.namespaceId // "public"')
  
  log "[${mcp_name}] Starting to publish to HiMarket..."
  
  # 1. Create API product
  log "[${mcp_name}] Creating API product..."
  local product_body="{\"name\":\"${product_name}\",\"description\":\"${product_desc}\",\"type\":\"${product_type}\"}"
  
  call_api "Create product" "POST" "/products" "$product_body" "$ADMIN_TOKEN" || true
  
  # Query product ID
  call_api "Query product" "GET" "/products" "" "$ADMIN_TOKEN" || return 1
  
  local product_id=$(echo "$API_RESPONSE" | jq -r ".data.content[]? // .[]? | select(.name==\"${product_name}\") | .productId" | head -1)
  
  if [[ -z "$product_id" ]]; then
    err "[${mcp_name}] Unable to get product ID"
    return 1
  fi
  
  log "[${mcp_name}] Product ID: ${product_id}"
  
  # 2. Associate product to Nacos MCP (core step)
  log "[${mcp_name}] Associating product to Nacos MCP..."
  
  # Construct type field: MCP Server (namespace_id)
  local ref_type="MCP Server (${namespace_id})"
  
  local ref_body="{\"nacosId\":\"${NACOS_ID}\",\"sourceType\":\"NACOS\",\"productId\":\"${product_id}\",\"nacosRefConfig\":{\"mcpServerName\":\"${mcp_name}\",\"fromGatewayType\":\"NACOS\",\"type\":\"${ref_type}\",\"namespaceId\":\"${namespace_id}\"}}"
  
  if call_api "Associate product to Nacos" "POST" "/products/${product_id}/ref" "$ref_body" "$ADMIN_TOKEN"; then
    if [[ "$API_HTTP_CODE" =~ ^2[0-9]{2}$ ]]; then
      success "[${mcp_name}] Product associated successfully"
    elif [[ "$API_HTTP_CODE" == "409" ]]; then
      success "[${mcp_name}] Product already associated (idempotent)"
    else
      err "[${mcp_name}] Product association failed: HTTP ${API_HTTP_CODE}"
      return 1
    fi
  else
    err "[${mcp_name}] Product association API call failed"
    return 1
  fi
  
  # 3. Publish to Portal (optional)
  if [[ "$publish_to_portal" == "true" ]]; then
    log "[${mcp_name}] Publishing product to Portal..."
    
    if call_api "Publish to Portal" "POST" "/products/${product_id}/publications/${PORTAL_ID}" "" "$ADMIN_TOKEN"; then
      success "[${mcp_name}] Published to Portal successfully"
    else
      log "[${mcp_name}] Failed to publish to Portal (may already be published)"
    fi
  fi
  
  success "[${mcp_name}] MCP publish completed"
  return 0
}

########################################
# Print summary information
########################################
print_summary() {
  log ""
  log "=========================================="
  log "✓ HiMarket initialization completed!"
  log "=========================================="
  log ""
  log "[Service URLs]"
  log "  Admin panel: http://${HIMARKET_HOST}"
  log "  Developer portal: ${HIMARKET_FRONTEND_URL}"
  log ""
  log "[Admin account]"
  log "  Username: ${ADMIN_USERNAME}"
  log "  Password: ${ADMIN_PASSWORD}"
  log ""
  log "[Developer account]"
  log "  Username: ${DEVELOPER_USERNAME}"
  log "  Password: ${DEVELOPER_PASSWORD}"
  log ""
  
  # Only show if registered
  if [[ "$REGISTER_NACOS" == "true" && -n "$NACOS_ID" ]]; then
    log "[Registered Nacos instance]"
    log "  Name: ${NACOS_NAME}"
    log "  ID: ${NACOS_ID}"
    log "  URL: ${NACOS_URL}"
    log ""
  fi
  
  if [[ "$REGISTER_GATEWAY" == "true" && -n "$GATEWAY_ID" ]]; then
    log "[Registered gateway instance]"
    log "  Name: ${GATEWAY_NAME}"
    log "  ID: ${GATEWAY_ID}"
    log "  Type: ${GATEWAY_TYPE}"
    log ""
  fi
  
  if [[ "$IMPORT_MCP_TO_NACOS" == "true" ]]; then
    log "[MCP imported to Nacos]"
    log "  Data file: ${MCP_JSON_FILE}"
    if [[ "$PUBLISH_MCP_TO_HIMARKET" == "true" ]]; then
      log "  Published to HiMarket"
    fi
    log ""
  fi
  
  log "[Portal information]"
  log "  Name: ${PORTAL_NAME}"
  log "  ID: ${PORTAL_ID}"
  log "  Bound domain: ${HIMARKET_FRONTEND_URL}"
  log ""
  log "=========================================="
  log ""
  log "🎉 You can now:"
  log "  1. Access admin panel to manage API products and developers"
  log "  2. Access developer portal to browse and subscribe to APIs"
  
  if [[ "$REGISTER_NACOS" == "true" || "$REGISTER_GATEWAY" == "true" ]]; then
    log "  3. Configure and manage registered instances in admin panel"
  fi
  
  log ""
}

########################################
# Main flow
########################################
main() {
  log "=========================================="
  log "HiMarket Local Environment One-Click Initialization Script"
  log "=========================================="
  log ""
  log "Configuration info:"
  log "  Frontend URL: ${HIMARKET_FRONTEND_URL}"
  log "  Register Nacos: ${REGISTER_NACOS}"
  log "  Register gateway: ${REGISTER_GATEWAY}"
  log "  Import MCP: ${IMPORT_MCP_TO_NACOS}"
  log "  Publish MCP: ${PUBLISH_MCP_TO_HIMARKET}"
  
  if [[ "$REGISTER_NACOS" == "true" ]]; then
    log "  Nacos URL: ${NACOS_URL}"
  fi
  
  if [[ "$REGISTER_GATEWAY" == "true" ]]; then
    log "  Gateway type: ${GATEWAY_TYPE}"
  fi
  
  if [[ "$IMPORT_MCP_TO_NACOS" == "true" ]]; then
    log "  MCP file: ${MCP_JSON_FILE}"
  fi
  
  log ""
  
  # Check dependencies
  check_dependencies
  
  # Execute initialization steps
  step_1_register_admin || exit 1
  step_2_admin_login || exit 1
  step_3_register_nacos || exit 1  # Has internal switch check
  step_4_register_gateway || exit 1  # Has internal switch check
  step_5_create_portal || exit 1
  step_6_bind_domain || exit 1
  step_7_register_developer || exit 1
  step_8_approve_developer || exit 1
  step_9_developer_login || exit 1
  step_10_import_mcp_to_nacos || exit 1  # Has internal switch check
  step_11_publish_mcp_to_himarket || exit 1  # Has internal switch check
  
  # Print summary
  print_summary
  
  log "Initialization completed!"
}

main "$@"

