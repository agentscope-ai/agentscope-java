---
title: "Interactive assistant: query and confirm inside a product"
description: "Keep context in a Conversation and track each turn, interaction, and result through its Invocation."
zh_link: /v2/zh/service/cases/business-assistant
---

An assistant on an order page explains a delay and uses follow-up input to decide whether to create a support ticket. The product retains its chat UI, login, and order permissions; Service handles the conversation and tool execution. Use one session-capable Agent.

## 1. Prepare users and business data

Download the [first-turn request](/examples/service/business-assistant/input.json.txt) as `input.json` and the [fictional orders](/examples/service/business-assistant/orders.json.txt) for tool testing. The fixture is not a running order API.

| User | Readable order | Fixed fact |
| --- | --- | --- |
| alice | O-1001 | Awaiting stock; tomorrow cannot be guaranteed |
| bob | O-2001 | Shipped; this does not guarantee an arrival date |

Implement a query tool that checks ownership using authenticated user identity. A ticket tool creates a ticket only after authorized confirmation and returns a real ID. Claiming to be alice in a message grants no permission. Without a ticket tool, demonstrate queries and suggestions only.

## 2. Publish the conversation service

[Publish](/v2/en/service/endpoints) a session-capable single Agent as the conversation Endpoint `order-assistant`. Instructions require real query evidence, no unsupported arrival promises, and confirmation before ticket creation.

Keep the Application key in the backend with invoke/read and any required interaction/cancellation scopes. Designated-human tool approvals still require the correct human identity; interact scope alone does not grant approval authority. Prepare Bash, curl, jq, BASE_URL, and ENDPOINT_KEY.

## 3. Create the first turn and keep ownership

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/order-assistant/conversations" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: alice-order-1001-turn-1' \
  --data-binary @input.json)
CONVERSATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.conversationId')
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
```

Store authenticated user → Conversation → turn Invocations in the application. Check authorization on every access; the association alone is not enforcement. Multiple keys in one Application share invocation ownership.

Restore messages and actions from the snapshot, then subscribe to events. Proxy browser requests through the backend rather than shipping a long-lived key to the browser.

## 4. Distinguish a new turn from a pending action

If the first turn finishes with an ordinary reply, submit the user's confirmation as a new turn:

```bash
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/conversations/$CONVERSATION_ID/turns" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: alice-order-1001-turn-2' \
  --data '{"message":"Create a support ticket for this order and record that arrival tomorrow is not guaranteed."}'
```

If the current Invocation is waiting for a required action, answer that action using its returned request_id, expected_version, and type. Do not substitute a new turn. Native confirmations, external tool outputs, and Workflow actions use different payloads; see [Unified service API](/v2/en/service/service-api).

Only one Invocation can be active per Conversation. Disable duplicate sends or queue them in the application; expose in-progress input only when capabilities allow it. Human handoff requires the application to create a transfer record and decide how to pause or end Agent work.

## 5. Acceptance and interruption

| Test | Required behavior |
| --- | --- |
| alice queries O-1001 | Explain stock delay without guaranteeing tomorrow |
| alice requests O-2001 | Tools and application deny access, not just model instructions |
| Follow-up | Same Conversation, new Invocation, retained context |
| Refresh during a pending action | Restore the same question without duplicate operations |
| Ticket creation | Authorized execution and a verifiable real ticket ID |
| Network replay | Same key reuses the turn; ticket system separately enforces business idempotency |
| Cancellation | Show requested cancellation until confirmed; reconcile existing writes |

Use the fixed data for tool authorization tests. Full acceptance requires the real Agent, tools, and UI.
