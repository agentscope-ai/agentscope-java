# GitLab Channel

`agentscope-extensions-channel-gitlab` connects your Agent to GitLab note (comment) hooks. When someone comments on an issue or merge request, the Agent replies as a new note.

## When to use

- You want an AI-powered bot that responds to GitLab issue or merge request comments.
- You run GitLab SaaS or a self-managed GitLab instance.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-channel-gitlab</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Prerequisites

1. Create a **Personal Access Token** (or Project/Group Access Token) with `api` scope.
2. Configure a **webhook** on your project or group:
   - URL: `https://your-host/api/channels/gitlab/{channelId}/webhook`
   - Secret token: (optional, for signature verification)
   - Trigger: **Note events**

## Quickstart

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

## Configuration properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `token` | Yes | — | Access token for the GitLab API |
| `apiBase` | No | `https://gitlab.com` | API base URL (set for self-managed instances) |
| `webhookPath` | No | `/api/channels/gitlab/{channelId}/webhook` | Override the webhook URL path |

## Bot-loop protection

At startup, the channel resolves the bot's GitLab user id by calling `GET /api/v4/user`. Incoming notes authored by the bot are dropped to prevent infinite loops.

## Message flow

**Inbound:** `GitLabWebhookController` → note.id dedup → bot self-note filter → `GitLabInboundMapper` → bot-loop guard → Gateway.

**Outbound:** `GitLabOutboundClient` posts replies as notes via the GitLab Notes API.
