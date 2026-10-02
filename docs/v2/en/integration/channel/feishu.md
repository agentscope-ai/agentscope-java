# Feishu Channel

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
