#!/usr/bin/env bash
set -euo pipefail

AISTIO_URL="${AISTIO_URL:-http://localhost:8080}"
TENANT="${TENANT:-default}"
NAMESPACE="${NAMESPACE:-default}"
LEADER_AGENT="${LEADER_AGENT:-lead-agent}"
WORKER_AGENT="${WORKER_AGENT:-worker-agent}"

auth=(-H "Authorization: Bearer ${AGENTSCOPE_API_TOKEN:?set AGENTSCOPE_API_TOKEN}" -H 'Content-Type: application/json')

team="$(curl -fsS "${auth[@]}" -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"name\":\"release-review\",\"leaderAgentRef\":\"$LEADER_AGENT\",\"policy\":{\"maxActiveTasks\":8,\"maxHops\":6,\"maxChildDepth\":4,\"requireReview\":true}}" "$AISTIO_URL/api/v1/teams")"
team_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["team"]["id"])' <<<"$team")"

issue="$(curl -fsS "${auth[@]}" -d "{\"tenant\":\"$TENANT\",\"namespace\":\"$NAMESPACE\",\"title\":\"Review the release\",\"description\":\"Produce an auditable release recommendation\",\"assigneeType\":\"team\",\"assigneeRef\":\"$team_id\"}" "$AISTIO_URL/api/v1/issues")"
issue_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["issue"]["id"])' <<<"$issue")"

curl -fsS "${auth[@]}" -d "{\"content\":\"Please run the independent verification\",\"mentions\":[{\"type\":\"agent\",\"ref\":\"$WORKER_AGENT\"}]}" "$AISTIO_URL/api/v1/issues/$issue_id/comments" | python3 -m json.tool
curl -fsS "${auth[@]}" "$AISTIO_URL/api/v1/agent-tasks?tenant=$TENANT&namespace=$NAMESPACE&issueId=$issue_id" | python3 -m json.tool
