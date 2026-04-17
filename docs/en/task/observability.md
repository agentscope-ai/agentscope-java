# Observability & Studio

AgentScope Java provides multiple mechanisms for observing and debugging agent execution:

- **Studio (Visual Debugging)**: Real-time visualization and interactive debugging through a Web UI
- **OpenTelemetry Tracing**: Export trace data to external observability platforms (e.g., Langfuse, Jaeger) via the OTLP protocol

---

## Studio (Visual Debugging)

Studio provides a Web interface for real-time visualization of Agent execution processes, supporting interactive debugging and message tracing.

---

### Core Features

- **Real-time Visualization**: Web interface displays Agent reasoning and execution processes
- **Interactive Input**: Interact with Agent through Web UI
- **Message Tracing**: View complete message flow and Trace
- **Multi-Run Management**: Support organization and comparison of multiple experimental runs

---

### Quick Start

#### 1. Start Studio Server

Start from source code
```bash
git clone https://github.com/agentscope-ai/agentscope-studio
cd agentscope-studio
npm install
npm run dev
```
Install via npm
```bash
npm install -g @agentscope/studio  # or npm install @agentscope/studio
as_studio
```
Studio will run at http://localhost:5173 (frontend dev server)

![Studio Server Page](../../imgs/studioServer.png)


#### 2. Java Application Integration

```java
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.studio.StudioUserAgent;

// Initialize Studio connection
StudioManager.init()
    .studioUrl("http://localhost:3000")
    .project("MyProject")
    .runName("demo_" + System.currentTimeMillis())
    .initialize()
    .block();

// Create Agent with Hook
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hook(new StudioMessageHook(StudioManager.getClient()))
    .build();

// Agent messages automatically sent to Studio
agent.call(msg).block();

// Clean up resources
StudioManager.shutdown();
```

#### 3. View Trace Information in AgentScope Studio

![Trace Information](../../imgs/studioServer-trace.png)



### StudioUserAgent

Receive user input through Web UI.

```java
import io.agentscope.core.studio.StudioUserAgent;

// Create user Agent
StudioUserAgent user = StudioUserAgent.builder()
    .name("User")
    .studioClient(StudioManager.getClient())
    .webSocketClient(StudioManager.getWebSocketClient())
    .build();

// Wait for Web UI user input
Msg userInput = user.call(null).block();
```
You can find the Project in Studio's Projects and debug through the WebUI

![Studio Server Web UI Input Interface](../../imgs/studioServer-webUI.png)

#### Conversation Loop

```java
Msg msg = null;
while (true) {
    // Get user input from Web UI
    msg = user.call(msg).block();

    if (msg == null || "exit".equalsIgnoreCase(msg.getTextContent())) {
        break;
    }

    // Agent processes
    msg = agent.call(msg).block();
}
```

---

### Complete Example

```java
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.studio.StudioUserAgent;

public class StudioExample {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        System.out.println("Connecting to Studio at http://localhost:3000...");

        // Initialize Studio
        StudioManager.init()
            .studioUrl("http://localhost:3000")
            .project("JavaExamples")
            .runName("studio_demo_" + System.currentTimeMillis())
            .initialize()
            .block();
        System.out.println("Connected to Studio\n");

        try {
            // Create Agent (with Studio Hook)
            ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("You are a helpful AI assistant.")
                .model(DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("qwen3-max")
                    .build())
                .hook(new StudioMessageHook(StudioManager.getClient()))
                .build();

            // Create user Agent
            StudioUserAgent user = StudioUserAgent.builder()
                .name("User")
                .studioClient(StudioManager.getClient())
                .webSocketClient(StudioManager.getWebSocketClient())
                .build();

            // Conversation loop
            System.out.println("Starting conversation (type 'exit' to quit)");
            System.out.println("Open http://localhost:3000 to interact\n");

            Msg msg = null;
            int turn = 1;
            while (true) {
                System.out.println("[Turn " + turn + "] Waiting for user input...");
                msg = user.call(msg).block();

                if (msg == null || "exit".equalsIgnoreCase(msg.getTextContent())) {
                    System.out.println("\nConversation ended");
                    break;
                }

                System.out.println("[Turn " + turn + "] User: " + msg.getTextContent());
                msg = agent.call(msg).block();

                if (msg != null) {
                    System.out.println("[Turn " + turn + "] Agent: "
                        + msg.getTextContent() + "\n");
                }
                turn++;
            }

        } finally {
            System.out.println("\nShutting down...");
            StudioManager.shutdown();
            System.out.println("Done\n");
        }
    }
}
```

---

### Advanced Usage

#### Manual Message Pushing

```java
StudioClient client = StudioManager.getClient();

Msg customMsg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(TextBlock.builder().text("Custom message").build())
    .build();

client.pushMessage(customMsg).block();
```


#### Multi-Agent Visualization

```java
// Add Hook to each Agent
ReActAgent agent1 = ReActAgent.builder()
    .name("Agent1")
    .hook(new StudioMessageHook(client))
    .build();

ReActAgent agent2 = ReActAgent.builder()
    .name("Agent2")
    .hook(new StudioMessageHook(client))
    .build();

// Studio will display messages from both Agents separately
```

---

## OpenTelemetry Tracing

AgentScope Java integrates with OpenTelemetry to provide distributed tracing for agent execution. You can export traces to any platform that supports the OTLP protocol, such as Langfuse.

### Quick Start

Use `TelemetryTracer` and register it via `TracerRegistry` to enable automatic tracing for all agent calls, model calls, and tool executions:

```java
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;

// Register a TelemetryTracer with an OTLP endpoint
TracerRegistry.register(
    TelemetryTracer.builder()
        .endpoint("https://your-otlp-endpoint/v1/traces")
        .build()
);

// After registration, all agent activities are automatically traced
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .build();
```

Once registered, the tracer automatically captures:
- **Agent calls** — span for each `agent.call()`
- **Model calls** — span for each LLM invocation
- **Tool executions** — span for each tool call
- **Formatting** — span for message formatting

No additional code is needed — tracing is applied globally.

### Configuration

`TelemetryTracer` supports the following builder options:

| Option         | Description                                       | Default |
|----------------|---------------------------------------------------|---------|
| `endpoint`     | OTLP endpoint URL                                 | —       |
| `addHeader`    | Add a single HTTP header (e.g., for auth)         | —       |
| `headers`      | Set all HTTP headers as a `Map`                   | —       |
| `enabled`      | Enable or disable tracing                         | `true`  |
| `tracer`       | Use a custom OpenTelemetry `Tracer` instance      | —       |

### Integration with Langfuse

[Langfuse](https://langfuse.com/) is an open-source observability platform that supports the OpenTelemetry protocol. You can send traces to Langfuse Cloud or a self-hosted instance.

```java
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import java.util.Base64;

// Encode your Langfuse API key as Base64
String publicKey = "pk-lf-xxxxxxxx";
String secretKey = "sk-lf-xxxxxxxx";
String encoded = Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes());

TracerRegistry.register(
    TelemetryTracer.builder()
        .endpoint("https://cloud.langfuse.com/api/public/otel/v1/traces")
        .addHeader("Authorization", "Basic " + encoded)
        .addHeader("x-langfuse-ingestion-version", "4")
        .build()
);
```

> **Note**: The `x-langfuse-ingestion-version` header is required by Langfuse when using the OTLP endpoint. Make sure to include it.

After running your agent, you can view the traces in the Langfuse dashboard, including the full call chain, latency, and input/output of each step.

### Integration with Other OTLP Backends

Any platform that supports the OTLP protocol can be used. For example, to send traces to a local Jaeger instance:

```java
TracerRegistry.register(
    TelemetryTracer.builder()
        .endpoint("http://localhost:4317")
        .build()
);
```

---

## More Resources

- **Studio Example**: [StudioExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/StudioExample.java)
- **Studio Repository**: https://github.com/agentscope-ai/agentscope-studio
- **Hook Documentation**: [hook.md](./hook.md)
