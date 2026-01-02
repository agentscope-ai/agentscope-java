# Create ReAct Agent

AgentScope provides an out-of-the-box ReAct agent `ReActAgent` for developers.

It supports the following features:

- **Basic Features**
    - Hooks around `reasoning` and `acting`
    - Structured output
- **Realtime Steering**
    - User interruption
    - Custom interrupt handling
- **Tools**
    - Sync/async tool functions
    - Streaming tool responses
    - Parallel tool calls
    - MCP server integration
- **Memory**
    - Agent-controlled long-term memory
    - Static long-term memory management

## Creating ReActAgent

The `ReActAgent` class exposes the following parameters in its constructor:

| Parameter | Further Reading | Description |
|-----------|-----------------|-------------|
| `name` (required) | | Agent's name |
| `sysPrompt` (required) | | System prompt |
| `model` (required) | [Model Integration](../task/model.md) | Model for generating responses |
| `toolkit` | [Tool System](../task/tool.md) | Module for registering/calling tool functions |
| `memory` | [Memory Management](../task/memory.md) | Short-term memory for conversation history |
| `longTermMemory` | [Long-term Memory](../task/long-term-memory.md) | Long-term memory |
| `longTermMemoryMode` | [Long-term Memory](../task/long-term-memory.md) | Long-term memory mode: `AGENT_CONTROL`, `STATIC_CONTROL`, or `BOTH` |
| `maxIters` | | Max iterations for generating response (default: 10) |
| `hooks` | [Hook System](../task/hook.md) | Event hooks for customizing agent behavior |
| `modelExecutionConfig` | | Timeout/retry config for model calls |
| `toolExecutionConfig` | | Timeout/retry config for tool calls |

Using DashScope API as an example, we create an agent as follows:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.annotation.Tool;
import io.agentscope.core.tool.annotation.ToolParam;

public class QuickStart {
    public static void main(String[] args) {
        // Prepare tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        // Create agent
        ReActAgent jarvis = ReActAgent.builder()
            .name("Jarvis")
            .sysPrompt("You are an assistant named Jarvis.")
            .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen3-max")
                .build())
            .toolkit(toolkit)
            .build();

        // Send message
        Msg msg = Msg.builder()
            .textContent("Hello Jarvis, what time is it now?")
            .build();

        Msg response = jarvis.call(msg).block();
        System.out.println(response.getTextContent());
    }
}

// Tool class
class SimpleTools {
    @Tool(name = "get_time", description = "Get current time")
    public String getTime(
            @ToolParam(name = "zone", description = "Timezone, e.g., Beijing") String zone) {
        return java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
```

## Additional Configuration

### Execution Control

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant.")
    .model(model)
    .maxIters(10)              // Max iterations (default: 10)
    .checkRunning(true)        // Prevent concurrent calls (default: true)
    .build();
```

### Timeout and Retry

```java
ExecutionConfig modelConfig = ExecutionConfig.builder()
    .timeout(Duration.ofMinutes(2))
    .maxAttempts(3)
    .build();

ExecutionConfig toolConfig = ExecutionConfig.builder()
    .timeout(Duration.ofSeconds(30))
    .maxAttempts(1)  // Tools typically don't retry
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .modelExecutionConfig(modelConfig)
    .toolExecutionConfig(toolConfig)
    .build();
```

### Tool Execution Context

Pass business context (e.g., user info) to tools without exposing to LLM:

```java
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .toolExecutionContext(context)
    .build();

// Auto-injected in tool
@Tool(name = "query", description = "Query data")
public String query(
    @ToolParam(name = "sql") String sql,
    UserContext ctx  // Auto-injected, no @ToolParam needed
) {
    return "Query result for user " + ctx.getUserId();
}
```

### Plan Management

Enable PlanNotebook for complex multi-step tasks:

```java
// Quick enable
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .enablePlan()
    .build();

// Custom configuration
PlanNotebook planNotebook = PlanNotebook.builder()
    .maxSubtasks(15)
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .planNotebook(planNotebook)
    .build();
```

## UserAgent

An agent that receives external input (e.g., command line, Web UI):

```java
UserAgent user = UserAgent.builder()
    .name("User")
    .build();

Msg userInput = user.call(null).block();
```
