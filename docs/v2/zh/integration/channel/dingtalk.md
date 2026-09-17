---
title: 钉钉 Channel
---

`agentscope-extensions-channel-dingtalk` 将你的 Agent 接入钉钉，支持两种接收模式：**Stream**（默认）——持久 WebSocket 实时接收机器人消息，无需暴露公网 webhook 端点；**HTTP 回调**——钉钉将消息以 App Secret 加签后 POST 到你的 Spring 应用。

## 适用场景

- Agent 需要响应钉钉机器人消息（单聊和群 @提醒）。
- 倾向 WebSocket 推送模型（Stream，默认）：无需公网端点、无需接入层配置。
- 倾向 HTTP 回调：应用已暴露公网回调端点，或机器人/凭据需运行时动态增删（多租户部署），希望用单一无状态入口替代一组 WebSocket 连接。

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
4. HTTP 回调模式：将机器人的消息接收配置为 HTTP 模式，回调地址填 `https://your-host/api/channels/dingtalk/{channelId}/callback`；若开启了消息体加密，另记下 **aes_key**。

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

改用 HTTP 回调模式（要求 Spring Web 应用；`DingTalkCallbackController` 由组件扫描装配）：

```java
DingTalkChannel channel = DingTalkChannel.fromProperties(
    "my-dingtalk",
    ChannelConfig.of("my-dingtalk", "main"),
    Map.of(
        "appKey",    "your-app-key",
        "appSecret", "your-app-secret",
        "robotCode", "your-robot-code",
        "mode",      "http",
        "aesKey",    "your-43-char-aes-key"   // 仅在开启消息体加密时需要
    ));
```

## 配置属性

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `appKey` | 是 | — | 企业内部应用 App Key |
| `appSecret` | 是 | — | 企业内部应用 App Secret |
| `robotCode` | 是 | — | 机器人编码，用于出站消息发送 |
| `mode` | 否 | `stream` | 接收模式：`stream`（持久 WebSocket）或 `http`（加签 HTTP 回调） |
| `aesKey` | 否 | — | 43 位 base64 AES 密钥，用于回调消息体解密；仅 `http` 模式，且仅在机器人开启加密时必填 |
| `apiBase` | 否 | `https://api.dingtalk.com` | OpenAPI 基地址 |
| `streamRegisterUrl` | 否 | `https://api.dingtalk.com/v1.0/gateway/connections/open` | Stream 网关注册地址 |

> **注意：** 面向公网的 `http` 回调建议开启消息体加密（`aesKey`）：请求签名仅认证 `timestamp` 请求头，不开启加密时，被截获的签名对在有效时间窗口内仍可重放。

## 消息流转

**入站（Stream）：** `DingTalkStreamClient` 通过 WebSocket 连接钉钉网关，接收机器人消息回调，ACK 每个帧后进入 `DingTalkInboundMapper` → 幂等去重 → 防循环 → Gateway。

**入站（HTTP 回调）：** 钉钉携带 `timestamp`/`sign` 请求头将消息 POST 到 `DingTalkCallbackController`。控制器先校验 HMAC-SHA256 签名（并拒绝过期时间戳），配置了 `aesKey` 时解密 `encrypt` 信封，随后进入与 Stream 模式相同的 去重 → 映射 → 防循环 → Gateway 管线。回复经由出站 API 发送，不通过 HTTP 响应返回。

**出站：** 通过 `DingTalkOutboundClient` 使用 OpenAPI 的 `batchSend` 接口发送回复——`oToMessages/batchSend` 用于单聊，`groupMessages/send` 用于群聊。文本和 Markdown 格式自动识别。

## 断线重连

Stream 模式下，客户端在 WebSocket 断开时自动以指数退避（1s → 60s 上限）重连。

## 多租户部署

当渠道凭据是运行时数据——每个租户一个钉钉企业应用，在进程持续服务的同时增删改——用 `DingTalkCredentialResolver` 装配回调 controller，而不是为每个租户注册一个 channel：

```java
@Bean
DingTalkTenantChannelManager dingTalkTenantChannels(TenantRepository tenantRepository, Gateway gateway) {
    return new DingTalkTenantChannelManager(
        tenantKey -> tenantRepository.findByKey(tenantKey)   // 应用自有的查询
            .map(row -> DingTalkChannelProperties.from(tenantKey, row.asPropertiesMap())),
        ChannelConfig.of("dingtalk", "main"),
        gateway);
}
// 组件扫描到的 DingTalkCallbackController 会自动注入该 bean，无需再声明 controller bean。
```

该装配面向 **http 回调模式**：解析结果必须配置 `mode=http`，因为租户的 channel 是按回调物化的——stream 模式的 WebSocket 在建连时绑定凭据，仍走静态装配。

每次回调 URL 的 `{tenantKey}` 路径段被解析为凭据，该租户的 channel 首次使用时物化。解析器在每次回调时都会被调用，而 channel 的凭据是就地刷新的，因此轮换在下一个请求即生效且不替换 channel 对象——租户的会话、去重状态与防循环保持不变。刷新按凭据的用途分组：只轮换回调 AES Key 会保留已缓存的 access token，轮换 App Secret 则丢弃它（并重建其 HMAC 同样依赖的请求签名 crypto）——每一代凭据各自持有一个 token 缓存，仍在使用上一代凭据的在途请求不会把它的 token 留给新一代。未知租户与其余被拒请求一样返回 401，端点因此不构成租户 key 探测口。

租户 key 同时用作 channel id：会话、去重 key 与防循环都按租户隔离，用户 id 相同的两个租户不会共享会话。共享的 `ChannelConfig` 可用 `channel` 级 binding 为不同租户指定不同的 agent。

注意：

- 解析返回空表示「无此租户」，回调被拒绝；查询失败请直接抛异常，它会体现为一次失败的回调，平台会重试。
- `DingTalkTenantChannelManager.evict(tenantKey)` 释放已删除租户的 channel 与 token 缓存。
- 物化的 channel 归 manager 所有：既不注册到 `DingTalkChannelRegistry`，也不注册到 harness 的 `ChannelManager`——通过 `ChannelManager#deliver` 的主动推送到达不了它们。
- Spring 装配：暴露一个 `DingTalkTenantChannelManager` bean。组件扫描到的 controller 会注入它并服务租户路由；没有该 bean 时仍走静态 registry。不要再声明第二个 `DingTalkCallbackController` bean——其请求映射会与扫描到的实例冲突并导致启动失败。
