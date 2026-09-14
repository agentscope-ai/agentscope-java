---
title: "Managed configuration and models"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Managed configuration has three layers: the Agent definition, session resources and Dataplane deployment settings. Complete [creation and the first conversation](/v2/en/service/managed-agent) before tuning them.

## Agent definition fields

These are API field names. The console edits them through Behavior, Workspace and Runtime/resource settings. Advanced settings on the creation page selects an environment.

| Field | Purpose | Guidance |
| --- | --- | --- |
| `name` / `description` | Display name and responsibilities | Describe inputs, outputs and suitable work |
| `system` | Instructions added to the Agent | Define boundaries and acceptance criteria; exclude secrets |
| `model` | Registered name or `provider:model` identifier | Leave empty for the Dataplane default |
| `maxIters` | Reasoning/tool iteration limit per Agent invocation | Console accepts 1–64 and displays 12 when unset; this is not a token budget |
| `workspaceId` | Shared capability definition | Select a prepared Workspace |
| `workspaceBinding` | Published revision, overrides and additional instructions | Select the revision and override scope in Workspace settings |
| `defaultEnvironmentId` | Default execution environment for new sessions | Create and verify the Environment first |
| `defaultMemoryStoreIds` | Default shared knowledge stores | Use Store IDs, not display names |
| `defaultVaultIds` | Default credential collections | Bind only the Vaults this Agent needs |
| `tools` / `mcpServers` / `skills` | Tool policy, MCP connections and skill selection | Add and verify one capability at a time |

Preserve version checks when saving. Reload after a configuration conflict. The Agent key is its stable catalog identity; renaming the display name does not migrate that identity.

## Default and explicit models

The standard Dataplane includes the DashScope model extension. The administrator supplies `DASHSCOPE_API_KEY` to the **Dataplane process/container**. `BUILDER_MODEL_NAME` chooses its default model; the deployment default is `qwen-max`. Restart the affected service after changing deployment environment variables.

An empty Agent Model uses this default Model instance. An explicit identifier such as `dashscope:qwen-max` is resolved through the model registry and the DashScope provider's credentials. Hosted provider accounts are separate from Managed model credentials.

```json
{
  "model": "dashscope:qwen-max",
  "system": "Read the supplied sources. Cite evidence and list open questions.",
  "maxIters": 12
}
```

This is a definition fragment, not a complete creation request. See [supported capabilities and integrations](/v2/en/service/managed-agent-capabilities) for extending models. Changing a model name does not install an extension or supply credentials.

## Selecting session resources

Normal Chat and Issue work uses the Agent's resource settings. Managed Session creation APIs can also specify `environmentId`, `memoryStoreIds` and `vaultIds`. Omitting a resource list uses the default binding; an explicit empty list requests no mounts of that default resource type.

```json
{
  "environmentId": "YOUR_ENVIRONMENT_ID",
  "memoryStoreIds": ["YOUR_MEMORY_STORE_ID"],
  "vaultIds": ["YOUR_VAULT_ID"]
}
```

Add this fragment to the relevant Session request along with its required Agent and other fields. Resources must be available to the current identity. Definitions and resource bindings are resolved while establishing the runtime context; verify changes with a new Chat or Issue.

## Change one layer at a time

1. Leave Model empty and verify the deployment default with a conversation.
2. Adjust Instructions and `maxIters` to verify responsibilities and iteration limits.
3. Bind [Workspace](/v2/en/service/workspaces) and [Environment](/v2/en/service/environments), then verify file tools.
4. Add [Memory](/v2/en/service/memory) and [Vault](/v2/en/service/vault), testing knowledge retrieval and tool authentication separately.

For model resolution errors, check identifiers and installed extensions. For 401/403 errors, check provider authentication. A wait for a Worker requires Environment diagnosis; increasing iteration limits does not repair a connection.
