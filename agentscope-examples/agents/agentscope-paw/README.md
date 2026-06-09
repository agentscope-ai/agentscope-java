# agentscope-paw

> 🇨🇳 中文版：[README_zh.md](README_zh.md)

## Overview

AgentScope Paw is the Java port of [QwenPaw] — a personal assistant you install on your
own machine. It runs as you, on your filesystem and your shell, and it gets
better over time: the skills it learns, the sub-agents it spawns, and the
memory it keeps are all just files in a workspace it edits for itself.

The other thing it does well is meet you where you already work. Out of the
box it talks to DingTalk, WeCom, Feishu/Lark, GitHub and GitLab, so you can
ping it from a DM or @-mention it on an issue instead of opening yet another
browser tab.

paw deliberately doesn't try to be more than that. There's no login, no
multi-tenant isolation, no Docker sandbox, no horizontal scaling. If you need
any of those — host paw-style agents for a team, or run untrusted code in
isolation — the sister projects [agentscope-builder](../agentscope-builder/)
and [agentscope-dataagent](../agentscope-dataagent/) cover those use cases.

### At a glance

| | paw |
|---|---|
| **Use it when** | You want a personal assistant on your own laptop / workstation |
| **Users** | One — the operator of the machine |
| **Isolation** | None — runs as you, with full access to your shell |
| **Self-evolution** | ✅ Skills, sub-agents, memory and `AGENTS.md` are all just files the agent grows over time |
| **Channels** | Built-in web UI + DingTalk · WeCom · Feishu/Lark · GitHub · GitLab |
| **Distribution** | ❌ Single process, single node |
| **Filesystem** | `LocalFilesystemWithShell` — direct host filesystem + shell |

### Architecture

paw is a thin Spring Boot shell around a **HarnessAgent** wired onto a
`LocalFilesystemWithShell`. There is no auth layer, no sandbox, no remote
store: every read, write, and shell command goes straight to the host OS.

```
┌─────────────────────────────────────────────────────────────────┐
│                          your laptop                            │
│  ┌─────────────────────┐   ┌─────────────────────────────────┐  │
│  │  Channels           │   │  HarnessAgent (per agent)       │  │
│  │  ├ chatui (web UI)  │──▶│   ├ Reasoning (LLM)             │  │
│  │  ├ dingtalk         │   │   ├ Skills · Sub-agents · MCP   │  │
│  │  ├ wecom · feishu   │   │   └ Self-evolution loop         │  │
│  │  └ github · gitlab  │   └────────────┬────────────────────┘  │
│  └─────────────────────┘                ▼                       │
│                          ┌──────────────────────────────────┐   │
│                          │  LocalFilesystemWithShell        │   │
│                          │   ├ Host FS  (~/.agentscope/...) │   │
│                          │   └ Host shell (bash / zsh)      │   │
│                          └──────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

[QwenPaw]: https://github.com/agentscope-ai/openpaw

---

## Quick start

Prerequisites:

- JDK 17+
- A model API key (DashScope by default). Set `DASHSCOPE_API_KEY` in your
  environment, or pass it as `--paw.dashscope.api-key=…`.

Build and run from the repo root:

```bash
mvn -pl agentscope-examples/agents/agentscope-paw -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-paw/target/agentscope-paw-*.jar
```

Then open <http://localhost:8080/>. The default home is `~/.agentscope`; set
`PAW_HOME` (or `--paw.home=…`) to override it. The first run auto-creates a
`default` built-in agent if `agentscope.json` is missing.

## Directory layout

All persistent state lives under `${paw.home}` (default `~/.agentscope`):

```
~/.agentscope/
├── agentscope.json          # built-in agent definitions
├── agents.json              # custom agent catalog (JSON file)
└── agents/
    └── <agentId>/
        ├── workspace/       # AGENTS.md, skills/, subagents/, tools.json, memory/, …
        └── sessions.json    # session-store index for that agent
```

Each agent — built-in or custom — gets its own workspace directory and its own
session store. The harness manages workspace files, skills, subagents, and
sub-session histories under `workspace/agents/<subId>/`.

## Agents

Two flavours, both backed by the same runtime:

- **Built-in agents** live in `~/.agentscope/agentscope.json`. They show up in
  the UI as read-only; edit the JSON to change them.
- **Custom agents** are created through the UI (or via `POST /api/agents`) and
  persisted to `~/.agentscope/agents.json`.

Use the **New agent** button in the UI to create one from a blank scaffold, a
bundled template, or an AI-generated draft.

## Channels

By default a single `chatui` channel is registered with `DmScope.MAIN`, so the
web UI talks to one shared session per agent. Additional channel adapters
(DingTalk, WeCom, …) can be enabled by adding entries to
`~/.agentscope/agentscope.json`.

### Built-in channel types

| `type` | Direction | Transport | Notes |
| --- | --- | --- | --- |
| `chatui` | inbound + outbound | in-process pull | Always-on local web UI. |
| `dingtalk` | inbound + outbound | **Stream** (WebSocket, no public port) | Enterprise internal app + Stream subscription. |
| `wecom` | inbound + outbound | HTTP callback + REST API | Self-built enterprise app; needs a public HTTPS URL for the callback. |
| `feishu` | inbound + outbound | HTTP event callback + REST API | Custom app with event subscription; needs a public HTTPS URL. |
| `github` | inbound + outbound | Webhook + REST API | Reacts to issue / PR review comments. Needs a public HTTPS URL. |
| `gitlab` | inbound + outbound | Webhook + REST API | Reacts to Issue / MR Note Hooks. Needs a public HTTPS URL (self-hosted GitLab works too). |

### Config schema

Every channel entry under `channels` shares the same skeleton:

```json
"channels": {
  "<channelId>": {
    "type": "dingtalk | wecom | feishu | github | gitlab | chatui",
    "defaultAgentId": "main",
    "dmScope": "MAIN | PER_PEER | PER_CHANNEL_PEER | PER_ACCOUNT_CHANNEL_PEER",
    "disabled": false,
    "bindings": [ /* optional routing rules — see ChannelRouter */ ],
    "properties": { /* provider-specific block, see below */ }
  }
}
```

> `agentscope.json` is parsed as plain JSON — `${ENV_VAR}` placeholders are
> **not** expanded. Either paste the literal values, or render the file with
> `envsubst < agentscope.json.template > agentscope.json` before launch.

### Run agentscope-paw locally with DingTalk

DingTalk Stream mode keeps the WebSocket open to the DingTalk gateway from your
laptop — no public callback, no tunnel needed. This is the simplest setup for
local testing.

1. **Create an enterprise internal app** in [DingTalk Developer Console]
   (开发者后台 → 应用开发 → 企业内部开发 → 创建应用).
   - Capture **AppKey** and **AppSecret** from the app's credentials page.
   - On "机器人" / "Robot" sub-page, create a robot and capture its
     **robotCode** (sometimes shown as `RobotCode` / `chatbotUserId`).
   - Under "权限管理", grant at least: `Contact.User.Read`, `im:bot:send`,
     `qyapi_send_msg_to_conversation`.

2. **Enable Stream mode**: in the app's "事件订阅" → choose **Stream 推送**
   (no HTTP callback URL required). Subscribe to the
   `/v1.0/im/bot/messages/get` topic — this is the bot DM/group message
   topic the included `DingTalkStreamClient` registers for.

3. **Write `~/.agentscope/agentscope.json`** (only the `channels` block is
   new; keep your existing `agents` block):

   ```json
   {
     "main": "default",
     "agents": {
       "default": {
         "name": "paw",
         "sysPrompt": "You are a helpful local assistant."
       }
     },
     "channels": {
       "dingtalk-dev": {
         "type": "dingtalk",
         "defaultAgentId": "default",
         "dmScope": "PER_PEER",
         "properties": {
           "appKey": "dingxxxxxxxxxx",
           "appSecret": "your-app-secret",
           "robotCode": "dingxxxxxxxxxx"
         }
       }
     }
   }
   ```

4. **Launch**:

   ```bash
   export DASHSCOPE_API_KEY=sk-...
   mvn -pl agentscope-examples/agents/agentscope-paw -am clean package -DskipTests
   java -jar agentscope-examples/agents/agentscope-paw/target/agentscope-paw-*.jar
   ```

   Watch the startup log for:

   ```
   PawBootstrap initialized: ..., channels=[chatui, dingtalk-dev]
   DingTalk channel 'dingtalk-dev' started: appKey=..., robotCode=...
   DingTalk Stream connected: endpoint=wss://...
   ```

5. **Test inbound (DM)**: in DingTalk, find your robot's profile and send it
   any text → the reply comes back in the same DM. Check `logs/` for the
   `DingTalk inbound` debug line.

6. **Test inbound (group @-mention)**: add the robot to a test group, send
   `@<bot> ping` → reply lands in the group.

7. **Test outbound (HTTP)**:

   ```bash
   # DM to a specific staff member
   curl -X POST http://localhost:8080/api/outbound/send \
     -H 'Content-Type: application/json' \
     -d '{
       "channelId": "dingtalk-dev",
       "peerKind": "DIRECT",
       "peerId": "<staffId-or-unionId>",
       "text": "hello from paw"
     }'

   # Group message
   curl -X POST http://localhost:8080/api/outbound/send \
     -H 'Content-Type: application/json' \
     -d '{
       "channelId": "dingtalk-dev",
       "peerKind": "GROUP",
       "peerId": "<openConversationId>",
       "markdown": "**alert**: build failed"
     }'
   ```

8. **Test outbound (agent tool)**: every agent is auto-wired with the
   `outbound_send` tool. Ask the agent something like _"use outbound_send to
   ping DingTalk DM `<staffId>` on channel `dingtalk-dev` with the text
   'hello'"_ — the LLM will invoke the tool and the message will pop in
   DingTalk.

### Run agentscope-paw locally with WeCom

WeCom uses an HTTP callback, so your laptop needs to be reachable from the
public internet. Use a tunnel like ngrok or frpc.

1. **Create a self-built application** in the [WeCom admin console]
   (我的企业 → 应用管理 → 自建).
   - Capture **corpId** (我的企业 → 企业信息) and the app's
     **agentId** + **secret**.
   - In "接收消息" → enable callbacks. Set:
     - **Token** — any random string; copy into config below.
     - **EncodingAESKey** — click "随机生成"; this is the 43-char base64 key.
     - **URL** — `https://<your-tunnel-host>/api/channels/wecom/<channelId>/callback`
       (replace `<channelId>` to match what you put in `agentscope.json`).
   - On "可信IP" / trusted IPs, add your tunnel's egress IP.

2. **Start a tunnel**:

   ```bash
   ngrok http 8080
   # → https://<random>.ngrok.io  ← use this as the URL above
   ```

3. **Write `~/.agentscope/agentscope.json`**:

   ```json
   {
     "channels": {
       "wecom-dev": {
         "type": "wecom",
         "defaultAgentId": "default",
         "dmScope": "PER_PEER",
         "properties": {
           "corpId": "ww1234567890abcdef",
           "agentId": 1000002,
           "secret": "your-app-secret",
           "token": "your-callback-token",
           "encodingAesKey": "43-character-base64-no-padding-aes-key-here"
         }
       }
     }
   }
   ```

4. **Launch paw**, then in the WeCom console click **保存** on the callback
   page — WeCom hits `GET /api/channels/wecom/wecom-dev/callback?echostr=…` to
   verify; you should see a `WeCom URL verification ok` log line.

5. **Test inbound**: open the app inside the WeCom client and DM it → reply
   appears. For groups, the app must be added to the group context first.

6. **Test outbound**: same `POST /api/outbound/send` shape as DingTalk;
   `peerId` is a WeCom userid for DMs or a chatId for groups.

### Run agentscope-paw locally with Feishu (Lark)

Feishu uses HTTP event subscription, so your laptop needs a public HTTPS URL.
Use a tunnel like ngrok or frpc.

1. **Create a custom app** in [Feishu Developer Console]
   (开发者后台 → 自建应用 → 创建企业自建应用).
   - Capture **App ID** (`cli_xxx`) and **App Secret** from the credentials page.
   - Under "权限管理" (Permissions), grant at least: `im:message`,
     `im:message:send_as_bot`, `im:chat`, and (recommended) `im:resource`.
   - Under "事件与回调" (Events & Callbacks) → enable the bot, then subscribe to
     `im.message.receive_v1` (Schema 2.0).
   - Set the **Encrypt Key** (推荐启用加密) — copy it; this enables AES-256-CBC
     payload encryption. Optional but recommended.
   - Set the **Verification Token** — any random string; copy it.
   - Set the **Request URL** to
     `https://<your-tunnel-host>/api/channels/feishu/<channelId>/callback`.

2. **Start a tunnel**:

   ```bash
   ngrok http 8080
   ```

3. **Write `~/.agentscope/agentscope.json`**:

   ```json
   {
     "channels": {
       "feishu-dev": {
         "type": "feishu",
         "defaultAgentId": "default",
         "dmScope": "PER_PEER",
         "properties": {
           "appId": "cli_xxxxxxxxxxxxxxxx",
           "appSecret": "your-app-secret",
           "encryptKey": "your-encrypt-key-from-console",
           "verificationToken": "your-verification-token",
           "apiBase": "https://open.feishu.cn"
         }
       }
     }
   }
   ```

   For Lark (international), use `"apiBase": "https://open.larksuite.com"`.

4. **Launch paw**, then in the Feishu console click **保存并发布** on the
   event-callback page. Feishu posts a one-time `url_verification` challenge to
   your URL — you should see a `Feishu URL verification ok` log line.

5. **Test inbound (DM)**: open the Feishu app, find your bot's profile and send
   it any text → reply comes back in the same DM.

6. **Test inbound (group)**: add the bot to a test group, send `@<bot> ping` →
   reply lands in the group.

7. **Test outbound**:

   ```bash
   curl -X POST http://localhost:8080/api/outbound/send \
     -H 'Content-Type: application/json' \
     -d '{
       "channelId": "feishu-dev",
       "peerKind": "GROUP",
       "peerId": "<chat_id>",
       "text": "hello from paw"
     }'
   ```

   Feishu addresses both DMs and groups by `chat_id`; the inbound mapper
   captures it so replies land back on the same chat without any extra config.

### Run agentscope-paw locally with GitHub

GitHub uses a webhook. The bot reacts to `issue_comment` and
`pull_request_review_comment` events and replies as a new comment on the same
issue/PR.

1. **Create a Personal Access Token (PAT)** at
   `Settings → Developer settings → Personal access tokens (fine-grained)`.
   Grant **Read & write** on **Issues** and **Pull requests** for the repos
   you want the bot to act on. Capture the token (`github_pat_...`).

2. **Start a tunnel**:

   ```bash
   ngrok http 8080
   ```

3. **Create a webhook** on the target repo:
   `Settings → Webhooks → Add webhook`.
   - **Payload URL**:
     `https://<your-tunnel-host>/api/channels/github/<channelId>/webhook`
   - **Content type**: `application/json`
   - **Secret**: any random string; copy it into config below.
   - **Events**: select **Let me choose individual events**, then tick
     **Issue comments** and **Pull request review comments**.

4. **Write `~/.agentscope/agentscope.json`**:

   ```json
   {
     "channels": {
       "github-acme": {
         "type": "github",
         "defaultAgentId": "default",
         "dmScope": "PER_PEER",
         "properties": {
           "token": "github_pat_xxxxxxxxxxxxxxxx",
           "webhookSecret": "your-webhook-shared-secret",
           "apiBase": "https://api.github.com"
         }
       }
     }
   }
   ```

   For GitHub Enterprise Server, set `apiBase` to your install (e.g.
   `https://github.acme.com/api/v3`).

5. **Launch paw**. On startup the log prints the resolved bot login from
   `GET /user`, which is used to filter self-authored comments (bot-loop
   protection).

6. **Test inbound**: comment on an issue or PR in the target repo → reply is
   posted as a new comment on the same thread.

7. **Test outbound**:

   ```bash
   curl -X POST http://localhost:8080/api/outbound/send \
     -H 'Content-Type: application/json' \
     -d '{
       "channelId": "github-acme",
       "peerKind": "THREAD",
       "peerId": "owner/repo#42",
       "markdown": "**heads up:** new build available"
     }'
   ```

   GitHub addresses issues and PRs uniformly through the issues comments
   endpoint, so the same `peerId` shape works for both.

### Run agentscope-paw locally with GitLab

GitLab uses a webhook. The bot reacts to **Note Hook** events on Issues and
Merge Requests, and replies as a new note on the same thread. Self-hosted
GitLab works the same as gitlab.com.

1. **Create a Project Access Token** at
   `Settings → Access Tokens` on the target project. Role: **Developer** (or
   higher); scopes: `api`. Capture the token (`glpat-...`).

2. **Start a tunnel** (skip if self-hosted GitLab can already reach your
   laptop):

   ```bash
   ngrok http 8080
   ```

3. **Add a webhook** at `Settings → Webhooks`:
   - **URL**:
     `https://<your-tunnel-host>/api/channels/gitlab/<channelId>/webhook`
   - **Secret token**: any random string; copy it.
   - **Trigger**: tick **Comments** (other event types are silently ignored
     by the controller).

4. **Write `~/.agentscope/agentscope.json`**:

   ```json
   {
     "channels": {
       "gitlab-internal": {
         "type": "gitlab",
         "defaultAgentId": "default",
         "dmScope": "PER_PEER",
         "properties": {
           "token": "glpat-xxxxxxxxxxxxxxxx",
           "webhookToken": "your-webhook-shared-token",
           "apiBase": "https://gitlab.com"
         }
       }
     }
   }
   ```

   For self-hosted GitLab, set `apiBase` to your install root (e.g.
   `https://gitlab.internal`). The adapter appends `/api/v4` automatically
   if it's not already in the URL.

5. **Launch paw**. On startup the log prints the resolved bot username from
   `GET /api/v4/user`, used to filter self-authored notes.

6. **Test inbound**: leave a comment on an issue or MR → reply is posted as a
   new note on the same thread.

7. **Test outbound**:

   ```bash
   curl -X POST http://localhost:8080/api/outbound/send \
     -H 'Content-Type: application/json' \
     -d '{
       "channelId": "gitlab-internal",
       "peerKind": "THREAD",
       "peerId": "group/project#7:Issue",
       "markdown": "**deploy:** rolled out v1.2.3"
     }'
   ```

   The trailing `:Issue` (or `:MergeRequest`) in `peerId` tells the outbound
   client which REST endpoint to hit — it matches the shape used by the
   inbound mapper.

### Programmatic outbound from an agent

The `outbound_send` tool is registered on every agent's toolkit at bootstrap.
Any prompt that nudges the agent to call it works — e.g.

> Use the `outbound_send` tool to message DingTalk user `dingstaff_001` on
> channel `dingtalk-dev` with the text "deploy finished".

When a sub-agent finishes, `HarnessGateway.tryDispatchAnnounce` automatically
re-uses the originating channel's `OutboundAddress`, so completion
notifications follow the user back to the same DingTalk/WeCom conversation
with no extra wiring.

### Reliability features (always on)

| Mechanism | Default | Source |
| --- | --- | --- |
| Idempotency | dedup by `<channelId>\|<msgId>`, 5 min TTL, ~10 k entries | `IdempotencyStore` |
| Bot-loop guard | 20 events / 60 s per peer; 60 s cooldown | `BotLoopGuard` |
| Signature verify (WeCom) | SHA-1(token, ts, nonce, encrypt) per WeCom spec | `WeComCrypto` |
| AES-256-CBC decrypt (WeCom) | 43-char base64 key + "=" → 32-byte AES key, IV = first 16 bytes | `WeComCrypto` |
| Access-token refresh | proactive at 80% of the issued TTL | `*AccessTokenProvider` |

### Troubleshooting

- **No DingTalk/WeCom channels at startup** — confirm the log line
  `PawBootstrap initialized: ..., channels=[chatui, ...]` lists your
  channelId. If only `chatui` shows, the entry was either skipped (missing
  `type`, unknown `type`, or `disabled: true`) or rejected by the factory
  (look for the `Failed to instantiate channel` error above it).
- **WeCom returns 401 on URL verification** — `token` / `encodingAesKey`
  mismatch with what's saved in the WeCom console.
- **DingTalk Stream keeps reconnecting** — most often a bad
  `appKey`/`appSecret`, missing Stream subscription permission, or the robot
  is not yet enabled. The client retries with exponential backoff (1 s → 60 s).
- **Outbound returns 400 `peerId is required`** — for groups, `peerId` is the
  provider's group id, not the channel id: DingTalk wants
  `openConversationId`, WeCom wants the chatId from the group registration.
- **Bot-loop guard tripped** — log shows `bot-loop guard cooldown`; the
  60 s window resets on its own. Send fewer than 20 messages/minute per peer
  during stress tests.

[DingTalk Developer Console]: https://open-dev.dingtalk.com/
[WeCom admin console]: https://work.weixin.qq.com/wework_admin/

## Configuration

Recognised properties (all under `paw.*`, with matching `PAW_*` env vars):

| Property | Default | Description |
| --- | --- | --- |
| `paw.home` | `~/.agentscope` | Root directory for built-ins, custom catalog, agent workspaces. |
| `paw.dashscope.api-key` | _empty_ | DashScope API key. When set, a `DashScopeChatModel` bean is created automatically. |
| `paw.dashscope.model-name` | `qwen-max` | Model name passed to DashScope. |
| `paw.dashscope.stream` | `true` | Whether to stream responses. |
| `paw.agent.name` | `paw` | Display name for the auto-generated `default` agent. |
| `paw.agent.sys-prompt` | `You are a helpful local assistant. …` | System prompt for the auto-generated `default` agent. |
| `server.port` | `8080` | HTTP port. |

If you provide your own `Model` Spring bean (for example by importing another
`@Configuration`), the auto-wired DashScope model is skipped.

## What this fork is _not_

agentscope-paw used to support multi-tenant deployment, JWT login, per-user
workspace namespacing, Docker-sandbox isolation, and agent sharing. All of
that has been removed. See [`builder.md`](builder.md) for the list of removed
modules and the recommended way to recover any of it from git history.

[AgentScope Java]: https://github.com/agentscope-ai/agentscope-java
