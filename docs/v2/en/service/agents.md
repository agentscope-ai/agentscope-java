---
title: "Create, register, and manage Agents"
description: Establish Agent identities through APIs, bind execution, and verify task and service capabilities.
zh_link: /v2/zh/service/agents
---

An Agent is a reusable capability for service calls, assigned work, and orchestration. Connect it to the shared catalog, verify execution readiness, and then submit work. Creating a definition does not itself run model inference.

This group uses APIs throughout. For visual operation, see [Agents in Console](/v2/en/service/console/agents).

## Choose an execution model

| Model | Who runs it | API operation |
| --- | --- | --- |
| Managed | Service Harness and Dataplane | `POST /api/v1/agents` with `binding.kind=managed` and `definition` |
| External | Your application | SDK or `POST /api/v1/agent-registrations`; application supplies execution adapters |
| Hosted | A Coding Agent on Runtime Host | `GET /api/v1/agents/runtime-options`, then `POST /api/v1/agents` with a `hosted-runtime` binding |

All three share Agent identity and catalog management. Use Managed for platform-run inference and tools, External for your own code application, or Hosted for an installed Coding Agent. Continue with [Managed creation](/v2/en/service/create-managed-agent), [External registration](/v2/en/service/register-agentscope-agent), or [Hosted connection](/v2/en/service/connect-hosted-agent).

## Prepare identity and scope

Management APIs use a platform user Bearer token. Examples use `curl` and `jq`; `BASE_URL` is the Gateway origin. Choose a tenant and namespace authorized for your account rather than assuming the placeholders identify existing resources. See [API authentication](/v2/en/service/api-reference).

```bash
export BASE_URL='https://YOUR_SERVICE_HOST'
export TOKEN='YOUR_PLATFORM_USER_TOKEN'
export TENANT='YOUR_TENANT'
export NAMESPACE='YOUR_NAMESPACE'
```

Keep scope fields, query parameters, and the `X-AgentScope-Tenant` / `X-AgentScope-Namespace` headers consistent. Endpoint API keys, host credentials, and registration information are separate from platform management identity.

## Find Agents and retain their IDs

```bash
curl --fail-with-body -sS -G "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE"
```

The response contains `items`. `agentKey` is a stable business identifier within the scope, `displayName` is for presentation, and `id` is the Agent ID used by other APIs. Save `agent.id` from creation or registration instead of using the display name as an ID.

The catalog describes identity and lifecycle; bindings describe execution locations. An identity can have multiple bindings and instances. After creation, inspect the bindings:

```bash
export AGENT_ID='RETURNED_AGENT_ID'
curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/bindings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE"
```

An `active` catalog entry does not guarantee that its model, credentials, tools, and execution resources are currently usable. Check Endpoint readiness before publishing, then verify the actual route with a small read-only task.

## Update configuration and lifecycle

| Change | Operation |
| --- | --- |
| Name, description, catalog status | `GET /api/v1/agents/{id}` → `PATCH /api/v1/agents/{id}` |
| Managed / Hosted behavior | `GET /api/v1/agents/{id}/definition` → `PATCH /api/v1/agents/{id}/definition` |
| Execution bindings | `GET/POST /api/v1/agents/{id}/bindings`; read the target binding from the list, then update with `PATCH /api/v1/agents/{id}/bindings/{bindingId}` |
| Runtime selection | `GET/PUT /api/v1/agent-runtime-policies/{id}` |
| Archive an Agent | `PATCH /api/v1/agents/{id}` with `{"version": CURRENT_VERSION, "status":"archived"}` |

Read the current version before updating and supply the concurrency condition required by the endpoint. Preserve unchanged definition fields: omitted values may replace existing tools, skills, or resource bindings. Refer to each runtime's configuration guide; simple registration does not require advanced routing policies.

Binding updates require the binding's current `version` and complete `configuration`, `priority`, and `enabled` values. These fields are replaced together; omitting `enabled` disables the binding. Preserve the other values when changing only one field.

External application behavior is primarily maintained in its code. Editing a platform definition does not hot-update that process. Update the Endpoint release when changing a published capability; existing calls retain their release contract.

## Use the connected capability

[Publish an Endpoint](/v2/en/service/endpoints) for application calls, then use the [Unified service API](/v2/en/service/service-api) for submissions, snapshots, events, and interactions. Use [Issues](/v2/en/service/issues) for assigned work with discussion and acceptance.

Create a [Team](/v2/en/service/create-team) for collaboration or a [Workflow](/v2/en/service/workflows) for an explicit process. Both reference existing Agent IDs. Managed-specific session, file, and checkpoint features use the [Native session API](/v2/en/service/session-event-log).
