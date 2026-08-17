# GitHub Channel

`agentscope-extensions-channel-github` 将你的 Agent 接入 GitHub issue 和 PR 评论线程。当有人在 issue 或 pull request 中评论时，Agent 以新评论的形式回复。

## 适用场景

- 你需要一个能响应 GitHub issue / PR 评论的 AI 机器人。
- 你需要 HMAC-SHA256 webhook 签名校验和 bot 自身回复检测。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-github</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 前置准备

1. 创建一个具有 `issues:write` 和 `pull_requests:write` 权限的 **Personal Access Token**（PAT）。
2. 在仓库或组织上配置 **webhook**：
   - Payload URL：`https://your-host/api/channels/github/{channelId}/webhook`
   - Content type：`application/json`
   - Secret：用于 HMAC-SHA256 签名校验的共享密钥
   - Events：勾选 **Issue comments** 和 **Pull request review comments**

## 快速开始

```java
GitHubChannel channel = GitHubChannel.fromProperties(
    "my-github",
    ChannelConfig.of("my-github", "main"),
    Map.of(
        "token",         "ghp_xxxxxxxxxxxx",
        "webhookSecret", "your-webhook-secret"
    ));

GatewayBootstrap gw = GatewayBootstrap.builder()
    .agent("main", agent)
    .channel(channel)
    .build();

gw.start();   // 通过 GET /user 解析 bot 身份，开始接收 webhook
```

## 配置属性

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `token` | 是 | — | REST API 的 Personal Access Token（PAT） |
| `webhookSecret` | 是 | — | `X-Hub-Signature-256` 校验用的共享密钥 |
| `apiBase` | 否 | `https://api.github.com` | REST API 基地址（GitHub Enterprise Server 设为对应地址） |
| `webhookPath` | 否 | `/api/channels/github/{channelId}/webhook` | 自定义 webhook 路径 |
| `botUserLogin` | 否 | （自动解析） | 覆盖 bot 账号的 login，用于循环检测 |

## 防循环保护

启动时，channel 通过 PAT 调用 `GET /user` 解析 bot 的 GitHub 用户 id。来自该 id 的评论会被静默丢弃，防止 bot 之间的无限循环。

## 消息流转

**入站：** `GitHubWebhookController` → HMAC-SHA256 签名校验 → 事件类型过滤（`issue_comment`、`pull_request_review_comment`） → comment.id 去重 → bot 自身评论过滤 → `GitHubInboundMapper`（仅 `action=created`） → 防循环 → Gateway。

**出站：** `GitHubOutboundClient` 通过 `POST /repos/{owner}/{repo}/issues/{number}/comments` 发送回复。

## Peer 模型

每个 issue/PR 线程建模为 `THREAD` peer，id 格式为 `owner/repo#number`。这意味着 Gateway 为每个 issue/PR 线程创建独立的 session——Agent 在同一线程内可以看到完整的对话历史。
