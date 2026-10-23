#!/usr/bin/env bash
# End-to-end smoke for the local stack:
# login -> Managed Agent/Environment/Session -> Runtime Policy ->
# Team/Issue/Run/AgentTask/ExecutionAttempt -> Comment/Artifact/Approval ->
# published Definition/declared Run/signal -> Automation.
set -euo pipefail

BASE="${BASE:-http://localhost:18080}"
TENANT="${SMOKE_TENANT:-admin}"
NAMESPACE="${SMOKE_NAMESPACE:-default}"
SMOKE_ID="${SMOKE_ID:-$(date +%s)-$$}"

json_path() {
    local path="$1"
    python3 -c '
import json, sys
value = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    value = value[int(part)] if isinstance(value, list) else value[part]
print(value)
' "$path"
}

assert_json() {
    local expression="$1" description="$2"
    python3 -c '
import json, sys
value = json.load(sys.stdin)
if not eval(sys.argv[1], {"__builtins__": {}}, {"value": value, "len": len}):
    raise SystemExit("assertion failed: " + sys.argv[2])
' "$expression" "$description"
}

echo "==> login"
TOKEN="$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | json_path token)"
AUTH="Authorization: Bearer $TOKEN"

echo "==> identity"
ME="$(curl -sf "$BASE/api/auth/me" -H "$AUTH")"
printf '%s' "$ME" | assert_json 'value.get("username") == "admin"' 'admin identity'
OWNER_ID="$(printf '%s' "$ME" | json_path userId)"

echo "==> create Managed Agent"
AGENT="$(curl -sf -X POST "$BASE/api/agents" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"smoke-agent-${SMOKE_ID}\",\"system\":\"You are a concise assistant.\",\"model\":\"qwen-plus\"}")"
AGENT_ID="$(printf '%s' "$AGENT" | json_path id)"
echo "  agent=$AGENT_ID"

echo "==> configure ordered Managed Runtime policy"
curl -sf -X PUT "$BASE/api/v1/agent-runtime-policies/$AGENT_ID" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"selectionMode\":\"ordered\",\"fallbackMode\":\"disabled\",\"maxConcurrency\":4,\"queueTimeoutSeconds\":120,\"attemptTimeoutSeconds\":300,\"retryPolicy\":{\"maxInfrastructureAttempts\":2},\"candidates\":[{\"binding\":{\"kind\":\"managed\",\"managedOwnerRef\":\"$OWNER_ID\",\"managedAgentRef\":\"$AGENT_ID\"}}]}" \
  | assert_json 'value["policy"]["agentRef"] != "" and value["policy"]["candidates"][0]["binding"]["kind"] == "managed"' 'Managed Runtime policy write'
curl -sf "$BASE/api/v1/agent-runtime-policies/$AGENT_ID?tenant=$TENANT&namespace=$NAMESPACE" -H "$AUTH" \
  | assert_json 'value["policy"]["selectionMode"] == "ordered" and value["policy"]["fallbackMode"] == "disabled"' 'Runtime policy read'

echo "==> ensure Environment"
ENV_ID="$(curl -sf "$BASE/api/environments" -H "$AUTH" | python3 -c '
import sys,json
envs=json.load(sys.stdin)
print(next((e["id"] for e in envs if not e.get("archivedAt")), ""))
')"
if [ -z "$ENV_ID" ]; then
  ENV_ID="$(curl -sf -X POST "$BASE/api/environments" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"name\":\"smoke-environment-${SMOKE_ID}\",\"type\":\"local\"}" | json_path id)"
fi
echo "  environment=$ENV_ID"

echo "==> create Session"
SID="$(curl -sf -X POST "$BASE/api/sessions" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"agent\":\"$AGENT_ID\",\"environmentId\":\"$ENV_ID\"}" | json_path id)"
echo "  session=$SID"

curl -sf "$BASE/api/sessions" -H "$AUTH" | assert_json 'len(value) >= 1' 'session list'
curl -sf "$BASE/api/admin/users" -H "$AUTH" | assert_json 'len(value) >= 1' 'admin user list'
curl -sf "$BASE/api/agents/$AGENT_ID/workspace" -H "$AUTH" | assert_json '"workspacePath" in value' 'workspace summary'

echo "==> create Team"
TEAM="$(curl -sf -X POST "$BASE/api/v1/teams" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"name\":\"smoke-team-${SMOKE_ID}\",\"leaderAgentRef\":\"$AGENT_ID\",\"policy\":{\"maxActiveTasks\":8,\"maxFanout\":4,\"maxArtifactBytes\":1048576,\"allowedArtifactMediaTypes\":[\"text/*\"]}}")"
TEAM_ID="$(printf '%s' "$TEAM" | json_path team.id)"
echo "  team=$TEAM_ID"

echo "==> create Team-assigned Issue and AgentTask"
ISSUE_RESULT="$(curl -sf -X POST "$BASE/api/v1/issues" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"title\":\"Smoke collaboration ${SMOKE_ID}\",\"description\":\"Verify the durable v4 collaboration and execution path\",\"assigneeType\":\"team\",\"assigneeRef\":\"$TEAM_ID\"}")"
ISSUE_ID="$(printf '%s' "$ISSUE_RESULT" | json_path issue.id)"
TASK_ID="$(printf '%s' "$ISSUE_RESULT" | json_path agentTask.id)"
echo "  issue=$ISSUE_ID task=$TASK_ID"

echo "==> verify adaptive Run/Node and unified ExecutionAttempt"
for _ in $(seq 1 30); do
  TASK_RESULT="$(curl -sf "$BASE/api/v1/agent-tasks/$TASK_ID" -H "$AUTH")"
  ATTEMPT_ID="$(printf '%s' "$TASK_RESULT" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("task", {}).get("currentAttemptId", ""))')"
  [ -n "$ATTEMPT_ID" ] && break
  sleep 1
done
if [ -z "${ATTEMPT_ID:-}" ]; then
  echo "AgentTask did not receive an ExecutionAttempt" >&2
  exit 1
fi
RUN_ID="$(printf '%s' "$TASK_RESULT" | json_path task.orchestrationRunId)"
NODE_ID="$(printf '%s' "$TASK_RESULT" | json_path task.runNodeId)"
curl -sf "$BASE/api/v1/execution-attempts/$ATTEMPT_ID" -H "$AUTH" \
  | assert_json 'value["attempt"]["backendKind"] == "managed" and value["attempt"]["runId"] != "" and value["attempt"]["nodeId"] != ""' 'Managed ExecutionAttempt linkage'
curl -sf "$BASE/api/v1/orchestration-runs/$RUN_ID/graph" -H "$AUTH" \
  | assert_json 'value["run"]["mode"] == "adaptive" and len(value["nodes"]) >= 1 and len(value["tasks"]) >= 1 and len(value["attempts"]) >= 1' 'adaptive Run graph'
echo "  run=$RUN_ID node=$NODE_ID attempt=$ATTEMPT_ID"

echo "==> add Comment and verify durable route/input"
COMMENT_RESULT="$(curl -sf -X POST "$BASE/api/v1/issues/$ISSUE_ID/comments" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"content":"Smoke follow-up for the Team leader"}')"
printf '%s' "$COMMENT_RESULT" | assert_json 'value["Comment"]["issueId"] != "" and len(value["Routes"]) >= 1' 'Comment route creation'
FOLLOW_UP_TASK_ID="$(printf '%s' "$COMMENT_RESULT" | json_path Routes.0.taskId)"
curl -sf "$BASE/api/v1/issues/$ISSUE_ID/comments?limit=20" -H "$AUTH" | assert_json 'len(value["items"]) >= 1' 'Issue discussion read'
curl -sf "$BASE/api/v1/agent-tasks/$FOLLOW_UP_TASK_ID" -H "$AUTH" \
  | assert_json "value[\"task\"][\"orchestrationRunId\"] == \"$RUN_ID\" and value[\"task\"][\"runNodeId\"] == \"$NODE_ID\" and len(value[\"task\"].get(\"inputs\", [])) >= 1" 'follow-up input stays in active Run/Node'
curl -sf "$BASE/api/v1/issues/$ISSUE_ID/summary" -H "$AUTH" | assert_json '"summary" in value' 'Issue summary'
curl -sf "$BASE/api/v1/issues/$ISSUE_ID/export" -H "$AUTH" | assert_json 'value["issue"]["id"] != ""' 'Issue export'

echo "==> upload and download Artifact"
ARTIFACT_FILE="$(mktemp /tmp/agentscope-smoke-artifact.XXXXXX.txt)"
ARTIFACT_DOWNLOAD="$(mktemp /tmp/agentscope-smoke-download.XXXXXX.txt)"
trap 'rm -f "$ARTIFACT_FILE" "$ARTIFACT_DOWNLOAD"' EXIT
printf 'shared artifact for %s\n' "$ISSUE_ID" >"$ARTIFACT_FILE"
ARTIFACT_RESULT="$(curl -sf -X POST "$BASE/api/v1/artifacts/uploads" -H "$AUTH" \
  -F "file=@${ARTIFACT_FILE};type=text/plain" \
  -F "tenant=$TENANT" -F "namespace=$NAMESPACE" \
  -F 'targetType=issue' -F "targetRef=$ISSUE_ID" -F 'relation=attachment')"
ARTIFACT_ID="$(printf '%s' "$ARTIFACT_RESULT" | json_path artifact.id)"
curl -sf -X POST "$BASE/api/v1/artifacts/$ARTIFACT_ID/complete" -H "$AUTH" \
  | assert_json 'value.get("complete") is True' 'Artifact integrity completion'
curl -sf -X POST "$BASE/api/v1/artifacts/$ARTIFACT_ID/download" -H "$AUTH" -o "$ARTIFACT_DOWNLOAD"
cmp -s "$ARTIFACT_FILE" "$ARTIFACT_DOWNLOAD"
echo "  artifact=$ARTIFACT_ID"

echo "==> request and decide Approval"
APPROVAL_RESULT="$(curl -sf -X POST "$BASE/api/v1/approvals" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"targetType\":\"issue\",\"targetRef\":\"$ISSUE_ID\",\"issueId\":\"$ISSUE_ID\",\"approverRef\":\"admin\",\"reason\":\"Smoke review\"}")"
APPROVAL_ID="$(printf '%s' "$APPROVAL_RESULT" | json_path approval.id)"
curl -sf -X POST "$BASE/api/v1/approvals/$APPROVAL_ID/decide" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"status":"approved","expectedVersion":1,"decision":{"note":"smoke approved"}}' \
  | assert_json 'value["approval"]["status"] == "approved"' 'Approval decision'
echo "  approval=$APPROVAL_ID"

echo "==> publish Definition and complete a declared signal Run"
ORCH_ISSUE_RESULT="$(curl -sf -X POST "$BASE/api/v1/issues" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"title\":\"Smoke orchestration ${SMOKE_ID}\",\"description\":\"Root Issue remains independent from Run completion\"}")"
ORCH_ISSUE_ID="$(printf '%s' "$ORCH_ISSUE_RESULT" | json_path issue.id)"
DEFINITION_RESULT="$(curl -sf -X POST "$BASE/api/v1/orchestration-definitions" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"name\":\"smoke-definition-${SMOKE_ID}\",\"description\":\"v4 signal smoke\",\"draftSpec\":{\"nodes\":[{\"key\":\"release\",\"type\":\"signal\",\"signalName\":\"release\"}]}}")"
DEFINITION_ID="$(printf '%s' "$DEFINITION_RESULT" | json_path definition.id)"
curl -sf -X POST "$BASE/api/v1/orchestration-definitions/$DEFINITION_ID/validate" -H "$AUTH" -H 'Content-Type: application/json' -d '{}' \
  | assert_json 'value.get("valid") is True and len(value["spec"]["nodes"]) == 1' 'Definition validation'
REVISION_RESULT="$(curl -sf -X POST "$BASE/api/v1/orchestration-definitions/$DEFINITION_ID/publish" -H "$AUTH")"
REVISION_ID="$(printf '%s' "$REVISION_RESULT" | json_path revision.id)"
curl -sf "$BASE/api/v1/orchestration-definitions/$DEFINITION_ID/revisions" -H "$AUTH" \
  | assert_json 'len(value["revisions"]) == 1 and value["revisions"][0]["revision"] == 1' 'immutable Definition revision'
DECLARED_RESULT="$(curl -sf -X POST "$BASE/api/v1/orchestration-definitions/$DEFINITION_ID/runs" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"smoke-run-${SMOKE_ID}\",\"issueId\":\"$ORCH_ISSUE_ID\",\"input\":{\"source\":\"smoke\"}}")"
DECLARED_RUN_ID="$(printf '%s' "$DECLARED_RESULT" | json_path run.id)"
printf '%s' "$DECLARED_RESULT" | assert_json 'value["run"]["mode"] == "declared" and value["run"]["state"] == "waiting"' 'declared Run waiting for signal'
curl -sf -X POST "$BASE/api/v1/orchestration-runs/$DECLARED_RUN_ID/signals/release" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"smoke-signal-${SMOKE_ID}\",\"payload\":{\"released\":true}}" >/dev/null
curl -sf "$BASE/api/v1/orchestration-runs/$DECLARED_RUN_ID" -H "$AUTH" \
  | assert_json 'value["run"]["state"] == "succeeded"' 'signal Run completion'
curl -sf "$BASE/api/v1/orchestration-runs/$DECLARED_RUN_ID/events" -H "$AUTH" \
  | assert_json 'len(value["events"]) >= 2' 'Run event stream'
curl -sf "$BASE/api/v1/issues/$ORCH_ISSUE_ID" -H "$AUTH" \
  | assert_json 'value["issue"]["status"] not in ("done", "cancelled", "archived")' 'Run completion does not complete Issue'
RERUN_RESULT="$(curl -sf -X POST "$BASE/api/v1/orchestration-runs/$DECLARED_RUN_ID/rerun" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"smoke-rerun-${SMOKE_ID}\"}")"
RERUN_ID="$(printf '%s' "$RERUN_RESULT" | json_path run.id)"
printf '%s' "$RERUN_RESULT" | assert_json "value[\"run\"][\"rerunOfRunId\"] == \"$DECLARED_RUN_ID\" and value[\"run\"][\"definitionRevisionId\"] == \"$REVISION_ID\" and value[\"run\"][\"state\"] == \"waiting\"" 'declared Run rerun lineage'
curl -sf -X POST "$BASE/api/v1/orchestration-runs/$RERUN_ID/signals/release" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"smoke-rerun-signal-${SMOKE_ID}\",\"payload\":{\"released\":true}}" >/dev/null
curl -sf "$BASE/api/v1/orchestration-runs/$RERUN_ID" -H "$AUTH" \
  | assert_json 'value["run"]["state"] == "succeeded"' 'rerun completion'
echo "  definition=$DEFINITION_ID revision=$REVISION_ID run=$DECLARED_RUN_ID rerun=$RERUN_ID issue=$ORCH_ISSUE_ID"

echo "==> create and trigger Automation through Issue path"
AUTOMATION_RESULT="$(curl -sf -X POST "$BASE/api/v1/automations" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"name\":\"smoke-automation-${SMOKE_ID}\",\"triggerType\":\"webhook\",\"triggerConfig\":{},\"actionType\":\"create_issue\",\"actionConfig\":{\"title\":\"Automated smoke ${SMOKE_ID}\"}}")"
AUTOMATION_ID="$(printf '%s' "$AUTOMATION_RESULT" | json_path automation.id)"
curl -sf -X POST "$BASE/api/v1/automations/$AUTOMATION_ID/trigger" -H "$AUTH" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-${SMOKE_ID}" -d '{}' \
  | assert_json 'value["run"]["status"] == "completed" and value["run"]["issueId"] != ""' 'Automation Issue creation'
echo "  automation=$AUTOMATION_ID"

echo "==> verify Inbox and collaboration lists"
curl -sf "$BASE/api/v1/inbox?tenant=$TENANT&namespace=$NAMESPACE" -H "$AUTH" | assert_json '"items" in value' 'Inbox list'
curl -sf "$BASE/api/v1/teams?tenant=$TENANT&namespace=$NAMESPACE" -H "$AUTH" | assert_json 'len(value["items"]) >= 1' 'Team list'
curl -sf "$BASE/api/v1/issues?tenant=$TENANT&namespace=$NAMESPACE&search=Smoke" -H "$AUTH" | assert_json 'len(value["items"]) >= 1' 'Issue search'

if [ -n "${DASHSCOPE_API_KEY:-}" ]; then
  echo "==> post user.message"
  curl -sf -X POST "$BASE/api/sessions/$SID/events" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"events":[{"type":"user.message","payload":{"text":"Say hi in one short sentence."}}]}' \
    | python3 -m json.tool >/dev/null
  echo "  turn accepted"
else
  echo "==> skip model turn (DASHSCOPE_API_KEY unset)"
fi

echo "==> smoke OK"
