---
title: "Channel"
description: "Route messages, manage sessions, and stream events through Channel"
---

## What they do

**Gateway** sits between your application code and the agent. It handles:

- **Session management** — maps each user conversation to a stable session id. The agent sees consistent memory across turns.
- **Per-session concurrency control** — concurrent messages to the same session are queued fairly so the agent never races itself.
- **Agent routing** — in multi-agent setups, routes each message to the right agent.

**Channel** adapts a messaging platform (HTTP, WebSocket, Slack, etc.) into the Gateway's routing model. It resolves who sent the message, which agent should handle it, and where to deliver the reply.

For most use cases you don't interact with Gateway or Channel directly — `agent.channel(...)` wires everything up behind the scenes.

## Quick start

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .sysPrompt("You are a helpful assistant.")
    .model("dashscope:qwen-plus")
    .build();

// Bind a ChatUI channel.
ChatUiChannel chat = agent.channel(ChatUiChannel.create());

// Send messages. Each userId gets its own session automatically.
Msg reply = chat.send(SendOptions.userId("user-1"), "Hello!").block();

// Same user, same session — conversation continues.
Msg followUp = chat.send(SendOptions.userId("user-1"), "Tell me more.").block();

// Different user, different session.
Msg otherUser = chat.send(SendOptions.userId("user-2"), "Hi there").block();
```

`agent.channel(...)` lazily creates an internal gateway, registers the agent, and injects the gateway into the channel. After this call, `chat` is ready to use.

### SendOptions

`SendOptions` tells the channel **who** is talking and **which conversation** this belongs to:

| Factory | Behavior |
|---------|----------|
| `SendOptions.userId("user-1")` | One session per user (most common) |
| `SendOptions.of("user-1", "session-a")` | Explicit session — multiple conversations per user |
| `SendOptions.userId("user-1").withAgentId("support")` | Route to a specific agent in multi-agent setups |

```java
// Same user, two independent conversations
chat.send(SendOptions.of("user-1", "session-a"), "Topic A").block();
chat.send(SendOptions.of("user-1", "session-b"), "Topic B").block();
```

## Streaming events + SSE

`sendStream()` returns `Flux<AgentEvent>` — the same fine-grained event stream as `agent.streamEvents()`, but routed through the gateway with session management.

```java
chat.sendStream(SendOptions.userId("user-1"), "What is the weather in Beijing?")
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        } else if (event instanceof ToolCallStartEvent tc) {
            System.out.println("\n[tool] " + tc.getToolCallName());
        }
    })
    .blockLast();
```

### Spring Boot SSE controller

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String userId,
                                          @RequestParam(required = false) String sessionId) {
    SendOptions options = sessionId != null
            ? SendOptions.of(userId, sessionId)
            : SendOptions.userId(userId);

    return chat.sendStream(options, message)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType().name());
                payload.put("id", event.getId());
                if (event instanceof TextBlockDeltaEvent delta) {
                    payload.put("delta", delta.getDelta());
                } else if (event instanceof SubagentExposedEvent se) {
                    payload.put("subagentId", se.getSubagentId());
                    payload.put("agentId", se.getAgentId());
                    payload.put("label", se.getLabel());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

## Talking to exposed subagents

When the agent spawns a subagent with `expose_to_user=true`, the gateway exposes that subagent as a user-addressable entry point. A `SubagentExposedEvent` is emitted into the `sendStream()` event stream carrying the `subagentId`.

### Discovering exposed subagents

```java
AtomicReference<String> subagentId = new AtomicReference<>();

chat.sendStream(SendOptions.userId("user-1"), "Spawn a researcher to investigate AI trends")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            subagentId.set(se.getSubagentId());
            System.out.printf("Subagent exposed: id=%s agent=%s label=%s%n",
                    se.getSubagentId(), se.getAgentId(), se.getLabel());
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
    .blockLast();
```

`SubagentExposedEvent` fields:

| Field | Description |
|-------|-------------|
| `subagentId` | Handle for sending messages to this subagent |
| `agentId` | Subagent type (e.g. `"researcher"`) |
| `sessionId` | Subagent's session id |
| `label` | Optional human-readable name |

### Sending messages to subagents

Once you have a `subagentId`, send messages directly to the subagent — bypassing the parent agent entirely:

```java
// Non-streaming
Msg reply = chat.sendToSubagent(subagentId, "Focus on LLM agents").block();

// Streaming
chat.sendToSubagentStream(subagentId, "Focus on LLM agents")
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
    .blockLast();
```

### SSE with subagent support

A typical SSE controller handles both main-agent and subagent messages:

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String userId,
                                          @RequestParam String message,
                                          @RequestParam(required = false) String subagentId) {
    Flux<AgentEvent> events;
    if (subagentId != null) {
        events = chat.sendToSubagentStream(subagentId, message);
    } else {
        events = chat.sendStream(SendOptions.userId(userId), message);
    }
    return events.map(event -> toSSE(event));
}
```

The client watches for `SUBAGENT_EXPOSED` events to render new conversation tabs, and passes the `subagentId` back on subsequent requests.

## Multi-agent routing

For scenarios with multiple `HarnessAgent` instances, use `GatewayBootstrap`:

```java
HarnessAgent salesAgent = HarnessAgent.builder()
    .name("sales").sysPrompt("You are a sales assistant.")
    .model("dashscope:qwen-plus").build();

HarnessAgent supportAgent = HarnessAgent.builder()
    .name("support").sysPrompt("You are a support agent.")
    .model("dashscope:qwen-plus").build();

GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("sales", salesAgent)
    .agent("support", supportAgent)
    .mainAgent("sales")          // default when no agent is specified
    .build();

ChatUiChannel chat = gw.chatUiChannel();
```

### Routing by agentId

Use `SendOptions.withAgentId()` to route a message to a specific agent:

```java
// Routes to sales (the default main agent)
chat.send(SendOptions.userId("user-1"), "What products?").block();

// Routes to support explicitly
chat.send(SendOptions.userId("user-1").withAgentId("support"), "Billing issue").block();
```

### Thread exposure with GatewayBootstrap

To enable `expose_to_user` on subagents, wire the gateway bridge into each agent's subagent middleware:

```java
GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", mainAgent)
    .build();

// Wire the bridge so agent_spawn(expose_to_user=true) works.
SubagentGatewayBridge bridge = gw.gatewayBridge();
// Pass bridge to the agent's SubagentsMiddleware via setGatewayBridge().
```

With `agent.channel(...)`, this wiring happens automatically.

## Custom Channel

Implement the `Channel` interface to adapt a new messaging platform:

```java
public class MySlackChannel implements Channel {
    @Override public String channelId() { return "slack"; }
    @Override public ChannelConfig config() { return myConfig; }
    @Override public void init(Gateway gateway) { this.gateway = gateway; }
    @Override public void start() { /* connect to Slack */ }
    @Override public void stop() { /* disconnect */ }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        RouteResult route = router.resolveRoute(config(), message);
        return gateway.run(route.context(), message.messages(), route.outboundAddress());
    }

    // Optional: streaming dispatch
    @Override
    public Flux<AgentEvent> dispatchStream(InboundMessage message) {
        RouteResult route = router.resolveRoute(config(), message);
        return gateway.runStream(route.context(), message.messages(), route.outboundAddress());
    }
}
```

Register it with `GatewayBootstrap`:

```java
GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(new MySlackChannel())
    .build();

gw.start();   // calls init() + start() on all channels
// ...
gw.stop();    // calls stop() on all channels
```

## Built-in channel adapters

AgentScope provides ready-to-use Channel adapters for popular messaging platforms as extension modules:

- [DingTalk](../../../integration/channel/dingtalk.md) — Stream protocol (persistent WebSocket)
- [Feishu / Lark](../../../integration/channel/feishu.md) — Event subscription callback
- [GitHub](../../../integration/channel/github.md) — Issue / PR comment webhook
- [GitLab](../../../integration/channel/gitlab.md) — Note hook
- [WeCom](../../../integration/channel/wecom.md) — Encrypted callback

See the [Channel Adapters](../../../integration/channel/index.md) integration overview for details.

## Related pages

- [Subagent](./subagent) — declaring and spawning subagents, background tasks, streaming forwarding
- [Architecture](./architecture) — how parent and child agents cooperate
