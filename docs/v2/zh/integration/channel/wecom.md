# 企业微信 Channel

`agentscope-extensions-channel-wecom` 通过**加密回调**机制将你的 Agent 接入企业微信（WeCom / WeChat Work）。一个 Spring `@RestController` 接收消息回调，解密后通过 Gateway 分发。

## 适用场景

- Agent 需要响应企业微信机器人消息（单聊和群聊）。
- 你的应用已经运行 Spring Boot。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-wecom</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 前置准备

1. 在[企业微信管理后台](https://work.weixin.qq.com/)创建一个**应用**。
2. 启用**接收消息** API 并配置回调 URL：
   `https://your-host/api/channels/wecom/{channelId}/callback`
3. 记下 **Corp ID**、**Agent ID**、**Secret**、**Token** 和 **EncodingAESKey**。

## 快速开始

```java
WeComChannel channel = WeComChannel.fromProperties(
    "my-wecom",
    ChannelConfig.of("my-wecom", "main"),
    Map.of(
        "corpId",         "your-corp-id",
        "agentId",        "1000002",
        "secret",         "your-secret",
        "token",          "your-callback-token",
        "encodingAesKey",  "your-encoding-aes-key"
    ));

GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(channel)
    .build();

gw.start();
```

## 配置属性

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `corpId` | 是 | — | 企业 Corp ID |
| `agentId` | 是 | — | 应用 Agent ID |
| `secret` | 是 | — | 应用密钥，用于获取 access token |
| `token` | 是 | — | 回调 token，用于签名校验 |
| `encodingAesKey` | 是 | — | AES 密钥，用于消息加解密 |
| `callbackPath` | 否 | `/api/channels/wecom/{channelId}/callback` | 自定义回调路径 |
| `apiBase` | 否 | `https://qyapi.weixin.qq.com` | 企业微信 API 基地址 |

## 加密

所有企业微信回调都是加密的。适配器使用 `WeComCrypto` 自动处理解密和签名校验，实现了[企业微信回调加密规范](https://developer.work.weixin.qq.com/document/path/90238)。

## 消息流转

**入站：** `WeComCallbackController` → URL 验证（echostr） → 解密 → MsgId 去重 → `WeComInboundMapper`（文本消息） → 防循环 → Gateway。

**出站：** `WeComOutboundClient` 通过 `/cgi-bin/message/send`（单聊）或 `/cgi-bin/appchat/send`（群聊）发送回复，使用 `WeComAccessTokenProvider` 获取 `access_token`。
