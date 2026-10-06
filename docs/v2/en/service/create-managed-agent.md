---
title: "Managed Agent: create and test through APIs"
description: Create a hosted Agent, select execution resources, and verify inference with the native session API.
zh_link: /v2/zh/service/create-managed-agent
---

Service runs Managed Agents. Supply responsibilities and resource configuration, then use the session API or publish the Agent as a business service without starting your own Agent process. This page covers creation and a text request; see the [API quickstart](/v2/en/service/first-session) for publishing and [Console](/v2/en/service/console/agents) for visual operation.

## Prepare execution resources

An administrator must have deployed Service and configured an available model. Set `BASE_URL`, `TOKEN`, `TENANT`, and `NAMESPACE` as described in [Agent management](/v2/en/service/agents). Examples use `curl` and `jq`.

List Environments available to the current identity:

```bash
curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  | jq '.[] | {id, name, type}'

export ENVIRONMENT_ID='CHOSEN_ENVIRONMENT_ID'
```

An Environment determines where tools execute; see [Environments](/v2/en/service/environments). Without an explicit default, the platform attempts to select or provision one under deployment policy. Creation fails if local execution is forbidden and no runnable environment exists. Prefer an explicit binding in production.

## Create the identity and Managed definition

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
        system:"Extract tasks, owners, deadlines and open questions from supplied notes. Mark missing information as unconfirmed; do not invent facts."}
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '{agent, binding}'
```

The response contains `agent`, `binding`, `policy`, and `definition`. Save `agent.id`. `agentKey` is a stable business identifier; recreating an existing identity is not a definition update. Omitting `definition.model` selects the deployment default; override it with a supported model identifier when needed.

A name without an execution binding is not a runnable Agent. This request establishes both the Managed binding and behavior definition. Verify actual execution after creation.

## Create a session and submit work

Create a Managed native session with the same user identity. Session creation does not send a model request:

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" '{agent:$agent}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')

curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H 'Idempotency-Key: notes-check-001' \
  --data '{"message":"Lee will finish installation notes by Friday. Review is Monday, time unconfirmed. Extract the action items."}'
```

`202` and the turn ID mean the work was accepted for background execution. Read `GET /api/v1/agent-sessions/{sessionId}/snapshot` for content and progress, and use `events/stream` for updates. Check that the reply retains the unconfirmed review time, and use the target turn's terminal state to determine completion. See [Managed session API](/v2/en/service/session-event-log) for snapshots, SSE, and error handling.

Reuse the key and input after network failure; use a new key for new work. Refreshing a page reads the existing session without resubmitting the test message.

## Add capabilities and publish

After verifying text requests, add Workspace, Memory, Vault, and tools incrementally. Read `GET /api/v1/agents/{id}/definition`, then update through `PATCH /api/v1/agents/{id}/definition` with the current `definition.version`. Preserve unchanged fields to avoid clearing other configuration. See [Configuration reference](/v2/en/service/managed-agent-configuration).

[Publish an Endpoint](/v2/en/service/endpoints) for business applications, assign accountable work through [Issues](/v2/en/service/issues), or add the Agent to a [Team](/v2/en/service/create-team). Native Managed APIs extend this runtime; the unified invocation API also serves ready External and Hosted Agents.
