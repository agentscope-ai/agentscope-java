---
title: Quickstart
description: Start with replies, live output and conversation history; add background tasks and recovery when needed.
zh_link: /v2/zh/docs/quickstart
---

## Installation

AgentScope Java requires JDK 17 or newer. Maven 3.9+ is recommended.

### Maven dependency

`HarnessAgent` is the recommended entry point — it packages workspace, long-term memory, session persistence, subagents, sandboxes, and other engineering capabilities into one builder. Depending on `agentscope-harness` pulls `agentscope-core` in transitively:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```


<Note>

Substitute `${agentscope.version}` with the latest version. See [Release Notes](/v2/en/docs/others/release-notes) for the latest version and full release details.

</Note>


If you only need the `ReActAgent` reasoning, tool and context APIs and want to compose the application capabilities yourself, `agentscope-core` is enough for the agent framework itself. Concrete model providers are separate: provider-specific chat models and formatters live in independent `agentscope-extensions-model-*` modules. The difference between `ReActAgent` and `HarnessAgent` is covered in [Harness Architecture](/v2/en/docs/harness/architecture).

The quickstart below uses DashScope through `.model("dashscope:qwen-plus")`, so add the matching model extension as well:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

MCP integration requires the official MCP SDK — see `agentscope-examples/documentation/pom.xml` for a working example.

## Your first agent

Configure a shared Builder at application startup, call `builder.build()` for each request, and close that Agent when execution finishes. Keep common configuration in the Builder and request identity in a fresh `RuntimeContext`. A new instance does not mean a new conversation. This example uses two instances for two turns in the same conversation. Set the model credential before running:

```bash
export DASHSCOPE_API_KEY=your_api_key
```

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;

public class FirstAgent {
    // Configure once at startup; only call build() after sharing.
    private static final HarnessAgent.Builder AGENT_BUILDER = HarnessAgent.builder()
            .name("note-taker")
            .agentId("note-taker")
            .sysPrompt("You are a note-taking assistant.")
            .model("dashscope:qwen-plus")
            .workspace(Path.of(".agentscope/workspace"));

    public static void main(String[] args) {
        try (HarnessAgent agent = AGENT_BUILDER.build()) {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("alice").sessionId("demo-session").build();
            agent.call(new UserMessage("My name is Alice. I am preparing a tech talk today."), ctx).block();
        }

        // A new instance restores the same conversation from its persisted history.
        try (HarnessAgent agent = AGENT_BUILDER.build()) {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("alice").sessionId("demo-session").build();
            Msg reply = agent.call(new UserMessage("What is my name? What am I doing today?"), ctx).block();
            System.out.println(reply.getTextContent());
        }
    }
}
```

`call` returns a `Mono<Msg>`. This CLI example uses `block()` to start execution and wait for the reply. One call can include multiple reasoning steps and tool calls. The next call with the same `userId` and `sessionId` uses the conversation's context.

By default, Harness saves execution history and working-state snapshots (checkpoints). Keep the same `agentId`, user, session identity and storage configuration across restarts to continue the conversation. You do not need to create an `AgentSession` for this persistence.

In this local example, native history lives under `.agentscope-runtime/` at the Workspace Filesystem root resolved for the current identity. With filesystem isolation or distributed storage, the location follows the selected root or namespace. See the [storage reference](/v2/en/docs/harness/session-log#storage-and-backend-configuration) when you need to inspect records or change backends.

### Streaming reasoning and tool calls

Use `streamEvents` to display output as it is generated. Add these imports at the top of the file and create a fresh instance for this request in `main`:

```java
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

try (HarnessAgent agent = AGENT_BUILDER.build()) {
    RuntimeContext ctx = RuntimeContext.builder()
            .userId("alice").sessionId("demo-session").build();
    agent.streamEvents(new UserMessage("List three steps to prepare for the talk."), ctx)
            .doOnNext(event -> {
                if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                    System.out.print(((TextBlockDeltaEvent) event).getDelta());
                } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                    System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
                }
            })
            .blockLast();
}
```

`streamEvents` returns a `Flux<AgentEvent>` that starts execution on subscription; `blockLast()` waits for that stream to finish here. It uses the same Agent capabilities and persistence configuration as `call`.

Choose `call` or `streamEvents` for each request according to the output you need. Calling `call` and then `streamEvents` with the same input executes that request again.

### Use it in a web request

In WebFlux, `Mono.using` builds an Agent from the shared Builder on subscription and closes it on completion, error or cancellation. Do not return an unsubscribed `Mono` from a try-with-resources block: that closes the Agent too early. This expression belongs in the handler:

```java
import reactor.core.publisher.Mono;

RuntimeContext ctx = RuntimeContext.builder()
        .userId(userId).sessionId(sessionId).build();
return Mono.using(
        AGENT_BUILDER::build,
        agent -> agent.call(new UserMessage(userInput), ctx),
        HarnessAgent::close);
```

Take `userId` from the authenticated identity and authorize access to `sessionId`. Do not call setters on the shared Builder from request handlers. Shared models, tools and middleware must support concurrent use. Different sessions can execute in parallel; the application should order requests to the same session across instances. Journal writer fencing is not an automatic queue. See [Agent lifecycle](/v2/en/docs/building-blocks/agent#instance-lifecycle).

### When to use AgentSession

Use `call` / `streamEvents` for ordinary conversations, workflow steps and streaming interfaces whose execution belongs to the current request. Returning the execution stream directly as an HTTP response can allow a client disconnect to cancel that execution.

Use `agent.session(ctx)` when your product needs work to continue after the page closes, durable queueing while busy, guidance during execution, or continuation of an interrupted task. Its `AgentSession` accepts tasks and schedules background execution; the frontend independently reads snapshots and durable events.

For background work, the application session manager or task worker owns the Agent until its work stops or the application shuts down. Do not close it when the submission HTTP request returns: its lifetime covers background execution, not just admission.

Continue with [Session operations, events and recovery](/v2/en/docs/harness/session-log), or run the [recoverable chat example](/v2/en/blogs/best-practices/session-chat). Applications using hosted agents over HTTP should read the [Service Agent API](/v2/en/service/session-event-log).

## Next steps

- [Agent](/v2/en/docs/building-blocks/agent) — direct calls, live events, structured output and tool interactions
- [Harness Architecture](/v2/en/docs/harness/architecture) — choose an invocation style and combine capabilities for your application
- [Workspace](/v2/en/docs/harness/workspace) — configure `AGENTS.md`, skills, subagents and tools
- [Context management](/v2/en/docs/harness/context) and [Memory](/v2/en/docs/harness/memory) — manage long conversations and information across sessions
- [Session operations, events and recovery](/v2/en/docs/harness/session-log) — build applications with background tasks, queueing and recovery
