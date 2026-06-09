---
title: "Channel"
description: "通过 Channel 路由消息、管理会话、流式传输事件"
---

## 它们做什么

**Gateway** 位于你的应用代码和 agent 之间，负责：

- **会话管理** — 把每个用户对话映射到稳定的 session id。agent 在跨轮次时看到一致的记忆。
- **Per-session 并发控制** — 同一 session 的并发消息会公平排队，agent 不会和自己竞争。
- **Agent 路由** — 在多 agent 场景下，把每条消息路由到正确的 agent。

**Channel** 把消息平台（HTTP、WebSocket、Slack 等）适配成 Gateway 的路由模型。它负责解析消息来源、选定目标 agent、以及把回复投递回去。

大多数场景下你不需要直接和 Gateway 或 Channel 打交道——`agent.channel(...)` 会在后台自动完成所有接线。

## 快速开始

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .sysPrompt("你是一个有用的助手。")
    .model("dashscope:qwen-plus")
    .build();

// 绑定一个 ChatUI channel。
ChatUiChannel chat = agent.channel(ChatUiChannel.create());

// 发送消息。每个 userId 自动获得独立的 session。
Msg reply = chat.send(SendOptions.userId("user-1"), "你好！").block();

// 同一个用户，同一个 session——对话继续。
Msg followUp = chat.send(SendOptions.userId("user-1"), "再多说一些。").block();

// 不同用户，不同 session。
Msg otherUser = chat.send(SendOptions.userId("user-2"), "你好").block();
```

`agent.channel(...)` 会懒加载创建内部 gateway，注册当前 agent，并把 gateway 注入到 channel 中。调用之后 `chat` 就可以直接使用了。

### SendOptions

`SendOptions` 告诉 channel **谁**在说话、这属于**哪个对话**：

| 工厂方法 | 行为 |
|---------|------|
| `SendOptions.userId("user-1")` | 每个用户一个 session（最常用） |
| `SendOptions.of("user-1", "session-a")` | 指定 session——同一用户多个对话 |
| `SendOptions.userId("user-1").withAgentId("support")` | 在多 agent 场景下路由到指定 agent |

```java
// 同一用户，两个独立对话
chat.send(SendOptions.of("user-1", "session-a"), "话题 A").block();
chat.send(SendOptions.of("user-1", "session-b"), "话题 B").block();
```

## 流式事件 + SSE

`sendStream()` 返回 `Flux<AgentEvent>`，和 `agent.streamEvents()` 一样的细粒度事件流，但经过 gateway 路由并带有会话管理。

```java
chat.sendStream(SendOptions.userId("user-1"), "北京今天天气怎么样？")
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        } else if (event instanceof ToolCallStartEvent tc) {
            System.out.println("\n[tool] " + tc.getToolCallName());
        }
    })
    .blockLast();
```

### Spring Boot SSE Controller

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

## 与暴露的子 Agent 对话

当 agent 通过 `expose_to_user=true` spawn 子 agent 时，gateway 会把这个子 agent 暴露为用户可直接寻址的入口。一个 `SubagentExposedEvent` 会出现在 `sendStream()` 的事件流中，携带 `subagentId`。

### 发现暴露的子 Agent

```java
AtomicReference<String> subagentId = new AtomicReference<>();

chat.sendStream(SendOptions.userId("user-1"), "找一个研究员帮我调查 AI 趋势")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            subagentId.set(se.getSubagentId());
            System.out.printf("子 Agent 已暴露: id=%s agent=%s label=%s%n",
                    se.getSubagentId(), se.getAgentId(), se.getLabel());
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
    .blockLast();
```

`SubagentExposedEvent` 字段：

| 字段 | 说明 |
|------|------|
| `subagentId` | 用于向该子 agent 发消息的句柄 |
| `agentId` | 子 agent 类型（如 `"researcher"`） |
| `sessionId` | 子 agent 的 session id |
| `label` | 可选的人类可读名称 |

### 向子 Agent 发消息

拿到 `subagentId` 之后，可以直接和子 agent 对话——完全绕过父 agent：

```java
// 非流式
Msg reply = chat.sendToSubagent(subagentId, "重点关注 LLM agent").block();

// 流式
chat.sendToSubagentStream(subagentId, "重点关注 LLM agent")
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
    .blockLast();
```

### 带子 Agent 支持的 SSE

典型的 SSE controller 同时处理主 agent 和子 agent 消息：

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

客户端监听 `SUBAGENT_EXPOSED` 事件来渲染新的对话标签页，后续请求时把 `subagentId` 传回来即可。

## 多 HarnessAgent 路由

如果有多个 `HarnessAgent` 实例，使用 `GatewayBootstrap`：

```java
HarnessAgent salesAgent = HarnessAgent.builder()
    .name("sales").sysPrompt("你是一个销售助手。")
    .model("dashscope:qwen-plus").build();

HarnessAgent supportAgent = HarnessAgent.builder()
    .name("support").sysPrompt("你是一个客服 agent。")
    .model("dashscope:qwen-plus").build();

GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("sales", salesAgent)
    .agent("support", supportAgent)
    .mainAgent("sales")          // 没有指定 agent 时的默认
    .build();

ChatUiChannel chat = gw.chatUiChannel();
```

### 按 agentId 路由

使用 `SendOptions.withAgentId()` 把消息路由到指定 agent：

```java
// 路由到 sales（默认 main agent）
chat.send(SendOptions.userId("user-1"), "有什么产品？").block();

// 显式路由到 support
chat.send(SendOptions.userId("user-1").withAgentId("support"), "账单问题").block();
```

### GatewayBootstrap 下暴露子 Agent

要在 GatewayBootstrap 模式下启用 `expose_to_user`，需要把 gateway bridge 接到每个 agent 的子 agent 中间件上：

```java
GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", mainAgent)
    .build();

// 接入 bridge，让 agent_spawn(expose_to_user=true) 生效。
SubagentGatewayBridge bridge = gw.gatewayBridge();
// 通过 setGatewayBridge() 传给 agent 的 SubagentsMiddleware。
```

使用 `agent.channel(...)` 时，这个接线会自动完成。

## 自定义 Channel

实现 `Channel` 接口来适配新的消息平台：

```java
public class MySlackChannel implements Channel {
    @Override public String channelId() { return "slack"; }
    @Override public ChannelConfig config() { return myConfig; }
    @Override public void init(Gateway gateway) { this.gateway = gateway; }
    @Override public void start() { /* 连接 Slack */ }
    @Override public void stop() { /* 断开连接 */ }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        RouteResult route = router.resolveRoute(config(), message);
        return gateway.run(route.context(), message.messages(), route.outboundAddress());
    }

    // 可选：流式分发
    @Override
    public Flux<AgentEvent> dispatchStream(InboundMessage message) {
        RouteResult route = router.resolveRoute(config(), message);
        return gateway.runStream(route.context(), message.messages(), route.outboundAddress());
    }
}
```

通过 `GatewayBootstrap` 注册：

```java
GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(new MySlackChannel())
    .build();

gw.start();   // 调用所有 channel 的 init() + start()
// ...
gw.stop();    // 调用所有 channel 的 stop()
```

## 内置 Channel 适配器

AgentScope 提供了多个开箱即用的 Channel 适配器作为扩展模块：

- [钉钉](../../../integration/channel/dingtalk.md) — Stream 协议（持久 WebSocket）
- [飞书 / Lark](../../../integration/channel/feishu.md) — 事件订阅回调
- [GitHub](../../../integration/channel/github.md) — Issue / PR 评论 webhook
- [GitLab](../../../integration/channel/gitlab.md) — Note hook
- [企业微信](../../../integration/channel/wecom.md) — 加密回调

详见 [Channel 适配器](../../../integration/channel/index.md)集成总览。

## 相关文档

- [子 Agent](./subagent) — 声明和 spawn 子 agent、后台任务、流式转发
- [架构](./architecture) — 主/子 agent 如何协作
