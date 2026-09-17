---
title: 企业微信 Channel
---

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

## 多租户部署

当渠道凭据是运行时数据——每个租户一个企业微信应用，在进程持续服务的同时增删改——用 `WeComCredentialResolver` 装配回调 controller，而不是为每个租户注册一个 channel：

```java
@Bean
WeComTenantChannelManager weComTenantChannels(TenantRepository tenantRepository, Gateway gateway) {
    return new WeComTenantChannelManager(
        tenantKey -> tenantRepository.findByKey(tenantKey)   // 应用自有的查询
            .map(row -> WeComChannelProperties.from(tenantKey, row.asPropertiesMap())),
        ChannelConfig.of("wecom", "main"),
        gateway);
}
// 组件扫描到的 WeComCallbackController 会自动注入该 bean，无需再声明 controller bean。
```

每次回调 URL 的 `{tenantKey}` 路径段被解析为凭据，该租户的 channel 首次使用时物化。解析器在每次回调时都会被调用，而 channel 的凭据是就地刷新的，因此轮换在下一个请求即生效且不替换 channel 对象——租户的会话、去重状态与防循环保持不变。刷新按凭据的用途分组：只轮换回调 token 会保留已缓存的 access token，轮换 secret 则丢弃它——每一代凭据各自持有一个 token 缓存，仍在使用上一代凭据的在途请求不会把它的 token 留给新一代。

租户 key 同时用作 channel id：会话、去重 key 与防循环都按租户隔离，用户 id 相同的两个租户不会共享会话。共享的 `ChannelConfig` 可用 `channel` 级 binding 为不同租户指定不同的 agent。

注意：

- 解析返回空表示「无此租户」，回调被拒绝；查询失败请直接抛异常，它会体现为一次失败的回调，平台会重试。
- `WeComTenantChannelManager.evict(tenantKey)` 释放已删除租户的 channel 与 token 缓存。
- 物化的 channel 归 manager 所有：既不注册到 `WeComChannelRegistry`，也不注册到 harness 的 `ChannelManager`——通过 `ChannelManager#deliver` 的主动推送到达不了它们。
- Spring 装配：暴露一个 `WeComTenantChannelManager` bean。组件扫描到的 controller 会注入它并服务租户路由；没有该 bean 时仍走静态 registry。不要再声明第二个 `WeComCallbackController` bean——其请求映射会与扫描到的实例冲突并导致启动失败。
