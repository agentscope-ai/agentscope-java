---
title: "Integrate applications with the Session API"
description: "Delegate tasks to an Agent through the Session API, follow progress, and participate during execution."
zh_link: /v2/zh/service/service-api
---

Once an Agent is configured, an application can delegate work to it through the Session API. Create a Session to select the Agent and configuration for this work, then submit a task. Each submission creates a Turn that tracks its execution and result. Service runs the work in the background and preserves its records, so the application can close the submission request and return to the same task later.

This page starts with application credentials and walks through creating a Session, submitting work, and handling results. For your first integration, use the Agent you already verified in [Create a Managed Agent](/v2/en/service/create-managed-agent). When you later need a Team or Workflow, use the same Session API, selecting the appropriate target and handling its supported input and interaction.

## Prepare application credentials

Use your platform Bearer token while developing. For a business backend, create an Application and issue an API key with explicit grants for the Agents, Teams or Workflows it may use. Credentials belong to the Application: replacing a key preserves access to its Sessions, while another application cannot read those Sessions simply because it uses the same Agent. Keep keys in your backend and check the business user's permissions there.

Complete [deployment](/v2/en/service/quickstart) and [create a working Managed Agent and prepare API credentials](/v2/en/service/create-managed-agent#api-setup) first. Keep `BASE_URL`, `TOKEN`, `TENANT`, `NAMESPACE` and `AGENT_ID` from those steps available. The Application owner performs these management requests:

```bash
set -euo pipefail

APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "name": "report-application"
}
JSON
)
APPLICATION_ID=$(jq -er '.application.id' <<< "$APPLICATION_JSON")
```

Issue a credential that can call this Agent:

```bash
KEY_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "name": "backend",
  "scopes": [
    "invoke",
    "read",
    "interact",
    "cancel",
    "webhooks:write"
  ],
  "targets": [
    {
      "type": "agent",
      "id": "$AGENT_ID"
    }
  ]
}
JSON
)
AGENTSCOPE_API_KEY=$(jq -er '.apiKey' <<< "$KEY_JSON")
CREDENTIAL_ID=$(jq -er '.credential.id' <<< "$KEY_JSON")
```

A key is returned in plaintext only when it is issued. To rotate it, create a replacement, update and verify your application, then revoke the previous credential. The scopes are `invoke`, `read`, `interact`, `cancel` and `webhooks:write`. An application key does not become a designated human approver merely because it has `interact`.

<Accordion title="List and revoke old credentials">

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

Issue and verify a replacement with the creation request above. Then replace `OLD_CREDENTIAL_ID` with the old credential ID from the list. This disables that key.

```bash
OLD_CREDENTIAL_ID="OLD_CREDENTIAL_ID_FROM_LIST"
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials/$OLD_CREDENTIAL_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

## Create a Session and select its target

Creating a Session selects and freezes the configuration for this work; it does not submit a task. Supply a stable idempotency key when creating a Session and retain the returned ID.

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

`target.type` is `agent`, `team` or `workflow`. Managed, External and Hosted Agents all use `agent` with their catalog ID; their runtime binding determines execution. Teams use a Team ID. Workflows use a definition ID and optionally a published `revisionId`; otherwise Service selects the latest published revision. Publishing a Workflow freezes its process definition rather than creating an application calling interface.

<Accordion title="Use a Team or published Workflow">

Grant the Team or Workflow in the credential’s `targets` and fill in actual IDs. These are alternatives to creating an Agent Session. A Workflow `revisionId` must refer to a published revision.

<Tabs>
<Tab title="Team">

```bash
TEAM_ID="YOUR_TEAM_ID"
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: team-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "team",
    "id": "$TEAM_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

</Tab>
<Tab title="Workflow">

```bash
WORKFLOW_ID="YOUR_WORKFLOW_ID"
REVISION_ID="YOUR_PUBLISHED_REVISION_ID"
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: workflow-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "workflow",
    "id": "$WORKFLOW_ID",
    "revisionId": "$REVISION_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

</Tab>
</Tabs>

</Accordion>

A Session freezes the target configuration and dependencies. Use `target.version` to select a Managed Agent definition version. Changes become available to new Sessions while existing Sessions retain their configuration. Environment, Memory, Vault and external business data have their own access and update rules; a configuration snapshot does not freeze external data.

If the Agent needs particular credentials to access external tools, select the corresponding Vault when creating the Session or inherit the Agent's default Vaults. The API key your application uses to call Service does not automatically authenticate tools to external systems. See [Select Vaults for a Session](/v2/en/service/vault#select-vaults-for-a-session) for configuration and the `vaultIds` field.

## Submit a Turn

Managed Agents accept a text `message` or an `input` array of user messages and content blocks. Teams and Workflows also accept structured business `input` for their task executor. Match the input to the target's actual behavior.

```bash
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-task-001" \
    --data-binary @- <<'JSON'
{
  "message": "Prepare a report using authorized sources, with citations and open questions."
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
```

Read the submitted task:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

`202 Accepted` means the task has been accepted; it may still be queued or running. After a network timeout, retry with the same Session, idempotency key and request body. Service returns the original Turn. Changing the body while reusing the key returns `409 Conflict`. Use a new key only for a new task.

Turns execute sequentially within a Session. Managed Agents retain conversational context. Each Team or Workflow Turn starts an independent task: its history is collected in the Session, but a previous task's internal execution context is not automatically inherited. Include any previous results needed by the next task in its input. Use separate Sessions for unrelated objects or parallel batches.

## Restore a page and observe execution

Persist the business object, Session ID and Turn ID together. On page refresh, restore `/snapshot`, then subscribe to `/events/stream` from its `as_of` cursor. A refresh restores the display and must not resubmit task input. If local state survives a disconnect, reconnect from the last successfully applied event cursor.

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

To observe one Turn, use `/turns/{turnId}/snapshot` and `/turns/{turnId}/events/stream`. Session and Turn cursors belong to separate logs and cannot be interchanged. A Session stream stays open for later Turns, so connection closure is not completion. Read the Turn status: `completed` is success, `partial_succeeded` is partial success, and `failed`, `cancelled` and `timed_out` are other terminal outcomes.

A Turn snapshot indexes `items`, `tools`, `required_actions`, `steps`, `artifacts` and `usage` by identifier. Managed Session snapshots also retain complete conversation, subagent and native interaction records. See the [API reference](/v2/en/service/api-reference) for the interfaces and [events and notifications](/v2/en/service/sse-events) for replay and callback handling.

## Participate during execution

Read pending work from `required_actions` and answer through the Turn's `/actions` resource. Include `request_id` and, when required, `expected_version`. Managed confirmation answers go in `payload`, for example `{"allow":true}`. A designated human must answer using their authorized user identity; an application key cannot impersonate them.

Read the current Turn’s pending actions first. Do not call `/actions` if none are pending:

```bash
ACTIONS_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
jq '.required_actions' <<< "$ACTIONS_JSON"
```

Inspect the selected action’s description, `kind`, and request. Copy its actual `request_id` unchanged, including prefixes such as `native:`. Answer the action the user selected rather than automatically allowing everything in the list.

```bash
REQUEST_ID="REQUEST_ID_FROM_PENDING_ACTION"
```

Choose the answer matching the pending action. Tool confirmation and business approval require an authorized user’s `TOKEN`; external tool results may use an application key with `interact` scope.

<Tabs>
<Tab title="Tool confirmation">

For a Managed `confirmation` action, let the user inspect the tool and arguments before answering. Use `allow: true` to allow, or `false` with a reason to deny. The user must be the designated confirmer or explicitly delegated by the Managed Agent owner.

```bash
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-confirmation-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "payload": {
    "allow": true,
    "reason": "The user reviewed the tool arguments and confirmed execution."
  }
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

</Tab>
<Tab title="External tool result">

For a Managed `external_execution` action, replace `output` with the actual execution result. On failure, use `is_error: true` and provide the actual error.

```bash
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-tool-result-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "payload": {
    "output": "Replace with actual tool execution result",
    "is_error": false
  }
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

</Tab>
<Tab title="Business approval">

Read the current approval version from the action, replace `1` below with that actual number, and use the designated approver’s `TOKEN`. Use `approved` or `rejected`; this answer does not use `payload.allow`.

```bash
EXPECTED_VERSION=1
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-approval-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "expected_version": $EXPECTED_VERSION,
  "decision": "approved"
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

</Tab>
</Tabs>

A `202 Accepted` response means the answer was received. Query the returned command ID until `command.status` is `completed` or `failed`. On failure, inspect `command.error` and reread the pending action. Retry a lost request with its original idempotency key and body.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

A completed command means the answer was handled; the task may still be running. Continue observing events or reading the Turn state. For other input operations such as adding context or steering, see [Sessions, tasks, and budgets](/v2/en/service/session-event-log).

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

Read Session `/capabilities` for the target's supported features, then Turn `/capabilities` and its `available_commands` before showing cancellation, input or resume controls. An accepted cancellation request still needs a confirmed terminal outcome. Continue with [Sessions, tasks, and budgets](/v2/en/service/session-event-log) to learn how to add requirements, answer pending actions, and resume execution.

```bash
curl -sS --fail-with-body "$SESSION_URL/capabilities" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/capabilities" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

## Credentials, budgets and notifications

Application `maxConcurrent` and `tokenBudget` apply across its keys and Sessions. Reported runtime usage drives token accounting and subsequent admission, so this is execution governance rather than a real-time hard limit on a model provider's bill. Session creation also accepts `timeoutSeconds` and `budget.maxTokens` for each Turn. Managed Session budgets have a separate `/budget` resource; see [Usage, subagents, and budgets](/v2/en/service/session-event-log#budgets) for configuration and usage queries.

<Accordion title="Set application limits and Turn budgets">

As the Application owner, read its current version before updating limits. Reread after a version conflict rather than overwriting another update.

```bash
APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
APPLICATION_VERSION=$(jq -er '.application.version' <<< "$APPLICATION_JSON")

curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "version": $APPLICATION_VERSION,
  "maxConcurrent": 5,
  "tokenBudget": 1000000
}
JSON
```

Set the timeout in seconds and token budget for each Turn when creating a new Session:

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: limited-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  },
  "timeoutSeconds": 300,
  "budget": {
    "maxTokens": 10000
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

</Accordion>

Register a Session Webhook when a backend needs notification of completion, failure or required interaction. Verify its signature, deduplicate by event ID and fetch the current Session or Turn state. See [Webhook notifications](/v2/en/service/sse-events#webhooks) for delivery and retry behavior.

## Check the deliverables

A `completed` Turn means this execution finished successfully. The application still needs to check that the deliverables meet its business requirements: a report may need sources, a file must be downloadable, and a structured result must contain the data required for further processing. For file output, use the interfaces in [Files and artifacts](/v2/en/service/files) to retrieve the actual artifact rather than treating a filename in the Agent's reply as proof of delivery.

Optional `inputSchema`, `outputSchema` and `resultMapping` settings are frozen when creating the Session. Verify real target output before defining mappings or validation. JSON written inside an Agent's text reply is not automatically a structured business result. These rules validate data shape; they do not assess content quality or automatically make the Agent revise its output.

<Accordion title="Validate structured input and results">

This assumes a published Workflow accepts `{topic}` and actually returns `{answer}`. Adapt the schemas and JSON Pointer to real target data; `/answer` selects a structured result field. Grant this Workflow to the application credential first.

```bash
WORKFLOW_ID="YOUR_WORKFLOW_ID"
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: schema-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "workflow",
    "id": "$WORKFLOW_ID"
  },
  "inputSchema": {
    "type": "object",
    "required": [
      "topic"
    ],
    "properties": {
      "topic": {
        "type": "string"
      }
    }
  },
  "outputSchema": {
    "type": "object",
    "required": [
      "answer"
    ],
    "properties": {
      "answer": {
        "type": "string"
      }
    }
  },
  "resultMapping": {
    "answer": "/answer"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: schema-task-001" \
  --data-binary @- <<'JSON'
{
  "input": {
    "topic": "Installation guide"
  }
}
JSON
```

</Accordion>

If the business process also needs an assignee, discussion, and human acceptance records, organize delivery with an Issue as described in [Work assignment, approval, and acceptance](/v2/en/service/issues). Issue acceptance confirms that the business work has been accepted, while Session task completion records that an execution has ended.

## SDK and runnable examples

Python `ServiceClient` wraps the same API:

```bash
export BASE_URL AGENTSCOPE_API_KEY AGENT_ID
```

```python
import os
from agentscope_service import ServiceClient

api = ServiceClient(os.environ["BASE_URL"], os.environ["AGENTSCOPE_API_KEY"])
session = api.create_session({"type": "agent", "id": os.environ["AGENT_ID"]},
                             idempotency_key="sdk-report-session-001")
turn = api.submit(session["id"], message="Prepare a report with sources.",
                  idempotency_key="sdk-report-task-001")
print(api.turn(session["id"], turn["id"]))
```

`agentscope-service/service-controlplane/examples/service-api` contains runnable registration and calling examples for Agents, Teams and Workflows. Use `ManagementClient` for configuration and `ServiceClient` for application execution. See the [API reference](/v2/en/service/api-reference) for interface entry points.
