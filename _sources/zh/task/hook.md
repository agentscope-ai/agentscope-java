# Hook

Hook 是一系列的扩展点，用于在特定执行阶段监控和修改智能体行为。

## Hook 概述

AgentScope Java 使用**统一事件模型**，所有 Hook 都需要实现 `onEvent(HookEvent)` 方法：

- **基于事件**：所有智能体活动生成事件
- **类型安全**：对事件类型进行模式匹配
- **优先级排序**：钩子按优先级执行（值越小优先级越高）
- **可修改**：某些事件允许修改执行上下文

## 支持的事件

| 事件类型              | 时机                      | 可修改 | 描述                                     |
|-----------------------|---------------------------|--------|------------------------------------------|
| PreCallEvent          | 智能体调用前              | ❌     | 智能体开始处理之前（仅通知）             |
| PostCallEvent         | 智能体调用后              | ✅     | 智能体完成响应之后（可修改最终消息）     |
| PreReasoningEvent     | 推理前                    | ✅     | LLM 推理之前（可修改输入消息）           |
| PostReasoningEvent    | 推理后                    | ✅     | LLM 推理完成之后（可修改推理结果）       |
| ReasoningChunkEvent   | 推理流式期间              | ❌     | 流式推理的每个块（仅通知）               |
| PreActingEvent        | 工具执行前                | ✅     | 工具执行之前（可修改工具参数）           |
| PostActingEvent       | 工具执行后                | ✅     | 工具执行之后（可修改工具结果）           |
| ActingChunkEvent      | 工具流式期间              | ❌     | 工具执行进度块（仅通知）                 |
| ErrorEvent            | 发生错误时                | ❌     | 发生错误时（仅通知）                     |

## 创建 Hook

### 基础 Hook

```java
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PostCallEvent;
import reactor.core.publisher.Mono;

public class LoggingHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        
        if (event instanceof PreCallEvent) {
            System.out.println("智能体启动: " + event.getAgent().getName());
            return Mono.just(event);
        }
        
        if (event instanceof PostCallEvent) {
            System.out.println("智能体完成: " + event.getAgent().getName());
            return Mono.just(event);
        }
        
        return Mono.just(event);
    }
}
```

### 带优先级的 Hook

```java
public class HighPriorityHook implements Hook {

    @Override
    public int priority() {
        return 10;  // 数字越小优先级越高（默认为 100）
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 此钩子在优先级 > 10 的钩子之前执行
        return Mono.just(event);
    }
}
```

### 修改事件

某些事件允许修改：

```java
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

public class PromptEnhancingHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent e) {
            List<Msg> messages = new ArrayList<>(e.getInputMessages());
            messages.add(0, Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .content(List.of(TextBlock.builder().text("逐步思考。").build()))
                    .build());
            e.setInputMessages(messages);
            return Mono.just(event);
        }
        return Mono.just(event);
    }
}
```

## 在智能体中配置 Hook

在构建智能体时注册 Hook：

```java
import io.agentscope.core.ReActAgent;
import java.util.List;

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .hooks(List.of(
                new LoggingHook(),
                new HighPriorityHook(),
                new PromptEnhancingHook()
        ))
        .build();
```

智能体构造后 Hook 不可变。

## Hook 示例

### 监控工具执行

跟踪工具调用：

```java
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Mono;

public class ToolMonitorHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        
        if (event instanceof PreActingEvent e) {
            System.out.println("调用工具: " + e.getToolUse().getName());
            System.out.println("参数: " + e.getToolUse().getInput());
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent e) {
            String resultText = e.getToolResult().getOutput().stream()
                    .filter(block -> block instanceof TextBlock)
                    .map(block -> ((TextBlock) block).getText())
                    .findFirst()
                    .orElse("");
            System.out.println("工具结果: " + resultText);
            return Mono.just(event);
        }

        return Mono.just(event);
    }
}
```

### 监控错误

监控和处理错误：

```java
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import reactor.core.publisher.Mono;

public class ErrorHandlingHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (event instanceof ErrorEvent e) {
            System.err.println("智能体错误: " + e.getAgent().getName());
            System.err.println("错误消息: " + e.getError().getMessage());
            return Mono.just(event);
        }

        return Mono.just(event);
    }
}
```

## 完整示例

查看完整的 Hook 示例：
- [HookExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/HookExample.java)

运行示例：
```bash
cd agentscope-examples/quickstart
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.HookExample"
```
