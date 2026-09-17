---
title: 飞书 Channel
---

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

## 多租户部署

当渠道凭据是运行时数据——每个租户一个飞书应用，在进程持续服务的同时增删改——用 `FeishuCredentialResolver` 装配回调 controller，而不是为每个租户注册一个 channel：

```java
@Bean
FeishuTenantChannelManager feishuTenantChannels(TenantRepository tenantRepository, Gateway gateway) {
    return new FeishuTenantChannelManager(
        tenantKey -> tenantRepository.findByKey(tenantKey)   // 应用自有的查询
            .map(row -> FeishuChannelProperties.from(tenantKey, row.asPropertiesMap())),
        ChannelConfig.of("feishu", "main"),
        gateway);
}
// 组件扫描得到的 FeishuCallbackController 会注入该 bean；无需再声明 controller bean。
```

每次回调 URL 的 `{tenantKey}` 路径段被解析为凭据，该租户的 channel 首次使用时物化。解析器在每次回调时都会被调用，而 channel 的凭据是就地刷新的，因此轮换在下一个请求即生效且不替换 channel 对象——租户的会话与防循环保持不变。刷新按凭据的用途分组：只轮换 Encrypt Key 或 Verification Token 会保留已缓存的 tenant access token，轮换 App Secret 则开启新的凭据代、由其自行获取新 token——每一代各自持有一个 token store，上一代仍在途的请求无法把它的 token 留给新一代。

租户 key 同时用作 channel id：会话与防循环都按租户隔离，用户 id 相同的两个租户不会共享会话。共享的 `ChannelConfig` 可用 `channel` 级 binding 为不同租户指定不同的 agent。

注意：

- 解析返回空表示「无此租户」，回调被拒绝；查询失败请直接抛异常，它会体现为一次失败的回调，平台会重试。
- `FeishuTenantChannelManager.evict(tenantKey)` 释放已删除租户的 channel 与 token 缓存。
- 物化的 channel 归 manager 所有：既不注册到 `FeishuChannelRegistry`，也不注册到 harness 的 `ChannelManager`——通过 `ChannelManager#deliver` 的主动推送到达不了它们。
- Spring 装配：暴露一个 `FeishuTenantChannelManager` bean 即可。组件扫描得到的 controller 会注入它并服务租户路由；没有该 bean 时继续服务静态注册表。不要再声明第二个 `FeishuCallbackController` bean——其请求映射会与扫描到的那个冲突，导致启动失败。
