# AgentScope Studio

`agentscope-extensions-studio` integrates Agents with [AgentScope Studio](https://github.com/agentscope-ai/agentscope-studio): every Agent invocation is pushed to Studio for visual debugging, trace replay, and human-in-the-loop input.

## When to use

- You want to inspect event streams, reasoning, and tool calls in Studio during development.
- You need to issue `requestUserInput` from Studio and let a real user respond.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-studio</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;

// 1) Initialize Studio connection (HTTP + WebSocket)
StudioManager.init()
    .studioUrl("http://localhost:8000")
    .project("MyProject")
    .runName("experiment_001")
    .initialize()
    .block();

// 2) Attach StudioMessageHook so messages are pushed to Studio
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hook(new StudioMessageHook(StudioManager.getClient()))
    .build();

// 3) Use the Agent normally; Studio mirrors the conversation
agent.call(msg).block();
```

## What Studio gives you

- **Message push**: every user / assistant / tool message is mirrored to Studio.
- **Traces**: Studio organizes events into a trace tree per `runName`.
- **Human-in-the-loop**: via `StudioUserAgent` or `requestUserInput`, Studio's UI prompts a real user to fill in input before execution continues.

## API overview

| Class | Purpose |
| --- | --- |
| `StudioManager` | Singleton entry point — initialize and access clients |
| `StudioConfig` | URL / project / runName configuration |
| `StudioClient` | HTTP client for events, messages, and run registration |
| `StudioWebSocketClient` | WebSocket client for inbound commands (e.g. user input) |
| `StudioMessageHook` | A `Hook` for `ReActAgent` that auto-pushes `Msg` |
| `StudioUserAgent` | "Human-played" Agent that blocks on Studio user input |

## When to disable

In production, you usually don't want this hook attached (every call writes to Studio). Gate it via Spring profile or `@ConditionalOnProperty`:

```java
@Bean
@ConditionalOnProperty("agentscope.studio.enabled")
StudioMessageHook studioHook() {
    StudioManager.init().studioUrl(url).project(project).initialize().block();
    return new StudioMessageHook(StudioManager.getClient());
}
```
