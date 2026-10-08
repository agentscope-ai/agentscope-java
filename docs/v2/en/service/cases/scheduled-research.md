---
title: "Run scheduled research and batch work"
description: "Connect business input, execution and verified delivery through the Session API."
zh_link: /v2/zh/service/cases/scheduled-research
---

A daily job summarizes changes in sources for account A-101, keeping verifiable facts separate from sales hypotheses. A scheduler initiates the work without relying on an online browser session.

This tutorial uses fictional material. Expected outputs are acceptance criteria, not evidence of a completed production run.

## Prepare input and the execution target

Download the [request fixture](/examples/service/scheduled-research/input.json.txt) as `input.json`. The sample includes research_date, strategy_version and collected source snapshots. Account, date and strategy version identify a business batch. New source material or strategy changes should have an explicit task version, and model hypotheses must not be presented as observed facts.

Prepare a research Agent with authorized source access. Let the scheduler create a Session for each independent account task, deriving stable Session and Turn idempotency keys from the batch identity. Separate Sessions allow parallel work, subject to application admission limits.

## Create a Session and submit the task

Prepare the Agent ID and application credential using the [integration guide](/v2/en/service/service-api). The Bash example uses curl and jq to turn the small source fixture into a Managed Agent message. A repository URL, file path or order ID in the input does not itself provide system access.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: scheduled-research-session-001' \
  --data "$(jq -n --arg id "$AGENT_ID" '{target:{type:"agent",id:$id}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: scheduled-research-task-001' \
  --data "$(jq '{message:(.message // (.input | tojson))}' input.json)")
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` confirms admission rather than completion. Save both IDs and retry network failures with the same keys and bodies. Use a new key for new work. Restore the Session snapshot and continue its event stream from `as_of` to display progress.

## Connect execution back to the application

Track submitted, active and retryable objects, then reconcile results through callbacks or polling. Fetch the final Turn state before writing back, and deduplicate callbacks. Platform Automation can organize internal work, while an external scheduler remains responsible for its own batch and business compensation rules.

## Verify the actual delivery

Test duplicate batch triggers, partial failures, unavailable sources and lost notifications. Every object should trace to a Session, Turn, input version and result. Research completion should not automatically contact the customer; the business application decides follow-up.

See [files and artifacts](/v2/en/service/files), [Session API interaction](/v2/en/service/service-api), and [events and notifications](/v2/en/service/sse-events) for the supporting interfaces.
