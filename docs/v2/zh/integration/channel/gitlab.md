# GitLab Channel

`agentscope-extensions-channel-gitlab` 将你的 Agent 接入 GitLab 评论（Note）hook。当有人在 issue 或 merge request 中评论时，Agent 以新 note 的形式回复。

## 适用场景

- 你需要一个能响应 GitLab issue / merge request 评论的 AI 机器人。
- 你使用 GitLab SaaS 或自建的 GitLab 实例。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-gitlab</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 前置准备

1. 创建一个具有 `api` 权限的 **Personal Access Token**（或 Project/Group Access Token）。
2. 在项目或群组上配置 **webhook**：
   - URL：`https://your-host/api/channels/gitlab/{channelId}/webhook`
   - Secret token：（可选，用于签名校验）
   - Trigger：勾选 **Note events**

## 快速开始

```java
GitLabChannel channel = GitLabChannel.fromProperties(
    "my-gitlab",
    ChannelConfig.of("my-gitlab", "main"),
    Map.of(
        "token", "glpat-xxxxxxxxxxxx"
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
| `token` | 是 | — | GitLab API 访问令牌 |
| `apiBase` | 否 | `https://gitlab.com` | API 基地址（自建实例设为对应地址） |
| `webhookPath` | 否 | `/api/channels/gitlab/{channelId}/webhook` | 自定义 webhook 路径 |

## 防循环保护

启动时，channel 通过 `GET /api/v4/user` 解析 bot 的 GitLab 用户 id。来自 bot 自身的 note 会被丢弃，防止无限循环。

## 消息流转

**入站：** `GitLabWebhookController` → note.id 去重 → bot 自身 note 过滤 → `GitLabInboundMapper` → 防循环 → Gateway。

**出站：** `GitLabOutboundClient` 通过 GitLab Notes API 发送回复。
