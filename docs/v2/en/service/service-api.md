---
title: "Integrate applications with the Session API"
description: "Delegate tasks to an Agent through the Session API, follow progress, and participate during execution."
zh_link: /v2/zh/service/service-api
---

Once an Agent is configured, an application can delegate work to it through the Session API. Create a Session to select the Agent and configuration for this work, then submit a task. Each submission creates a Turn that tracks its execution and result. Service runs the work in the background and preserves its records, so the application can close the submission request and return to the same task later.

This page starts with application credentials and walks through creating a Session, submitting work, and handling results. For your first integration, use the Agent you already verified in [Create a Managed Agent](/v2/en/service/create-managed-agent). When you later need a Team or Workflow, use the same Session API, selecting the appropriate target and handling its supported input and interaction.

## Prepare application credentials

Use your platform Bearer token while developing. For a business backend, create an Application and issue an API key with explicit grants for the Agents, Teams or Workflows it may use. Credentials belong to the Application: replacing a key preserves access to its Sessions, while another application cannot read those Sessions simply because it uses the same Agent. Keep keys in your backend and check the business user's permissions there.

Complete [deployment and login](/v2/en/service/quickstart) and [create a working Managed Agent](/v2/en/service/create-managed-agent) first. Keep `BASE_URL`, `TOKEN`, `TENANT`, `NAMESPACE` and `AGENT_ID` from those steps available. The Application owner performs these management requests:

```bash
APPLICATION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/applications" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    '{tenant:$tenant,namespace:$namespace,name:"report-application"}')")
APPLICATION_ID=$(printf '%s' "$APPLICATION_JSON" | jq -er '.application.id')
KEY_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" \
    '{name:"backend",scopes:["invoke","read","interact","cancel"],targets:[{type:"agent",id:$agent}]}')")
AGENTSCOPE_API_KEY=$(printf '%s' "$KEY_JSON" | jq -er '.apiKey')
```

A key is returned in plaintext only when it is issued. To rotate it, create a replacement, update and verify your application, then revoke the previous credential. The scopes are `invoke`, `read`, `interact`, `cancel` and `webhooks:write`. An application key does not become a designated human approver merely because it has `interact`.

## Create a Session and select its target

Creating a Session selects and freezes the configuration for this work; it does not submit a task. Supply a stable idempotency key when creating a Session and retain the returned ID.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: report-session-001' \
  --data "$(jq -n --arg agent "$AGENT_ID" '{target:{type:"agent",id:$agent}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

`target.type` is `agent`, `team` or `workflow`. Managed, External and Hosted Agents all use `agent` with their catalog ID; their runtime binding determines execution. Teams use a Team ID. Workflows use a definition ID and optionally a published `revisionId`; otherwise Service selects the latest published revision. Publishing a Workflow freezes its process definition rather than creating an application calling interface.

A Session freezes the target configuration and dependencies. Use `target.version` to select a Managed Agent definition version. Changes become available to new Sessions while existing Sessions retain their configuration. Environment, Memory, Vault and external business data have their own access and update rules; a configuration snapshot does not freeze external data.

If the Agent needs particular credentials to access external tools, select the corresponding Vault when creating the Session or inherit the Agent's default Vaults. The API key your application uses to call Service does not automatically authenticate tools to external systems. See [Select Vaults for a Session](/v2/en/service/vault#select-vaults-for-a-session) for configuration and the `vaultIds` field.

## Submit a Turn

Managed Agents accept a text `message` or an `input` array of user messages and content blocks. Teams and Workflows also accept structured business `input` for their task executor. Match the input to the target's actual behavior.

```bash
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: report-task-001' \
  --data '{"message":"Prepare a report using authorized sources, with citations and open questions."}')
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` means the task has been accepted; it may still be queued or running. After a network timeout, retry with the same Session, idempotency key and request body. Service returns the original Turn. Changing the body while reusing the key returns `409 Conflict`. Use a new key only for a new task.

Turns execute sequentially within a Session. Managed Agents retain conversational context. Each Team or Workflow Turn starts an independent task: its history is collected in the Session, but a previous task's internal execution context is not automatically inherited. Include any previous results needed by the next task in its input. Use separate Sessions for unrelated objects or parallel batches.

## Restore a page and observe execution

Persist the business object, Session ID and Turn ID together. On page refresh, restore `/snapshot`, then subscribe to `/events/stream` from its `as_of` cursor. A refresh restores the display and must not resubmit task input. If local state survives a disconnect, reconnect from the last successfully applied event cursor.

To observe one Turn, use `/turns/{turnId}/snapshot` and `/turns/{turnId}/events/stream`. Session and Turn cursors belong to separate logs and cannot be interchanged. A Session stream stays open for later Turns, so connection closure is not completion. Read the Turn status: `completed` is success, `partial_succeeded` is partial success, and `failed`, `cancelled` and `timed_out` are other terminal outcomes.

A Turn snapshot indexes `items`, `tools`, `required_actions`, `steps`, `artifacts` and `usage` by identifier. Managed Session snapshots also retain complete conversation, subagent and native interaction records. Use the machine-readable contract for each resource shape and [events and notifications](/v2/en/service/sse-events) for replay and callback handling.

## Participate during execution

Read pending work from `required_actions` and answer through the Turn's `/actions` resource. Include `request_id` and, when required, `expected_version`. Managed confirmation answers go in `payload`, for example `{"allow":true}`. A designated human must answer using their authorized user identity; an application key cannot impersonate them.

Read Session `/capabilities` for the target's supported features, then Turn `/capabilities` and its `available_commands` before showing cancellation, input or resume controls. An accepted cancellation request still needs a confirmed terminal outcome. Continue with [Sessions, tasks, and budgets](/v2/en/service/session-event-log) to learn how to add requirements, answer pending actions, and resume execution.

## Credentials, budgets and notifications

Application `maxConcurrent` and `tokenBudget` apply across its keys and Sessions. Reported runtime usage drives token accounting and subsequent admission, so this is execution governance rather than a real-time hard limit on a model provider's bill. Session creation also accepts `timeoutSeconds` and `budget.maxTokens` for each Turn. Managed Session budgets have a separate `/budget` resource; see [Usage, subagents, and budgets](/v2/en/service/session-event-log#budgets) for configuration and usage queries.

Register a Session Webhook when a backend needs notification of completion, failure or required interaction. Verify its signature, deduplicate by event ID and fetch the current Session or Turn state. See [Webhook notifications](/v2/en/service/sse-events#webhooks) for delivery and retry behavior.

## Check the deliverables

A `completed` Turn means this execution finished successfully. The application still needs to check that the deliverables meet its business requirements: a report may need sources, a file must be downloadable, and a structured result must contain the data required for further processing. For file output, use the interfaces in [Files and artifacts](/v2/en/service/files) to retrieve the actual artifact rather than treating a filename in the Agent's reply as proof of delivery.

Optional `inputSchema`, `outputSchema` and `resultMapping` settings are frozen when creating the Session. Verify real target output before defining mappings or validation. JSON written inside an Agent's text reply is not automatically a structured business result. These rules validate data shape; they do not assess content quality or automatically make the Agent revise its output.

If the business process also needs an assignee, discussion, and human acceptance records, organize delivery with an Issue as described in [Work assignment, approval, and acceptance](/v2/en/service/issues). Issue acceptance confirms that the business work has been accepted, while Session task completion records that an execution has ended.

## SDK and runnable examples

Python `ServiceClient` wraps the same API:

```python
import os
from aistio import ServiceClient

api = ServiceClient(os.environ["BASE_URL"], os.environ["AGENTSCOPE_API_KEY"])
session = api.create_session({"type": "agent", "id": os.environ["AGENT_ID"]},
                             idempotency_key="report-session-001")
turn = api.submit(session["id"], message="Prepare a report with sources.",
                  idempotency_key="report-task-001")
print(api.turn(session["id"], turn["id"]))
```

`agentscope-service/aistio/examples/service-api` contains runnable registration and calling examples for Agents, Teams and Workflows. Use `ManagementClient` for configuration and `ServiceClient` for application execution. The contract is `agentscope-service/docs/service-api/openapi-v1.json`.
