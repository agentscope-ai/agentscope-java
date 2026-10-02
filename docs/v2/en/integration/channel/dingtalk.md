# DingTalk Channel

`agentscope-extensions-channel-dingtalk` connects your Agent to DingTalk (钉钉) using the **Stream protocol** — a persistent WebSocket that receives bot messages in real time without exposing a public webhook endpoint.

## When to use

- Your Agent needs to respond to DingTalk bot messages (DM and group @-mentions).
- You prefer a WebSocket-based push model over polling or webhook callbacks.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-dingtalk</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Prerequisites

1. Create an **Enterprise Internal App** in the [DingTalk Developer Console](https://open-dev.dingtalk.com/).
2. Enable the **Bot** capability and subscribe to the bot-messages topic.
3. Note down the **App Key**, **App Secret**, and **Robot Code**.

## Quickstart

```java
DingTalkChannel channel = DingTalkChannel.fromProperties(
    "my-dingtalk",
    ChannelConfig.of("my-dingtalk", "main"),
    Map.of(
        "appKey",    "your-app-key",
        "appSecret", "your-app-secret",
        "robotCode", "your-robot-code"
    ));

GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(channel)
    .build();

gw.start();   // opens the Stream WebSocket and begins dispatching
```

## Configuration properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `appKey` | Yes | — | Enterprise internal app key |
| `appSecret` | Yes | — | Enterprise internal app secret |
| `robotCode` | Yes | — | Robot code used as outbound sender id |
| `apiBase` | No | `https://api.dingtalk.com` | OpenAPI base URL |
| `streamRegisterUrl` | No | `https://api.dingtalk.com/v1.0/gateway/connections/open` | Stream gateway registration endpoint |

## Message flow

**Inbound:** The `DingTalkStreamClient` opens a WebSocket to the DingTalk gateway, receives bot-message callbacks, ACKs each frame, then dispatches through `DingTalkInboundMapper` → idempotency check → bot-loop guard → Gateway.

**Outbound:** Replies are sent via `DingTalkOutboundClient` using the OpenAPI `batchSend` endpoints — `oToMessages/batchSend` for DMs and `groupMessages/send` for groups. Text and Markdown formats are auto-detected.

## Reconnection

The Stream client reconnects automatically with exponential backoff (1s → 60s cap) when the WebSocket drops.
