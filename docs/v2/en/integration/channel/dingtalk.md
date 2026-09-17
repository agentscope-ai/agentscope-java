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

## Multi-tenant deployments

When channel credentials are runtime data — one DingTalk enterprise app per tenant, added, rotated and removed while the process serves traffic — wire the callback controller with a `DingTalkCredentialResolver` instead of registering one channel per tenant:

```java
@Bean
DingTalkTenantChannelManager dingTalkTenantChannels(TenantRepository tenantRepository, Gateway gateway) {
    return new DingTalkTenantChannelManager(
        tenantKey -> tenantRepository.findByKey(tenantKey)   // application-owned lookup
            .map(row -> DingTalkChannelProperties.from(tenantKey, row.asPropertiesMap())),
        ChannelConfig.of("dingtalk", "main"),
        gateway);
}
// The component-scanned DingTalkCallbackController injects this bean; no controller bean is needed.
```

This serves tenants over the **http callback mode**: resolved properties must configure `mode=http`, because a tenant's channel is materialized per callback — a stream-mode WebSocket binds its credentials at connect time and stays on the static wiring.

The `{tenantKey}` path segment of each callback resolves to credentials, and the tenant's channel is materialized on first use. The resolver is consulted on every callback and the channel's credentials are refreshed in place, so a rotation takes effect on the next request without replacing the channel — the tenant keeps its sessions, deduplication state and bot-loop guard. Refresh is grouped by what the credentials feed: rotating only the callback AES key keeps the cached access token, while rotating the app secret discards it (and rebuilds the request-signature crypto, whose HMAC it also feeds) — each credential generation mints into a token store of its own, so a request still in flight on the previous generation cannot leave its token behind for the new one. An unknown tenant answers 401 like every other rejected request, so the endpoint is not a tenant-key oracle.

The tenant key doubles as the channel id: conversations, deduplication keys and bot-loop guards are namespaced per tenant, so two tenants whose user ids collide do not share sessions. Per-tenant agent routing is expressed with `channel`-tier bindings in the shared `ChannelConfig`.

Notes:

- An empty resolve result means "no such tenant" and the callback is rejected; let lookup failures throw, so they surface as a failed callback and the platform retries.
- `DingTalkTenantChannelManager.evict(tenantKey)` releases a deleted tenant's channel and token cache.
- Materialized channels are owned by the manager: they are not registered in `DingTalkChannelRegistry` or in a harness `ChannelManager`, so proactive delivery through `ChannelManager#deliver` does not reach them.
- Spring wiring: expose a `DingTalkTenantChannelManager` bean. The component-scanned controller injects it and serves the tenant route; with no such bean it keeps serving the static registry. Do not declare a second `DingTalkCallbackController` bean — its request mappings would collide with the scanned one's and fail startup.
