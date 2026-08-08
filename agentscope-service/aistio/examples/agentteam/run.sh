#!/usr/bin/env bash
# AgentTeams smoke E2E against aistiod /api/v1/teams
set -euo pipefail

AISTIO_URL="${AISTIO_URL:-http://localhost:8081}"
TOKEN="${TOKEN:-}"
NS="${NS:-default}"
OWNER_ID="${OWNER_ID:-}"
MANAGED_AGENT_ID="${MANAGED_AGENT_ID:-}"
BYO_AGENT_REF="${BYO_AGENT_REF:-byo-writer}"
TEAM_NAME="${TEAM_NAME:-demo-team-$(date +%s)}"

if [[ -z "$TOKEN" ]]; then
  echo "TOKEN (Bearer ...) is required" >&2
  exit 1
fi

auth=(-H "Authorization: $TOKEN" -H "Content-Type: application/json")

echo "== create team $TEAM_NAME =="
CREATE_BODY=$(cat <<EOF
{
  "name": "$TEAM_NAME",
  "namespace": "$NS",
  "objective": "Ship the AgentTeams smoke checklist",
  "lead": {
    "agentRef": "${MANAGED_AGENT_ID:-lead-agent}",
    "deployMode": "managed",
    "managedAgentId": "$MANAGED_AGENT_ID",
    "ownerId": "$OWNER_ID",
    "prompt": "You are the lead. Assign and coordinate."
  },
  "members": [
    {
      "name": "worker-managed",
      "agentRef": "${MANAGED_AGENT_ID:-worker-agent}",
      "deployMode": "managed",
      "managedAgentId": "$MANAGED_AGENT_ID",
      "ownerId": "$OWNER_ID",
      "prompt": "Execute assigned tasks."
    },
    {
      "name": "worker-byo",
      "agentRef": "$BYO_AGENT_REF",
      "deployMode": "byo",
      "prompt": "BYO teammate via team_join."
    }
  ]
}
EOF
)

# Managed fields are only useful when both OWNER_ID and MANAGED_AGENT_ID are set;
# otherwise fall back to BYO-only roster so the script still exercises the API.
if [[ -z "$OWNER_ID" || -z "$MANAGED_AGENT_ID" ]]; then
  echo "(OWNER_ID/MANAGED_AGENT_ID unset — creating BYO-only team)"
  CREATE_BODY=$(cat <<EOF
{
  "name": "$TEAM_NAME",
  "namespace": "$NS",
  "objective": "Ship the AgentTeams smoke checklist",
  "lead": {
    "agentRef": "lead-agent",
    "deployMode": "byo",
    "prompt": "You are the lead."
  },
  "members": [
    {
      "name": "worker-1",
      "agentRef": "$BYO_AGENT_REF",
      "deployMode": "byo"
    },
    {
      "name": "worker-2",
      "agentRef": "${BYO_AGENT_REF}-2",
      "deployMode": "byo"
    }
  ]
}
EOF
)
fi

curl -fsS "${auth[@]}" -d "$CREATE_BODY" "$AISTIO_URL/api/v1/teams" | tee /tmp/team-create.json
echo

echo "== get team =="
curl -fsS "${auth[@]}" "$AISTIO_URL/api/v1/teams/$TEAM_NAME?namespace=$NS" | tee /tmp/team-get.json
echo

echo "== create unassigned task (self-claim candidate) =="
TASK1=$(curl -fsS "${auth[@]}" -d '{"subject":"draft outline","description":"self-claim me"}' \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/tasks?namespace=$NS")
echo "$TASK1"
TASK1_ID=$(echo "$TASK1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["taskId"])')
TASK1_VER=$(echo "$TASK1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["version"])')

echo "== create task then lead-assign =="
TASK2=$(curl -fsS "${auth[@]}" -d '{"subject":"write section A"}' \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/tasks?namespace=$NS")
TASK2_ID=$(echo "$TASK2" | python3 -c 'import sys,json; print(json.load(sys.stdin)["taskId"])')
TASK2_VER=$(echo "$TASK2" | python3 -c 'import sys,json; print(json.load(sys.stdin)["version"])')
ASSIGNEE=$(python3 -c 'import json; m=json.load(open("/tmp/team-get.json"))["members"]; print(next(x["memberName"] for x in m if x["memberName"]!="lead"))')
curl -fsS "${auth[@]}" -d "{\"owner\":\"$ASSIGNEE\",\"resourceVersion\":\"$TASK2_VER\"}" \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/tasks/$TASK2_ID/assign?namespace=$NS"
echo

echo "== self-claim task1 as $ASSIGNEE =="
curl -fsS "${auth[@]}" -d "{\"claimedBy\":\"$ASSIGNEE\",\"resourceVersion\":\"$TASK1_VER\"}" \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/tasks/$TASK1_ID/claim?namespace=$NS"
echo

echo "== start assigned task2 =="
TASK2_CUR=$(curl -fsS "${auth[@]}" "$AISTIO_URL/api/v1/teams/$TEAM_NAME/tasks?namespace=$NS" \
  | python3 -c "import sys,json; t=[x for x in json.load(sys.stdin)['tasks'] if x['taskId']=='$TASK2_ID'][0]; print(t['version'])")
curl -fsS "${auth[@]}" -d "{\"claimedBy\":\"$ASSIGNEE\",\"resourceVersion\":\"$TASK2_CUR\"}" \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/tasks/$TASK2_ID/claim?namespace=$NS"
echo

echo "== complete task1 =="
curl -fsS "${auth[@]}" -d '{"result":"outline.md#ref"}' \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/tasks/$TASK1_ID/complete?namespace=$NS"
echo

echo "== mailbox (small text + artifact ref) =="
curl -fsS "${auth[@]}" -d "{\"from\":\"lead\",\"to\":\"$ASSIGNEE\",\"content\":\"see teams/$TEAM_NAME/outline.md\"}" \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/messages?namespace=$NS"
echo

echo "== dynamic spawn =="
curl -fsS "${auth[@]}" -d "{\"name\":\"dyn-1\",\"agentRef\":\"$BYO_AGENT_REF\",\"prompt\":\"ephemeral helper\"}" \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/members?namespace=$NS" || echo "(spawn may fail if dynamic policy / activator unavailable)"
echo

echo "== events =="
curl -fsS "${auth[@]}" "$AISTIO_URL/api/v1/teams/$TEAM_NAME/events?namespace=$NS&limit=20"
echo

echo "== complete team =="
curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' -d '{}' \
  "$AISTIO_URL/api/v1/teams/$TEAM_NAME/complete?namespace=$NS" | python3 -m json.tool
echo
echo "OK: team $TEAM_NAME exercised"
