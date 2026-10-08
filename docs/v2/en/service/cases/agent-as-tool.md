---
title: "Expose a specialist Agent to another Agent"
description: "Connect business input, execution and verified delivery through the Session API."
zh_link: /v2/zh/service/cases/agent-as-tool
---

A procurement assistant delegates evidence verification for supplier V-101 to a specialist Agent, then uses the findings in its own work. Several applications can reuse the specialist while the calling application supplies the tool wrapper.

This tutorial uses fictional material. Expected outputs are acceptance criteria, not evidence of a completed production run.

## Prepare input and the execution target

Download the [request fixture](/examples/service/agent-as-tool/input.json.txt) as `input.json`. Staff count and web support are supplier claims; independent security review and delivery SLA evidence are missing. Separate confirmed facts, missing evidence and follow-up questions without approving procurement or expanding the caller’s data access.

Issue the calling application a credential granting this specialist Agent. After validating user and supplier access, the adapter creates a Session and submits a Turn. For long work, return a handle containing both IDs and provide separate tools to query, answer and cancel the task.

## Create a Session and submit the task

Prepare the Agent ID and application credential using the [integration guide](/v2/en/service/service-api). The Bash example uses curl and jq to turn the small source fixture into a Managed Agent message. A repository URL, file path or order ID in the input does not itself provide system access.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: agent-as-tool-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: agent-as-tool-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` confirms admission rather than completion. Save both IDs and retry network failures with the same keys and bodies. Use a new key for new work. Restore the Session snapshot and continue its event stream from `as_of` to display progress.

## Connect execution back to the application

Persist the mapping from the parent tool call to the Session and Turn. Retry the same tool call with its original key and saved task record. When the parent is cancelled, the adapter cancels the downstream work and confirms its outcome. Cross-application cancellation and cumulative budgets require explicit adapter behavior.

## Verify the actual delivery

Test duplicate calls, unauthorized suppliers, missing evidence and parent cancellation. If MCP access is needed, implement and publish the tool wrapper in the application; Service’s internal collaboration MCP serves a different purpose.

See [files and artifacts](/v2/en/service/files), [Session API interaction](/v2/en/service/service-api), and [events and notifications](/v2/en/service/sse-events) for the supporting interfaces.
