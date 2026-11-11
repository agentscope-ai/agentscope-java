---
title: "Generate a customer proposal inside a CRM"
description: "Connect business input, execution and verified delivery through the Session API."
zh_link: /v2/zh/service/cases/in-product-delivery
---

A user working on opportunity OPP-104 wants a proposal draft they can review after leaving the page. The application associates the opportunity version with a Session, making generation part of the existing CRM workflow.

This tutorial uses fictional material. Expected outputs are acceptance criteria, not evidence of a completed production run.

## Prepare input and the execution target

Download the [request fixture](/examples/service/in-product-delivery/input.json.txt) as `input.json`. The sample describes 80 stores and unresolved SSO, data region and concurrency requirements. Ask the Agent for proposal.md and open-questions.md with source versions, leaving customer commitments to the business reviewer.

Configure a Managed Agent that can read authorized opportunity material and write to its workspace. The application checks the current user’s opportunity permissions before supplying data or controlled tools. If the work later requires several specialties, select a Team in a new Session while keeping the same calling model.

## Create a Session and submit the task

Prepare the Agent ID and application credential using the [integration guide](/v2/en/service/service-api). The Bash example uses curl and jq to turn the small source fixture into a Managed Agent message. A repository URL, file path or order ID in the input does not itself provide system access.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: in-product-delivery-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: in-product-delivery-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` confirms admission rather than completion. Save both IDs and retry network failures with the same keys and bodies. Use a new key for new work. Restore the Session snapshot and continue its event stream from `as_of` to display progress.

## Connect execution back to the application

Restore the original Session when reopening the opportunity. Submit a new Turn for a changed requirement version and retain previous results so an older completion cannot overwrite a newer proposal. A successful write creates a workspace file; making it downloadable still requires uploading its bytes or registering an Artifact.

## Verify the actual delivery

Check that both files are retrievable, commitments have supporting sources and unknowns remain explicit. Save the formal deliverable after business review; Turn completion is an execution outcome, not approval of the proposal.

See [files and artifacts](/v2/en/service/files), [Session API interaction](/v2/en/service/service-api), and [events and notifications](/v2/en/service/sse-events) for the supporting interfaces.
