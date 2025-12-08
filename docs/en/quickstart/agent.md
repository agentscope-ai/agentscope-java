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

> For more details on these features, please refer to the related documentation. This section focuses on how to create and run a ReAct agent.

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
                .modelName("qwen-max")
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
