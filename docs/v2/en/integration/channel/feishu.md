---
title: Feishu Channel
---

`agentscope-extensions-channel-feishu` connects your Agent to Feishu / Lark (飞书) via the **Event Subscription v2** callback mechanism. A Spring `@RestController` receives webhook callbacks, optionally decrypts encrypted payloads, and dispatches messages through the Gateway.

## When to use

- Your Agent needs to respond to Feishu bot messages in 1:1 chats or group @-mentions.
- Your application already runs Spring Boot (the callback controller auto-registers).

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-feishu</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Prerequisites

1. Create a **Custom App** in the [Feishu Developer Console](https://open.feishu.cn/).
2. Enable the **Bot** capability.
3. Configure the **Event Subscription** callback URL to point to your application:
   `https://your-host/api/channels/feishu/{channelId}/callback`
4. Note down the **App ID** and **App Secret**. Optionally configure an **Encrypt Key** and **Verification Token**.

## Quickstart

```java
FeishuChannel channel = FeishuChannel.fromProperties(
    "my-feishu",
    ChannelConfig.of("my-feishu", "main"),
    Map.of(
        "appId",     "cli_xxxxx",
        "appSecret", "your-app-secret"
    ));

GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(channel)
    .build();

gw.start();
```

The `FeishuCallbackController` is a Spring `@RestController` that auto-registers at `/api/channels/feishu/{channelId}/callback`. It handles the URL verification handshake automatically.

## Configuration properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `appId` | Yes | — | Feishu custom-app id (cli_xxx) |
| `appSecret` | Yes | — | Feishu custom-app secret |
| `encryptKey` | No | — | AES-256-CBC encrypt key; enables payload encryption |
| `verificationToken` | No | — | URL verification token for the challenge handshake |
| `callbackPath` | No | `/api/channels/feishu/{channelId}/callback` | Override the callback URL path |
| `apiBase` | No | `https://open.feishu.cn` | Feishu Open API base URL |

## Encryption

When `encryptKey` is configured, the callback body arrives as `{"encrypt":"<base64>"}`. The adapter decrypts it automatically (AES-256-CBC with SHA-256 key derivation) and verifies the `X-Lark-Signature` header.

## Message flow

**Inbound:** `FeishuCallbackController` → optional decryption → URL verification check → event_id dedup → `FeishuInboundMapper` (text messages only in MVP) → bot-loop guard → Gateway.

**Outbound:** `FeishuOutboundClient` sends replies via `POST /open-apis/im/v1/messages` with a `tenant_access_token` from `FeishuAccessTokenProvider`. Tokens are cached and proactively refreshed at ~80% of TTL.

## Multi-tenant deployments

When channel credentials are runtime data — one Feishu app per tenant, added, rotated and removed while the process serves traffic — wire the callback controller with a `FeishuCredentialResolver` instead of registering one channel per tenant:

```java
@Bean
FeishuTenantChannelManager feishuTenantChannels(TenantRepository tenantRepository, Gateway gateway) {
    return new FeishuTenantChannelManager(
        tenantKey -> tenantRepository.findByKey(tenantKey)   // application-owned lookup
            .map(row -> FeishuChannelProperties.from(tenantKey, row.asPropertiesMap())),
        ChannelConfig.of("feishu", "main"),
        gateway);
}
// The component-scanned FeishuCallbackController injects this bean; no controller bean is needed.
```

The `{tenantKey}` path segment of each callback resolves to credentials, and the tenant's channel is materialized on first use. The resolver is consulted on every callback and the channel's credentials are refreshed in place, so a rotation takes effect on the next request without replacing the channel — the tenant keeps its sessions and bot-loop guard. Refresh is grouped by what the credentials feed: rotating only the encrypt key or the verification token keeps the cached tenant access token, while rotating the app secret starts a credential generation that mints its own — each generation gets a token store of its own, so a request still in flight on the previous generation cannot leave its token behind for the new one.

The tenant key doubles as the channel id: conversations and bot-loop guards are namespaced per tenant, so two tenants whose user ids collide do not share sessions. Per-tenant agent routing is expressed with `channel`-tier bindings in the shared `ChannelConfig`.

Notes:

- An empty resolve result means "no such tenant" and the callback is rejected; let lookup failures throw, so they surface as a failed callback and the platform retries.
- `FeishuTenantChannelManager.evict(tenantKey)` releases a deleted tenant's channel and token cache.
- Materialized channels are owned by the manager: they are not registered in `FeishuChannelRegistry` or in a harness `ChannelManager`, so proactive delivery through `ChannelManager#deliver` does not reach them.
- Spring wiring: expose a `FeishuTenantChannelManager` bean. The component-scanned controller injects it and serves the tenant route; with no such bean it keeps serving the static registry. Do not declare a second `FeishuCallbackController` bean — its request mappings would collide with the scanned one's and fail startup.
