---
title: "Changelog"
description: "Core differences between AgentScope Java 2.0 and 1.0"
---

AgentScope Java 2.0 is a breaking release. The notes below summarize the differences against 1.0 from a Java perspective, grouped by module.

## Agent

- A new `Agent` interface (`io.agentscope.core.agent.Agent`) is now layered on top of the 1.0 `ReActAgent`, composed from three capability interfaces: `CallableAgent`, `StreamableAgent`, `ObservableAgent`.
- The recommended entry point is still `ReActAgent.builder()...build()`. `call(...)` returns `Mono<Msg>`, `streamEvents(...)` returns `Flux<AgentEvent>` — finer-grained observability and control.
- The event stream supports **permission gating** and **human-in-the-loop** confirmation (`RequireUserConfirmEvent`, `RequireExternalExecutionEvent`, `UserConfirmResultEvent`, `ExternalExecutionResultEvent`).
- The 1.0 `Hook` mechanism is deprecated in 2.0 in favour of the new agent middleware system (`MiddlewareBase`, `Middleware`, `MiddlewareChain`). `ReActAgent.Builder.hooks(...)` is kept for migration; prefer `middlewares(...)`.
- State is now explicitly modelled by `AgentState`. Conversation history is no longer held by `Memory` implementations — it lives on `AgentState.getContext()` and is loaded / saved automatically by the `Session` configured on the builder.

## Event **New**

- A new event system (`io.agentscope.core.event`), better suited to frontend integration and human-in-the-loop scenarios.
- Event types are centralised in the `AgentEventType` enum: `AGENT_START` / `AGENT_END`, `MODEL_CALL_START` / `MODEL_CALL_END`, the `*_START` / `*_DELTA` / `*_END` triplets for text / thinking / data blocks, `TOOL_CALL_*`, `TOOL_RESULT_*`, `EXCEED_MAX_ITERS`, `REQUIRE_USER_CONFIRM`, `REQUIRE_EXTERNAL_EXECUTION`, `USER_CONFIRM_RESULT`, `EXTERNAL_EXECUTION_RESULT`, `REQUEST_STOP`.

## Message

Content block refactor:

- All content blocks are Jackson POJOs implementing the `ContentBlock` interface — uniform validation, serialization, and extensibility.
- A unified `DataBlock` (with a `mediaType` discriminator) is added alongside the existing `ImageBlock` / `AudioBlock` / `VideoBlock`. The legacy concrete subclasses still work for smooth migration.
- New `HintBlock` for agent guidance and intermediate reasoning.
- `ToolUseBlock` and `ToolResultBlock` gain a `state` field (`ToolCallState` / `ToolResultState`) for richer lifecycle modeling.
- All blocks gain an `id` field for traceability and reference.

`Msg` refactor:

- `Msg implements State` with constructor-time content validation: `USER` allows text/data/image/audio/video, `SYSTEM` allows text only, `ASSISTANT` is unrestricted.
- New fields on `Msg`: `timestamp`, `getUsage()` (`ChatUsage`), `getGenerateReason()` (`GenerateReason` enum: `MODEL_STOP`, `TOOL_SUSPENDED`, `REASONING_STOP_REQUESTED`, `ACTING_STOP_REQUESTED`, `INTERRUPTED`, `MAX_ITERATIONS`).
- New role-pinned subclasses `UserMessage`, `AssistantMessage`, `SystemMessage`, `ToolResultMessage` make it convenient to build messages by role; `Msg.builder()` and per-subclass `Builder` are both available.

## Permission **New**

- A new permission system (`io.agentscope.core.permission`) for fine-grained tool-execution gating, human-in-the-loop confirmation, and overall agent autonomy control.
- Core components: `PermissionEngine`, `PermissionContextState`, `PermissionRule`, `PermissionDecision`, `PermissionMode` (`DEFAULT` / `ACCEPT_EDITS` / `EXPLORE` / `BYPASS` / `DONT_ASK`), `PermissionBehavior`.

## Tool

- New `ToolBase` abstract class and `AgentTool` interface unify the base contract for all tools.
- New built-in task tool: `TodoTools.todoWrite` (`tool/builtin/TodoTools.java`), letting the agent maintain a structured task list per session, with the `TaskReminderMiddleware` automatically re-injecting the list each turn.

`Toolkit` refactor:

- `Toolkit` treats tools, skills, MCP, and tool groups as first-class citizens.
- New `ToolGroup` / `ToolGroupManager` / `ToolGroupScope` for on-demand activation, with the reserved `basic` group always active.
- New `MetaToolFactory` lets the agent switch tool groups at runtime.
- New `McpTool` and reflective `@Tool` / `@ToolParam`-based `ReflectiveFunctionTool` adapters unify tool registration (`Toolkit#registerTool(Object)` reflectively registers any object's `@Tool` methods).

## MCP

- MCP support is consolidated under `io.agentscope.core.tool.mcp`: `McpClientManager`, `McpClientWrapper` (`McpSyncClientWrapper` / `McpAsyncClientWrapper`), `McpClientBuilder`, `McpTool`, `McpContentConverter`.
- Use `McpClientBuilder` to construct stdio / SSE / Streamable HTTP clients — see the examples under `agentscope-examples/documentation/.../mcp`.

## Skill **New**

- A new skill loader abstraction (`io.agentscope.core.skill`) supports loading skills from a directory and watching for changes.
- Components: `AgentSkill`, `SkillRegistry`, `SkillBox`, `SkillToolFactory`, `SkillFilter`, `SkillFileFilter`, `SkillToolGroup`. Skills can be packaged into `ToolGroup`s and activated on demand.

## Model

- Credential management is decoupled from model classes into the new `io.agentscope.core.credential` module (`DashScopeCredential` / `OpenAICredential` / `AnthropicCredential` / `GeminiCredential` / `OllamaCredential` / `DeepSeekCredential` / `KimiCredential` / `XAICredential`).
- Built-in chat models: `DashScopeChatModel`, `OpenAIChatModel`, `AnthropicChatModel`, `GeminiChatModel`, `OllamaChatModel`, all extending `ChatModelBase`.
- Formatters are integrated into the chat model abstraction with a default formatter per provider (`DashScopeChatFormatter`, `OpenAIChatFormatter`, `AnthropicChatFormatter`, `GeminiChatFormatter`, `OllamaChatFormatter`).
- New `ModelCard` schema (`credential/ModelCard.java`) describes a model's identity, capabilities, and parameter overrides.
- New `ModelRegistry` resolves models from `provider:model` strings (e.g. `dashscope:qwen-max`, `openai:gpt-5`).

## Middleware **New**

- The hook mechanism has been refactored into a more general agent middleware system (`io.agentscope.core.middleware`).
- `MiddlewareBase` exposes 5 hooks: the onion-shaped `onAgent` / `onReasoning` / `onActing` / `onModelCall`, plus the pipeline-shaped `onSystemPrompt`.
- Built-in `TaskReminderMiddleware` (paired with `TodoTools`, injecting a reminder before each reasoning step).
- `OtelTracingMiddleware` (in `tracing/`) is the new entry point for OpenTelemetry tracing.

## Memory

- The `Memory` interface is deprecated in 2.0 (`@Deprecated(forRemoval = true, since = "2.0.0")`) — it was too tightly coupled to agent internals.
- New code should use `AgentState.getContext()` for conversation history and persist via `Session` + `SessionKey`.

## RAG & Long-Term Memory

- RAG and long-term memory are unified under one module (`io.agentscope.core.rag`, `io.agentscope.core.memory.LongTermMemory`).
- Migration from 1.0 to 2.0 is in progress; knowledge bases, document readers, and stores will land in subsequent releases on the 2.0 architecture.

## Session & State **New**

- New `Session` abstraction with two built-in implementations: `InMemorySession` (in-process) and `JsonSession` (JSON file persistence).
- `SessionKey` (default: `SimpleSessionKey`) identifies each session; once a `ReActAgent` is configured with `.session(...).sessionKey(...)`, `AgentState` is automatically persisted after every `call`.
- `RuntimeContext` provides per-call metadata: pass `userId`, `sessionId`, and arbitrary `put(key, value)` data into `call(msgs, runtimeContext)` — hooks and tools share the same context for the duration of one call.
