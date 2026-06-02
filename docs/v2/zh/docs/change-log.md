---
title: "Changelog"
description: "AgentScope Java 2.0 与 1.0 的核心区别"
---

:::{note}
**当前最新版本：`2.0.0-RC1`**。完整的版本说明与上游变更列表请见 [GitHub Release Notes](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.0-RC1)。
:::

AgentScope Java 2.0 版本尽量保持了对 1.x 版本的兼容，确保大部分用户的平滑升级，但同时 2.0 版本也带来了 API 层面的不兼容变更。本页分为两部分：

- **迁移指南** —— 对 1.x 的变更，按紧迫度再分两层：
  - **Part A · 必须迁移** —— 不改会编译失败或运行抛异常
  - **Part B · 推荐迁移** —— 当前仍可调用，但已标 `@Deprecated(forRemoval = true)`，下一个 minor 版本会移除
- **新增内容** —— 不在迁移指南中覆盖的增量功能

## 迁移指南

### Part A —— 必须迁移（不迁移会编译失败或运行抛异常）

本节列出的 API 已被删除、重命名或语义收紧。1.x 中能编译运行的代码到 2.0 上会直接报错。

#### A.1 已删除的 `ReActAgent.Builder` 方法

| 2.0 中已删除 | 替代方案 |
|---|---|
| `.memory(Memory)` | `.session(Session).sessionKey(SessionKey)` —— 会话历史保存在 `AgentState.getContext()`；配置好的 `Session` 在每次 `call()` 后自动 save/load |
| `.statePersistence(StatePersistence)` | 同上 —— `Session` 已接管持久化职责 |

详见 → [上下文](harness/context.md)

#### A.2 已删除的包 / 类

| 2.0 中已删除 | 替代方案 |
|---|---|
| `io.agentscope.core.session.SessionManager` | 在 builder 上配置 `Session` + `SessionKey`，框架自动持久化 |
| `io.agentscope.core.pipeline.*`（`Pipeline`、`Pipelines`、`SequentialPipeline`、`FanoutPipeline`、`MsgHub`） | 多智能体编排改用 middleware + 子 agent + event stream，参见 → [子 Agent](harness/subagent.md) |
| `io.agentscope.core.model.tts.*`（14 个文件：DashScope TTS / Realtime TTS / `AudioPlayer` 等） | core 不再内置 TTS；如需 TTS，请直接对接上游 SDK |
| `io.agentscope.core.hook.PendingToolRecoveryHook` | 改用 `Builder.enablePendingToolRecovery(boolean)` |
| `io.agentscope.core.hook.TTSHook` | 随 TTS 模块一起去掉 |

#### A.3 `state` 包重构（编译错误）

| v1 | v2 |
|---|---|
| `AgentMetaState` | `AgentState` |
| `StateModule` | **删除** —— 不再作为 `Memory`、`Toolkit` 等的父接口 |
| `StatePersistence` | **删除** —— 由 `Session` 抽象接管 |
| `ToolkitState` | 移到 `io.agentscope.core.session.legacy.ToolkitState`（仅兼容，新代码不要引用） |
| （新增） | `Task`、`TaskContextState`、`ToolContextState`、`PlanModeContextState`、`ReadCacheEntry` |

凡是从 `io.agentscope.core.state` import `AgentMetaState`、`StateModule`、`StatePersistence`、`ToolkitState` 的代码都会编译失败。详见 → [上下文](harness/context.md)

#### A.4 `Msg` 构造按 role 严格校验（运行抛异常）

`Msg` 现在在构造时按 `role` 对 `content` 做校验：

- `USER` —— 仅允许 `TextBlock` / `DataBlock` / `ImageBlock` / `AudioBlock` / `VideoBlock`
- `SYSTEM` —— 仅允许 `TextBlock`
- `ASSISTANT` —— 不限制

v1 中容忍的非法组合（例如 `USER` 携带 `ToolUseBlock`）现在会在构造时直接抛异常。推荐改用 role 子类 `UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResultMessage`，在调用处就显式表达 role 与 content 的对应关系。详见 → [消息与事件](building-blocks/message-and-event.md)

---

### Part B —— 推荐迁移（`@Deprecated(forRemoval = true)`，仍可调用）

本节列出在 2.0 中仍可调用、但已标记下一 minor 移除的 API。可以按节奏迁移，但建议尽早。

#### B.1 `SkillBox` → SkillRepository

- `SkillBox` 类与 `Builder.skillBox(SkillBox)` 均标 `@Deprecated(forRemoval = true, since = "2.0.0")`
- 新方式：通过 `AgentSkillRepository`（内置 `ClasspathSkillRepository`、`FileSystemSkillRepository`）注入技能，使用 `Builder.skillRepository(...)` / `.skillRepositories(...)`。只要注册了至少一个 repository，`DynamicSkillMiddleware` 会自动安装，在每次 `call()` 前重建 skill prompt
- 细粒度过滤：`Builder.skillFilter(SkillFilter)`。若需要关闭自动中间件（例如让 `HarnessAgent` 接管），用 `Builder.dynamicSkillsEnabled(false)`

详见 → [技能](harness/skill.md)

#### B.2 Hook → Middleware

整个 `io.agentscope.core.hook` 包 —— 包括 `Hook` 接口、`HookEvent`、`HookEventType` 与所有 `*Event` 类 —— 均标 `@Deprecated(forRemoval = true, since = "2.0.0")`。原有 import 仍能编译，`Builder.hook(...)` / `.hooks(...)` 仍可调用（由 `LegacyHookDispatcher` 桥接），v1 代码不会立刻 break。推荐改用 `io.agentscope.core.middleware`：

- `MiddlewareBase` 提供 5 个 stage：洋葱型 `onAgent` / `onReasoning` / `onActing` / `onModelCall`，管道型 `onSystemPrompt`
- Builder：`.middleware(MiddlewareBase)` 与 `.middlewares(List<? extends MiddlewareBase>)`
- 内置：`TaskReminderMiddleware`（与 `TodoTools` 配合，在每个 reasoning step 前注入任务提醒）

详见 → [Middleware](building-blocks/middleware.md)

#### B.3 `Memory` → `Session` + `AgentState`

- `io.agentscope.core.memory.Memory` 接口与所有实现（`InMemoryMemory`、`LongTermMemory` 等）均标 `@Deprecated(forRemoval = true, since = "2.0.0")`
- `Memory` 不再 `extends StateModule`；新增 `saveTo(Session, SessionKey)` / `loadFrom(Session, SessionKey)` 作 v1 桥接，方便现有实现继续通过 `Session` 走持久化
- 新模型：
  - **会话历史**保存在 `AgentState.getContext()`
  - **持久化**通过 `Session` 抽象（内置 `InMemorySession`、`JsonSession`），按 `SessionKey` 分桶
  - Builder 链：`.session(Session).sessionKey(SessionKey)` —— `AgentState` 在每次 `call()` 后自动 save/load

详见 → [上下文](harness/context.md)

#### B.4 事件订阅：hook + chunk → `streamEvents()`

v1 中通过 `Hook` + 各种 `*ChunkEvent` 拼装文本 / 工具增量的代码，可直接迁到 `agent.streamEvents()`：返回 `Flux<AgentEvent>`，覆盖 agent 全生命周期及 HITL 流程的 28 个类型化事件（`RequireUserConfirmEvent`、`RequireExternalExecutionEvent`、`UserConfirmResultEvent`、`ExternalExecutionResultEvent` 等）。

配合 `Msg` 重构新增的能力：

- `DataBlock` —— 统一的多模态块，base64 / URL 二选一
- `HintBlock` —— agent 引导提示 / 中间推理
- `ToolUseBlock` / `ToolResultBlock` 增加 `state` 字段（`ToolCallState` / `ToolResultState`）—— 完整建模 tool-call 生命周期
- 所有 block 加 `id` 字段 —— 跨事件流稳定引用

详见 → [消息与事件](building-blocks/message-and-event.md)

##### `stream()` → `streamEvents()`（与 Python 2.0 对齐）

Python 2.0 的 `agent.reply_stream()` 只返回一种事件流签名（`AsyncGenerator[AgentEvent, None]`），对应 Java 的细粒度 `io.agentscope.core.event.AgentEvent` 体系。为了与之对齐，Java 端的粗粒度 `Flux<Event> stream(...)` API 在 2.0.0 全部 `@Deprecated`：

- **方法（`forRemoval = true`，下一个 minor 移除）**
  - `StreamableAgent.stream(...)` —— 接口上的全部 11 个 `stream(...)` 重载（默认方法 + 抽象方法）
  - `AgentBase.stream(...)` —— 3 个 `Flux<Event>` 实现
  - `ReActAgent.stream(..., RuntimeContext)` —— 4 个 `RuntimeContext` 后缀重载
  - `HarnessAgent.stream(...)` —— 9 个重载（3 个接口 `@Override` + 6 个 `RuntimeContext` 变体）。`HarnessAgent` 新增 `streamEvents(Msg/List<Msg>[, RuntimeContext])` 4 个方法，内部委托到 `ReActAgent.streamEvents(...)` 并复用沙箱生命周期 `acquireForCall` / `releaseForCall`
  - `ReActAgent.streamEvents(..., RuntimeContext)` 新增 —— 对齐 `call(..., RuntimeContext)` 的 context 透传形态
- **类型（软弃用，暂不 `forRemoval`）**
  - `io.agentscope.core.agent.Event`、`EventType`、`EventSource`
  - 这些类目前仍被 harness（子 agent 事件转发：`SubAgentTool` / `SubagentEventBus` / `DefaultAgentManager` / `AgentSpawnTool`）、AGUI、A2A、chat-completions-web、kotlin extension 等内部模块作为事件总线 / 适配器的输入消费。等这些模块完成迁移到 `AgentEvent` 后再翻成 `forRemoval = true`，避免一次性把下游全打成警告
  - **当前 gap**：`HarnessAgent.streamEvents(...)` 暂时**不转发子 agent 事件** —— `AgentEvent` 体系还没有等价的 `EventSource` 通道；需要子 agent 事件流的场景仍需用 `stream(...)`（已弃用），等通道落地后再统一切换

新代码统一改用：

```java
agent.streamEvents(new UserMessage("Hello"))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            }
        })
        .blockLast();
```

#### B.5 RAG 模块：推进中

- `Knowledge`、`KnowledgeRetrievalTools`、`RAGMode`、`GenericRAGHook` 全部 `@Deprecated(forRemoval = true, since = "2.0.0")`
- Builder：`.knowledge(...)` / `.knowledges(...)` / `.ragMode(...)` / `.retrieveConfig(...)` 同步弃用
- v2 架构下的 knowledge base / document reader / store 将在后续 minor 版本上线。v1 实现在 2.0 仍可调用以保兼容，但**新代码不要依赖**

#### B.6 长期记忆模块：推进中

- `LongTermMemory`、`LongTermMemoryMode`、`LongTermMemoryTools` 全部 `@Deprecated(forRemoval = true, since = "2.0.0")`
- Builder：`.longTermMemory(...)` / `.longTermMemoryMode(...)` / `.longTermMemoryAsyncRecord(...)` 同步弃用
- 同样在 v2 架构下重写中；新代码先不要依赖

#### B.7 core 内置 Shell / File 工具：迁到 Harness

- `io.agentscope.core.tool.coding.*`（`ShellCommandTool`、`CommandValidator`、`UnixCommandValidator`、`WindowsCommandValidator`）与 `io.agentscope.core.tool.file.*`（`ReadFileTool`、`WriteFileTool`、`FileToolUtils`）全部 `@Deprecated(forRemoval = true, since = "2.0.0")`
- 这些工具直接在宿主机进程上执行命令和读写文件，不带 workspace / 权限隔离，因此从 core 内置工具集中移出
- 推荐方案：使用 `agentscope-harness` 模块在 workspace 上下文里运行等价工具 —— 享受统一的本地 / Docker / 云沙箱后端、文件 IO 权限、读写缓存、HITL 审批等能力

详见 → [Harness 文件系统](harness/filesystem.md)

---

## 新增内容

下面列出的能力都是 2.0 的增量新增，对 1.x 代码 0 影响。事件系统、消息重构、middleware 机制已在上方迁移指南完整覆盖，此处不再重复。

### Toolkit & Permission

工具执行是 2.0 主要的扩展面，而权限系统直接挂在工具执行路径上，因此合并讲。

- **Toolkit 升级**：
  - 统一基类：`ToolBase` / `AgentTool`
  - 工具组：`ToolGroup` / `ToolGroupScope` / `MetaToolFactory` —— 按需激活；保留的 `basic` 组始终在线
  - 注解驱动：`ReflectiveFunctionTool` + `@Tool` / `@ToolParam`；`Toolkit#registerTool(Object)` 反射注册任意带注解的方法
  - 内置任务工具：`io.agentscope.core.tool.builtin.TodoTools.todoWrite`（与 `TaskReminderMiddleware` 配合）
- **Permission 系统**（新包 `io.agentscope.core.permission`）：
  - `PermissionEngine`、`PermissionRule`、`PermissionMode`（`DEFAULT` / `ACCEPT_EDITS` / `EXPLORE` / `BYPASS` / `DONT_ASK`）、`PermissionBehavior`
  - 每次 tool 调用前自动经 `PermissionEngine`：允许 / 用户审批 / 拒绝；HITL 决策回流到 `UserConfirmResultEvent`

详见 → [工具](building-blocks/tool.md)、[权限系统](building-blocks/permission-system.md)

### 模型容错与凭据

- 新包：`io.agentscope.core.credential` —— 8 个 provider credential 类 + `ModelCard`
- `ModelRegistry`：按 `"provider:model"` 字符串解析（如 `dashscope:qwen-max`、`openai:gpt-5`）
- Builder 新增：`.model(String)`、`.maxRetries(int)`、`.fallbackModel(Model)` / `.fallbackModel(String)`、`.stopOnReject(boolean)` —— 主模型失败自动重试 / 切换备用模型

详见 → [模型](building-blocks/model.md)

### Workspace（Harness 模块）

- 工作区抽象：本地文件系统 / Docker / E2B 云沙箱统一接口
- 预热池：支持提前批量初始化执行环境，适配 RL rollout 等并行场景

详见 → [Workspace](harness/workspace.md)

### Builder 其他新方法

- `.enableTaskList(...)` / `.enableTaskList(boolean)` —— 启用内置 `TodoTools`
- `.permissionContext(PermissionContextState)` —— 预置 permission 规则
- `ReActAgent.Builder.fromAgent(ReActAgent)` —— 从现有 agent 的可观察配置（name、description、system prompt、model、maxIters、generateOptions、toolkit）派生新的 builder
- `HarnessAgent.Builder.fromAgent(ReActAgent)` —— 把 ReActAgent 迁到 HarnessAgent 的辅助方法。在 `ReActAgent.Builder.fromAgent` 的 7 个字段之上额外继承 ReActAgent 上**所有可观察的配置**：`session` / `sessionKey`、`ModelConfig`（`maxRetries` / `fallbackModel`）、`ReactConfig.stopOnReject`、`modelExecutionConfig` / `toolExecutionConfig` / `toolExecutionContext`、`structuredOutputReminder`、`enablePendingToolRecovery`、`checkRunning`、`permissionContext`、`middlewares`、`hooks`。`enableMetaTool` / `enableTaskList` 不复制（这两个是 Builder-time 工具注册开关，toolkit copy 已经把它们注册的工具带过来了）。harness 独有的 workspace / filesystem / subagent / skill / plan mode / 各 `disable*` 等仍需手动设置。javadoc 里有完整列表
- **ReActAgent 新增 6 个 getter 以支撑上述迁移**：`getModelExecutionConfig()` / `getToolExecutionConfig()` / `getToolExecutionContext()` / `isPendingToolRecoveryEnabled()` / `getPermissionContext()`（位于 `ReActAgent`）；`getStructuredOutputReminder()`（位于 `StructuredOutputCapableAgent`）；`isCheckRunning()`（位于 `AgentBase`）

详见 → [智能体](building-blocks/agent.md)
