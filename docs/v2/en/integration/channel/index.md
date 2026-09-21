---
title: Channel Adapters
---

These extensions connect your Agent to real-world messaging platforms through the Harness [Channel](/v2/en/docs/harness/channel) interface. Each adapter handles platform-specific authentication, webhook verification, message parsing, and reply delivery — so your Agent code stays platform-agnostic.

| Extension | Platform | Transport |
| --- | --- | --- |
| [DingTalk](/v2/en/integration/channel/dingtalk) | DingTalk (钉钉) | Stream protocol (persistent WebSocket) |
| [Feishu](/v2/en/integration/channel/feishu) | Feishu / Lark (飞书) | Event subscription callback (HTTP) |
| [GitHub](/v2/en/integration/channel/github) | GitHub | Webhook (HTTP) |
| [GitLab](/v2/en/integration/channel/gitlab) | GitLab | Webhook (HTTP) |
| [WeCom](/v2/en/integration/channel/wecom) | WeCom (企业微信) | Encrypted callback (HTTP) |

## How it works

Each channel adapter follows the same pattern:

1. **Inbound** — receives messages from the platform (via webhook, WebSocket, etc.), parses them into a normalized `InboundMessage`, deduplicates, applies bot-loop protection, and dispatches through the Gateway.
2. **Outbound** — delivers agent replies back to the platform through the platform's send API.

All adapters share three common utilities from `agentscope-extensions-channel-common`:

- **IdempotencyStore** — deduplicates retried webhook deliveries by message id.
- **BotLoopGuard** — per-peer rate limiter that prevents runaway bot-to-bot loops.
- **AccessTokenStore** — caches the outbound access token used for platform API calls.

## Multi-instance deployments

The common utilities keep state in the JVM by default (`IdempotencyStore`,
`InMemoryAccessTokenStore`), which only sees a single process. When channel adapters run on
several replicas — behind a load balancer, or one process per bot — construct them through the
`fromProperties` overload that accepts shared-storage implementations:

```java
FeishuChannel.fromProperties(channelId, routing, rawProperties, deduplicator, tokenStore);
```

- A shared `InboundEventDeduplicator` recognizes platform redeliveries across instances.
- A shared `AccessTokenStore` (Feishu, WeCom, DingTalk) lets one replica's token refresh or
  invalidation serve the whole deployment, instead of each replica fetching and caching its own
  token for the same credential.

## Shared dependency

Every channel adapter depends on `agentscope-extensions-channel-common` (included transitively) and `agentscope-harness` (provided at runtime by your application).
