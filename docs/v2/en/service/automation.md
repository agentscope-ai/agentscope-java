---
title: "Schedules and event triggers"
zh_link: /v2/zh/service/automation
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

An Automation separates what to execute from when to trigger it. One daily-digest Runbook can run on a weekday schedule or after an external event. Delivery and Run records let applications inspect event receipt, execution, and results.

When configuring a rule, select an Agent or Team as its execution target, then choose whether a manual request, Cron schedule, or Webhook event starts the work. For a fixed sequence of steps, define a [Workflow](/v2/en/service/workflows) and invoke its published revision through the [Session API](/v2/en/service/service-api). Automation cannot currently trigger a Workflow directly. Work from messaging platforms requires a separate [Channel connection](/v2/en/service/channels); a Channel cannot be configured as an Automation trigger.

## Create a digest rule

The examples use Bash, `curl`, and `jq`. Set `SERVICE_URL` to your Service address, `TOKEN` to a user Bearer token, and `TENANT` / `NAMESPACE` to your authorized scope; see [API authentication](/v2/en/service/api-reference). Define this request helper:

```bash
api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' "$@"
}
```


Set `AGENT_ID` to the digest Agent. Create a disabled rule with both schedule and Webhook triggers, so you can inspect a test execution before enabling automatic work:

```bash
created=$(api "$SERVICE_URL/api/v1/automations" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" --arg agent "$AGENT_ID" \
  '{tenant:$tenant,namespace:$namespace,name:"Project digest",enabled:false,
    execution:{runbook:"Summarize project progress from the Workspace, citing sources and open questions.",
      assigneeType:"agent",assigneeRef:$agent,outputMode:"create_issue",
      completionPolicy:"review",concurrencyPolicy:"skip",
      queueTimeoutSeconds:3600,runTimeoutSeconds:3600},
    triggers:[{type:"cron",enabled:true,schedule:"0 9 * * 1-5",timezone:"Asia/Shanghai"},
      {type:"webhook",enabled:true,events:["build.completed"]}]}')")
AUTOMATION_ID=$(jq -r '.automation.id' <<<"$created")
TRIGGER_ID=$(jq -r '.automation.triggers[] | select(.type=="webhook") | .id' <<<"$created")
AUTOMATION_SECRET=$(jq -r '.webhookSecret' <<<"$created")
```

`execution.runbook` describes the work. Use assignee type `team` for a Team. Environment and tools come from the target itself. `outputMode:"create_issue"` retains collaborative, reviewable work; `run_only` exposes results through automation execution and uses automatic completion.

Save `webhookSecret` in the sender's credential configuration; it is returned only on creation or rotation. Retain trigger IDs when updating the trigger collection.

## Preview and test

Specify the intended time zone. Schedule preview returns the next five trigger times:

```bash
api "$SERVICE_URL/api/v1/automations/schedule-preview" \
  --data '{"schedule":"0 9 * * 1-5","timezone":"Asia/Shanghai"}'
tested=$(api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/trigger?test=true" \
  -H 'Idempotency-Key: digest-test-001' --data '{"project":"example"}')
AUTOMATION_RUN_ID=$(jq -r '.run.id' <<<"$tested")
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID"
```

Test runs execute real models and tools and can exercise disabled rules. Ordinary manual invocation uses `/trigger` without `test=true`. Save `run.id`; HTTP 202 is acceptance, not completion.

After reviewing results and artifacts, enable the current version:

```bash
current=$(api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID")
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID" -X PATCH \
  --data "$(jq -n --argjson version "$(jq '.automation.version' <<<"$current")" \
  '{enabled:true,expectedVersion:$version}')"
```

Use PATCH with `expectedVersion` to update the Runbook, target, or triggers. Reload on conflict. Both the rule and its relevant trigger must be enabled for automatic dispatch.

## Receive an external Webhook

External systems post a JSON object or array to this URL. It authenticates with `X-Automation-Secret`, not a user Bearer token:

```bash
curl --fail-with-body "$SERVICE_URL/hooks/v1/automations/$AUTOMATION_ID/$TRIGGER_ID" \
  -H 'Content-Type: application/json' \
  -H "X-Automation-Secret: $AUTOMATION_SECRET" \
  -H 'Idempotency-Key: build-001' -H 'X-Event-Type: build.completed' \
  --data '{"project":"example","commit":"COMMIT_SHA","result":"passed"}'
```

Retransmit the same event with the same key and content; use a new key for new work. An empty `events` filter accepts all event types. The payload's `event` field can also specify the type. Input is appended as `Trigger data`, so define field meanings, material access, and missing-data handling in the Runbook.

This inbound Webhook lets external systems trigger Service work. Register a [Session or Turn Webhook](/v2/en/service/sse-events#webhooks) when your backend needs result notifications. The directions, URLs, and authentication differ. Adapt through your backend if the external sender cannot provide required headers.

## Inspect results, overlap, and retries

```bash
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/deliveries?limit=25"
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/runs?limit=25"
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID"
```

Deliveries describe receipt, filtering, or rejection. Runs record execution `status`, `waitReason`, input, output, and errors; detail responses link Issues, tasks, and Artifacts. Work with `review` completion can still await [human acceptance](/v2/en/service/issues#inbox) after computation finishes.

`concurrencyPolicy:"skip"` skips overlapping triggers; `queue` processes them in order. `queueTimeoutSeconds` bounds waiting and `runTimeoutSeconds` bounds execution; each accepts 60–604800 seconds. Disabling a rule stops future triggers. Cancel existing work through `POST /api/v1/automations/{id}/runs/{runId}/cancel`.

Use `/runs/{runId}/rerun` for another execution or `/deliveries/{deliveryId}/replay` to replay a delivery. Both use a new `Idempotency-Key` and retain lineage. Inspect the failure first because re-execution can repeat external side effects. Rotate secrets with `/rotate-secret`, providing `expectedVersion`, and update the sender.

See [Console: automation and channels](/v2/en/service/console/index#console-automation) for configuration and execution-history UI flows.
