---
title: "Integrate applications with the Session API"
description: "Delegate tasks to an Agent through the Session API, follow progress, and participate during execution."
zh_link: /v2/zh/service/service-api
---

<Note>
Current MQ / Redis Managed Agents use the [native Managed Session API](/v2/en/service/claude-session-api) at `/v1/sessions`, with `agent` creation and `user.message` writes through `/events`. Follow [your first Managed Agent](/v2/en/service/create-managed-agent) for a runnable walkthrough. The target-based `/api/v1/agent-sessions` examples below describe the compatibility path for other backends; its snapshots, SSE cursors and controls are not interchangeable with the native Managed protocol.
</Note>


Once an Agent is configured, an application can delegate work to it through the Session API. Create a Session to select the Agent and configuration for this work, then submit a task. Each submission creates a Turn that tracks its execution and result. Service runs the work in the background and preserves its records, so the application can close the submission request and return to the same task later.

This page starts with application credentials and walks through creating a Session, submitting work, and handling results. For your first integration, use the Agent you already verified in [Create a Managed Agent](/v2/en/service/create-managed-agent). When you later need a Team or Workflow, use the same Session API, selecting the appropriate target and handling its supported input and interaction.

<span id="prepare-application-credentials"></span>

## Prepare

Complete [local deployment](/v2/en/service/quickstart) and [create your first Agent](/v2/en/service/create-managed-agent). Keep `BASE_URL` and `AGENT_ID` and call the APIs below directly; local mode needs no Application or API key. See [production deployment](/v2/en/service/kubernetes#production-application-credentials) for accounts, grants, and application credentials.

## Create a Session and select its target

Creating a Session selects and freezes the configuration for this work; it does not submit a task. Supply a stable idempotency key when creating a Session and retain the returned ID.

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
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
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
```

`202 Accepted` means the task has been accepted; it may still be queued or running. After a network timeout, retry with the same Session, idempotency key and request body. Service returns the original Turn. Changing the body while reusing the key returns `409 Conflict`. Use a new key only for a new task.

Turns execute sequentially within a Session. Managed Agents retain conversational context. Each Team or Workflow Turn starts an independent task: its history is collected in the Session, but a previous task's internal execution context is not automatically inherited. Include any previous results needed by the next task in its input. Use separate Sessions for unrelated objects or parallel batches.

## Restore a page and observe execution

Persist the business object, Session ID and Turn ID together. On page refresh, restore `/snapshot`, then subscribe to `/events/stream` from its `as_of` cursor. A refresh restores the display and must not resubmit task input. If local state survives a disconnect, reconnect from the last successfully applied event cursor.

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot"
)
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

To observe one Turn, use `/turns/{turnId}/snapshot` and `/turns/{turnId}/events/stream`. For Managed Agents, Turn reads filter the same Session log and use its cursor. Other backends retain separate Session and Turn logs and cursors. A Session stream stays open for later Turns, so connection closure is not completion. Read the Turn status: `completed` is success, `partial_succeeded` is partial success, and `failed`, `cancelled` and `timed_out` are other terminal outcomes.

A Turn snapshot indexes `items`, `tools`, `required_actions`, `steps`, `artifacts` and `usage` by identifier. Managed Session snapshots also retain complete conversation, subagent and native interaction records. See the [API reference](/v2/en/service/api-reference) for the interfaces and [events and notifications](/v2/en/service/sse-events) for replay and callback handling.

## Participate during execution

Read pending work from `required_actions` and answer through the Turn's `/actions` resource. Include `request_id` and, when required, `expected_version`. Managed answers use `{"answers":[{"request_id":"...","allow":true}]}`; their response is the accepted Turn. `GET /turns/{turnId}/actions` lists command receipts. Generic Team and Workflow commands keep their own payload and receipt format. Local mode accepts answers with the development identity; production approval identity rules are in the [production guide](/v2/en/service/kubernetes#production-api-access).

Read the current Turn’s pending actions first. Do not call `/actions` if none are pending:

```bash
ACTIONS_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot"
)
jq '.required_actions' <<< "$ACTIONS_JSON"
```

Inspect the selected action’s description, `kind`, and request. Copy its actual `request_id` unchanged; use the ID returned by that runtime. Answer the action the user selected rather than automatically allowing everything in the list.

```bash
REQUEST_ID="REQUEST_ID_FROM_PENDING_ACTION"
```

Choose the answer matching the pending action and submit it directly in local mode. See the [production guide](/v2/en/service/kubernetes#production-api-access) for approval identity requirements.

<Tabs>
<Tab title="Tool confirmation">

For a Managed `confirmation` action, let the user inspect the tool and arguments before answering. Use `allow: true` to allow, or `false` with a reason to deny. Local mode accepts this answer directly; production confirmation identity requirements are in the [production guide](/v2/en/service/kubernetes#production-api-access).

```bash
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-confirmation-001" \
    --data-binary @- <<JSON
{
  "answers": [{
    "request_id": "$REQUEST_ID",
    "allow": true,
    "reason": "The user reviewed the tool arguments and confirmed execution."
  }]
}
JSON
)
# Managed answers return the Turn; follow /snapshot and /events/stream.
```

</Tab>
<Tab title="External tool result">

For a Managed `external_execution` action, replace `output` with the actual execution result. On failure, use `is_error: true` and provide the actual error.

```bash
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-tool-result-001" \
    --data-binary @- <<JSON
{
  "answers": [{
    "request_id": "$REQUEST_ID",
    "output": "Replace with actual tool execution result",
    "is_error": false
  }]
}
JSON
)
# Managed answers return the Turn; follow /snapshot and /events/stream.
```

</Tab>
<Tab title="Business approval">

Read the current approval version from the action and replace `1` below with that actual number. Use `approved` or `rejected`; this answer does not use `payload.allow`.

```bash
EXPECTED_VERSION=1
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
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

For an Endpoint Invocation backed by Managed execution, a command can remain `submitted` while its native Input is pending. The receipt exposes `input_id`, `input_status` and `input_delivery_status`; delivery alone does not mean the Agent applied the input. A completed cancellation command still requires a terminal Turn outcome before the application reports execution stopped. Managed Turns continue after a reply or recover automatically; they do not expose a separate `resume` command. Direct Managed Session integrations use the [native Session API](/v2/en/service/claude-session-api).

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID"
```

A completed command means the answer was handled; the task may still be running. Continue observing events or reading the Turn state. For other input operations such as adding context or steering, see [Sessions, tasks, and budgets](/v2/en/service/session-event-log).

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
```

Read Session `/capabilities` for the target's supported features, then Turn `/capabilities` and its `available_commands` before showing cancellation, input or resume controls. An accepted cancellation request still needs a confirmed terminal outcome. Continue with [Sessions, tasks, and budgets](/v2/en/service/session-event-log) to learn how to add requirements, answer pending actions, and resume execution.

```bash
curl -sS --fail-with-body "$SESSION_URL/capabilities"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/capabilities"
```

<span id="credentials-budgets-and-notifications"></span>

## Budgets and notifications

See [production deployment](/v2/en/service/kubernetes#production-application-credentials) for application-wide limits. Other backends accept `timeoutSeconds` and `budget.maxTokens` for each Turn. For Managed Agents, `budget.maxTokens` initializes the Session-wide `max_total_tokens` budget; `timeoutSeconds` is rejected. Managed budgets also have a `/budget` resource; see [Usage, subagents, and budgets](/v2/en/service/session-event-log#budgets) for configuration and usage queries.

<Accordion title="Set a Managed Session budget">



Set the total token budget when creating a Managed Session:

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: limited-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  },
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

For other backends, optional `inputSchema`, `outputSchema` and `resultMapping` settings are frozen when creating the Session. Managed Agents validate `inputSchema` against the message or structured user-message list and reject `outputSchema` and `resultMapping`. Verify real target output before defining mappings or validation. JSON written inside an Agent's text reply is not automatically a structured business result. These rules validate data shape; they do not assess content quality or automatically make the Agent revise its output.

<Accordion title="Validate structured input and results">

This assumes a published Workflow accepts `{topic}` and actually returns `{answer}`. Adapt the schemas and JSON Pointer to real target data; `/answer` selects a structured result field. Grant this Workflow to the application credential first.

```bash
WORKFLOW_ID="YOUR_WORKFLOW_ID"
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
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

Python `ServiceClient` wraps the same API: Credential-free construction requires the SDK from source containing this feature; the published `2.1.0b1` SDK still requires a credential.

```bash
export BASE_URL AGENT_ID
```

```python
import os
from agentscope_service import ServiceClient

api = ServiceClient(os.environ["BASE_URL"])
session = api.create_session({"type": "agent", "id": os.environ["AGENT_ID"]},
                             idempotency_key="sdk-report-session-001")
turn = api.submit(session["id"], message="Prepare a report with sources.",
                  idempotency_key="sdk-report-task-001")
print(api.turn(session["id"], turn["id"]))
```

`agentscope-service/service-controlplane/examples/service-api` contains runnable registration and calling examples for Agents, Teams and Workflows. Use `ManagementClient` for configuration and `ServiceClient` for application execution. See the [API reference](/v2/en/service/api-reference) for interface entry points.

## Managed runtime request routing

The Gateway routes `/v1/sessions` to Session Portal. Control Plane resolves and registers fixed Agent / Environment definitions; Portal persists the Session and accepts Turns without a Brain bootstrap or a ready wait. Brain consumes ordered Turn messages, and Hands commits runtime facts and drives Sandbox tools. These components can be deployed and scaled independently, using shared database, RocketMQ and Redis infrastructure.

The source Managed runtime requires RocketMQ 5.5.1, a separate **Cluster Proxy**, and four LiteTopic parents: Turns, Brain runtime events, Hands tool events, and resumes. Each fully scoped Session has its own LiteTopic. Consumer groups use LitePushConsumer with FIFO and wildcard subscriptions, so new Sessions need no subscription directory. The SDK and Proxy renew deliveries automatically for up to three hours; the application has no per-delivery MQ renewal loop. During a long tool wait, Brain saves its checkpoint and publishes the suspension, then releases Harness compute resources and its Redis execution lease while keeping the original Turn callback open. The original tool result wakes the same logical Turn through the separate resume channel. Hands atomically records a completion notification with the terminal transaction and reliably delivers it through a Redis logical mailbox. The matching callback returns SUCCESS, the SDK acknowledges the delivery, and the next Turn in that Session can proceed. Notification identity contains no SDK receipt, and receiving a notification does not assert that the Broker confirmed an ACK. Runtime and tool events have separate consumers; producer roles and event kinds must match their channels. This source requirement is not a claim that released deployment bundles have been migrated.

Managed file uploads, lists, metadata and downloads use Portal's immutable file store. An upload returns `file_id`; use that ID directly in Turn / Input content blocks and Artifact publication. Files can be uploaded before the first Turn. Control Plane's Managed file entrypoints retain the original Actor and Session scope and do not create a second file copy or rewrite its ID. Other backends keep their existing file protocols.

Once an Agent run has started, an Agent error or explicit failed result ends the Turn after its failure checkpoint, input receipts and known tool outcomes are committed. Recovery of that failure decision does not call the model again. Internal tool waits, log transport errors and lost execution guards retain their recovery paths. Normal queuing is controlled by the original Turn FIFO callback and the SDK ACK; the database retains business outcomes, checkpoints and execution epochs for idempotency and recovery.

`waiting_tool` is a nonterminal Turn status: the Sandbox operation continues while the Brain execution attempt has ended. The Turn keeps its ID, input admission and Session ordering position; a resumed attempt receives a new execution epoch.

REST, snapshots and SSE use committed native public events and the same cursor. Disconnecting SSE stops the subscription; it does not cancel the Turn. Capabilities come from Portal and only advertise implemented operations. Full Checkpoint / fork integration and platform subagent support are still being implemented.

Checkpoint metadata is available from `GET /v1/sessions/{session}/checkpoints` and `/{checkpoint}`. `workspace_available` reports whether a private immutable workspace image has been committed for that checkpoint; it does not certify that the checkpoint has no unresolved tools or interactions. Metadata responses never include the native state or workspace archive.

The native administrative entrypoints accept `POST /v1/sessions/{session}/checkpoints/restore` with `checkpoint_id`, or `POST /v1/sessions/{session}/fork` with `checkpoint_id` and `target_session_id`. Both require an `Idempotency-Key` and return an asynchronous operation. A Fork target must already be registered as an empty Managed root Session with the same Agent definition and resource scope. Read `/checkpoint-operations/{operation_id}` on the source Session to follow validation, workspace import and completion. The Java, TypeScript and Python Managed clients expose matching methods. Console uses these native operation IDs for status and cancellation. Complete multi-replica acceptance remains pending; these writes are not yet advertised as complete capabilities.

The Control Plane's `/api/v1/agent-sessions/{session}/fork` route uses the same checkpoint and operation IDs. It registers both existing Session resources with their frozen definitions before submitting the operation, retaining the original Actor. Registration creates no Turn and requires no Brain bootstrap. Read or cancel `/checkpoint-operations/{operation_id}` on the same Control Plane Session route. Restore uses `/checkpoints/restore`. The request contains only the checkpoint and, for Fork, the target Session ID; it has no recovery reason field.

New Turns, file writes and lifecycle changes wait until the administrative operation has completed or been rejected. If a historical checkpoint has no workspace image and subsequent tools changed the workspace, the operation is rejected with `checkpoint_workspace_unavailable`. `POST /checkpoint-operations/{operation_id}/cancel` can release a validation or snapshot operation. Once import begins, an unconfirmed outcome keeps admission closed and cancellation returns 409; retrying queries the original execution instead of importing again. Definition files are staged once and are not recreated on ordinary provider reconnection.

self_hosted workspace operations use the outbound Environment Worker through the same `/v1/hands/environments/{environment}/tool-tasks` protocol as other Sandbox operations. Configure an existing Sandbox ID for each Session; a Fork requires independent source and target instances. Brain owns checkpoint validation, operation phases and the final baseline commit. It publishes immutable Execute/Control commands; Hands owns the physical binding, Worker lease and original execution result. Worker replacement queries the original execution ID and retransmits its locally retained result until Hands confirms capture. Brain receives the result event before advancing the checkpoint operation. No separate workspace claim or launch-permission endpoint exists. Workers receive no database, MQ or Redis credentials. The compressed archive limit is 32 MiB.