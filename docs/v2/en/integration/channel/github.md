# GitHub Channel

`agentscope-extensions-channel-github` connects your Agent to GitHub issue and PR comment threads. When someone comments on an issue or pull request, the Agent replies as a new comment.

## When to use

- You want an AI-powered bot that responds to GitHub issue or PR comments.
- You need HMAC-SHA256 webhook signature verification and bot-loop self-detection.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-github</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Prerequisites

1. Create a **Personal Access Token** (PAT) with `issues:write` and `pull_requests:write` permissions.
2. Configure a **webhook** on your repository or organization:
   - Payload URL: `https://your-host/api/channels/github/{channelId}/webhook`
   - Content type: `application/json`
   - Secret: a shared secret for HMAC-SHA256 signature verification
   - Events: select **Issue comments** and **Pull request review comments**

## Quickstart

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

gw.start();   // resolves bot identity via GET /user, starts accepting webhooks
```

## Configuration properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `token` | Yes | — | Personal Access Token (PAT) for the REST API |
| `webhookSecret` | Yes | — | Shared secret for `X-Hub-Signature-256` verification |
| `apiBase` | No | `https://api.github.com` | REST API base (set for GitHub Enterprise Server) |
| `webhookPath` | No | `/api/channels/github/{channelId}/webhook` | Override the webhook URL path |
| `botUserLogin` | No | (auto-resolved) | Override the bot account login for loop detection |

## Bot-loop protection

At startup, the channel resolves the bot's GitHub user id by calling `GET /user` with the configured PAT. Incoming comments authored by this id are silently dropped to prevent infinite bot-to-bot loops.

## Message flow

**Inbound:** `GitHubWebhookController` → HMAC-SHA256 signature verify → event type filter (`issue_comment`, `pull_request_review_comment`) → comment.id dedup → bot self-comment filter → `GitHubInboundMapper` (only `action=created`) → bot-loop guard → Gateway.

**Outbound:** `GitHubOutboundClient` posts replies via `POST /repos/{owner}/{repo}/issues/{number}/comments`.

## Peer model

Each issue/PR thread is modeled as a `THREAD` peer with id `owner/repo#number`. This means the Gateway creates one session per issue/PR thread — the Agent sees the full conversation history within that thread.
