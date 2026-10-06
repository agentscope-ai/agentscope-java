---
title: "Managed definition and session API parameters"
description: Create and update Agent definitions, select models and resources, and configure native session requests.
zh_link: /v2/zh/service/managed-agent-configuration
---

Managed configuration has three layers: Agent definition, session resources, and Dataplane deployment settings. Follow [Create and test](/v2/en/service/create-managed-agent) for the workflow; this page documents fields, defaults, and updates.

## Definition APIs

| Operation | API | Request and response |
| --- | --- | --- |
| Create identity, definition, and binding | `POST /api/v1/agents` | Scope, `agentKey`, `binding:{kind:"managed"}`, and `definition`; returns agent/binding/policy/definition |
| Read definition | `GET /api/v1/agents/{id}/definition` | Returns `agentId`, `definition` |
| Update definition | `PATCH /api/v1/agents/{id}/definition` | Top-level behavior fields and current definition `version`; name required; returns agent/definition |
| Version list / detail | `GET /api/v1/agents/{id}/versions`, `GET /api/v1/agents/{id}/versions/{version}` | Returns versions or version, with agentId |

Use platform Bearer identity and an authorized Namespace. Creation nests behavior under `definition`; updates put the fields at the body root. Catalog `agent.version` and `definition.version` govern different resources.

## Definition fields

| Field | Type and default | Purpose |
| --- | --- | --- |
| `name` / `description` | string; creation may fill name from displayName, update requires name | Display and responsibility |
| `system` | string | Stable behavior; no secrets |
| `model` | string, empty selects deployment default | Registry name or provider:model |
| `maxIters` | int, omitted/nonpositive values store as 20 | Reasoning/tool iteration cap, not a token budget; Console bounds are not API validation bounds |
| `workspaceId` | string | Shared capability resource ID |
| `workspaceBinding` | object | Published version, explicit overrides, and added instructions; [Workspace](/v2/en/service/workspaces) |
| `workspacePath` | string | Explicit workspace path, subject to deployment and runtime |
| `defaultEnvironmentId` | string | Default tool environment; Managed creation validates or provisions under deployment policy |
| `defaultMemoryStoreIds` | string[] | Default knowledge Store IDs |
| `defaultVaultIds` | string[] | Default tool credential collection IDs |
| `tools` / `mcpServers` / `skills` | Structured configuration | Policies, connections, and skills; [Capabilities](/v2/en/service/managed-agent-capabilities) |
| `multiagent` | Structured configuration | Internal delegation, distinct from platform Teams |
| `version` | Positive integer for updates | Latest definition version; reread and review conflicts |

Definition updates are not arbitrary partial merges. Read the current definition and retain unchanged writable fields before PATCH so other settings are not cleared. This example uses variables from the [Creation guide](/v2/en/service/create-managed-agent) and changes only system:

```bash
DEFINITION=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$DEFINITION" | jq '.definition | {
  name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
  workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
  defaultVaultIds, defaultMemoryStoreIds, version
} | .system = "Read supplied sources. Cite evidence and list open questions."')
curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED"
```

When a Workspace is bound, instructions and tools must also follow workspaceBinding overrides/instructions rules. Changing a display name does not migrate the stable Agent key.

## Default and explicit models

The standard Dataplane includes DashScope support. Configure `DASHSCOPE_API_KEY` in the Dataplane process/container and use `BUILDER_MODEL_NAME` for the default model; the standard default is qwen-max. Restart the component after deployment variable changes.

An empty model uses the default Model. Explicit `dashscope:qwen-max` resolves through the registry. Other providers require their extension and credentials in the distribution; changing the name does not install support. Model, platform-login, and Vault tool credentials serve separate purposes.

## Session resource selection

`POST /api/v1/agent-sessions` creates a native Managed session with these common fields:

| Field | Purpose |
| --- | --- |
| `agent` | Agent ID |
| `environmentId` | Tool environment; omission inherits the Agent default |
| `memoryStoreIds` / `vaultIds` | Knowledge/credential resources; omission inherits defaults, empty arrays disable those default mounts |

```json
{
  "agent": "YOUR_AGENT_ID",
  "environmentId": "YOUR_ENVIRONMENT_ID",
  "memoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "vaultIds": []
}
```

Save the response session id, then submit a turn. Resources must be accessible to the identity; an Environment key is not a user Bearer token. Input, file, action, budget, and recovery bodies are in the [Native session guide](/v2/en/service/session-event-log), with routes in [API reference](/v2/en/service/api-reference).

Chat, Issue, and Endpoint entry points resolve resources through their own contracts; do not send the native session body to an Invocation endpoint. Publish a new release when changing a published service, then verify with new work.

## Change one layer at a time

Verify text with the default model, then adjust responsibilities and iteration limits. Add Workspace, Environment, Memory, and Vault incrementally. Check provider/deployment for model resolution failures and Environment or pending actions for tool waits; raising maxIters does not repair a connection failure.
