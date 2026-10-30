---
title: "Turn an incident into a reviewable repair"
description: "Connect business input, execution and verified delivery through the Session API."
zh_link: /v2/zh/service/cases/incident-to-pr
---

Incident INC-204 reports incorrect status filtering and pagination. After deduplicating alerts, the engineering platform starts a repair against a specific repository and commit, asking the Agent to investigate, modify code and provide test evidence.

This tutorial uses fictional material. Expected outputs are acceptance criteria, not evidence of a completed production run.

## Prepare input and the execution target

Download the [request fixture](/examples/service/incident-to-pr/input.json.txt) as `input.json`. Replace repository and base_commit in the sample with your exercise repository and actual commit. The exercise includes [OrderQuery.java](/examples/service/incident-to-pr/OrderQuery.java.txt), [tests](/examples/service/incident-to-pr/OrderQueryTest.java.txt) and [CI configuration](/examples/service/incident-to-pr/ci.yml.txt), covering filtering before pagination, preserved order and parameter boundaries.

Use a Managed Agent with coding tools or a Hosted Coding Agent connected through a Runtime Host. Prepare repository access, dependencies and test commands in its environment. A Team or Workflow can separate diagnosis, implementation and review while remaining a Session target.

## Create a Session and submit the task

Prepare the Agent ID and application credential using the [integration guide](/v2/en/service/service-api). The Bash example uses curl and jq to turn the small source fixture into a Managed Agent message. A repository URL, file path or order ID in the input does not itself provide system access.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: incident-to-pr-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: incident-to-pr-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` confirms admission rather than completion. Save both IDs and retry network failures with the same keys and bodies. Use a new key for new work. Restore the Session snapshot and continue its event stream from `as_of` to display progress.

## Connect execution back to the application

Record the incident, base commit, repair attempt, Session and Turn together. Retry the same repair with its original idempotency key; submit new review feedback as another Turn. Session callbacks can bring completion back to the engineering platform without keeping a browser connection open.

## Verify the actual delivery

Review the actual diff, executed tests, their output and the PR URL. Preserve existing tests, add boundary coverage and leave merging to an authorized repository reviewer. Neither a claim that the bug is fixed nor a completed Turn replaces that evidence.

See [files and artifacts](/v2/en/service/files), [Session API interaction](/v2/en/service/service-api), and [events and notifications](/v2/en/service/sse-events) for the supporting interfaces.
