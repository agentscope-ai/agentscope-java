---
title: "Tools, MCP, and permissions"
description: "Declare built-in tools and MCP connections in an Agent definition, save the configuration, and use it through a Session with authentication and permissions."
zh_link: /v2/zh/service/tools
---

Tools let a Managed Agent read files, execute commands, and access business systems. To give an Agent these capabilities, save the tool configuration in its **Agent definition**. When an application creates a Session with that Agent's ID, Service configures the running HarnessAgent from the definition version selected for the Session. The application does not need to declare tools again when submitting a task.

This page walks through that configuration path. First, follow the [deployment guide](/v2/en/service/quickstart) to prepare `BASE_URL`, `TOKEN`, `TENANT`, `NAMESPACE`, and `ENVIRONMENT_ID`, and check that the selected environment is available. For an initial file exercise, start with [your first Managed Agent](/v2/en/service/create-managed-agent). Continue here when you are ready to connect a business system through MCP.

## How tools belong to an Agent

Within a Managed Agent's `definition`, `tools` controls which tools the Agent can use and whether calls require confirmation. An `agent_toolset` configures HarnessAgent's built-in tools, while an `mcp_toolset` configures tools exposed by one MCP server. Both can appear in the same `tools` array, allowing an Agent to query a business system and then organize the result with file tools.

MCP also needs `definition.mcpServers` to describe the connections. Each connection's `name` identifies it within this Agent. The corresponding `mcp_toolset.mcpServerName` references that name, associating tool selection and permission policies with the connection. For example, a connection named `catalog` uses a toolset with `mcpServerName: "catalog"`. Both fields belong to the same Agent definition; this path does not require creating a separate Tool resource and binding its ID.

When creating an Agent through `POST /api/v1/agents`, place these fields inside `definition`. To change an existing Agent, save them through `PATCH /api/v1/agents/{agentId}/definition`. Tool and MCP catalogs help you choose configurations, but browsing a catalog or deploying an MCP server does not itself change an Agent's definition.

The configuration below applies to Managed Agents hosted by Service. External Agents manage their tools in the external application; Hosted Agents depend on the tool mappings supported by their runtime provider. The Managed definition API currently accepts `agent_toolset` and `mcp_toolset`. To call your own business functions, expose them as MCP tools and connect them through `mcpServers`.

## Prepare built-in tools and an MCP connection

The following document assistant can query a product catalog through MCP and read or write files in its execution environment. Prepare a real MCP server exposing `lookup_product`, replace the URL with its address, and save the JSON below as `agent-definition.json` in your terminal's current directory. If your server uses another tool name, update both `configs` and the verification task later in this guide.

This file contains the Agent's behavior definition. The next section submits it as the creation request's `definition`. Each toolset starts with `defaultConfig.enabled: false` and explicitly enables the tools it needs. File reads and product lookups can execute directly; file writes require user confirmation.

```json
{
  "name": "Product assistant",
  "system": "Answer using actual product catalog data. When a file is requested, organize the result and read it back after writing. Report failed lookups and missing information honestly.",
  "maxIters": 20,
  "mcpServers": [
    {
      "name": "catalog",
      "transport": "http",
      "url": "https://YOUR_MCP_HOST/mcp",
      "required": true,
      "initializationTimeout": "PT30S",
      "timeout": "PT30S"
    }
  ],
  "tools": [
    {
      "type": "agent_toolset",
      "defaultConfig": {"enabled": false},
      "configs": [
        {"name": "read", "enabled": true, "permissionPolicy": {"type": "always_allow"}},
        {"name": "write", "enabled": true, "permissionPolicy": {"type": "always_ask"}}
      ]
    },
    {
      "type": "mcp_toolset",
      "mcpServerName": "catalog",
      "defaultConfig": {"enabled": false},
      "configs": [
        {"name": "lookup_product", "enabled": true, "permissionPolicy": {"type": "always_allow"}}
      ]
    }
  ]
}
```

<span id="configure-built-in-tools"></span>

### Choose built-in tools

The `agent_toolset.defaultConfig` sets the default availability of built-in tools, and `configs` overrides individual tools. With the default set to `false`, this example enables only `read` and `write` in that built-in toolset. This setting does not disable the product lookup in the separate `mcp_toolset`. Add entries to the same `configs` array when you need Shell access or file search.

| Configuration name | Name in execution records | Purpose |
| --- | --- | --- |
| `read` / `write` / `edit` | `read_file` / `write_file` / `edit_file` | Read, create, and modify files |
| `glob` / `grep` / `list_dir` | `glob_files` / `grep_files` / `list_files` | Find files and content |
| `bash` | `execute` | Run commands in a Shell-capable Environment |

An enabled tool also needs the appropriate execution resources. File tools operate in the selected [Environment](/v2/en/service/environments): Local runs inside Dataplane, while Sandbox and self_hosted Workers use their own files and dependencies. The remote file environment does not provide Shell access. A path or command in the Agent's instructions does not mount files, install programs, or grant operating-system permissions.

<span id="connect-a-business-mcp-server"></span>

### Match MCP connections and tool names

In the example, `catalog` associates the connection with its toolset, while `lookup_product` must be a tool actually exposed by the server. Use the server's original tool name in `configs`. At runtime, MCP tools receive a connection-name prefix, so execution records show `catalog__lookup_product`. Do not put that prefixed runtime name back into `configs`.

Connection names must be unique within the Agent, contain 1–64 letters, numbers, hyphens, or underscores, and must not contain a double underscore. When adding a connection, configure its matching `mcp_toolset` as well. When renaming or removing one, update the referencing toolset at the same time. A toolset that references an undeclared connection is rejected.

With `mcp_toolset.defaultConfig.enabled` set to `false`, only tools explicitly enabled in `configs` are available. Setting it to `true` enables the connection's tools by default, including tools the server adds later; individual entries can disable exceptions. Usually, maintaining this selection in the toolset is sufficient. If the connection also specifies `enableTools` or `disableTools`, those filters further restrict availability.

| Transport | Configuration and execution requirements |
| --- | --- |
| `http` | Streamable HTTP with a `url` reachable from Dataplane |
| `sse` | A `url` matching the server's SSE MCP protocol |
| `stdio` | An explicit Local Environment, with `command`, `args`, and program dependencies available inside Dataplane |

`initializationTimeout` controls initialization waits, while `timeout` controls call waits; both use duration strings such as `PT30S`. `required` defaults to `true`, so a connection that cannot load prevents the Turn from proceeding. With `false`, the Agent can continue without that connection's tools, and connection errors remain visible in Session events. Saving a definition does not execute a real business call, so use a Session to verify connectivity, authentication, and results.

## Save the configuration to an Agent

With the definition file ready, either create a new Agent or apply the tool configuration to an existing one. Both routes save a definition version for the chosen Agent. Use the route that matches your current task.

### Create a new Agent

The following command places `agent-definition.json` inside the request's `definition` and binds the prepared execution environment through `defaultEnvironmentId`. The `binding.kind: "managed"` setting tells Service to run the definition with HarnessAgent. The command saves the returned Agent ID and definition version for Session creation later.

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --slurpfile definition agent-definition.json \
    --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"product-assistant", displayName:$definition[0].name,
      binding:{kind:"managed"},
      definition:($definition[0] + {defaultEnvironmentId:$env})
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
AGENT_VERSION=$(printf '%s' "$AGENT_JSON" | jq -er '.definition.version')
printf '%s' "$AGENT_JSON" | jq '{agentId:.agent.id, definition:.definition}'
```

The response's `definition.tools` and `definition.mcpServers` should contain the saved configuration. The tools now belong to the Agent identified by `AGENT_ID`, but execution has not started. Because `agentKey` is a stable business identifier, update the existing Agent if that key already exists; repeating the creation request does not overwrite its definition.

### Update an existing Agent

To configure an existing Agent, first set `AGENT_ID` to its platform ID. The commands below read its current definition, preserve writable fields such as instructions, model, and resource bindings, and replace `tools` and `mcpServers` with the lists from the file. The file's name and instructions do not overwrite those of the existing Agent.

```bash
CURRENT=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$CURRENT" | jq --slurpfile config agent-definition.json '
  .definition | {
    name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
    workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
    defaultVaultIds, defaultMemoryStoreIds, version
  }
  | .tools = $config[0].tools
  | .mcpServers = $config[0].mcpServers
  | if (.workspaceId // "") != "" then
      .workspaceBinding.overrides =
        (((.workspaceBinding.overrides // []) + ["tools", "mcpServers"]) | unique)
    else . end')
SAVED=$(curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED")
AGENT_VERSION=$(printf '%s' "$SAVED" | jq -er '.definition.version')
printf '%s' "$SAVED" | jq '.definition | {version, tools, mcpServers, workspaceBinding}'
```

For this PATCH request, definition fields appear at the top level without another `definition` wrapper. The endpoint does not merge arbitrary partial fields, so sending only `tools` would omit other settings you need to preserve. Both `tools` and `mcpServers` are saved as complete lists. Merge any existing tools or connections you want to keep into the file's arrays before applying it.

The request uses the current `definition.version` to detect concurrent changes. A successful update returns a new version. If it returns `409 Conflict`, read the definition again, review the differences, and then resubmit. This version belongs to the behavior definition and is distinct from the catalog identity's `agent.version`.

### When the Agent is bound to a Workspace

An Agent can inherit tools and MCP connections from a published [Workspace](/v2/en/service/workspaces#publish-and-bind-an-agent) revision. The `workspaceBinding.overrides` array determines which fields use the Agent's own values. When `tools` or `mcpServers` is absent from that array, the corresponding field comes from the Workspace, even if the request supplies an Agent-specific value.

For this reason, the update example adds explicit overrides for both `tools` and `mcpServers` when it detects a Workspace binding, while preserving other overrides. These two fields then belong to this Agent and will no longer inherit changes from subsequently selected Workspace versions. To keep a shared tool configuration instead, update and publish the Workspace, then select the new revision for the Agent. In either case, inspect the returned definition to confirm which configuration is in effect.

## Supply execution resources and authentication for a Session

After saving the Agent definition, an application references the Agent through `target` when creating a Session. Service pins the selected definition version, including its tools and MCP connections. The command below explicitly uses the returned `AGENT_VERSION`; omitting `target.version` selects the current definition when the Session is created. Existing Sessions retain their saved versions, so create a new Session to test a tool configuration change.

MCP authentication comes from a [Vault](/v2/en/service/vault). For the `catalog` connection, add a `static_bearer` credential whose `target` is `catalog` or the connection's full URL, and store the token issued by the external service as its `secret`. Attach that Vault through `vaultIds` when creating the Session. At runtime, the matching connection receives a Bearer authorization header. Creating a Vault or saving a credential does not automatically attach it to every Session using this Agent.

The command assumes you have obtained `VAULT_ID` through the Vault guide. If the MCP server needs no authentication, set `VAULT_IDS_JSON` to `[]`. To give future Sessions a default Vault list, save the IDs as `defaultVaultIds` in the Agent definition and omit `vaultIds` from the Session request. An explicit `[]` means that this Session does not attach the default Vaults.

```bash
VAULT_IDS_JSON=$(jq -n --arg id "$VAULT_ID" '[$id]')
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" --argjson version "$AGENT_VERSION" \
    --argjson vaults "$VAULT_IDS_JSON" '{
      target:{type:"agent", id:$agent, version:$version}, vaultIds:$vaults
    }')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

For custom headers, use `${VARIABLE}` in `mcpServers[].headers` to reference an `environment_variable` credential in an attached Vault. The Vault guide also covers OAuth authorization and saving credentials. A connection's `env` is passed only to local stdio processes; it does not authenticate remote HTTP requests and is not the Session's Environment resource. Tool credentials serve a different purpose from the platform token or Application key used to call the Session API.

## Configure the same Agent in the console

Open the target Agent and navigate to **Definition → Tools & MCP**. The built-in tool area selects tools and their call permissions. Under **MCP connections → Add connection**, enter the name, Transport, and URL, then configure default availability and individual tool rules. **Save connection** saves both the MCP connection and its matching toolset to this Agent, so there is no additional Agent-binding step.

If the page shows that tools come from a Workspace, decide whether to edit the shared Workspace or configure overrides for this Agent. Both edit permissions and inheritance affect whether the fields are editable. Test saved changes in a new Session. For OAuth, complete **Connect account** or **Connect GitHub** and check that the Session has the corresponding Vault attached. Catalog entries provide configuration starting points; they still need a working MCP service and its authentication.

<span id="tool-permissions"></span>
<span id="three-distinct-controls"></span>
<span id="handle-confirmation-in-native-sessions"></span>
<span id="in-published-applications"></span>
<span id="verify-a-confirmation-flow"></span>

## Permissions and confirmation

Platform access determines who may edit an Agent, use an Environment, or attach a Vault. A toolset's `enabled` setting determines which tools the Agent may select. The `permissionPolicy` then controls whether a selected tool can execute. These controls apply separately: an enabled file-writing tool with `always_ask` still needs confirmation before it runs.

`always_allow` permits direct execution, `always_ask` requests confirmation, and `deny` rejects execution. Set a toolset-wide default through `defaultConfig.permissionPolicy`, then override it in individual `configs` entries. Without explicit policies, built-in tools default to allow and MCP toolsets default to ask. Explicit policies, as in this example, help maintainers understand the behavior of business operations.

When the example Agent attempts to write a file, the Session snapshot's `required_actions` contains a confirmation request. Show the actual tool and arguments to the user, then submit their decision with the returned `request_id` to `POST /api/v1/agent-sessions/{sessionId}/turns/{turnId}/actions`. Follow both the command receipt and task state. See [answering a required action](/v2/en/service/session-event-log#answer-a-required-action) for the full request. An application key with an interaction scope cannot impersonate a designated human approver.

Tool confirmation authorizes that operation; it does not expand operating-system or business-system permissions. It also serves a different purpose from Workflow step approvals and Issue result acceptance. After rejection or cancellation, inspect any external operations that have already happened, since task cancellation cannot guarantee their reversal.

<span id="verify-execution"></span>

## Verify that the binding takes effect

Submit a bounded task to the new Session, such as looking up a known product and writing the result to `product-summary.md` before reading it back. Replace `KNOWN_PRODUCT_ID` below with a product whose data you can verify. This is a verification input, not a preinstalled demonstration record.

```bash
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: catalog-check-001' \
  --data '{"message":"Use the product catalog to look up KNOWN_PRODUCT_ID. Write the actual result to product-summary.md and read it back to verify. If the lookup fails, explain why without inventing product data."}')
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN" | jq '{tools, required_actions, turns}'
```

The task runs in the background, so the first snapshot may not contain tool records yet. Follow the [Session guide](/v2/en/service/session-event-log) to read subsequent snapshots or subscribe to events. Verify an actual `catalog__lookup_product` call, its arguments, and its returned data. A confirmation should appear before the file write; after approval, the Agent can write and read back the file. Check the product data against its source rather than relying on the model's statement that it performed a lookup. Network retries reuse the same idempotency key and message; new tasks use new keys.

If the expected tool does not appear, first inspect the saved toolsets, connection references, and the definition version selected by the Session. If a connection was attempted but failed, check Dataplane networking, attached Vaults, and external permissions. With `required: false`, an Agent may finish other work despite a connection failure, so inspect tool results and Session error events together. To provide a downloadable deliverable after writing a file, follow [files and artifacts](/v2/en/service/files) to publish its reference.
