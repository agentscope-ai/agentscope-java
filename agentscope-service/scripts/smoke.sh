#!/usr/bin/env bash
# Smoke: login → create agent → env → session → (optional) post event
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"

echo "==> login"
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
AUTH="Authorization: Bearer $TOKEN"

echo "==> me"
curl -sf "$BASE/api/auth/me" -H "$AUTH" | python3 -m json.tool >/dev/null

echo "==> create agent"
AGENT=$(curl -sf -X POST "$BASE/api/agents" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"smoke-agent","system":"You are a concise assistant.","model":"qwen-plus"}')
AGENT_ID=$(echo "$AGENT" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "  agent=$AGENT_ID"

echo "==> ensure environment"
ENV_ID=$(curl -sf "$BASE/api/environments" -H "$AUTH" | python3 -c '
import sys,json
envs=json.load(sys.stdin)
print(next((e["id"] for e in envs if not e.get("archivedAt")), ""))
')
if [ -z "$ENV_ID" ]; then
  ENV_ID=$(curl -sf -X POST "$BASE/api/environments" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"name":"default-local","type":"local"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
fi
echo "  env=$ENV_ID"

echo "==> create session"
SID=$(curl -sf -X POST "$BASE/api/sessions" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"agent\":\"$AGENT_ID\",\"environmentId\":\"$ENV_ID\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "  session=$SID"

echo "==> list sessions"
curl -sf "$BASE/api/sessions" -H "$AUTH" | python3 -c 'import sys,json; print("  count=", len(json.load(sys.stdin)))'

echo "==> list admin users (admin)"
curl -sf "$BASE/api/admin/users" -H "$AUTH" | python3 -c 'import sys,json; print("  users=", len(json.load(sys.stdin)))'

echo "==> workspace summary"
curl -sf "$BASE/api/agents/$AGENT_ID/workspace" -H "$AUTH" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  exists=", d.get("exists"), "path=", d.get("workspacePath"))'

echo "==> clone agent"
CLONE_ID=$(curl -sf -X POST "$BASE/api/agents/$AGENT_ID/clone" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "  clone=$CLONE_ID"

if [ -n "${DASHSCOPE_API_KEY:-}" ]; then
  echo "==> post user.message"
  curl -sf -X POST "$BASE/api/sessions/$SID/events" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"events":[{"type":"user.message","payload":{"text":"Say hi in one short sentence."}}]}' \
    | python3 -m json.tool >/dev/null
  echo "  turn accepted"
else
  echo "==> skip turn (DASHSCOPE_API_KEY unset)"
fi

echo "==> smoke OK"
