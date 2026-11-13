---
title: "Schedules and event triggers"
zh_link: /v2/zh/service/automation
---

<Note>
This page uses the `2.1.0-BETA1` prerelease.
</Note>

An Automation separates what to execute from when to trigger it. One daily-digest Runbook can run on a weekday schedule or after an external event. Delivery and Run records let applications inspect event receipt, execution, and results.

When configuring a rule, select an Agent or Team as its execution target, then choose whether a manual request, Cron schedule, or Webhook event starts the work. For a fixed sequence of steps, define a [Workflow](/v2/en/service/workflows) and invoke its published revision through the [Session API](/v2/en/service/service-api). Automation cannot currently trigger a Workflow directly. Work from messaging platforms requires a separate [Channel connection](/v2/en/service/channels); a Channel cannot be configured as an Automation trigger.

## Create a digest rule

The examples use Bash, `curl`, and `jq`. Set `BASE_URL` to your Service address, and `TENANT` / `NAMESPACE` to the default scope; see [API authentication](/v2/en/service/api-reference). Run the following steps in the same Bash terminal:

```bash
set -euo pipefail
```


Set `AGENT_ID` to the digest Agent. Create a disabled rule with both schedule and Webhook triggers, so you can inspect a test execution before enabling automatic work:

```bash
AUTOMATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/automations" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "name": "Project digest",
  "enabled": false,
  "execution": {
    "runbook": "Summarize project progress from the Workspace with sources and open questions.",
    "assigneeType": "agent",
    "assigneeRef": "$AGENT_ID",
    "outputMode": "create_issue",
    "completionPolicy": "review",
    "concurrencyPolicy": "skip",
    "queueTimeoutSeconds": 3600,
    "runTimeoutSeconds": 3600
  },
  "triggers": [
    {
      "type": "cron",
      "enabled": true,
      "schedule": "0 9 * * 1-5",
      "timezone": "Asia/Shanghai"
    },
    {
      "type": "webhook",
      "enabled": true,
      "events": [
        "build.completed"
      ]
    }
  ]
}
JSON
)
AUTOMATION_ID=$(jq -er '.automation.id' <<< "$AUTOMATION_JSON")
TRIGGER_ID=$(jq -er '.automation.triggers[] | select(.type == "webhook") | .id' <<< "$AUTOMATION_JSON")
AUTOMATION_SECRET=$(jq -er '.webhookSecret' <<< "$AUTOMATION_JSON")
```

`execution.runbook` describes the work. Use assignee type `team` for a Team. Environment and tools come from the target itself. `outputMode:"create_issue"` retains collaborative, reviewable work; `run_only` exposes results through automation execution and uses automatic completion.

Save `webhookSecret` in the sender's credential configuration; it is returned only on creation or rotation. Retain trigger IDs when updating the trigger collection.

## Preview and test

Specify the intended time zone. Schedule preview returns the next five trigger times:

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/automations/schedule-preview" \
  -H "Content-Type: application/json" \
  --data-binary @- <<'JSON'
{
  "schedule": "0 9 * * 1-5",
  "timezone": "Asia/Shanghai"
}
JSON
```

```bash
TESTED_RUN=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/automations/$AUTOMATION_ID/trigger?test=true" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: digest-test-001" \
    --data-binary @- <<'JSON'
{
  "project": "example"
}
JSON
)
AUTOMATION_RUN_ID=$(jq -er '.run.id' <<< "$TESTED_RUN")
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID"
```

Test runs execute real models and tools and can exercise disabled rules. Ordinary manual invocation uses `/trigger` without `test=true`. Save `run.id`; HTTP 202 is acceptance, not completion.

After reviewing results and artifacts, enable the current version:

```bash
AUTOMATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/automations/$AUTOMATION_ID"
)
AUTOMATION_VERSION=$(jq -er '.automation.version' <<< "$AUTOMATION_JSON")
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/automations/$AUTOMATION_ID" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "enabled": true,
  "expectedVersion": $AUTOMATION_VERSION
}
JSON
```

Use PATCH with `expectedVersion` to update the Runbook, target, or triggers. Reload on conflict. Both the rule and its relevant trigger must be enabled for automatic dispatch.

## Receive an external Webhook

External systems post a JSON object or array to this URL. It authenticates with `X-Automation-Secret`, not a user Bearer token:

```bash
curl -sS --fail-with-body "$BASE_URL/hooks/v1/automations/$AUTOMATION_ID/$TRIGGER_ID" \
  -H "Content-Type: application/json" \
  -H "X-Automation-Secret: $AUTOMATION_SECRET" \
  -H "X-Event-Type: build.completed" \
  -H "Idempotency-Key: build-001" \
  --data-binary @- <<'JSON'
{
  "project": "example",
  "commit": "COMMIT_SHA",
  "result": "passed"
}
JSON
```

Retransmit the same event with the same key and content; use a new key for new work. An empty `events` filter accepts all event types. The payload's `event` field can also specify the type. Input is appended as `Trigger data`, so define field meanings, material access, and missing-data handling in the Runbook.

This inbound Webhook lets external systems trigger Service work. Register a [Session or Turn Webhook](/v2/en/service/sse-events#webhooks) when your backend needs result notifications. The directions, URLs, and authentication differ. Adapt through your backend if the external sender cannot provide required headers.

## Inspect results, overlap, and retries

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/automations/$AUTOMATION_ID/deliveries" \
  --data-urlencode "limit=25"
```

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/automations/$AUTOMATION_ID/runs" \
  --data-urlencode "limit=25"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID"
```

Deliveries describe receipt, filtering, or rejection. Runs record execution `status`, `waitReason`, input, output, and errors; detail responses link Issues, tasks, and Artifacts. Work with `review` completion can still await [human acceptance](/v2/en/service/issues#inbox) after computation finishes.

`concurrencyPolicy:"skip"` skips overlapping triggers; `queue` processes them in order. `queueTimeoutSeconds` bounds waiting and `runTimeoutSeconds` bounds execution; each accepts 60–604800 seconds. Disabling a rule stops future triggers. Cancel existing work through `POST /api/v1/automations/{id}/runs/{runId}/cancel`.

Use `/runs/{runId}/rerun` for another execution or `/deliveries/{deliveryId}/replay` to replay a delivery. Both use a new `Idempotency-Key` and retain lineage. Inspect the failure first because re-execution can repeat external side effects. Rotate secrets with `/rotate-secret`, providing `expectedVersion`, and update the sender.

See [Console: automation and channels](/v2/en/service/console/index#console-automation) for configuration and execution-history UI flows.

<span id="curl-management"></span>

## Cancellation, retries and secret maintenance

Use the local URL and default scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup).

Use these operations when needed after inspecting the run or delivery failure. Cancellation, rerun and delivery replay are distinct choices. Re-execution can repeat external side effects.

<Tabs>
<Tab title="Cancel run">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID/cancel"
```

</Tab>
<Tab title="Rerun">

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID/rerun" \
  -H "Idempotency-Key: digest-rerun-001"
```

</Tab>
<Tab title="Replay delivery">

```bash
DELIVERY_ID="DELIVERY_ID_FROM_LIST"
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/automations/$AUTOMATION_ID/deliveries/$DELIVERY_ID/replay" \
  -H "Idempotency-Key: build-replay-001"
```

</Tab>
</Tabs>

<Accordion title="Rotate the Webhook secret">

Read the current rule version. Update the sender with the new secret after rotation; the old secret becomes invalid.

```bash
AUTOMATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/automations/$AUTOMATION_ID"
)
AUTOMATION_VERSION=$(jq -er '.automation.version' <<< "$AUTOMATION_JSON")
```

```bash
ROTATED_AUTOMATION=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/automations/$AUTOMATION_ID/rotate-secret" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "expectedVersion": $AUTOMATION_VERSION
}
JSON
)
AUTOMATION_SECRET=$(jq -er '.webhookSecret' <<< "$ROTATED_AUTOMATION")
```

</Accordion>
