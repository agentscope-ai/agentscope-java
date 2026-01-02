# Hook

Hooks provide extension points to monitor and modify agent behavior at specific execution stages.

## Hook Overview

AgentScope Java uses a **unified event model** where all hooks implement the `onEvent(HookEvent)` method:

- **Event-based**: All agent activities generate events
- **Type-safe**: Pattern matching on event types
- **Priority-ordered**: Hooks execute by priority (lower value = higher priority)
- **Modifiable**: Some events allow modification of execution context

## Supported Events

| Event Type            | Timing                    | Modifiable | Description                              |
|-----------------------|---------------------------|------------|------------------------------------------|
| PreCallEvent          | Before agent call         | ❌         | Before agent starts processing (notification-only)           |
| PostCallEvent         | After agent call          | ✅         | After agent completes response (can modify final message)           |
| PreReasoningEvent     | Before reasoning          | ✅         | Before LLM reasoning (can modify input messages)          |
| PostReasoningEvent    | After reasoning           | ✅         | After LLM reasoning (can modify reasoning result)            |
| ReasoningChunkEvent   | During reasoning stream   | ❌         | Each chunk of streaming reasoning (notification-only)        |
| PreActingEvent        | Before tool execution     | ✅         | Before tool execution (can modify tool parameters)                    |
| PostActingEvent       | After tool execution      | ✅         | After tool execution (can modify tool result)                |
| ActingChunkEvent      | During tool stream        | ❌         | Tool execution progress chunks (notification-only)    |
| ErrorEvent            | On error                  | ❌         | When errors occur (notification-only)                        |

## Creating Hooks

### Basic Hook

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
            System.out.println("Agent starting: " + event.getAgent().getName());
            return Mono.just(event);
        }
        
        if (event instanceof PostCallEvent) {
            System.out.println("Agent finished: " + event.getAgent().getName());
            return Mono.just(event);
        }
        
        return Mono.just(event);
    }
}
```

### Hook with Priority

```java
public class HighPriorityHook implements Hook {

    @Override
    public int priority() {
        return 10;  // Lower number = higher priority (default is 100)
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // This hook executes before hooks with priority > 10
        return Mono.just(event);
    }
}
```

### Modifying Events

Some events allow modification:

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
                    .content(List.of(TextBlock.builder().text("Think step by step.").build()))
                    .build());
            e.setInputMessages(messages);
            return Mono.just(event);
        }
        return Mono.just(event);
    }
}
```

## Configure Hooks in Agent

Register hooks when building an agent:

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

Hooks are immutable after agent construction.

## Hook Examples

### Monitoring Tool Execution

Track tool calls:

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
            System.out.println("Calling tool: " + e.getToolUse().getName());
            System.out.println("Arguments: " + e.getToolUse().getInput());
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent e) {
            String resultText = e.getToolResult().getOutput().stream()
                    .filter(block -> block instanceof TextBlock)
                    .map(block -> ((TextBlock) block).getText())
                    .findFirst()
                    .orElse("");
            System.out.println("Tool result: " + resultText);
            return Mono.just(event);
        }

        return Mono.just(event);
    }
}
```

### Monitoring Errors

Monitor and handle errors:

```java
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import reactor.core.publisher.Mono;

public class ErrorHandlingHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (event instanceof ErrorEvent e) {
            System.err.println("Error in agent: " + e.getAgent().getName());
            System.err.println("Error message: " + e.getError().getMessage());
            return Mono.just(event);
        }

        return Mono.just(event);
    }
}
```

## Complete Example

See the complete Hook example:
- `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/HookExample.java`

Run the example:
```bash
cd agentscope-examples/quickstart
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.HookExample"
```
