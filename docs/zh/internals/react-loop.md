# ReAct 循环原理

本文深入介绍 ReActAgent 的内部执行流程，帮助理解框架的核心运行机制。

## 执行流程概览

ReActAgent 的核心是 **ReAct 循环**（Reasoning + Acting），每次调用 `call()` 或 `stream()` 方法时执行以下流程：

```
                                用户输入
                                    │
                                    ▼
                           ┌────────────────┐
                           │   PreCallEvent │ (通知型)
                           └────────────────┘
                                    │
         ┌──────────────────────────┼──────────────────────────┐
         │                          ▼                          │
         │                 ┌────────────────┐                  │
         │                 │ 检查中断状态    │                  │
         │                 └────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │                ┌─────────────────┐                  │
         │                │ PreReasoningEvent│ (可修改)        │
         │                └─────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │                ┌─────────────────┐                  │
         │                │   调用 LLM      │ ← ReasoningChunkEvent (流式)
         │                └─────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │               ┌──────────────────┐                  │
         │               │PostReasoningEvent│ (可修改)         │
         │               └──────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │                  ┌──────────────┐                   │
         │                  │ 需要工具调用？ │                   │
         │                  └──────────────┘                   │
         │                    │          │                     │
         │                   是          否                    │
         │                    │          │                     │
         │                    ▼          └──────────┐          │
         │           ┌────────────────┐            │          │
         │           │ PreActingEvent │ (可修改)   │          │
         │           └────────────────┘            │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │           ┌────────────────┐            │          │
         │           │   执行工具     │ ← ActingChunkEvent     │
         │           └────────────────┘            │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │           ┌─────────────────┐           │          │
         │           │ PostActingEvent │ (可修改)  │          │
         │           └─────────────────┘           │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │           ┌────────────────┐            │          │
         │           │ 结果存入 Memory │            │          │
         │           └────────────────┘            │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │            ┌──────────────┐             │          │
         │            │ 检查迭代次数  │             │          │
         │            └──────────────┘             │          │
         │                    │                    │          │
         │          未超限 ───┴─── 超限            │          │
         │             │            │              │          │
         │      ┌──────┘            ▼              │          │
         │      │           返回最终响应 ◀─────────┘          │
         │      │                   │                        │
         └──────┼───────────────────┼────────────────────────┘
                │                   │
                └───────┬───────────┘
                        ▼
               ┌────────────────┐
               │ PostCallEvent  │ (可修改)
               └────────────────┘
                        │
                        ▼
                    返回响应
```

## 推理阶段（Reasoning）

推理阶段负责调用 LLM 生成下一步行动决策。

### 消息组装

在调用 LLM 前，框架按以下顺序组装消息：

```java
// 伪代码展示消息组装逻辑
List<Msg> messages = new ArrayList<>();

// 1. 系统提示（包含工具 Schema）
messages.add(buildSystemMessage());

// 2. 长期记忆召回内容（如果启用 STATIC_CONTROL 模式）
if (longTermMemoryMode.includesStatic()) {
    String memories = longTermMemory.retrieve(userMsg).block();
    if (memories != null) {
        messages.add(wrapAsMemoryHint(memories));
    }
}

// 3. 短期记忆中的历史消息
messages.addAll(memory.getMessages());

// 4. 当前用户输入
messages.add(userMsg);
```

### Formatter 转换

不同 LLM 提供商的 API 格式不同，Formatter 负责将统一的 `Msg` 转换为特定格式：

| 提供商 | Formatter | 主要差异 |
|--------|-----------|----------|
| DashScope | `DashScopeFormatter` | 工具调用使用 `tool_calls` 字段，支持 `thinking` 输出 |
| OpenAI | `OpenAIFormatter` | 标准 OpenAI 消息格式 |

**格式化过程**：

1. **角色映射** - USER/ASSISTANT/SYSTEM/TOOL → 对应 API 角色
2. **内容块转换** - TextBlock/ImageBlock/ToolUseBlock → API 特定格式
3. **工具 Schema 注入** - 将 Toolkit 中的工具定义转换为 JSON Schema
4. **多智能体身份处理** - 在消息中注入发送者名称

### 迭代终止条件

推理循环在以下情况终止：

1. **LLM 未请求工具调用** - 返回纯文本响应
2. **达到最大迭代次数** - 默认 `maxIters = 10`
3. **收到中断信号** - 通过 `agent.interrupt()` 触发
4. **发生不可恢复错误** - 抛出异常

## 行动阶段（Acting）

当 LLM 响应包含 `ToolUseBlock` 时，进入行动阶段。

### 工具调用解析

```java
// LLM 响应中的工具调用块
ToolUseBlock toolUse = extractToolUse(response);
// 包含: name（工具名）, id（调用 ID）, input（参数 Map）
```

### 参数注入机制

工具执行时，参数来自三个来源（按优先级从高到低）：

1. **LLM 提供的参数** - 来自 `ToolUseBlock.input`
2. **预设参数（Preset Parameters）** - 注册时配置的隐藏参数
3. **执行上下文（ToolExecutionContext）** - 按类型自动注入的对象

```
LLM 参数 → 预设参数 → 执行上下文
   ↓           ↓           ↓
 优先级高    优先级中     优先级低
```

### 并行执行

当 LLM 返回多个工具调用时，Toolkit 可以并行执行：

```java
// ToolkitConfig 配置
ToolkitConfig config = ToolkitConfig.builder()
    .parallel(true)  // 启用并行执行
    .build();
```

并行执行使用 `Schedulers.boundedElastic()` 调度器，避免阻塞主线程。

### 工具结果存储

执行完成后，工具结果以 `ToolResultBlock` 形式存入 Memory：

```java
// 工具结果消息
Msg toolResultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(List.of(
        ToolResultBlock.builder()
            .toolUseId(toolUse.getId())
            .output(result)
            .build()
    ))
    .build();

memory.addMessage(toolResultMsg);
```

## 中断机制

AgentScope 采用**协作式中断**，而非强制终止。

### 中断检查点

中断状态在以下位置检查：

1. 每次推理循环开始前
2. 工具执行完成后、下一次推理前

### 中断类型

```java
// 无消息中断 - 立即停止
agent.interrupt();

// 带消息中断 - 返回指定消息
agent.interrupt(Msg.builder()
    .textContent("操作已取消")
    .build());
```

### 设计原因

采用协作式而非强制中断的原因：

1. **状态一致性** - 确保 Memory 和 Toolkit 状态完整
2. **资源清理** - 允许工具完成清理操作
3. **可恢复性** - 中断后可以从断点继续

## Hook 事件生命周期

Hook 在 ReAct 循环的关键节点触发：

### 事件触发顺序

```
PreCallEvent → [循环开始]
    → PreReasoningEvent
    → (LLM 调用中) → ReasoningChunkEvent (多次)
    → PostReasoningEvent
    → [如果有工具调用]
        → PreActingEvent
        → (工具执行中) → ActingChunkEvent (多次)
        → PostActingEvent
    → [循环继续或结束]
→ PostCallEvent
```

### 可修改事件 vs 通知事件

| 事件类型 | 可修改 | 说明 |
|----------|--------|------|
| PreCallEvent | 否 | 仅通知智能体开始处理 |
| PreReasoningEvent | 是 | 可修改输入消息列表 |
| PostReasoningEvent | 是 | 可修改 LLM 响应 |
| ReasoningChunkEvent | 否 | 流式输出通知 |
| PreActingEvent | 是 | 可修改工具调用参数 |
| PostActingEvent | 是 | 可修改工具执行结果 |
| ActingChunkEvent | 否 | 工具进度通知 |
| PostCallEvent | 是 | 可修改最终响应 |
| ErrorEvent | 否 | 错误通知 |

### 优先级执行

多个 Hook 按优先级顺序执行（数值越小越先执行）：

```java
public class HighPriorityHook implements Hook {
    @Override
    public int priority() {
        return 10;  // 比默认值 100 先执行
    }
}
```

## 状态管理

ReActAgent 是有状态对象，包含以下可序列化状态：

### 状态组成

```java
// saveState() 导出的状态
Map<String, Object> state = new HashMap<>();
state.put("memory", memory.saveState());      // Memory 状态
state.put("toolkit", toolkit.saveState());    // Toolkit 状态（工具组状态等）
state.put("interrupted", interrupted);         // 中断标志
```

### 并发限制

由于有状态设计，**同一 Agent 实例不能被并发调用**：

```java
// 错误示例
ReActAgent agent = ReActAgent.builder()...build();
executor.submit(() -> agent.call(msg1));  // 并发问题
executor.submit(() -> agent.call(msg2));  // 并发问题

// 正确示例
executor.submit(() -> {
    ReActAgent agent = ReActAgent.builder()...build();
    agent.call(msg1);
});
```

## 响应式执行模型

AgentScope 基于 Project Reactor 构建，所有操作返回 `Mono` 或 `Flux`。

### 执行链路

```java
// call() 方法的响应式实现（简化）
public Mono<Msg> call(Msg input) {
    return Mono.defer(() -> {
        // 触发 PreCallEvent
        return hookManager.firePreCall(input)
            .flatMap(this::runReActLoop)
            .flatMap(hookManager::firePostCall);
    });
}

private Mono<Msg> runReActLoop(Msg input) {
    return Mono.defer(() -> {
        // 检查中断
        if (interrupted) {
            return Mono.just(interruptMessage);
        }
        // 推理
        return reasoning()
            .flatMap(response -> {
                if (needsToolCall(response)) {
                    // 行动 + 递归
                    return acting(response)
                        .then(runReActLoop(input));
                }
                return Mono.just(response);
            });
    });
}
```

### 背压处理

流式输出使用 `Flux`，支持背压：

```java
public Flux<Msg> stream(Msg input) {
    return Flux.create(sink -> {
        // 推送增量消息
        // sink 自动处理背压
    });
}
```

## 相关文档

- [消息系统架构](./message-system.md)
- [工具执行引擎](./tool-execution.md)
- [Hook 事件系统](./hook-event-system.md)
