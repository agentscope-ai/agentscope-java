---
title: Channel 适配器
---

这些扩展通过 Harness 的 [Channel](/v2/zh/docs/harness/channel) 接口将你的 Agent 接入真实的消息平台。每个适配器负责平台特有的认证、webhook 签名校验、消息解析和回复投递——你的 Agent 代码无需感知平台差异。

| 扩展 | 平台 | 传输方式 |
| --- | --- | --- |
| [钉钉](/v2/zh/integration/channel/dingtalk) | DingTalk（钉钉） | Stream 协议（持久 WebSocket） |
| [飞书](/v2/zh/integration/channel/feishu) | Feishu / Lark（飞书） | 事件订阅回调（HTTP） |
| [GitHub](/v2/zh/integration/channel/github) | GitHub | Webhook（HTTP） |
| [GitLab](/v2/zh/integration/channel/gitlab) | GitLab | Webhook（HTTP） |
| [企业微信](/v2/zh/integration/channel/wecom) | WeCom（企业微信） | 加密回调（HTTP） |

## 工作原理

所有 channel 适配器遵循相同模式：

1. **入站** — 从平台接收消息（通过 webhook、WebSocket 等），解析为统一的 `InboundMessage`，去重、防循环，然后通过 Gateway 分发。
2. **出站** — 通过平台的发送 API 把 Agent 回复投递回去。

所有适配器共享 `agentscope-extensions-channel-common` 中的三个通用组件：

- **IdempotencyStore** — 按消息 id 去重，防止 webhook 重试导致重复处理。
- **BotLoopGuard** — 按 peer 限速，防止 bot 之间的消息死循环。
- **AccessTokenStore** — 缓存出站调用平台 API 所用的 access token。

## 多实例部署

上述通用组件默认将状态保存在 JVM 内（`IdempotencyStore`、`InMemoryAccessTokenStore`），只在单进程内可见。当 channel 适配器运行在多个副本上（负载均衡后多实例，或每个 bot 一个进程）时，请通过接受共享存储实现的 `fromProperties` 重载构造适配器：

```java
FeishuChannel.fromProperties(channelId, routing, rawProperties, deduplicator, tokenStore);
```

- 共享的 `InboundEventDeduplicator` 使平台重试投递在所有实例间都能被识别。
- 共享的 `AccessTokenStore`（飞书、企业微信、钉钉）让任一副本的 token 刷新或失效服务于整个部署，而不是每个副本为同一凭据各自获取、各自缓存。

## 共享依赖

每个 channel 适配器都依赖 `agentscope-extensions-channel-common`（传递依赖自动引入）和 `agentscope-harness`（由你的应用在运行时提供）。
