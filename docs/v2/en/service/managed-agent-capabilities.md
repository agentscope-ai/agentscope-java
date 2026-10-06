---
title: "Managed capabilities and integration types"
zh_link: /v2/zh/service/managed-agent-capabilities
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Managed Agents execute the platform Harness. Distinguish model connections, execution environments, tools and work entry points when extending an Agent.

## Discover and configure capabilities through APIs

Manage behavior through `GET/PATCH /api/v1/agents/{id}/definition`; see [Definition parameters](/v2/en/service/managed-agent-configuration). `GET /api/v1/agents/{id}/workspace-capabilities` checks supported Workspace projection. File, tool, skill, and subagent resource APIs are documented under [Workspace](/v2/en/service/workspaces).

After publishing, read `GET /invoke/v1/endpoints/{slug}/capabilities` for capabilities guaranteed across release candidates. After submission, read `GET /invoke/v1/invocations/{id}/capabilities`; available_commands depends on the selected binding and current state. An Agent being Managed alone does not authorize every command at every moment.

Invocation events cover messages, tools, steps, and artifacts. Native Managed sessions add file, subagent, and checkpoint restore/fork operations. IDs, snapshot shapes, and cursors are not interchangeable; see [SSE](/v2/en/service/sse-events).

## Model integrations

| Integration | Requirements |
| --- | --- |
| Standard DashScope default | Configure `DASHSCOPE_API_KEY` and the default model on Dataplane |
| Explicit DashScope model | Use `dashscope:model-name` with the extension and credentials present |
| Another ModelProvider | Build a custom Dataplane distribution containing its extension and connection/authentication settings |
| Custom model object | Supply a default `Model` bean or register named models/factories with `ModelRegistry` in a custom Dataplane |

The Java SDK provides [model extensions](/v2/en/integration/model/index) for OpenAI and compatible APIs, Anthropic, Gemini and Ollama. These are integration options; their existence in the SDK does not make every provider part of the standard Service image. The selected model must support the tool calls and input types your task uses.

## Tools and MCP

Harness and the chosen Environment provide built-in tools. `agent_toolset` controls built-in tools, while `mcp_toolset` references an `mcpServerName`. Define the connection in `mcpServers`.

| Connection | Managed requirements |
| --- | --- |
| HTTP MCP | Endpoint reachable from Dataplane, matching transport and credentials supplied through Vault |
| stdio MCP | Explicit `local` Environment; command and dependencies installed in Dataplane |
| Shell / file tools | Environment determines capability; `remote` does not provide Shell |
| External system tools | Configure operations, authentication and approval policy; begin with a read-only call |

Common `mcpServers` fields are `name`, `transport`, `url`, `command`, `args`, `headers`, `env`, `queryParams`, `enableTools`, `disableTools`, `required`, `initializationTimeout` and `timeout`. Use `command/args` for stdio and `url` for a network endpoint. Timeout fields accept duration strings supported by the adapter, such as `PT30S`.

`enabled` controls tool availability; `permissionPolicy.type` accepts `always_allow`, `always_ask` or `deny`. A configured tool still needs permission to execute; requests requiring confirmation enter the corresponding approval flow.

## Skills and internal Subagents

[Workspace](/v2/en/service/workspaces) holds `AGENTS.md`, Skill files and Subagent declarations. Skills provide repeatable procedures and supporting material. Subagents provide specialist delegation inside an Agent. Install system dependencies in the actual Environment.

Use a [Team](/v2/en/service/teams) for independent ownership, persistent discussion, mixed runtimes or a combined deliverable. Internal Subagents are not automatically exposed as independent Team services.

## Knowledge, credentials and entry points

- [Memory Store](/v2/en/service/memory): bound shared knowledge accessed on demand; writes depend on tool and mount policy during execution.
- [Vault](/v2/en/service/vault): tool connection credentials; it does not configure every model connection.
- Chat: personal conversations. Issue: delivery and acceptance.
- Automation, Workflow and Team: schedule, compose or delegate Managed work.
- [Managed native session API](/v2/en/service/session-event-log): hosted sessions, background tasks, interactions and recovery; [SSE](/v2/en/service/sse-events) exposes messages, tools, children and artifacts.
- [Endpoint](/v2/en/service/endpoints) and Channel: expose capabilities through published protocols and message routing.

Keep a minimal input, expected tool call and checkable output for each added capability. Verify the Agent alone before adding it to a Team or Workflow.
