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

通过 `ReActAgent.builder()...build()` 创建智能体，下面给出最常见的两种配置场景。

::::{tab-set}
:::{tab-item} 最简配置
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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
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
                .model(
                        DashScopeChatModel.builder()
                                .apiKey("YOUR_API_KEY")
                                .modelName("qwen-max")
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .build();
```
:::
::::

### 参数说明

| 参数 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `name` | `String` | 必填 | 智能体标识符，用于消息和日志 |
| `sysPrompt` | `String` | 必填 | 智能体的基础系统提示词 |
| `model` | `Model` | 必填 | 用于推理的大语言模型（继承自 `ChatModelBase`） |
| `toolkit` | `Toolkit` | `new Toolkit()` | 管理工具、MCP 客户端、技能和工具组 |
| `middlewares` | `List<? extends MiddlewareBase>` | `List.of()` | 应用于 agent / reasoning / acting / model call / system prompt 钩子 |
| `session` + `sessionKey` | `Session` + `SessionKey` | `null`（不持久化） | 配置后 agent 在每次 `call` 后自动加载/保存 `AgentState` |
| `permissionContext` | `PermissionContextState` | 默认 `DEFAULT` 模式 | 工具执行的细粒度规则，参见 [权限系统](/zh/v2/building-blocks/permission-system) |
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

`streamEvents` 逐一产出 `AgentEvent` 对象，让你实时将文本输出、工具调用进度和生命周期事件流式传输给用户。

```java
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;

agent.streamEvents(new UserMessage("总结一下 README 的内容。"))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            }
        })
        .blockLast();
```

### observe

使用 `observe` 将消息注入智能体上下文而不触发 reply——适用于多智能体场景中，一个智能体需要观察另一个智能体输出的情况。

```java
agent.observe(otherAgentMsg).block();
```

## 人机交互

当智能体遇到以下两种情况时，会暂停执行并发出特殊事件：需要**用户确认**的工具调用（权限系统返回 ASK），或标记为**外部执行**的工具（结果必须来自智能体外部）。两种情况下，都可以通过把结果事件再次喂给 agent 的下一次 `call` 来恢复执行。

### 用户确认

当权限系统判断某个工具调用需要用户批准时，智能体会发出 `RequireUserConfirmEvent` 并暂停。

### 接收 RequireUserConfirmEvent

使用 `streamEvents` 检测暂停。事件结构如下：

    - **`getReplyId()` · `String` · *required*** — 当前 reply 的 ID，用于恢复智能体。
    - **`getToolCalls()` · `List<ToolUseBlock>` · *required*** — 等待用户确认的工具调用列表。每个 `ToolUseBlock` 包含：

      :::{dropdown} 详情
      - **`getId()` · `String`** — 此工具调用的唯一标识符。
      - **`getName()` · `String`** — 工具名称（如 `"todo_write"`、`"my_tool"`）。
      - **`getInput()` · `Map<String, Object>`** — 解析后的输入参数。
      - **`getSuggestedRules()` · `List<PermissionRule>`** — 自动生成的权限规则，用户可接受以允许类似的未来调用。
      :::

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

  ### 构建确认结果

为每个待处理的工具调用创建 `ConfirmResult`，指明是否允许执行。也可以修改工具调用输入或接受建议的权限规则：

    ```java
    import io.agentscope.core.event.ConfirmResult;
    import java.util.ArrayList;
    import java.util.List;

    List<ConfirmResult> confirmResults = new ArrayList<>();
    for (var tc : confirmEvent.getToolCalls()) {
        confirmResults.add(
                new ConfirmResult(
                        /* confirmed = */ true,                 // false 表示拒绝
                        /* toolCall  = */ tc,                   // 传回（可选择修改）
                        /* rules     = */ tc.getSuggestedRules() // 接受规则以便未来自动允许
                        ));
    }
    ```

  ### 恢复智能体

将 `confirmResults` 通过 metadata 传给下一次 `call`：

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

    - **已确认**的工具调用立即执行，智能体继续推理
    - **已拒绝**的工具调用会产生 LLM 可见的错误结果，LLM 可能会用不同方式重试
    - **已接受的规则**会持久化到权限引擎中——匹配的未来调用将自动允许，无需再次提示

### 外部工具执行

当智能体调用 `isExternalTool() == true` 的工具时，会发出 `RequireExternalExecutionEvent` 并暂停。工具的逻辑在智能体外部运行——通常由人工操作员或外部系统执行。

### 接收 RequireExternalExecutionEvent

事件结构如下：

    - **`getReplyId()` · `String` · *required*** — 当前 reply 的 ID，用于恢复智能体。
    - **`getToolCalls()`** — " required>
      需要外部执行的工具调用列表。

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

  ### 外部执行并构建结果

在智能体外部执行操作，并将结果封装为 `ToolResultBlock` 对象：

    ```java
    import io.agentscope.core.message.TextBlock;
    import io.agentscope.core.message.ToolResultBlock;
    import io.agentscope.core.message.ToolResultState;
    import io.agentscope.core.event.ExternalExecutionResultEvent;
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

  ### 恢复智能体

传回 `ExternalExecutionResultEvent` 对应的 message 以恢复智能体。具体可参考 `agentscope-examples/documentation/.../hitl/InterruptionExample.java`。结果会被注入智能体上下文，推理从中断处继续。

:::{tip}
构建交互式 UI 时使用 `streamEvents`——它可以实时检测暂停事件并立即提示用户。以编程方式处理事件的自动化流程则使用 `call`。完整可运行示例见 `agentscope-examples/documentation/.../hitl/PermissionHITLExample.java`。
:::

## 持久化智能体状态

`AgentState` 是 agent 的全部可恢复状态——对话上下文、压缩摘要、权限规则、工具状态和当前 reply 位置。它实现 `State` 接口，可序列化为 JSON 并存储在任意后端。

`Session` 接口是状态存储的抽象，内置两种实现：

| 实现 | 描述 |
|------|------|
| `InMemorySession` | 进程内 Map，适合单元测试 |
| `JsonSession` | 文件系统 JSON 持久化，按 `SessionKey` 分目录 |

将 `Session` 与 `SessionKey` 配置到 builder 后，agent 会自动在每次 `call` 完成时把 `AgentState` 写回；启动时如果对应 key 已有数据，则自动加载。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class PersistDemo {
    public static void main(String[] args) {
        Path sessionDir = Paths.get(System.getProperty("user.home"), ".agentscope", "sessions");
        Session session = new JsonSession(sessionDir);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("my_agent")
                        .sysPrompt("你是一个有帮助的助手。")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(new Toolkit())
                        .session(session)
                        .sessionKey(SimpleSessionKey.of("user_123:agent_456:session_789"))
                        .build();

        // 启动时如果 sessionKey 对应的数据已存在，会自动加载到 AgentState
        int loaded = agent.getState().getContext().size();
        System.out.println("loaded " + loaded + " message(s) from session");

        // 执行一轮 reply —— 完成后自动持久化
        Msg result =
                agent.call(List.of(new UserMessage("继续之前的任务。"))).block();
        System.out.println(result.getTextContent());
    }
}
```

:::{note}
`SessionKey` 默认实现 `SimpleSessionKey` 接受单一 ID 字符串；如需 `(userId, agentId, sessionId)` 这样的多维分桶，自定义实现 `SessionKey` 接口即可（参见 javadoc 示例）。
:::

## 延伸阅读

::::{grid} 2

:::{grid-item-card} 权限系统
:link: /zh/v2/building-blocks/permission-system

控制智能体可以调用哪些工具以及在什么条件下调用。
:::
  :::{grid-item-card} 中间件
:link: /zh/v2/building-blocks/middleware

在 agent、reasoning、acting 和 model call 钩子处拦截和修改智能体行为。
:::

::::
