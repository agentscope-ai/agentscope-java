# Hook 事件系统

本文深入介绍 AgentScope Hook 系统的内部设计，包括事件类型、执行流程和扩展机制。

## 设计理念

Hook 系统采用**事件驱动架构**，在 ReAct 循环的关键节点发布事件，允许外部逻辑介入。

### 核心原则

1. **单一入口** - 所有事件通过 `onEvent(HookEvent)` 处理
2. **类型安全** - 使用 Java 模式匹配区分事件类型
3. **响应式** - 返回 `Mono<T>` 支持异步处理
4. **可组合** - 多个 Hook 按优先级链式执行

## 事件层次结构

```
HookEvent (接口)
├── PreCallEvent           // 智能体调用前
├── PostCallEvent          // 智能体调用后
├── PreReasoningEvent      // LLM 推理前
├── PostReasoningEvent     // LLM 推理后
├── ReasoningChunkEvent    // LLM 流式输出
├── PreActingEvent         // 工具执行前
├── PostActingEvent        // 工具执行后
├── ActingChunkEvent       // 工具流式输出
└── ErrorEvent             // 错误发生时
```

## 事件详解

### PreCallEvent

智能体开始处理用户输入前触发。

```java
public class PreCallEvent implements HookEvent {
    private final Agent agent;           // 当前智能体
    private final Msg inputMessage;      // 用户输入消息
    // 只读，不可修改
}
```

**触发时机**：`agent.call(msg)` 或 `agent.stream(msg)` 调用后立即触发

**用途**：
- 记录调用日志
- 监控统计
- 输入验证

### PostCallEvent

智能体处理完成后触发。

```java
public class PostCallEvent implements HookEvent {
    private final Agent agent;
    private Msg finalMessage;            // 可修改的最终响应

    public void setFinalMessage(Msg msg) { this.finalMessage = msg; }
}
```

**触发时机**：ReAct 循环结束，准备返回响应前

**用途**：
- 响应后处理
- 内容过滤
- 响应增强

### PreReasoningEvent

调用 LLM 前触发。

```java
public class PreReasoningEvent implements HookEvent {
    private final Agent agent;
    private List<Msg> inputMessages;     // 可修改的输入消息列表

    public void setInputMessages(List<Msg> msgs) { this.inputMessages = msgs; }
}
```

**触发时机**：每次 LLM 调用前（包括工具调用后的后续推理）

**用途**：
- 注入额外上下文
- 消息预处理
- 动态提示词

### PostReasoningEvent

LLM 返回后触发。

```java
public class PostReasoningEvent implements HookEvent {
    private final Agent agent;
    private Msg reasoningResult;         // 可修改的 LLM 响应

    public void setReasoningResult(Msg msg) { this.reasoningResult = msg; }
}
```

**触发时机**：LLM 响应完成后（流式场景为所有 chunk 合并后）

**用途**：
- 响应后处理
- 工具调用拦截
- 内容审核

### ReasoningChunkEvent

LLM 流式输出时触发。

```java
public class ReasoningChunkEvent implements HookEvent {
    private final Agent agent;
    private final Msg incrementalChunk;  // 增量内容（只读）
    private final Msg accumulatedMessage; // 累积内容（只读）
}
```

**触发时机**：每收到一个流式 chunk 触发一次

**用途**：
- 实时显示输出
- 流式日志

### PreActingEvent

工具执行前触发。

```java
public class PreActingEvent implements HookEvent {
    private final Agent agent;
    private ToolUseBlock toolUse;        // 可修改的工具调用

    public void setToolUse(ToolUseBlock toolUse) { this.toolUse = toolUse; }
}
```

**触发时机**：每个工具执行前

**用途**：
- 参数验证
- 参数修改
- 权限检查

### PostActingEvent

工具执行后触发。

```java
public class PostActingEvent implements HookEvent {
    private final Agent agent;
    private final ToolUseBlock toolUse;  // 原始工具调用（只读）
    private ToolResultBlock toolResult;  // 可修改的执行结果

    public void setToolResult(ToolResultBlock result) { this.toolResult = result; }
}
```

**触发时机**：工具执行完成后

**用途**：
- 结果后处理
- 结果缓存
- 审计日志

### ActingChunkEvent

工具流式输出时触发。

```java
public class ActingChunkEvent implements HookEvent {
    private final Agent agent;
    private final ToolUseBlock toolUse;
    private final ToolResultBlock chunk;  // 增量结果（只读）
}
```

**触发时机**：工具通过 `ToolEmitter` 发送进度时

**用途**：
- 进度显示
- 流式日志

### ErrorEvent

发生错误时触发。

```java
public class ErrorEvent implements HookEvent {
    private final Agent agent;
    private final Throwable error;        // 错误对象（只读）
    private final ErrorPhase phase;       // 错误发生阶段
}

public enum ErrorPhase {
    REASONING,  // LLM 调用阶段
    ACTING      // 工具执行阶段
}
```

**触发时机**：LLM 调用或工具执行出错时

**用途**：
- 错误日志
- 监控告警
- 错误分析

## Hook 执行机制

### 优先级排序

多个 Hook 按优先级升序执行（数值越小越先执行）：

```java
public interface Hook {
    default int priority() {
        return 100;  // 默认优先级
    }

    <T extends HookEvent> Mono<T> onEvent(T event);
}
```

**执行顺序**：

```
Hook A (priority: 10)
    ↓
Hook B (priority: 50)
    ↓
Hook C (priority: 100)
    ↓
Hook D (priority: 200)
```

### 链式处理

事件在 Hook 链中传递，每个 Hook 可以修改事件：

```java
// HookManager 内部实现（简化）
public <T extends HookEvent> Mono<T> fireEvent(T event) {
    Mono<T> chain = Mono.just(event);

    for (Hook hook : sortedHooks) {
        chain = chain.flatMap(e -> hook.onEvent(e));
    }

    return chain;
}
```

### 可修改事件的处理

```java
// PreReasoningEvent 修改示例
Hook promptEnhancer = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent e) {
            List<Msg> messages = new ArrayList<>(e.getInputMessages());
            messages.add(0, systemHint);  // 添加系统提示
            e.setInputMessages(messages);
        }
        return Mono.just(event);
    }
};
```

### 通知型事件的处理

```java
// ReasoningChunkEvent 处理示例
Hook streamPrinter = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof ReasoningChunkEvent e) {
            System.out.print(e.getIncrementalChunk().getTextContent());
            // 不能修改，只能观察
        }
        return Mono.just(event);
    }
};
```

## 事件流程图

完整的事件触发流程：

```
agent.call(msg)
        │
        ▼
┌───────────────────┐
│   PreCallEvent    │ ← 通知型
└───────────────────┘
        │
        ▼
┌───────────────────┐
│ PreReasoningEvent │ ← 可修改 inputMessages
└───────────────────┘
        │
        ▼
┌───────────────────┐
│    LLM 调用       │ → ReasoningChunkEvent (多次，通知型)
└───────────────────┘
        │
        ▼
┌────────────────────┐
│ PostReasoningEvent │ ← 可修改 reasoningResult
└────────────────────┘
        │
        ▼
    需要工具调用？
        │
       是
        │
        ▼
┌───────────────────┐
│  PreActingEvent   │ ← 可修改 toolUse
└───────────────────┘
        │
        ▼
┌───────────────────┐
│    工具执行       │ → ActingChunkEvent (多次，通知型)
└───────────────────┘
        │
        ▼
┌───────────────────┐
│  PostActingEvent  │ ← 可修改 toolResult
└───────────────────┘
        │
        ▼
    继续循环或结束
        │
        ▼
┌───────────────────┐
│   PostCallEvent   │ ← 可修改 finalMessage
└───────────────────┘
        │
        ▼
    返回响应
```

## 实现模式

### 模式匹配处理

推荐使用 Java 模式匹配处理不同事件：

```java
public class MultiEventHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreCallEvent e -> {
                log.info("开始处理: {}", e.getInputMessage());
                yield Mono.just(event);
            }
            case PostCallEvent e -> {
                log.info("处理完成: {}", e.getFinalMessage());
                yield Mono.just(event);
            }
            case ErrorEvent e -> {
                log.error("错误: {}", e.getError().getMessage());
                yield Mono.just(event);
            }
            default -> Mono.just(event);
        };
    }
}
```

### 异步处理

Hook 支持异步操作：

```java
public class AsyncHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent e) {
            // 异步记录到数据库
            return logToDatabase(e)
                .thenReturn(event);
        }
        return Mono.just(event);
    }

    private Mono<Void> logToDatabase(PostActingEvent event) {
        return Mono.fromRunnable(() -> {
            // 数据库操作
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
```

### 条件拦截

```java
public class ConditionalHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent e) {
            String toolName = e.getToolUse().getName();

            // 拦截危险工具
            if ("delete_file".equals(toolName)) {
                return Mono.error(new SecurityException("禁止删除文件"));
            }
        }
        return Mono.just(event);
    }
}
```

## 注册方式

### Builder 配置

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hook(loggingHook)
    .hook(monitoringHook)
    .hooks(List.of(hook1, hook2))  // 批量添加
    .build();
```

### 运行时不可变

Agent 构造后 Hook 列表不可修改，保证线程安全。

## 相关文档

- [ReAct 循环原理](./react-loop.md)
- [Hook 使用指南](../task/hook.md)
