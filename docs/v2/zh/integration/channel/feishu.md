# 飞书 Channel

`agentscope-extensions-channel-feishu` 通过**事件订阅 v2** 回调机制将你的 Agent 接入飞书 / Lark。一个 Spring `@RestController` 接收 webhook 回调，可选地解密加密载荷，然后通过 Gateway 分发消息。

## 适用场景

- Agent 需要响应飞书机器人消息（单聊和群 @提醒）。
- 你的应用已经运行 Spring Boot（回调控制器自动注册）。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-feishu</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 前置准备

1. 在[飞书开发者后台](https://open.feishu.cn/)创建一个**自建应用**。
2. 启用**机器人**能力。
3. 配置**事件订阅**回调地址指向你的应用：
   `https://your-host/api/channels/feishu/{channelId}/callback`
4. 记下 **App ID** 和 **App Secret**。可选地配置 **Encrypt Key** 和 **Verification Token**。

## 快速开始

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

`FeishuCallbackController` 是一个 Spring `@RestController`，自动注册在 `/api/channels/feishu/{channelId}/callback`，并自动处理 URL 验证握手。

## 配置属性

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `appId` | 是 | — | 飞书自建应用 ID（cli_xxx） |
| `appSecret` | 是 | — | 飞书自建应用密钥 |
| `encryptKey` | 否 | — | AES-256-CBC 加密密钥；启用载荷加密 |
| `verificationToken` | 否 | — | URL 验证 token |
| `callbackPath` | 否 | `/api/channels/feishu/{channelId}/callback` | 自定义回调路径 |
| `apiBase` | 否 | `https://open.feishu.cn` | 飞书开放平台 API 基地址 |

## 加密

配置 `encryptKey` 后，回调 body 以 `{"encrypt":"<base64>"}` 形式到达。适配器自动解密（AES-256-CBC + SHA-256 密钥派生）并校验 `X-Lark-Signature` 头。

## 消息流转

**入站：** `FeishuCallbackController` → 可选解密 → URL 验证检查 → event_id 去重 → `FeishuInboundMapper`（当前仅支持文本消息） → 防循环 → Gateway。

**出站：** `FeishuOutboundClient` 通过 `POST /open-apis/im/v1/messages` 发送回复，使用 `FeishuAccessTokenProvider` 获取 `tenant_access_token`。Token 自动缓存并在约 80% TTL 时主动刷新。
