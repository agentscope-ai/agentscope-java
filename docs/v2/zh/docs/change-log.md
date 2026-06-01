---
title: "Changelog"
description: "AgentScope Java 2.0 与 1.0 的核心区别"
---

AgentScope Java 2.0 是一次破坏性更新。下面按模块汇总相对 1.0 的差异（Java 视角）。

## Agent

- 在 1.0 的 `ReActAgent` 之上抽出新的 `Agent` 接口（`io.agentscope.core.agent.Agent`），由 `CallableAgent`、`StreamableAgent`、`ObservableAgent` 三个能力接口组合而成。
- 推荐入口仍是 `ReActAgent.builder()...build()`。`call(...)` 返回 `Mono<Msg>`，`streamEvents(...)` 返回 `Flux<AgentEvent>` —— 提供更细粒度的可观测性与控制。
- 通过事件流支持 **permission 校验** 与 **human-in-the-loop** 确认（`RequireUserConfirmEvent`、`RequireExternalExecutionEvent`、`UserConfirmResultEvent`、`ExternalExecutionResultEvent`）。
- 1.0 的 `Hook` 机制在 2.0 中被弃用，由新的 agent middleware 系统取代（`MiddlewareBase`、`Middleware`、`MiddlewareChain`）。`ReActAgent.Builder.hooks(...)` 仍保留过渡期入口，但优先使用 `middlewares(...)`。
- 状态以新的 `AgentState` 显式管理。会话历史不再由 `Memory` 实现持有，而是写入 `AgentState.getContext()`，并由配置在 builder 上的 `Session` 自动加载/保存。

## Event **New**

- 新增 event 系统（`io.agentscope.core.event`），更好地服务前端集成与 human-in-the-loop 场景。
- 事件类型集中在 `AgentEventType` 枚举中，包括 `AGENT_START` / `AGENT_END`、`MODEL_CALL_START` / `MODEL_CALL_END`、文本/思考/数据块的 `*_START` / `*_DELTA` / `*_END`、`TOOL_CALL_*`、`TOOL_RESULT_*`、`EXCEED_MAX_ITERS`、`REQUIRE_USER_CONFIRM`、`REQUIRE_EXTERNAL_EXECUTION`、`USER_CONFIRM_RESULT`、`EXTERNAL_EXECUTION_RESULT`、`REQUEST_STOP`。

## Message

Content block 重构：

- 重构所有 content block 为 Jackson POJO，统一通过 `ContentBlock` 接口暴露，提升校验、序列化与扩展性。
- 在原 `ImageBlock` / `AudioBlock` / `VideoBlock` 之外新增统一的 `DataBlock`，通过 `mediaType` 字段保留扩展性；旧的具体子类仍可使用，便于平滑迁移。
- 新增 `HintBlock`，用于 agent 引导与中间推理。
- `ToolUseBlock` 与 `ToolResultBlock` 增加 `state` 字段（`ToolCallState` / `ToolResultState`），更完整地建模 tool-call 生命周期。
- 为所有 block 新增 `id` 字段，提升可追踪性与引用能力。

`Msg` 类重构：

- `Msg implements State`，强制 content 校验：`USER` 仅允许 text/data/image/audio/video，`SYSTEM` 仅允许 text，`ASSISTANT` 不限制。
- 为 `Msg` 新增 `timestamp` 字段、`getUsage()`（`ChatUsage`）、`getGenerateReason()`（`GenerateReason` 枚举：`MODEL_STOP`、`TOOL_SUSPENDED`、`REASONING_STOP_REQUESTED`、`ACTING_STOP_REQUESTED`、`INTERRUPTED`、`MAX_ITERATIONS`），提升可观测性与计量能力。
- 新增按 role 固定的子类 `UserMessage`、`AssistantMessage`、`SystemMessage`、`ToolResultMessage`，便于按对应 role 创建消息；`Msg.builder()` 与各子类 `Builder` 同时存在。

## Permission **New**

- 新增权限系统（`io.agentscope.core.permission`），用于 tool 执行的细粒度门控、human-in-the-loop 确认以及 agent 整体自治度控制。
- 核心组件：`PermissionEngine`、`PermissionContextState`、`PermissionRule`、`PermissionDecision`、`PermissionMode`（`DEFAULT` / `ACCEPT_EDITS` / `EXPLORE` / `BYPASS` / `DONT_ASK`）、`PermissionBehavior`。

## Tool

- 新增 `ToolBase` 抽象类与 `AgentTool` 接口，统一所有 tool 的基类与契约。
- 新增任务管理内置 tool：`TodoTools.todoWrite`（`tool/builtin/TodoTools.java`），用于 agent 在会话内维护结构化任务列表，配合 `TaskReminderMiddleware` 自动注入提醒。

`Toolkit` 重构：

- `Toolkit` 将 tool、skill、MCP 与 tool group 作为一等公民统一支持。
- 新增 `ToolGroup` / `ToolGroupManager` / `ToolGroupScope`，支持按需激活，保留名 `basic` 组始终在线。
- 新增 `MetaToolFactory`，供 agent 在运行时切换 tool group。
- 新增 `McpTool` 与基于 `@Tool` / `@ToolParam` 注解的 `ReflectiveFunctionTool` 适配器，统一 tool 注册方式（`Toolkit#registerTool(Object)` 反射注册任意带 `@Tool` 的方法）。

## MCP

- 将 MCP 实现统一到 `io.agentscope.core.tool.mcp` 包：`McpClientManager`、`McpClientWrapper`（`McpSyncClientWrapper` / `McpAsyncClientWrapper`）、`McpClientBuilder`、`McpTool`、`McpContentConverter`。
- 通过 `McpClientBuilder` 构建 stdio / SSE / Streamable HTTP 客户端，参见 `agentscope-examples/documentation/.../mcp` 下示例。

## Skill **New**

- 新增 skill loader 抽象（`io.agentscope.core.skill`），支持基于目录的 skill 加载与监听。
- 提供 `AgentSkill`、`SkillRegistry`、`SkillBox`、`SkillToolFactory`、`SkillFilter`、`SkillFileFilter`、`SkillToolGroup` 等组件，支持把 skill 打包为 `ToolGroup`，按需激活与组织。

## Model

- 将 credential 管理从 model 类中解耦，集中到新的 `io.agentscope.core.credential` 模块（`DashScopeCredential` / `OpenAICredential` / `AnthropicCredential` / `GeminiCredential` / `OllamaCredential` / `DeepSeekCredential` / `KimiCredential` / `XAICredential`）。
- 当前内置 chat model：`DashScopeChatModel`、`OpenAIChatModel`、`AnthropicChatModel`、`GeminiChatModel`、`OllamaChatModel`，均继承自 `ChatModelBase`。
- 将 formatter 集成到 chat model 抽象中，并为不同 model provider 提供默认 formatter（`DashScopeChatFormatter`、`OpenAIChatFormatter`、`AnthropicChatFormatter`、`GeminiChatFormatter`、`OllamaChatFormatter`）。
- 新增 `ModelCard` schema（`credential/ModelCard.java`），描述模型身份、能力与参数覆盖。
- 新增 `ModelRegistry`，支持按 `provider:model` 字符串解析模型（如 `dashscope:qwen-max`、`openai:gpt-5`）。

## Middleware **New**

- 将 hook 机制重构为更通用的 agent middleware 系统（`io.agentscope.core.middleware`）。
- `MiddlewareBase` 暴露 5 个钩子：洋葱模式的 `onAgent` / `onReasoning` / `onActing` / `onModelCall` 和管道模式的 `onSystemPrompt`。
- 内置 `TaskReminderMiddleware`（与 `TodoTools` 配合，在每个 reasoning step 前注入 todo 状态提醒）。
- 通过 `OtelTracingMiddleware`（位于 `tracing/`）作为 OpenTelemetry tracing 的入口。

## Memory

- 在 2.0 中弃用 `Memory` 接口（`@Deprecated(forRemoval = true, since = "2.0.0")`），原因是该接口与 agent 逻辑耦合过深。
- 新代码请使用 `AgentState.getContext()` 持有会话历史，并通过 `Session` + `SessionKey` 完成持久化。

## RAG & Long-Term Memory

- 将 RAG 与 long-term memory 统一到单一模块下（`io.agentscope.core.rag`、`io.agentscope.core.memory.LongTermMemory`）。
- 从 1.0 到 2.0 的迁移正在进行中，knowledge base、document reader 与 store 将基于 2.0 架构在后续版本中陆续上线。

## Session & State **New**

- 新增 `Session` 抽象与两种内置实现：`InMemorySession`（进程内）、`JsonSession`（文件系统 JSON 持久化）。
- 通过 `SessionKey`（默认实现 `SimpleSessionKey`）定位每条会话；ReActAgent 配置 `.session(...).sessionKey(...)` 后会在每次 `call` 后自动持久化 `AgentState`。
- `RuntimeContext` 提供 per-call metadata：在 `call(msgs, runtimeContext)` 中传入 `userId`、`sessionId` 与任意 `put(key, value)` 数据，hook 与 tool 在本次调用期间可读写同一上下文。
