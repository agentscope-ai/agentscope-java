# 钉钉 Channel

`agentscope-extensions-channel-dingtalk` 通过 **Stream 协议**（持久 WebSocket）将你的 Agent 接入钉钉，无需暴露公网 webhook 端点即可实时接收机器人消息。

## 适用场景

- Agent 需要响应钉钉机器人消息（单聊和群 @提醒）。
- 你更倾向于 WebSocket 推送模型而非轮询或 webhook 回调。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-dingtalk</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 前置准备

1. 在[钉钉开发者后台](https://open-dev.dingtalk.com/)创建一个**企业内部应用**。
2. 启用**机器人**能力并订阅机器人消息 topic。
3. 记下 **App Key**、**App Secret** 和 **Robot Code**。

## 快速开始

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

gw.start();   // 打开 Stream WebSocket，开始接收消息
```

## 配置属性

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `appKey` | 是 | — | 企业内部应用 App Key |
| `appSecret` | 是 | — | 企业内部应用 App Secret |
| `robotCode` | 是 | — | 机器人编码，用于出站消息发送 |
| `apiBase` | 否 | `https://api.dingtalk.com` | OpenAPI 基地址 |
| `streamRegisterUrl` | 否 | `https://api.dingtalk.com/v1.0/gateway/connections/open` | Stream 网关注册地址 |

## 消息流转

**入站：** `DingTalkStreamClient` 通过 WebSocket 连接钉钉网关，接收机器人消息回调，ACK 每个帧后进入 `DingTalkInboundMapper` → 幂等去重 → 防循环 → Gateway。

**出站：** 通过 `DingTalkOutboundClient` 使用 OpenAPI 的 `batchSend` 接口发送回复——`oToMessages/batchSend` 用于单聊，`groupMessages/send` 用于群聊。文本和 Markdown 格式自动识别。

## 断线重连

Stream 客户端在 WebSocket 断开时自动以指数退避（1s → 60s 上限）重连。
