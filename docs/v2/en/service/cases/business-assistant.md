---
title: "Offer a conversational business assistant inside a product"
description: "Connect business input, execution and verified delivery through the Session API."
zh_link: /v2/zh/service/cases/business-assistant
---

A user asks why order O-1001 is delayed and whether next-day delivery can be guaranteed. The assistant should read the facts, explain known status and create a support ticket only after confirmation, using the same business identity as the product.

This tutorial uses fictional material. Expected outputs are acceptance criteria, not evidence of a completed production run.

## Prepare input and the execution target

Download the [request fixture](/examples/service/business-assistant/input.json.txt) as `input.json`. The request contains the user’s question; the accompanying [order data](/examples/service/business-assistant/orders.json.txt) provides fictional facts. Prepare a read-only order lookup tool and a controlled ticket creation tool. Check business-user authorization inside the tool and keep unverified delivery commitments explicit.

Select a Managed Agent and configure confirmation for write operations. Store the Session by user and order, then submit follow-up Turns to the same Session so the assistant retains the conversation. Reconsider Session boundaries when the order or business identity changes.

## Create a Session and submit the task

Prepare the Agent ID and application credential using the [integration guide](/v2/en/service/service-api). The Bash example uses curl and jq to turn the small source fixture into a Managed Agent message. A repository URL, file path or order ID in the input does not itself provide system access.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: business-assistant-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: business-assistant-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` confirms admission rather than completion. Save both IDs and retry network failures with the same keys and bodies. Use a new key for new work. Restore the Session snapshot and continue its event stream from `as_of` to display progress.

## Connect execution back to the application

When the Agent requires confirmation, render the actual pending operation from required_actions. Return its request ID and decision through that Turn’s actions resource, using the designated user’s identity when necessary. Restore the Session on refresh instead of resubmitting the ticket creation request.

## Verify the actual delivery

Verify that the answer matches order data, no ticket is written before confirmation and an actual ticket ID exists afterward. Duplicate clicks, retries and page refreshes must not create extra Turns. The business tool also needs its own idempotency identifier to prevent duplicate external writes.

See [files and artifacts](/v2/en/service/files), [Session API interaction](/v2/en/service/service-api), and [events and notifications](/v2/en/service/sse-events) for the supporting interfaces.
