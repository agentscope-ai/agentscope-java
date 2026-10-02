---
title: "消息与事件"
description: "智能体通信，与前端流式数据传输"
---

消息（Message）与事件（Event）是 AgentScope 中两种基础数据结构。

- **消息** — 智能体间通信与持久化的基本单元。每个 `Msg` 代表一个完整的对话轮次，存储在上下文中并在智能体之间传递。
- **事件** — 前端交互与流式传输的基本单元。事件携带增量进度更新（文本 token、工具调用片段、权限请求等），驱动实时界面和人工介入工作流。

单次 `call` 调用产生的事件序列最终汇聚成恰好一条 assistant `Msg`，这保证了完整的消息状态始终可以从事件流中还原。

## 消息

`Msg`（位于 `io.agentscope.core.message`）代表对话中的一个轮次——用户输入、智能体回复或系统指令，内容以有序的类型化块（`ContentBlock`）列表表示。

:::{tip}
一条 assistant 消息对应智能体一次完整的 `call` 周期（反复推理和执行，直到产出最终回复）。
:::

### 结构

`Msg` 类的核心字段（getter）如下：

| 方法 | 类型 | 说明 |
|------|------|------|
| `getId()` | `String` | 唯一消息标识符 |
| `getName()` | `String` | 发送方名称（可空） |
| `getRole()` | `MsgRole` | `USER` / `ASSISTANT` / `SYSTEM` / `TOOL` |
| `getContent()` | `List<ContentBlock>` | 有序内容块列表（不可变） |
| `getMetadata()` | `Map<String, Object>` | 任意键值元数据 |
| `getTimestamp()` | `String` | 创建时间（`yyyy-MM-dd HH:mm:ss.SSS`） |
| `getUsage()` | `ChatUsage` | Token 用量（仅 assistant 消息） |
| `getGenerateReason()` | `GenerateReason` | 退出原因：`MODEL_STOP` / `TOOL_SUSPENDED` / `REASONING_STOP_REQUESTED` / `ACTING_STOP_REQUESTED` / `INTERRUPTED` / `MAX_ITERATIONS` |

### 内容块

消息内容由类型化的块组成，每种块代表一类独立信息。块类位于 `io.agentscope.core.message`：

| 块类型 | 说明 | 允许出现在 |
|--------|------|-----------|
| `TextBlock` | 纯文本内容 | USER、ASSISTANT、SYSTEM |
| `DataBlock` | 二进制数据（图片、音频、视频），通过 base64 或 URL；统一替代旧的 ImageBlock/AudioBlock/VideoBlock | USER、ASSISTANT |
| `ImageBlock` / `AudioBlock` / `VideoBlock` | 旧版具体多媒体块（仍兼容，新代码建议用 `DataBlock`） | USER |
| `ThinkingBlock` | 模型推理过程（思维链） | ASSISTANT |
| `ToolUseBlock` | 工具调用，包含 `id` / `name` / `input` / `state`（`ToolCallState`） | ASSISTANT |
| `ToolResultBlock` | 工具执行结果，包含 `state`（`ToolResultState`） | ASSISTANT |
| `HintBlock` | 以用户上下文形式注入循环的指令 | ASSISTANT |

:::{note}
角色约束在构造时强制执行：`USER` 消息只能包含 text/data/image/audio/video 块；`SYSTEM` 消息只能包含 `TextBlock`；`ASSISTANT` 消息可包含所有块类型。
:::

### 创建消息

按 role 固定的子类提供便捷构造（`io.agentscope.core.message.UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResultMessage`）。当 content 是普通字符串时，会自动包装为 `TextBlock`。

```java
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;

// 用户消息 —— 文本
UserMessage userText = new UserMessage("user", "这张图片里有什么？");

// 多模态用户消息
UserMessage userMulti =
        new UserMessage(
                "user",
                TextBlock.builder().text("描述这张图片：").build(),
                DataBlock.builder()
                        .source(Base64Source.builder()
                                .data("...")
                                .mediaType("image/png")
                                .build())
                        .build());

// 系统消息 —— 仅文本
SystemMessage systemMsg = new SystemMessage("system", "你是一个有用的助手。");

// 助手消息 —— 允许所有块类型
AssistantMessage assistantMsg = new AssistantMessage("agent", "结果如下...");
```

需要更多可选字段（`metadata`、`timestamp`、`usage`、`generateReason`）时使用各子类的 `builder()`：

```java
UserMessage msg =
        UserMessage.builder()
                .name("user")
                .textContent("Hello")
                .build();
```

### 访问内容

`Msg` 提供了一组辅助方法用于提取特定块类型：

| 方法 | 返回值 |
|------|--------|
| `getTextContent()` | 所有 `TextBlock` 的拼接文本（按 `\n` 连接），无文本块时返回空字符串 |
| `getContentBlocks(Class<T>)` | 按类型过滤后的块列表 |
| `getFirstContentBlock(Class<T>)` | 首个匹配类型的块，无则返回 null |
| `hasContentBlocks(Class<T>)` | 若存在指定类型的块则返回 `true` |

```java
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;

// 获取所有文本内容
String text = msg.getTextContent();

// 获取所有工具调用
List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);

// 检查消息是否包含工具结果
if (msg.hasContentBlocks(ToolResultBlock.class)) {
    // ...
}
```

## 事件

事件是消息的流式对应物。智能体执行过程中会持续产出一系列 `AgentEvent` 对象（位于 `io.agentscope.core.event`），表示增量进度——文本 token 到达、工具调用逐步构建、结果流式返回。每个事件都是轻量且自包含的。

### 事件生命周期

每个事件都携带 `getReplyId()`，将其关联到正在构建的消息。在一次回复中，`getBlockId()` 或 `getToolCallId()` 用作事件关联键，表示事件属于同一个内容块生命周期。事件遵循 **start → delta → end** 模式：

```{mermaid}
sequenceDiagram
    participant Client
    participant Agent

    Agent->>Client: AgentStartEvent

    rect rgba(100, 150, 255, 0.1)
        Note over Client,Agent: 推理阶段
        Agent->>Client: ModelCallStartEvent
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: TextBlock (blockId)
            Agent->>Client: TextBlockStartEvent
            Agent->>Client: TextBlockDeltaEvent (×N)
            Agent->>Client: TextBlockEndEvent
        end
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: DataBlock (blockId)
            Agent->>Client: DataBlockStartEvent
            Agent->>Client: DataBlockDeltaEvent (×N)
            Agent->>Client: DataBlockEndEvent
        end
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: ToolUseBlock (toolCallId)
            Agent->>Client: ToolCallStartEvent
            Agent->>Client: ToolCallDeltaEvent (×N)
            Agent->>Client: ToolCallEndEvent
        end
        Agent->>Client: ModelCallEndEvent
    end

    rect rgba(100, 255, 150, 0.1)
        Note over Client,Agent: 执行阶段
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: ToolResultBlock (toolCallId)
            Agent->>Client: ToolResultStartEvent
            Agent->>Client: ToolResultTextDeltaEvent (×N)
            Agent->>Client: ToolResultDataDeltaEvent (×N)
            Agent->>Client: ToolResultEndEvent
        end
    end

    Agent->>Client: AgentEndEvent
```

同一次回复中的所有事件共享相同的 `replyId`。在回复内部，用 `blockId` 关联文本/思考/数据块事件，用 `toolCallId` 关联工具调用和工具结果事件。`blockId` 是 `replyId` 作用域内的关联键，不要求是全局唯一的随机 ID；当某类内容块在一次回复中最多出现一个生命周期时，实现可以使用稳定的类型标识（如文本块的固定标识）作为 `blockId`。

### 事件类型

所有事件继承自 `AgentEvent`（位于 `io.agentscope.core.event`），提供以下公共方法：

| 方法 | 类型 | 说明 |
|------|------|------|
| `getId()` | `String` | 唯一事件标识符 |
| `getCreatedAt()` | `String` | ISO 8601 时间戳 |
| `getType()` | `AgentEventType` | 事件类型枚举 |
| `getSource()` | `String` | 事件来源路径。顶层 Agent 为 `null`；子 Agent 事件为斜杠分隔的路径（如 `"main/researcher"`），用于区分父子 Agent 事件 |

事件按类别分组如下。除特别说明外，每个事件还携带 `getReplyId()`，关联到正在构建的消息。

  :::{dropdown} 生命周期事件
**AgentStartEvent** — 智能体开始新的回复。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |
    | `getSessionId()` | `String` | 会话 ID |
    | `getName()` | `String` | 智能体名称 |
    | `getRole()` | `String` | 智能体角色（默认 `"assistant"`） |

    **AgentEndEvent** — 智能体完成回复。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |

    **ExceedMaxItersEvent** — 智能体达到最大推理-执行迭代次数。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |

    **RequestStopEvent** — 中间件或工具发起的提前停止请求。
:::

  :::{dropdown} 文本流式事件
**TextBlockStartEvent** — 新的文本块开始。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |
    | `getBlockId()` | `String` | 文本块在当前回复中的关联键 |

    **TextBlockDeltaEvent** — 增量文本内容到达。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |
    | `getBlockId()` | `String` | 文本块在当前回复中的关联键 |
    | `getDelta()` | `String` | 增量文本内容 |

    **TextBlockEndEvent** — 文本块完成。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |
    | `getBlockId()` | `String` | 文本块在当前回复中的关联键 |
:::

  :::{dropdown} 思考流式事件
**ThinkingBlockStartEvent / ThinkingBlockDeltaEvent / ThinkingBlockEndEvent** —— 与文本流式事件结构对应，仅用于模型的思维链内容；`blockId` 同样表示当前回复中的关联键。
:::

  :::{dropdown} 数据流式事件
**DataBlockStartEvent / DataBlockDeltaEvent / DataBlockEndEvent** —— 与文本流式事件结构对应，承载图片 / 音频 / 视频等二进制数据：

    - `DataBlockStartEvent`：`getMediaType()` 返回 MIME 类型（如 `"image/png"`）。
    - `DataBlockDeltaEvent`：`getData()` 返回增量 base64 编码数据。
:::

  :::{dropdown} 工具调用流式事件
**ToolCallStartEvent** — 智能体开始一次工具调用。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |
    | `getToolCallId()` | `String` | 工具调用唯一标识符 |
    | `getToolCallName()` | `String` | 被调用的工具名称 |

    **ToolCallDeltaEvent** — 增量工具调用参数到达；`getDelta()` 返回 JSON 参数片段。

    **ToolCallEndEvent** — 工具调用参数完成。
:::

  :::{dropdown} 工具结果流式事件
**ToolResultStartEvent** — 工具开始执行（带 `toolCallId`、`toolCallName`）。

    **ToolResultTextDeltaEvent** — 工具的增量文本输出；`getDelta()` 返回文本片段。

    **ToolResultDataDeltaEvent** — 工具的二进制数据输出；与 `DataBlockDeltaEvent` 类似，包含 `mediaType` / `data` / `url` 字段。

    **ToolResultEndEvent** — 工具执行完成。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |
    | `getToolCallId()` | `String` | 对应工具调用的 ID |
    | `getState()` | `ToolResultState` | 最终状态：`SUCCESS`、`ERROR`、`INTERRUPTED`、`DENIED`、`RUNNING` |
:::

  :::{dropdown} 模型调用事件
**ModelCallStartEvent** — 模型 API 调用开始（带 `modelName`）。

    **ModelCallEndEvent** — 模型 API 调用完成（带 `inputTokens` / `outputTokens`）。
:::

  :::{dropdown} 人工介入事件
**RequireUserConfirmEvent** — 智能体暂停等待用户确认。

    | 方法 | 类型 | 说明 |
    |------|------|------|
    | `getReplyId()` | `String` | 回复消息 ID |
    | `getToolCalls()` | `List<ToolUseBlock>` | 待用户确认的工具调用列表 |

    **RequireExternalExecutionEvent** — 智能体暂停等待外部执行。

    **UserConfirmResultEvent** — 用户提供确认结果（输入事件）。携带 `List<ConfirmResult>`。

    **ExternalExecutionResultEvent** — 外部系统提供执行结果（输入事件）。携带 `List<ToolResultBlock>`。
:::

  :::{dropdown} 子 Agent 事件
**SubagentExposedEvent** — 通过 `agent_spawn(expose_to_user=true)` 生成的子 Agent 被暴露为用户可寻址的入口点。SSE / 流式消费端可据此在 UI 上渲染新的会话入口。

| 方法 | 类型 | 说明 |
|------|------|------|
| `getSubagentId()` | `String` | 子 Agent 的唯一标识 |
| `getAgentId()` | `String` | 子 Agent 的 agent 类型 ID |
| `getSessionId()` | `String` | 子 Agent 的会话 ID |
| `getLabel()` | `String` | 用户可见的标签名（可选） |
:::

## 从事件流重建消息

事件与消息并非相互独立，而是同一数据的两种视图。`streamEvents` 产出的事件流可以按 `replyId` / `blockId` / `toolCallId` 聚合还原成完整的 `AssistantMessage`。这保证了最终消息状态可以仅凭事件流完整还原。

可以参考 `agentscope-core` 中的 `agent/StreamingHook.java` 与 `agentscope-examples/documentation/.../streaming/AgentEventStreamExample.java`，它们演示了用 Reactor 算子按 block 分组并累积内容的标准做法。

```java
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;

StringBuilder accumulated = new StringBuilder();

agent.streamEvents(userMsg)
        .doOnNext(event -> {
            if (event instanceof AgentStartEvent start) {
                System.out.println("[start replyId=" + start.getReplyId() + "]");
            } else if (event instanceof TextBlockDeltaEvent delta) {
                accumulated.append(delta.getDelta());
            } else if (event instanceof ToolCallStartEvent tc) {
                System.out.println("[tool] " + tc.getToolCallName());
            } else if (event instanceof ToolResultEndEvent end) {
                System.out.println("[tool result state=" + end.getState() + "]");
            } else if (event instanceof AgentEndEvent end) {
                System.out.println("\n[end] full text:\n" + accumulated);
            }
        })
        .blockLast();
```

:::{tip}
这种设计让部署更加灵活：后端可以通过 SSE 把事件流推给前端，前端在客户端侧重建消息。即使连接中断，从任意检查点重放事件序列也能精确恢复消息状态。
:::

### 示例：流式界面

构建流式界面的典型模式（Spring WebFlux SSE 形态可参考 `streaming/StreamingWebExample.java`）：

```java
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;

agent.streamEvents(new UserMessage("user", "帮我修复这个 bug"))
        .doOnNext(event -> {
            if (event instanceof AgentStartEvent start) {
                System.out.println("[start replyId=" + start.getReplyId() + "]");
            } else if (event instanceof TextBlockDeltaEvent delta) {
                System.out.print(delta.getDelta());
            } else if (event instanceof ToolCallStartEvent tc) {
                System.out.println("\n[正在调用 " + tc.getToolCallName() + "...]");
            } else if (event instanceof ToolResultEndEvent end) {
                System.out.println("[工具执行完成：" + end.getState() + "]");
            } else if (event instanceof AgentEndEvent end) {
                System.out.println("\n[完成]");
            }
        })
        .blockLast();
```

## 延伸阅读

::::{grid} 2

:::{grid-item-card} 智能体
:link: ./agent.html

智能体如何在 ReAct 循环中产出事件和消息
:::
  :::{grid-item-card} 上下文
:link: context.html

消息如何存储与持久化
:::

::::
