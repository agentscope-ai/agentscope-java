---
title: "Configure Agents and models"
description: Create and update Agent definitions, associate tools and MCP connections with an Agent, and select models and Session resources.
zh_link: /v2/zh/service/managed-agent-configuration
---

After verifying [your first Managed Agent](/v2/en/service/create-managed-agent), extend its capabilities as the work requires. Its definition stores the model, stable instructions, and tools. Use [Tools and MCP](/v2/en/service/tools) to declare connections and bind tools for external systems, or [Workspaces and Skills](/v2/en/service/workspaces) to supply reusable instructions and task methods. After saving, create a new Session to verify that execution uses the configuration.

This page introduces “Configure Agents and resources”, documenting definition fields, defaults, and updates and explaining how resources bind to Agents and Sessions. Sessions can inherit default resources or select them explicitly at creation, while service administrators maintain Dataplane deployment settings. The following sections begin with reading and updating the definition, then cover models and resource configuration.

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
| `tools` | Toolset array | `agent_toolset` selects built-in tools; `mcp_toolset` selects MCP tools; both configure call permissions |
| `mcpServers` | Connection array | MCP addresses and transports; a toolset's `mcpServerName` references the connection's `name` |
| `skills` | Skill configuration array | Available skills; see [Workspace and Skills](/v2/en/service/workspaces) |
| `multiagent` | Structured configuration | Internal delegation, distinct from platform Teams |
| `version` | Positive integer for updates | Latest definition version; reread and review conflicts |

Saving `tools` and `mcpServers` in this definition associates the configuration with the Agent. An application then creates a Session using the Agent's ID, and Service assembles tools from the selected definition version. Task requests do not need to repeat the configuration. See [saving tools to an Agent](/v2/en/service/tools#save-the-configuration-to-an-agent) for complete declaration, creation, and update examples. MCP authentication comes from the Agent's default Vaults or the Session's `vaultIds`.

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

<span id="managed-agent-capabilities"></span>
<span id="discover-and-configure-capabilities-through-apis"></span>
<span id="tools-and-mcp"></span>
<span id="skills-and-internal-subagents"></span>
<span id="knowledge-credentials-and-entry-points"></span>

### Model integrations

| Integration | Requirements |
| --- | --- |
| Standard DashScope default | Configure `DASHSCOPE_API_KEY` and the default model on Dataplane |
| Explicit DashScope model | Use `dashscope:model-name` with the extension and credentials present |
| Another ModelProvider | Build a custom Dataplane distribution containing its extension and connection/authentication settings |
| Custom model object | Supply a default `Model` bean or register named models/factories with `ModelRegistry` in a custom Dataplane |

The Java SDK provides [model extensions](/v2/en/integration/model/index) for OpenAI and compatible APIs, Anthropic, Gemini and Ollama. These are integration options; their existence in the SDK does not make every provider part of the standard Service image. The selected model must support the tool calls and input types your task uses.

## How resources relate to Agents and Sessions

Workspaces, Environments, Memory Stores, and Vaults are reusable resources, but they enter execution differently. A published Workspace is resolved into an Agent definition, supplying instructions, skills, and tool configuration. Environments, Memory Stores, and Vaults are runtime resources selected by each Session. Creating a resource makes it manageable on the platform; the associations below make it available to a task.

| Resource | Agent definition association | Session selection |
| --- | --- | --- |
| Workspace | `workspaceId` and `workspaceBinding.version` select a published revision | `target` selects an Agent definition version containing the resolved Workspace content |
| Environment | `defaultEnvironmentId` selects the default execution environment | `environmentId` can select another environment for this Session |
| Memory Store | `defaultMemoryStoreIds` selects default knowledge sources | `memoryStoreIds` can replace the Store list for this Session |
| Vault | `defaultVaultIds` selects default tool credential collections | `vaultIds` can replace the Vault list for this Session |

Agent defaults apply when creating new Sessions. Updating the definition does not replace the resources selected by existing Sessions. A Session saves resource IDs, which do not make the referenced resources immutable: Memory documents can change, Vault credentials can rotate, and Environment configuration can be maintained. Workspace publication content is pinned with the Agent definition version. Consider other consumers when modifying a shared resource itself.

### Set default resources on an Agent

First create the required Environment, Memory Store, or Vault and retain the returned IDs. The following example sets all three defaults for an existing Managed Agent. Replace the placeholders and save the JSON as `resource-defaults.json`. To change only one resource category, include only that field. The command below preserves omitted fields; each supplied ID array is the complete default list, and an empty array clears that default binding.

```json
{
  "defaultEnvironmentId": "YOUR_ENVIRONMENT_ID",
  "defaultMemoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "defaultVaultIds": ["YOUR_VAULT_ID"]
}
```

Use `AGENT_ID` and the platform identity variables from the [creation guide](/v2/en/service/create-managed-agent). The command reads the complete definition, retains its other writable fields, and merges the resource defaults before submitting its version. Changing resources therefore does not clear tools or instructions.

```bash
CURRENT=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$CURRENT" | jq --slurpfile resources resource-defaults.json '
  .definition | {
    name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
    workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
    defaultVaultIds, defaultMemoryStoreIds, version
  } | . + ($resources[0] | with_entries(select(
    .key == "defaultEnvironmentId" or .key == "defaultMemoryStoreIds" or .key == "defaultVaultIds"
  )))')
curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED" \
  | jq '.definition | {version, defaultEnvironmentId, defaultMemoryStoreIds, defaultVaultIds}'
```

Inspect the returned resource IDs and new definition version before creating a Session. A `409 Conflict` means the definition changed after you read it; reread and review before submitting again. Default bindings select available resources, while tool availability, confirmation policies, and external access permissions still apply separately.

In the console, select these defaults under the Managed Agent's **Runtime configuration → Session defaults**. Bind a published Workspace under **Definition → Workspace**. Check the resource selections when creating a new Session to see whether it uses the defaults or explicit choices.

## Session resource selection

When creating a Session through `POST /api/v1/agent-sessions`, use `target` to select the Agent and optionally provide resources for this Session:

| Field | Purpose |
| --- | --- |
| `target` | Select an Agent with `{type:"agent", id:"AGENT_ID"}`; optionally specify its definition `version` |
| `environmentId` | Tool environment; omission inherits the Agent default |
| `memoryStoreIds` / `vaultIds` | Knowledge/credential resources; omission inherits defaults, empty arrays disable those default mounts |

```json
{
  "target": {"type": "agent", "id": "YOUR_AGENT_ID"},
  "environmentId": "YOUR_ENVIRONMENT_ID",
  "memoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "vaultIds": []
}
```

Save the returned Session ID, then submit a Turn to that Session. Resources must be accessible to the identity; an Environment key is not a user Bearer token. Input, file, action, budget, and recovery bodies are in the [Session guide](/v2/en/service/session-event-log), with routes in [API reference](/v2/en/service/api-reference).

Session creation freezes the Agent definition and runtime configuration. After a definition update, create a new Session; existing Sessions retain their selected version. Use the Session PATCH resource to adjust its Environment, Memory, or Vault settings, subject to resource permissions. See the [API guide](/v2/en/service/service-api).

## Change one layer at a time

Verify text with the default model, then adjust responsibilities and iteration limits. Add Workspace, Environment, Memory, and Vault incrementally. Check provider/deployment for model resolution failures and Environment or pending actions for tool waits; raising maxIters does not repair a connection failure.
