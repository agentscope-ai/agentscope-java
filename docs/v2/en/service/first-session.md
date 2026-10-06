---
title: "API quickstart"
description: Create an Agent, publish a service, submit work, and read results through snapshots and SSE.
zh_link: /v2/zh/service/first-session
---

This tutorial organizes meeting actions through a complete API flow: create an Agent, publish an Endpoint, submit work, and read its results and events. It starts with a Managed Agent running in Service. The Agent API also connects External and Hosted Agents and publishes Agents, Teams, or Workflows as services.

You need a deployed Service, a platform user token with resource creation and usage permissions in the selected namespace, and `curl` and `jq`. An administrator should have configured an available model and Environment. See [local installation](/v2/en/service/quickstart) for deployment and [Console](/v2/en/service/console/index) for graphical operations. Creating and publishing these resources does not invoke a model; submitting the Job starts real inference.

## 1. Prepare identity and execution resources

Set `BASE_URL` to the Gateway's full origin without a trailing slash. See [accounts and permissions](/v2/en/service/access) for platform identity. Run the commands in order in one terminal:

```bash
export BASE_URL='https://YOUR_SERVICE_HOST'
export TOKEN='YOUR_PLATFORM_USER_TOKEN'
export TENANT='YOUR_TENANT'
export NAMESPACE='YOUR_NAMESPACE'

curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" | jq '.[] | {id, name, type}'

export ENVIRONMENT_ID='CHOSEN_ENVIRONMENT_ID'
```

Set the variable to an available Environment's `id`. The Environment determines where tools execute; deployment configuration supplies the model. If none is available, prepare one using the [Environment guide](/v2/en/service/environments).

## 2. Create the notes assistant

This request creates the unified Agent identity, its Managed runtime binding, and its behavior definition:

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"notes-assistant", displayName:"Notes assistant",
      binding:{kind:"managed"},
      definition:{name:"Notes assistant", maxIters:20, defaultEnvironmentId:$env,
        system:"Organize materials into tasks, owners, deadlines, and open questions. Mark missing information as unconfirmed; do not invent facts."}
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '{agent, binding}'
```

Retain `agent.id`. The `agentKey` is a stable business identifier. Omitting the model uses the deployment default. When repeating the tutorial, reuse the existing Agent or choose a new key; creation does not overwrite an existing definition. See [creating a Managed Agent](/v2/en/service/create-managed-agent) for tools, Workspace, and model settings.

## 3. Publish a Job Endpoint

An Endpoint supplies a stable address and invocation contract. This example uses Job mode and platform authentication, so subsequent queries use the same `TOKEN`. It leaves input and output schemas unrestricted for the first request. See [Endpoints](/v2/en/service/endpoints) for production contracts and application credentials.

```bash
export ENDPOINT_SLUG='notes-assistant-job'
ENDPOINT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/endpoints" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg agent "$AGENT_ID" --arg slug "$ENDPOINT_SLUG" '{
      tenant:$tenant, namespace:$namespace,
      name:"Notes assistant API", slug:$slug,
      targetType:"agent", targetRef:$agent, invocationMode:"job",
      authPolicy:{type:"platform"}, timeoutSeconds:300
    }')")
ENDPOINT_ID=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.id')
ENDPOINT_VERSION=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.version')

curl --fail-with-body -sS "$BASE_URL/api/v1/endpoints/$ENDPOINT_ID/readiness" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" | jq
```

Inspect `readiness.state`, `reason`, and `compatible`. Resolve an incompatible target or unavailable runtime before proceeding. Creation only saves the draft. The slug forms part of the public URL and must be unused.

When the target is ready, publish using the version returned on creation:

```bash
curl --fail-with-body -sS "$BASE_URL/api/v1/endpoints/$ENDPOINT_ID/publish" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --argjson version "$ENDPOINT_VERSION" '{version:$version}')" \
  | jq '{endpoint, readiness}'

curl --fail-with-body -sS "$BASE_URL/invoke/v1/endpoints/$ENDPOINT_SLUG/capabilities" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" | jq
```

Check that the Endpoint is `published`. Its release fixes the public contract, and capabilities reports what that release guarantees. On a version conflict, reload the Endpoint, check the configuration, and submit its current version.

## 4. Submit work

```bash
INVOCATION_JSON=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/$ENDPOINT_SLUG/jobs" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H 'Idempotency-Key: notes-job-001' \
  --data '{
    "title":"Organize meeting actions",
    "description":"Alex will finish the installation guide by Friday. Review is on Monday, time unconfirmed. List tasks, owners, deadlines, and open questions.",
    "input":{}
  }')
printf '%s' "$INVOCATION_JSON" | jq
```

The response is `202 Accepted` with `invocationId`, `statusUrl`, `snapshotUrl`, and `eventsUrl`. Work has been accepted but may still be queued or running. Retain these values. Retries of the same business request use the same `Idempotency-Key` and content; use a new key for genuinely new work.

## 5. Load a snapshot, then subscribe to new events

The returned URLs query status, load accumulated content, and subscribe to later changes. This helper accepts relative or absolute URLs without constructing internal execution addresses:

```bash
service_url() {
  case "$1" in
    http://*|https://*) printf '%s' "$1" ;;
    *) printf '%s%s' "$BASE_URL" "$1" ;;
  esac
}
STATUS_URL=$(service_url "$(printf '%s' "$INVOCATION_JSON" | jq -er '.statusUrl')")
SNAPSHOT_URL=$(service_url "$(printf '%s' "$INVOCATION_JSON" | jq -er '.snapshotUrl')")
EVENTS_URL=$(service_url "$(printf '%s' "$INVOCATION_JSON" | jq -er '.eventsUrl')")

SNAPSHOT_JSON=$(curl --fail-with-body -sS "$SNAPSHOT_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
printf '%s' "$SNAPSHOT_JSON" | jq
CURSOR=$(printf '%s' "$SNAPSHOT_JSON" | jq -er '.as_of')

curl -N --fail-with-body "$EVENTS_URL" \
  -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Last-Event-ID: $CURSOR"
```

The snapshot's `items`, `tools`, `steps`, and `artifacts` contain existing messages, tool progress, execution steps, and deliverables. Its `as_of` cursor marks the included position. SSE continues after that position, including events produced between the snapshot read and connection establishment. If the snapshot is already terminal, read the result directly.

In a frontend, render the snapshot first, deduplicate subsequent events by ID, and retain the last successfully applied SSE `id`. Reconnect from that cursor after a connection loss. On a page reload, you can fetch a new complete snapshot and subscribe from it. Restoring a cursor alone without the earlier content leaves an incomplete view. See the [SSE guide](/v2/en/service/sse-events) for events, tool rendering, and expired cursors.

## 6. Verify the final result

After SSE ends or the connection drops, use the status endpoint to verify execution:

```bash
curl --fail-with-body -sS "$STATUS_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  | jq '.invocation | {id, status, result, error}'
```

`completed` means success, with output in `invocation.result`. Check that the review time remains unconfirmed. `partial_succeeded` is a partial outcome requiring business evaluation; `failed`, `cancelled`, and `timed_out` are other terminal states. `accepted`, `dispatching`, `running`, `waiting`, and `cancel_requested` are still active. A completed tool or member task, or a closed SSE connection, does not establish the Invocation's overall outcome.

When human input is required, use the snapshot's `required_actions` and invocation capabilities to present available actions. See the [unified invocation API](/v2/en/service/service-api) for inputs, approvals, cancellation, and resume.

## Continue from here

An available External or Hosted Agent can use the same Job submission, snapshot, SSE, and result flow; event detail depends on the runtime. Teams and Workflows also publish as Job Endpoints, with a Workflow targeting a published revision. Prepare targets using [application registration](/v2/en/service/register-agentscope-agent), [Hosted Agent connection](/v2/en/service/connect-hosted-agent), [Team creation](/v2/en/service/create-team), or [Workflows](/v2/en/service/workflows), then publish using [Endpoints](/v2/en/service/endpoints).

Agents with conversation support can also publish Conversation Endpoints. Use the [native session API](/v2/en/service/session-event-log) for Managed steer, session logs, and checkpoints. For business work with ownership, discussion, and human acceptance, continue with the [Issue API](/v2/en/service/issues).
