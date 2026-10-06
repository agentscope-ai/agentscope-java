---
title: "Recurring research: background work per business object"
description: "Submit independent Jobs and track concurrency, retries, callbacks, and batch reconciliation."
zh_link: /v2/zh/service/cases/scheduled-research
---

Sales operations researches accounts daily and shares evidence-backed changes with sales. A scheduler submits independent Jobs without a chat window. The business system tracks the batch; Service executes each research task.

## 1. Start with one fixed source snapshot

Download the [request fixture](/examples/service/scheduled-research/input.json.txt) as `input.json`. Account A-101, date 2026-10-05, and strategy research-v1 are fixed test inputs:

- The release source says the fictional customer launched web support on October 4.
- CRM rev-3 records interest in order lookup; budget and purchase date are unknown.
- Delivery includes sourced changes, separately labeled sales hypotheses, and open questions. Do not contact the account automatically.

Inline sources avoid a dependency on external sites for fixture testing. Production tools need authorized access, retrieval times, and source versions. Missing evidence is not proof of no change.

## 2. Prepare a service and a batch ledger

[Publish](/v2/en/service/endpoints) the job Endpoint `account-research`, accepting request, account_id, research_date, strategy_version, and sources. Instructions separate observations from inference and prohibit invented budgets, buying intent, or dates.

Keep these **application records**; they are not a built-in Service batch API:

| Field | Purpose |
| --- | --- |
| batch_id, account_id, research_date, strategy_version | Identify business intent |
| attempt, idempotency_key, request_digest | Distinguish business retries from network retries |
| invocation_id, state, last_checked_at | Track accepted work |
| result_version, notification_id | Deduplicate writeback and notifications |

Use a database uniqueness constraint on business intent and attempt. Configure application/endpoint concurrency and token limits. Prepare Bash, curl, jq, BASE_URL, and a backend key with invoke/read.

## 3. Submit one object, then expand

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/account-research/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: A-101-2026-10-05-research-v1-attempt-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

Submit accounts separately so results can be reconciled per object. If a network timeout leaves acceptance uncertain, retry the same key and body. Back off on 429 using Retry-After and bound scheduler concurrency.

After a terminal failure, a deliberate business retry gets a new attempt and key. Reusing the key only returns the original call. Record the cause and bound retries. New dates, sources, or strategies also form new business intent.

## 4. Collect results and notify

Query each Invocation. If using Webhooks, verify the raw-body signature and deduplicate event IDs. Query immediately after subscribing in case work already finished. A callback triggers a fresh status/result read; it is not the deliverable.

Check source freshness, research date, and business version before writing back to the CRM. Deduplicate notifications separately. Users mostly need evidence-backed changes, failures, and questions, not every tool event.

Platform Automation currently starts Issue/Workflow work rather than directly invoking a public Endpoint. This external scheduler deliberately follows the Application, Release, and Invocation path; see [Unified service API](/v2/en/service/service-api).

## 5. Failure and acceptance matrix

| Experiment | Required result |
| --- | --- |
| Duplicate trigger | One application attempt and the same Invocation |
| Timeout after submission | Same key recovers work without missing or duplicate records |
| One account fails | Retain the failure while collecting other results |
| Scheduler restarts | Resume queries from durable records rather than resubmit everything |
| Duplicate Webhook | No duplicate writeback or notification |
| Source date changes | Traceable new request; old batch results preserved |
| Budget reached | Show admission/cancellation state rather than treating a missing report as complete |
| Fixed fixture | Confirm web launch and order-query interest; budget and purchase date remain unknown |

Token budgets use reported usage and can lag; reconcile actual costs separately. No real schedule or production batch is executed by this fixture. Measure object count, latency, cost, and restart recovery before launch.
