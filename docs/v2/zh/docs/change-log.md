---
title: "V1 迁移指南"
description: "从 AgentScope Java 1.x 升级到 2.0 的完整迁移指南"
---

:::{tip}
如果你在找各版本的具体变更记录，请见 [Release Notes](others/release-notes.md)。
:::

AgentScope Java 2.0 版本尽量保持了对 1.x 版本的兼容，确保大部分用户的平滑升级，但同时 2.0 版本也带来了 API 层面的不兼容变更。本页分为两部分：

- **迁移指南** —— 对 1.x 的变更，按紧迫度再分两层：
  - **Part A · 必须迁移** —— 不改会编译失败或运行抛异常
  - **Part B · 推荐迁移** —— 当前仍可调用，但已标 `@Deprecated(forRemoval = true)`；为保证平滑升级，这些 API 在 2.0.x 期间会持续保留，将在未来大版本（如 2.1）中逐步清理
- **新增内容** —— 不在迁移指南中覆盖的增量功能

## 迁移指南

### Part A —— 必须迁移（不迁移会编译失败或运行抛异常）

本节列出的 API 已被删除、重命名或语义收紧。1.x 中能编译运行的代码到 2.0 上会直接报错。

#### A.1 已删除的 `ReActAgent.Builder` 方法

| 2.0 中已删除 | 替代方案 |
|---|---|
| `.memory(Memory)` | `.stateStore(AgentStateStore)` |
| `.statePersistence(StatePersistence)` | `.stateStore(AgentStateStore)` |
| `.structuredOutputReminder(StructuredOutputReminder)` | 不再需要，模型层原生支持 |

详见 → [上下文](building-blocks/context.md)

#### A.2 已删除的包 / 类

| 2.0 中已删除 | 替代方案 |
|---|---|
| `io.agentscope.core.session.SessionManager` | `.stateStore(AgentStateStore)` |
| `io.agentscope.core.pipeline.*`（`Pipeline`、`Pipelines`、`SequentialPipeline`、`FanoutPipeline`、`MsgHub`） | middleware + 子 agent + event stream |
| `io.agentscope.core.model.tts.*`（14 个文件：DashScope TTS / Realtime TTS / `AudioPlayer` 等） | 直接对接上游 TTS SDK |
| `io.agentscope.core.model.StructuredOutputReminder` | 不再需要，模型层原生支持 |
| `io.agentscope.core.agent.StructuredOutputCapableAgent` | 不再需要，能力已内联到 `ReActAgent` |
| `io.agentscope.core.hook.PendingToolRecoveryHook` | `Builder.enablePendingToolRecovery(boolean)` |
| `io.agentscope.core.hook.TTSHook` | 随 TTS 模块删除 |

#### A.3 模型提供商迁出 core

OpenAI、Gemini、Anthropic、DashScope、Ollama 的 Chat Model 实现不再打包在 `agentscope-core` 中。core 现在只保留共享模型契约，例如 `Model`、`ChatModelBase`、`Formatter`、`ModelRegistry` 和 `ModelProvider` SPI。

如果 v1 代码从 core import 具体模型提供商类，需要改为引入对应模型扩展模块：

| v1 import / 依赖 | v2 替代方案 |
|---|---|
| `io.agentscope.core.model.OpenAIChatModel` | 引入 `agentscope-extensions-model-openai`；import `io.agentscope.extensions.model.openai.OpenAIChatModel` |
| `io.agentscope.core.model.GeminiChatModel` | 引入 `agentscope-extensions-model-gemini`；import `io.agentscope.extensions.model.gemini.GeminiChatModel` |
| `io.agentscope.core.model.AnthropicChatModel` | 引入 `agentscope-extensions-model-anthropic`；import `io.agentscope.extensions.model.anthropic.AnthropicChatModel` |
| `io.agentscope.core.model.DashScopeChatModel` | 引入 `agentscope-extensions-model-dashscope`；import `io.agentscope.extensions.model.dashscope.DashScopeChatModel` |
| `io.agentscope.core.model.OllamaChatModel` | 引入 `agentscope-extensions-model-ollama`；import `io.agentscope.extensions.model.ollama.OllamaChatModel` |
| `io.agentscope.core.formatter.<provider>.*` | `io.agentscope.extensions.model.<provider>.formatter.*` |
| `io.agentscope.core.credential.<Provider>Credential` | `io.agentscope.extensions.model.<provider>.credential.<Provider>Credential` |

`ModelRegistry` 字符串 id 仍然可用，但前提是对应模型扩展模块已经在 classpath 中：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("dashscope:qwen-plus")
    .build();
```

Spring Boot 应用应使用对应模型提供商的 starter，而不是依赖 core 中的通用模型创建路径：

| 模型提供商 | Spring Boot starter |
|---|---|
| OpenAI | `agentscope-openai-spring-boot-starter` |
| DashScope | `agentscope-dashscope-spring-boot-starter` |
| Gemini | `agentscope-gemini-spring-boot-starter` |
| Anthropic | `agentscope-anthropic-spring-boot-starter` |
| Ollama | `agentscope-ollama-spring-boot-starter` |

详见 → [模型](building-blocks/model.md)、[模型提供商](../integration/overview.md)

#### A.4 `state` 包重构（编译错误）

| v1 | v2 |
|---|---|
| `AgentMetaState` | `AgentState` |
| `StateModule` | **删除** |
| `StatePersistence` | **删除**，改用 `AgentStateStore` |
| `ToolkitState` | `io.agentscope.core.state.legacy.ToolkitState`（仅兼容） |
| （新增） | `Task`、`TaskContextState`、`ToolContextState`、`PlanModeContextState`、`ReadCacheEntry` |

凡是从 `io.agentscope.core.state` import `AgentMetaState`、`StateModule`、`StatePersistence`、`ToolkitState` 的代码都会编译失败。详见 → [上下文](building-blocks/context.md)

#### A.5 `PlanNotebook` 已删除 —— 改用 `HarnessAgent.enablePlanMode()`

整个 `io.agentscope.core.plan` 包（`PlanNotebook`、`Plan`、`SubTask`、`PlanStorage`、`PlanToHint` 及相关类）已完整删除，无 deprecated 桥接。

**变更说明**：v1 的 `PlanNotebook` 将计划建模为结构化的 `Plan` + `SubTask` 对象，带状态机（todo → in_progress → done → abandoned）和 8 个工具函数。v2 的替代方案是完全不同的设计思路 —— plan mode 是一个**只读的调查设计阶段**，agent 在一个纯 markdown 文件中完成方案设计，然后才获得写权限。

| v1 `PlanNotebook` | v2 Plan Mode |
|---|---|
| `ReActAgent.builder().planNotebook(PlanNotebook.builder().build())` | `HarnessAgent.builder().enablePlanMode()` |
| 结构化 `Plan` + `SubTask` 对象，带状态机 | 纯 markdown 文件（`plans/PLAN.md`） |
| 8 个工具：`createPlan`、`reviseCurrentPlan`、`updateSubtaskState`、`finishSubtask`、`finishPlan`、`viewSubtasks`、`viewHistoricalPlans`、`recoverHistoricalPlan` | 3 个工具：`plan_enter`、`plan_write`、`plan_exit` |
| 计划与执行混合 —— 无只读限制 | plan mode 为只读；`plan_exit` 触发 HITL 门控，用户确认后 agent 才恢复写权限 |
| `PlanToHint` 在每个 reasoning step 前注入上下文提示 | `PlanModeMiddleware` 在 plan mode 下阻止 mutating 工具调用 |
| `PlanStorage`（内存）+ `StateModule` 持久化 | 计划文件通过 `WorkspaceManager` 写入；状态保存在 `AgentState.planModeContext` |

**子任务跟踪**：如果你的 v1 代码依赖 `PlanNotebook` 的子任务状态跟踪（把工作拆成子任务并在执行过程中逐个勾选），v2 的等价能力是**任务列表** —— 在 builder 上调用 `.enableTaskList(true)` 启用，会注册 `TodoTools` 和 `TaskReminderMiddleware`。

#### A.6 `Msg` 构造按 role 严格校验（运行抛异常）

`Msg` 现在在构造时按 `role` 对 `content` 做校验：

- `USER` —— 仅允许 `TextBlock` / `DataBlock` / `ImageBlock` / `AudioBlock` / `VideoBlock`
- `SYSTEM` —— 仅允许 `TextBlock`
- `ASSISTANT` —— 不限制

v1 中容忍的非法组合（例如 `USER` 携带 `ToolUseBlock`）现在会在构造时直接抛异常。推荐改用 role 子类 `UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResultMessage`，在调用处就显式表达 role 与 content 的对应关系。详见 → [消息与事件](building-blocks/message-and-event.md)

#### A.7 Agent 完全无状态（架构变更）

`ReActAgent` 现在是 **完全无状态** 的——实例本身不持有任何可变的"当前会话"状态。所有 per-call 可变状态（`AgentState`、`PermissionEngine`、事件 sink）封装在内部 `CallExecution` 对象中，通过 Reactor Context 在调用链上透传。同一个 Agent 实例可以安全地并发服务多个 `(userId, sessionId)` 组合，不同 session 的调用互不干扰。

**v1 → v2 影响**：

| 已移除 | 替代方案 |
|---|---|
| `ReActAgent.getCurrentSessionId()` | `RuntimeContext.getSessionId()` |
| `ReActAgent.getCurrentUserId()` | `RuntimeContext.getUserId()` |
| `AgentBase(name, desc, checkRunning, hooks)` 构造器 | `AgentBase(name, desc, hooks)` |
| `ReActAgent.getState()` | `ReActAgent.getAgentState()` 或 `getAgentState(userId, sessionId)` |

`isCheckRunning()` 仍可调用（返回 `false`），`Builder.checkRunning(boolean)` 仍可调用（被忽略），均已标 `@Deprecated`。

---

### Part B —— 推荐迁移（`@Deprecated(forRemoval = true)`，仍可调用）

本节列出在 2.0 中仍可调用、但已标记 `@Deprecated(forRemoval = true)` 的 API。为保证平滑升级，这些 API 在 2.0.x 期间会持续保留，将在未来大版本（如 2.1）中逐步清理。可以按节奏迁移，但建议尽早。

#### B.1 `SkillBox` → SkillRepository

- `SkillBox` 类与 `Builder.skillBox(SkillBox)` 均标 `@Deprecated(forRemoval = true, since = "2.0.0")`
- 新方式：通过 `AgentSkillRepository`（内置 `ClasspathSkillRepository`、`FileSystemSkillRepository`）注入技能，使用 `Builder.skillRepository(...)` / `.skillRepositories(...)`。只要注册了至少一个 repository，`DynamicSkillMiddleware` 会自动安装，在每次 `call()` 前重建 skill prompt
- 细粒度过滤：`Builder.skillFilter(SkillFilter)`

详见 → [技能](harness/skill.md)

#### B.2 Hook → Middleware

整个 `io.agentscope.core.hook` 包 —— 包括 `Hook` 接口、`HookEvent`、`HookEventType` 与所有 `*Event` 类 —— 均标 `@Deprecated(forRemoval = true, since = "2.0.0")`。原有 import 仍能编译，`Builder.hook(...)` / `.hooks(...)` 仍可调用（由 `LegacyHookDispatcher` 桥接），v1 代码不会立刻 break。推荐改用 `io.agentscope.core.middleware`：

- `MiddlewareBase` 提供 5 个 stage：洋葱型 `onAgent` / `onReasoning` / `onActing` / `onModelCall`，管道型 `onSystemPrompt`
- Builder：`.middleware(MiddlewareBase)` 与 `.middlewares(List<? extends MiddlewareBase>)`
- 内置：`TaskReminderMiddleware`（与 `TodoTools` 配合，在每个 reasoning step 前注入任务提醒）

详见 → [Middleware](building-blocks/middleware.md)

#### B.3 `Memory` → `AgentStateStore` + `AgentState`

- `io.agentscope.core.memory.Memory` 接口与所有实现（`InMemoryMemory`、`LongTermMemory` 等）均标 `@Deprecated(forRemoval = true, since = "2.0.0")`
- `Memory` 不再 `extends StateModule`；新增 `saveTo(AgentStateStore, userId, sessionId)` / `loadFrom(AgentStateStore, userId, sessionId)` 作桥接，方便现有实现继续通过 `AgentStateStore` 走持久化
- 新模型：
  - **会话历史**保存在 `AgentState.getContext()`
  - **持久化**通过 `AgentStateStore` 抽象（内置 `InMemoryAgentStateStore`、`JsonFileAgentStateStore`），按 `(userId, sessionId)` 二元组分桶
  - Builder 链：`.stateStore(AgentStateStore)` —— `AgentState` 在每次 `call()` 后自动 save/load，按该次调用 `RuntimeContext` 的 `(userId, sessionId)` 寻址

详见 → [上下文](building-blocks/context.md)

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

- **方法（`forRemoval = true`，将在未来大版本如 2.1 中清理）**
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

#### B.7 core 内置 Shell / File 工具：不再 deprecated

- `io.agentscope.core.tool.coding.*`（`ShellCommandTool`、`CommandValidator`、`UnixCommandValidator`、`WindowsCommandValidator`）与 `io.agentscope.core.tool.file.*`（`ReadFileTool`、`WriteFileTool`、`FileToolUtils`）自 2.0.0-RC1 起**不再标 `@Deprecated`**
- 这些工具直接在宿主机进程上执行命令和读写文件。对于不需要 workspace / 沙箱隔离的 `ReActAgent` 用户，它们是给 agent 添加 shell 和文件访问能力的推荐方式：

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ReadFileTool("/path/to/base/dir"));
toolkit.registerTool(new WriteFileTool("/path/to/base/dir"));
toolkit.registerTool(new ShellCommandTool());

ReActAgent agent = ReActAgent.builder()
    .toolkit(toolkit)
    /* ... */
    .build();
```

- 对于 `HarnessAgent` 用户，harness 模块自带 workspace 感知的文件和 shell 工具（`read_file`、`write_file`、`execute` 等），提供统一的本地 / Docker / 云沙箱后端、权限隔离、读写缓存、HITL 审批，推荐在需要 workspace 集成的场景下使用 harness 内置工具

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

- 新包：`io.agentscope.core.credential` —— 共享 credential 契约与 `ModelCard`；特定模型提供商的 credential 随对应模型扩展模块提供
- `ModelRegistry`：在对应模型扩展模块位于 classpath 时，按 `"provider:model"` 字符串解析（如 `dashscope:qwen-max`、`openai:gpt-5`）
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
- `HarnessAgent.Builder.fromAgent(ReActAgent)` —— 把 ReActAgent 迁到 HarnessAgent 的辅助方法。在 `ReActAgent.Builder.fromAgent` 的 7 个字段之上额外继承 ReActAgent 上**所有可观察的配置**：`stateStore` / `defaultSessionId`、`ModelConfig`（`maxRetries` / `fallbackModel`）、`ReactConfig.stopOnReject`、`modelExecutionConfig` / `toolExecutionConfig` / `toolExecutionContext`、`enablePendingToolRecovery`、`checkRunning`、`permissionContext`、`middlewares`、`hooks`。`enableMetaTool` / `enableTaskList` 不复制（这两个是 Builder-time 工具注册开关，toolkit copy 已经把它们注册的工具带过来了）。harness 独有的 workspace / filesystem / subagent / skill / plan mode / 各 `disable*` 等仍需手动设置。javadoc 里有完整列表
- **ReActAgent 新增 getter 以支撑上述迁移**：`getModelExecutionConfig()` / `getToolExecutionConfig()` / `getToolExecutionContext()` / `isPendingToolRecoveryEnabled()` / `getPermissionContext()`（位于 `ReActAgent`）；`isCheckRunning()`（位于 `AgentBase`，已弃用，始终返回 `false`）

详见 → [智能体](building-blocks/agent.md)

### Memory / Compaction 独立模型

`MemoryConfig` 和 `CompactionConfig` 新增 `.model(Model)` / `.model(String)` builder 方法，允许为记忆提取（flush）、记忆整理（consolidation）和上下文压缩（compaction）指定独立于 agent 主模型的轻量模型。不设则 fallback 到 agent 主模型（保持原行为）。

```java
HarnessAgent.builder()
    .model("openai:o3")
    .memory(MemoryConfig.builder()
        .model("openai:gpt-4.1-mini")
        .build())
    .compaction(CompactionConfig.builder()
        .model("openai:gpt-4.1-mini")
        .build())
    .build();
```
