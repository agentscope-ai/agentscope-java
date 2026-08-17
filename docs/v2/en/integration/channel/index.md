# Channel Adapters

These extensions connect your Agent to real-world messaging platforms through the Harness [Channel](../../docs/harness/channel.md) interface. Each adapter handles platform-specific authentication, webhook verification, message parsing, and reply delivery — so your Agent code stays platform-agnostic.

| Extension | Platform | Transport |
| --- | --- | --- |
| [DingTalk](dingtalk.md) | DingTalk (钉钉) | Stream protocol (persistent WebSocket) |
| [Feishu](feishu.md) | Feishu / Lark (飞书) | Event subscription callback (HTTP) |
| [GitHub](github.md) | GitHub | Webhook (HTTP) |
| [GitLab](gitlab.md) | GitLab | Webhook (HTTP) |
| [WeCom](wecom.md) | WeCom (企业微信) | Encrypted callback (HTTP) |

## How it works

Each channel adapter follows the same pattern:

1. **Inbound** — receives messages from the platform (via webhook, WebSocket, etc.), parses them into a normalized `InboundMessage`, deduplicates, applies bot-loop protection, and dispatches through the Gateway.
2. **Outbound** — delivers agent replies back to the platform through the platform's send API.

All adapters share two common utilities from `agentscope-extensions-channel-common`:

- **IdempotencyStore** — deduplicates retried webhook deliveries by message id.
- **BotLoopGuard** — per-peer rate limiter that prevents runaway bot-to-bot loops.

## Shared dependency

Every channel adapter depends on `agentscope-extensions-channel-common` (included transitively) and `agentscope-harness` (provided at runtime by your application).
