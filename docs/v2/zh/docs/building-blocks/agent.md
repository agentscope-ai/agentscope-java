---
title: "智能体"
description: "了解如何在 AgentScope Java 2.0 中定义和配置智能体"
---

## 概述

`Agent`（接口位于 `io.agentscope.core.agent.Agent`，默认实现是 `ReActAgent`）是 AgentScope 的核心抽象——一个推理-行动循环引擎，将模型、工具、权限系统、人机交互、上下文管理、中间件、状态管理和事件系统整合到一个统一接口中。

其主要职责包括：

- 接收输入消息或事件，调用工具完成任务
- 管理上下文（会话历史保存在 `AgentState.getContext()` 中，可通过 `Session` 自动持久化）
- 在关键生命周期阶段提供中间件钩子，支持自定义逻辑
- 自动管理并发和串行工具执行

### 核心接口

`Agent` 接口由三个能力接口组合而成：`CallableAgent`、`StreamableAgent`、`ObservableAgent`。最常用的方法如下：

| 方法 | 描述 |
|------|------|
| `call(List<Msg>)` / `call(List<Msg>, RuntimeContext)` | 运行推理-行动循环，返回 `Mono<Msg>` |
| `streamEvents(List<Msg>)` / `streamEvents(Msg)` | 同 `call`，但以流式方式逐一产出 `AgentEvent` 对象 |
| `observe(Msg)` / `observe(List<Msg>)` | 将消息添加到上下文，不触发推理（返回 `Mono<Void>`） |

`ReActAgent` 在此之上还提供 `call(msgs, structuredOutputClass, runtimeContext)` 等结构化输出重载，以及通过 `RuntimeContext` 传递 per-call 元数据的便捷入口。

### 主循环

智能体在每次 `call` 调用时运行推理-行动循环，下图展示了主要控制流程：

```{mermaid}
flowchart TD
    A([输入: 消息 / 事件]) --> B{等待\n外部事件?}
    B -- 是 --> C[处理事件\n更新工具状态]
    B -- 否 --> D[将消息添加到上下文]
    C --> E
    D --> E

    E{检查下一步动作} -- 退出 --> F([返回: 等待\n外部交互])
    E -- 推理 --> G[必要时压缩上下文]
    G --> H[LLM 调用]
    H -- 无工具调用 --> I([返回最终消息])
    H -- 有工具调用 --> Acting

    subgraph Acting [行动]
        direction TB
        J[批量工具调用\n串行 / 并发] --> L[执行工具调用]
        L --> M{权限\n检查}
        M -- 允许 --> N[运行工具 → 结果]
        M -- 询问 / 外部 --> O([暂停并发出\nRequireUserConfirmEvent])
        M -- 拒绝 --> P[将错误结果返回 LLM]
    end

    N --> E
    P --> E
```

## 配置智能体

通过 `ReActAgent.builder()...build()` 创建智能体。`.model(...)` 既接受 `ModelRegistry` 解析的字符串 id（最常用、自动读取 env），也接受手动 builder 构造的 `Model` 实例（需要精细控制超时、自定义 endpoint 时用）。

::::{tab-set}
:::{tab-item} 字符串 model id（推荐）
```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("你是一个有帮助的助手。")
                // 由 ModelRegistry 解析；自动读取 DASHSCOPE_API_KEY
                // 切换其他厂商时改成 "openai:gpt-5.5" / "anthropic:claude-sonnet-4-5"
                // / "gemini:gemini-2.0-flash" / "ollama:llama3" 即可。
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .build();
```
:::
:::{tab-item} 显式 Model builder
```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("你是一个有帮助的助手。")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey("YOUR_API_KEY")
                                .modelName("qwen-max")
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(new Toolkit())
                .build();
```
:::
:::{tab-item} 配置 Toolkit / MCP
```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new TodoTools());          // 通过反射注册带 @Tool 的方法
toolkit.registerTool(new MyCustomTools());      // 自定义工具类（带 @Tool 注解的方法）

McpClientWrapper amap = McpClientBuilder.streamableHttp()
        .name("amap")
        .url("https://mcp.amap.com/mcp?key=" + System.getenv("AMAP_API_KEY"))
        .build();
toolkit.registerMcpClient(amap).block();

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("你是一个有帮助的助手。")
                .model("dashscope:qwen-max")
                .toolkit(toolkit)
                .build();
```
:::
::::

:::{tip}
`ModelRegistry` 的字符串形式（`<provider>:<model>`）支持 `dashscope` / `openai` / `anthropic` / `gemini` / `ollama`，会自动从环境变量读取 API key（`DASHSCOPE_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY`）。需要在长期运行场景下同时获得工作区、会话持久化、记忆压缩、子 agent 等能力，请改用 [`HarnessAgent`](../harness/architecture.md) —— 它对 `ReActAgent` 做了一层薄包装，builder 接口大体一致。
:::

### 参数说明

| 参数 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `name` | `String` | 必填 | 智能体标识符，用于消息和日志 |
| `sysPrompt` | `String` | 必填 | 智能体的基础系统提示词 |
| `model` | `Model` | 必填 | 用于推理的大语言模型（继承自 `ChatModelBase`） |
| `toolkit` | `Toolkit` | `new Toolkit()` | 管理工具、MCP 客户端、技能和工具组 |
| `middlewares` | `List<? extends MiddlewareBase>` | `List.of()` | 应用于 agent / reasoning / acting / model call / system prompt 钩子 |
| `session` + `sessionKey` | `Session` + `SessionKey` | `null`（不持久化） | 配置后 agent 在每次 `call` 后自动加载/保存 `AgentState` |
| `permissionContext` | `PermissionContextState` | 默认 `DEFAULT` 模式 | 工具执行的细粒度规则，参见 [权限系统](./permission-system.md) |
| `modelConfig` | `ModelConfig` | 默认值 | 模型重试次数和备用模型 |
| `reactConfig` | `ReactConfig` | 默认值 | 最大迭代次数和拒绝处理方式 |
| `maxIters` | `int` | `10` | ReAct 主循环最大迭代次数（也可放在 `reactConfig` 中） |

## 运行智能体

`call` 和 `streamEvents` 都接受相同的输入消息列表，驱动相同的推理-行动循环，区别在于结果的交付方式。

### call

`call` 在内部消费所有事件，当智能体完成或因外部交互暂停时返回最终 `Msg`。

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;

UserMessage msg = new UserMessage("当前目录有哪些文件？");
Msg result = agent.call(List.of(msg), RuntimeContext.empty()).block();
System.out.println(result.getTextContent());
```

### streamEvents

`streamEvents` 逐一产出 `AgentEvent` 对象，让你实时将文本输出、工具调用进度和生命周期事件流式传输给用户。按 `event.getType()` 分发即可针对每类事件做不同处理：

```java
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

agent.streamEvents(new UserMessage("总结一下 README 的内容。"))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                // 模型返回的流式文本片段 —— 追加到界面或标准输出
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                // 智能体即将调用工具 —— 展示调用信息
                System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolName());
            }
            // 其他事件：思考块、工具结果、回复结束等
        })
        .blockLast();
```

完整事件类型与字段参考 [消息与事件](./message-and-event.md)。

### observe

使用 `observe` 将消息注入智能体上下文而不触发 reply——适用于多智能体场景中，一个智能体需要观察另一个智能体输出的情况。

```java
agent.observe(otherAgentMsg).block();
```

## RuntimeContext（per-call 上下文）

`RuntimeContext`（`io.agentscope.core.agent.RuntimeContext`）是 **per-call 元数据袋**：每次 `call` / `stream` 把一份实例传进去，agent 在执行期间把它绑定到自身，下游的工具、middleware、hook 都能读到同一份引用；调用结束后自动解绑。

它**不是**持久化状态——`AgentState`（聊天上下文、压缩摘要、权限规则、tool state）才是。`RuntimeContext` 的作用是承载「当前这一次调用」相关的瞬态数据：tenant / userId / request-id、DB 连接、审计 logger、特性开关，等等。

### 内置字段与属性层

`RuntimeContext` 有三类「槽位」：

| 槽位 | 设置方式 | 读取方式 |
|------|---------|---------|
| 会话字段 | `sessionId(String)` / `userId(String)` / `sessionKey(SessionKey)` | `getSessionId()` / `getUserId()` / `getSessionKey()` |
| 字符串属性（任意 key-value） | `put(String key, Object value)` | `<T> T get(String key)` |
| 类型化属性（按 `Class<T>` 注入业务 POJO） | `put(Class<T> type, T value)` / `put(String key, Class<T> type, T value)` | `<T> T get(Class<T> type)` / `<T> T get(String key, Class<T> type)` |

类型化属性是给 tool 用的——`@Tool` 方法里声明同类型参数即可被框架自动注入，详见 [Tool — 接收 Context](./tool.md#接收-context)。字符串属性通常用于内部协调（例如 middleware 之间传值）。两层互不串扰：类型化层放进去的对象不会出现在 `getExtra()` 里，反之亦然。

### 构造并传入

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.SimpleSessionKey;
import java.util.List;

RuntimeContext ctx =
        RuntimeContext.builder()
                .userId("alice")
                .sessionId("session-001")
                .sessionKey(SimpleSessionKey.of("alice:assistant:session-001"))
                .put("request_id", "req-abc-123")          // 字符串层
                .put(UserContext.class, new UserContext("alice", "en"))  // 类型层（业务 POJO）
                .build();

Msg result = agent.call(List.of(new UserMessage("Hi.")), ctx).block();
```

`ReActAgent` 提供 `call` / `stream` 的 `RuntimeContext` 重载；`streamEvents` 未直接重载，需要传 context 时改用 `stream(msgs, options, ctx)` 或先在 builder 上配置全局 `toolExecutionContext`。不传 context 时框架使用 `RuntimeContext.empty()`，会话字段为 `null`，属性表为空。

### 谁能读到

- **Tool**（`@Tool` 方法或 `ToolBase.callAsync`）—— 见 [Tool — 接收 Context](./tool.md#接收-context)。
- **Middleware**（`MiddlewareBase` 所有 hook）—— 通过 `agent.getRuntimeContext()` 取当前实例。详见 [Middleware — 读取 RuntimeContext](./middleware.md#读取-runtimecontext)。
- **同一次调用的所有线程**—— `RuntimeContext` 内部使用 `ConcurrentMap`，hook / tool 之间可以读写同一实例做协调。

### 与持久化的关系

- `RuntimeContext` 的字段**不会**进 `AgentState`，也不会被 `Session` 写回磁盘。
- `sessionKey` 字段是给业务层使用的便利字段；agent 自身依然以 builder 上配置的 `sessionKey()` 作为持久化 key，运行时 `RuntimeContext.sessionKey()` 不会切换持久化目标。

完整示例：`agentscope-examples/documentation/.../context/RuntimeContextExample.java`、`tool/ToolExecutionContextExample.java`。

:::{note}
存在一个旧的 `ToolExecutionContext`（`io.agentscope.core.tool`），已标记 `@Deprecated`，新代码统一使用 `RuntimeContext`。它在底层会被自动桥接到 `RuntimeContext.asToolExecutionContext()`，老代码不会立即失效。
:::

## 人机交互

当智能体遇到以下两种情况时，会暂停执行并发出特殊事件：需要**用户确认**的工具调用（权限系统返回 ASK），或标记为**外部执行**的工具（结果必须来自智能体外部）。两种情况下，都可以通过把结果事件再次喂给 agent 的下一次 `call` 来恢复执行。

### 用户确认

当权限系统判断某个工具调用需要用户批准时，智能体会发出 `RequireUserConfirmEvent` 并暂停。

**1. 接收 `RequireUserConfirmEvent`** —— 用 `streamEvents` 监听暂停。事件携带 `getReplyId()`（用于恢复）和 `getToolCalls()` —— 一组 `ToolUseBlock`，每个暴露 `getId()` / `getName()` / `getInput()` / `getSuggestedRules()`。

```java
import io.agentscope.core.event.RequireUserConfirmEvent;

agent.streamEvents(msg)
        .doOnNext(event -> {
            if (event instanceof RequireUserConfirmEvent confirm) {
                confirm.getToolCalls().forEach(tc -> {
                    System.out.println("工具: " + tc.getName() + ", 输入: " + tc.getInput());
                    System.out.println("建议规则: " + tc.getSuggestedRules());
                });
            }
        })
        .blockLast();
```

**2. 构建确认结果** —— 为每个待处理工具调用构造一个 `ConfirmResult`。可以在传回前修改工具输入，或接受 suggested rules 让今后相同的调用自动放行：

```java
import io.agentscope.core.event.ConfirmResult;
import java.util.ArrayList;
import java.util.List;

List<ConfirmResult> confirmResults = new ArrayList<>();
for (var tc : confirmEvent.getToolCalls()) {
    confirmResults.add(
            new ConfirmResult(
                    /* confirmed = */ true,                  // false 表示拒绝
                    /* toolCall  = */ tc,                    // 传回（可选择修改）
                    /* rules     = */ tc.getSuggestedRules() // 接受规则 → 未来调用自动放行
                    ));
}
```

**3. 恢复智能体** —— 将 `confirmResults` 通过 metadata 传给下一次 `call`：

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;

UserMessage resumeMsg =
        UserMessage.builder()
                .metadata(java.util.Map.of(
                        Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();

Msg result = agent.call(List.of(resumeMsg), RuntimeContext.empty()).block();
```

- **已确认**的工具调用立即执行，智能体继续推理。
- **已拒绝**的工具调用会产生 LLM 可见的错误结果，LLM 可能会用不同方式重试。
- **已接受的规则**会持久化到权限引擎中——匹配的未来调用将自动允许，无需再次提示。

### 外部工具执行

当智能体调用 `isExternalTool() == true` 的工具时，会发出 `RequireExternalExecutionEvent` 并暂停。工具的逻辑在智能体外部运行——通常由人工操作员或外部系统执行。

**1. 接收 `RequireExternalExecutionEvent`** —— 结构与用户确认一致：`getReplyId()` 加一组等待外部执行的 `getToolCalls()`。

```java
import io.agentscope.core.event.RequireExternalExecutionEvent;

agent.streamEvents(msg)
        .doOnNext(event -> {
            if (event instanceof RequireExternalExecutionEvent ext) {
                ext.getToolCalls().forEach(tc ->
                        System.out.println("外部执行: " + tc.getName() + "(" + tc.getInput() + ")"));
            }
        })
        .blockLast();
```

**2. 外部执行并构建结果** —— 在智能体外部完成操作，把每个结果封装为 `ToolResultBlock`：

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.ArrayList;
import java.util.List;

List<ToolResultBlock> executionResults = new ArrayList<>();
for (var tc : externalEvent.getToolCalls()) {
    String output = runExternalOperation(tc.getName(), tc.getInput());
    executionResults.add(
            ToolResultBlock.builder()
                    .id(tc.getId())
                    .name(tc.getName())
                    .output(List.of(TextBlock.builder().text(output).build()))
                    .state(ToolResultState.SUCCESS)
                    .build());
}
```

**3. 恢复智能体** —— 将结果作为下一次 `call` 的输入消息回传。结果会被注入智能体上下文，推理从中断处继续。完整示例见 `agentscope-examples/documentation/.../hitl/InterruptionExample.java`。

:::{tip}
构建交互式 UI 时使用 `streamEvents`——它可以实时检测暂停事件并立即提示用户。以编程方式处理事件的自动化流程则使用 `call`。完整可运行示例见 `agentscope-examples/documentation/.../hitl/PermissionHITLExample.java`。
:::

## 配置和使用 Session

`AgentState` 是 agent 的全部可恢复状态——对话上下文、压缩摘要、权限规则、工具状态和当前 reply 位置。`Session` 是它的存储抽象。

**只需在 builder 上配 `session(...)` 与 `sessionKey(...)` 两项，agent 就会自动持久化与恢复**：每次 `call` 结束把 `AgentState` 写回，下次同 key 启动时自动加载。

```java
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.SimpleSessionKey;
import java.nio.file.Paths;

ReActAgent agent = ReActAgent.builder()
        .name("my_agent")
        .sysPrompt("你是一个有帮助的助手。")
        .model(model)
        .toolkit(new Toolkit())
        .session(new JsonSession(Paths.get(System.getProperty("user.home"), ".agentscope/sessions")))
        .sessionKey(SimpleSessionKey.of("user_123:agent_456:session_789"))
        .build();

// 启动时已自动加载（如该 key 有历史数据）
int loaded = agent.getState().getContext().size();

// 完成后自动持久化
agent.call(List.of(new UserMessage("继续之前的任务。"))).block();
```

内置与扩展实现：

| 实现 | 模块 | 适用 |
|------|------|------|
| `InMemorySession` | `agentscope-core` | 单元测试 / 单进程 demo |
| `JsonSession` | `agentscope-core` | 单机开发，按 `SessionKey` 分目录落 JSON |
| `RedisSession` | `agentscope-extensions-session-redis` | 多副本生产，跨进程跨机器共享 |
| `MysqlSession` | `agentscope-extensions-session-mysql` | 需要落关系型库（审计 / 报表） |

`SessionKey` 默认实现 `SimpleSessionKey.of(id)` 已经够用；要按 `(userId, agentId, sessionId)` 多维分桶就自实现 `SessionKey` 接口。

通过 `agent.getAgentState()` 可以读写当前快照（旁路场景，如管理台、审计）：

```java
AgentState state = agent.getAgentState();
state.getContext().size();         // 当前对话消息数
String json = state.toJson();      // 序列化为 JSON
```

完整字段、跨节点接续、与压缩 / Plan Mode / 子 agent 的协作细节见 [Harness — Context](../harness/context.md)。

## 延伸阅读

::::{grid} 2

:::{grid-item-card} 权限系统
:link: ./permission-system.html

控制智能体可以调用哪些工具以及在什么条件下调用。
:::

:::{grid-item-card} 中间件
:link: ./middleware.html

在 agent、reasoning、acting 和 model call 钩子处拦截和修改智能体行为。
:::

::::
