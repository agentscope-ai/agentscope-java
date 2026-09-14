---
title: "External frameworks and custom adapters"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

External support depends on both the SDK adapter and the supplied framework object. Use these implemented integration paths to choose your target and acceptance scope.

## Java

`agentscope-extensions-aistio` adapts AgentScope Java `Agent` objects. Its contract includes context, messages, session commands, abort and task queries. The underlying Agent still determines whether a specific operation can execute.

Optional extensions include `SessionHistorySource` for history, `AgentRuntimeSource` for Workspace/Subagent inventory and runtime details, and `AgentTaskStarter` for dispatched work. `HarnessAgentTaskStarter` can consume platform definitions through a Workspace factory. The adapter advertises `agent-task` only when a task starter is configured; creating a bridge alone does not create a work executor.

## Built-in Python adapters

| Framework | Typical target/integration | Implemented focus |
| --- | --- | --- |
| AgentScope | Agent instance and hooks | Context, messages, commands, abort and task queries |
| OpenAI Agents SDK | Session or object containing a Session | Session items, context and messages; commands depend on backend methods |
| LangChain / LangGraph | Framework objects and callbacks/state | Model/tool events, context and messages |
| Google ADK | Framework objects such as SessionService | Session events, context, messages and supported commands |
| Claude Agent SDK | Client / Session store objects | Session storage, context, messages and supported commands |
| OpenClaw | Gateway RPC client/connection | Context, messages, Subagent and Workspace inventory |

Automatic selection calls `can_handle(target)` and chooses the first match in registration order. Supply `adapter=` explicitly for unsupported wrappers or ambiguous matching.

**The current built-in Python adapters do not implement `handle_agent_task`.** They provide observation and their implemented session capabilities, without automatic Issue/Team dispatch support. External and Hosted integrations are different even when names overlap: Claude Agent SDK integration is separate from running Claude Code CLI through Runtime Host.

## Implementing a custom adapter

| Extension point | Responsibility |
| --- | --- |
| `can_handle` | Recognize targets without starting work |
| `attach` / `detach` | Install and remove framework hooks or observers |
| `extract_context` | Extract session context |
| `list_messages` | Return available message history |
| `handle_command` / `abort` | Execute real commands/cancellation and report actual failures |
| `handle_agent_task` | Create isolated execution for dispatched work, including completion, failure and cancellation |

Subclass `FrameworkAdapter` and pass it to `aistio.instrument(..., adapter=your_adapter)`, or register it with `register_adapter()`. The base class derives capabilities from overridden methods; empty implementations must not be used to claim support.

## Additional task acceptance checks

Verify that concurrent Attempts do not share session state, then test retries, cancellation, event recovery and Artifact upload. A final assistant message alone does not complete an Attempt. Team coordinators also need node completion/failure behavior. The application maintains its own models, tools and deployment dependencies.

Related: [connection settings](/v2/en/service/external-agent-configuration), [execution model](/v2/en/service/external-agent-execution) and [Team collaboration](/v2/en/service/team-collaboration).
