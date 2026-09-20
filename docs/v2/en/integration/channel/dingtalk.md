---
title: DingTalk Channel
---

`agentscope-extensions-channel-dingtalk` connects your Agent to DingTalk (钉钉). It supports two reception modes: **Stream** (default) — a persistent WebSocket that receives bot messages in real time without exposing a public webhook endpoint — and **HTTP callback** — DingTalk POSTs each message to your Spring application, signed with your App Secret.

## When to use

- Your Agent needs to respond to DingTalk bot messages (DM and group @-mentions).
- Prefer the WebSocket push model (Stream, default): no public endpoint, no ingress configuration.
- Prefer HTTP callbacks: your application already exposes public callback endpoints, or robots/credentials change at runtime (multi-tenant deployments) and you want a single stateless ingress instead of a pool of WebSocket connections.

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
4. For HTTP callback mode: configure the robot's message reception to HTTP mode with the callback URL `https://your-host/api/channels/dingtalk/{channelId}/callback`, and note down the **aes_key** if you enable body encryption.

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

HTTP callback mode instead (requires a Spring web application; the `DingTalkCallbackController` is picked up by component scan):

```java
DingTalkChannel channel = DingTalkChannel.fromProperties(
    "my-dingtalk",
    ChannelConfig.of("my-dingtalk", "main"),
    Map.of(
        "appKey",    "your-app-key",
        "appSecret", "your-app-secret",
        "robotCode", "your-robot-code",
        "mode",      "http",
        "aesKey",    "your-43-char-aes-key"   // only if body encryption is enabled
    ));
```

## Configuration properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `appKey` | Yes | — | Enterprise internal app key |
| `appSecret` | Yes | — | Enterprise internal app secret |
| `robotCode` | Yes | — | Robot code used as outbound sender id |
| `mode` | No | `stream` | Reception mode: `stream` (persistent WebSocket) or `http` (signed HTTP callbacks) |
| `aesKey` | No | — | 43-character base64 AES key for callback body decryption; `http` mode only, required only when the robot is configured with encryption |
| `apiBase` | No | `https://api.dingtalk.com` | OpenAPI base URL |
| `streamRegisterUrl` | No | `https://api.dingtalk.com/v1.0/gateway/connections/open` | Stream gateway registration endpoint |

> **Note:** For internet-facing `http` callbacks, enabling message encryption (`aesKey`) is recommended: the request signature authenticates only the `timestamp` header, so without encryption a captured signature pair remains replayable within the freshness window.

## Message flow

**Inbound (Stream):** The `DingTalkStreamClient` opens a WebSocket to the DingTalk gateway, receives bot-message callbacks, ACKs each frame, then dispatches through `DingTalkInboundMapper` → idempotency check → bot-loop guard → Gateway.

**Inbound (HTTP callback):** DingTalk POSTs each bot message to `DingTalkCallbackController` with `timestamp`/`sign` headers. The controller verifies the HMAC-SHA256 signature (rejecting stale timestamps), decrypts the `encrypt` envelope when an `aesKey` is configured, then hands the payload to the same dedup → mapping → bot-loop guard → Gateway pipeline. Replies are delivered through the outbound API, not the HTTP response.

**Outbound:** Replies are sent via `DingTalkOutboundClient` using the OpenAPI `batchSend` endpoints — `oToMessages/batchSend` for DMs and `groupMessages/send` for groups. Text and Markdown formats are auto-detected.

## Reconnection

In Stream mode, the client reconnects automatically with exponential backoff (1s → 60s cap) when the WebSocket drops.
