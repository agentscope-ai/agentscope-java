---
title: "Quickstart"
description: "Get started quickly with AgentScope Java 2.0"
---

## Installation

AgentScope Java requires JDK 17 or newer. Maven 3.9+ is recommended.

### Maven dependency

Add `agentscope-core` to your `pom.xml` (replace `${agentscope.version}` with the latest release):

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

The DashScope / OpenAI / Anthropic / Gemini / Ollama formatters and chat models all live inside `agentscope-core`. MCP integration requires the official MCP SDK — see `agentscope-examples/documentation/pom.xml` for a working example.

### Build from source

```bash
git clone -b main https://github.com/agentscope-ai/agentscope-java
cd agentscope-java
./mvnw -DskipTests install
```

### Verify the install

Once the dependency is in place, run the following snippet to confirm it resolves:

```java
import io.agentscope.core.Version;

public class CheckInstall {
    public static void main(String[] args) {
        System.out.println(Version.VERSION);
    }
}
```

## Your first agent

The example below builds a minimal agent: a DashScope chat model resolved by `ModelRegistry` from the `dashscope:qwen-plus` id, an empty toolkit, and a `ReActAgent` instance. The agent exposes two entry points — `call` returns the final message, while `streamEvents` yields events incrementally for rendering reasoning and tool-call progress.

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class FirstAgent {
    public static void main(String[] args) {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Friday")
                        .sysPrompt("You are a helpful assistant named Friday.")
                        // String form resolved via ModelRegistry — picks up DASHSCOPE_API_KEY
                        // from the environment. Use "openai:gpt-5.5", "anthropic:claude-sonnet-4-5",
                        // "gemini:gemini-2.0-flash", or "ollama:llama3" to switch providers.
                        .model("dashscope:qwen-plus")
                        .toolkit(new Toolkit())
                        .build();

        UserMessage userMsg = new UserMessage("user", "Hello, who are you?");

        // Option 1: wait for the final assistant message.
        Msg replyMsg = agent.call(List.of(userMsg), RuntimeContext.empty()).block();
        // `replyMsg.getContent()` is a list of content blocks — inspect text, tool calls, etc.
        System.out.println(replyMsg.getTextContent());

        // Option 2: stream incremental events (text deltas, tool calls, etc.).
        agent.streamEvents(new UserMessage("Tell me a fun fact."))
                .doOnNext(event -> {
                    // Dispatch on `event.getType()` — each branch maps to one event type.
                    if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                        // Streaming text fragment — append to UI or stdout.
                        System.out.print(((TextBlockDeltaEvent) event).getDelta());
                    } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                        // The agent is about to call a tool — surface the call info.
                        ToolCallStartEvent start = (ToolCallStartEvent) event;
                        System.out.println("\n[tool] " + start.getToolName());
                    }
                    // Other events: thinking blocks, tool results, reply end, etc.
                })
                .blockLast();
    }
}
```

:::{tip}
Make sure `DASHSCOPE_API_KEY` is set in the environment before running. To switch providers, change the model id string — `openai:gpt-5.5`, `anthropic:claude-sonnet-4-5`, `gemini:gemini-2.0-flash`, `ollama:llama3` — and export the matching API key (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`). When you need explicit control over options like timeouts or custom endpoints, build the model with `XxxChatModel.builder()` and pass it to `.model(Model)` instead.
:::
