# agentscope-codingagent

Coding agent built on top of **HarnessAgent** from the AgentScope Java library.

## Overview

The agent has no UI of its own — every interaction comes through a **channel adapter**. All
adapters implement the same `Channel` interface and share the routing, session, and HarnessAgent
stack; only the transport differs. Pick whichever matches how you want to reach the agent:

| Channel  | Transport                  | When to use                                  |
|----------|----------------------------|----------------------------------------------|
| CLI      | stdin/stdout (in-process)  | Local dev, demos, single user                |
| GitHub   | HTTP webhook               | Issue/PR-driven coding & review              |
| DingTalk | Stream WebSocket           | IM chat with the bot (no public URL needed)  |
| Feishu   | HTTP callback              | IM chat with the bot (public URL required)   |

Long-running coding or review work runs in isolated Docker sandboxes; results are posted back
through the same channel that initiated the request.

```
GitHub Webhooks
      │
      ▼
GitHubWebhookHandler (Spring WebFlux)
      │  HMAC verification · dedup · thread-ID routing
      ▼
RunDispatcher
      │  immediate dispatch or enqueue (busy thread)
      ▼
HarnessGateway → CodingAgent / ReviewerAgent (HarnessAgent)
      │              │
      │              ├─ DockerSandbox (per-session isolation)
      │              ├─ GitHubApiTool · HttpRequestTool · FetchUrlTool · WebSearchTool
      │              └─ ReviewerTools: add_finding · publish_review
      ▼
GitHub API (comments, PR reviews)
```

## Agents

| Agent ID   | Class                  | Role                                        |
|------------|------------------------|---------------------------------------------|
| `coding`   | `CodingAgentFactory`   | Implements issues, writes code, pushes PRs  |
| `reviewer` | `ReviewerAgentFactory` | Reviews PRs, records findings, posts review |

## Quick Start

The fastest way to try the agent — one env var, one Maven command, an interactive REPL on your
local filesystem. No Docker, no webhooks, no GitHub App.

```bash
# 1. Set your model key (default model is DashScope; OpenAI / Anthropic also supported, see ENV_VARS.md)
export DASHSCOPE_API_KEY=sk-...

# 2. From the repo root, build dependencies once (skip on subsequent runs)
cd agentscope-java
mvn install -pl agentscope-examples/agents/agentscope-codingagent -am -DskipTests -q

# 3. Launch the CLI (re-run this anytime to chat again)
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent

```

You'll see a banner, then a `You>` prompt. The agent operates inside its own workspace at
`~/.agentscope/codingagent/workspace/` (not your repo) — the pattern is to clone the
target repo *into* the workspace and work there. So good first prompts are:

```
You> write hello.txt with a haiku about Java
You> fetch https://github.com/anthropics/anthropic-sdk-python/blob/main/README.md and summarize it
You> clone https://github.com/owner/repo into the workspace and tell me what it does
You> /exit
```

Workspace, chat history, and config are isolated from your project tree and from other harness
apps (builder, dataagent, claw):

- **Workspace** (skills/memory/sessions/files the agent reads/writes): `~/.agentscope/codingagent/workspace/`
- **Chat history** (per-thread SQLite): `./.agentscope/codingagent.db` (relative to where you ran `mvn`)
- **Config**: hard-coded in `CodingChatCli` — the CLI ignores any `.agentscope/agentscope.json`
  in your project root so a stale file from a different harness app won't override it.

### Optional: review a real GitHub PR

```bash
export GITHUB_TOKEN=ghp_...          # PAT with `repo` scope
# in the REPL:
You> review https://github.com/owner/repo/pull/42
```

This routes the request to the **reviewer** agent, which fetches the diff, records structured
findings, and publishes a single GitHub review.

### Optional: isolate sessions in a Docker sandbox

```bash
# Build the sandbox image once (see "Building the Sandbox Image" below)
export SANDBOX_TYPE=docker
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

Each chat thread then gets its own ephemeral container — safer for letting the agent run
arbitrary `execute` commands.

## Webhook Service

```bash
# Required env vars
export GITHUB_WEBHOOK_SECRET=your-webhook-secret
export DASHSCOPE_API_KEY=sk-...      # or set CODING_MODEL_ID=anthropic:... + ANTHROPIC_API_KEY
export GITHUB_TOKEN=ghp_...          # or use GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY

# Optional
export TAVILY_API_KEY=tvly-...       # enables web_search tool
export SANDBOX_TYPE=docker           # run each session in a Docker sandbox (default: none)

# Build and run
mvn spring-boot:run -pl agentscope-examples/agents/agentscope-codingagent
```

The service starts on port `8080` (override with `PORT=...`).

### GitHub App Setup

1. Create a GitHub App with permissions: `Issues: Read/Write`, `Pull requests: Read/Write`,
   `Contents: Read/Write`, `Metadata: Read`.
2. Subscribe to webhook events: `issue_comment`, `pull_request`, `pull_request_review_comment`.
3. Set `Webhook URL` to `https://your-host/webhooks/github`.
4. Generate a webhook secret and set `GITHUB_WEBHOOK_SECRET`.
5. Install the app on your target repositories.

## Starting a Conversation

Once the webhook service (or DingTalk Stream client) is running, you start a session by performing
an action in the source platform. The agent has no UI of its own — every interaction is a comment,
event, or chat message that the corresponding adapter picks up.

### Via GitHub

The `GitHubWebhookHandler` listens at `POST /webhooks/github` and routes events through
`ThreadIdFactory` so all activity on the same issue/PR shares one agent session.

**1. Ask the coding agent to work on an issue**

In any issue on a repo where your GitHub App is installed, post a comment:

```
Please implement this feature: <describe what you want>
```

- Trigger: `issue_comment.created`
- Thread key: `github:issue:<owner>/<repo>#<issue_number>`
- Routed to: `coding` agent
- The agent clones the repo into its sandbox, makes changes, and posts results as a follow-up
  comment (and optionally a PR).

Follow-up comments on the same issue continue the same session.

**2. Ask the reviewer agent to review a PR**

On any PR, click **Reviewers → Request review** and pick the GitHub App account:

- Trigger: `pull_request.review_requested`
- Thread key: `github:reviewer:<owner>/<repo>#<pr_number>`
- Routed to: `reviewer` agent
- The agent fetches the diff, records structured findings, and publishes a single review.

**3. Iterate on a PR with the coding agent**

On any PR, leave a review comment on a specific line (the `pull_request_review_comment` event):

```
Can you refactor this function to use streams?
```

- Trigger: `pull_request_review_comment.created`
- Thread key: `github:pr:<owner>/<repo>#<pr_number>`
- Routed to: `coding` agent
- The agent reads the comment in context, edits the branch, and replies in-thread.

**Notes**
- Comments authored by the agent's own GitHub account are skipped (`isSelfComment` check) to avoid
  loops.
- While a thread is busy, additional events are enqueued in `SqliteBaseStore` and injected into
  the next reasoning step by `MessageQueueHook`.
- Only `issue_comment.created`, `pull_request.review_requested`, and
  `pull_request_review_comment.created` actions trigger dispatch; `pull_request.opened` is parsed
  but not auto-dispatched, and `push` events are observed only (no agent run).

### Via DingTalk

The DingTalk adapter (`DingtalkChannel`) connects to DingTalk's Stream gateway over a persistent
WebSocket — **no public HTTP endpoint is required**, so it works behind NAT or in a dev laptop.

**Prerequisites — one-time setup**

1. Create an enterprise internal app at <https://open-dev.dingtalk.com>.
2. Add a robot to the app and copy `robotCode`.
3. Note the app's `AppKey` and `AppSecret` (Credentials & Basic Info page).
4. Under **Event subscription**, choose **Stream mode** (流式) and subscribe to the topic
   `/v1.0/im/bot/messages/get`.
5. In your home dir, edit `~/.agentscope/codingagent/agentscope.json` and add:

   ```json
   {
     "channels": {
       "dingtalk": {
         "defaultAgentId": "coding",
         "dmScope": "PER_PEER",
         "properties": {
           "appKey": "dingxxxxxxxx",
           "appSecret": "xxxxxxxxxxxx",
           "robotCode": "dingxxxxxxxx"
         }
       }
     }
   }
   ```

6. Start the service the same way as for GitHub:

   ```bash
   mvn spring-boot:run -pl agentscope-examples/agents/agentscope-codingagent
   ```

   On boot you should see:
   `DingTalk channel 'dingtalk' started: appKey=…, robotCode=…`
   `DingTalk Stream connected: <gateway-host>`

**1. Start a 1:1 chat (DM)**

In the DingTalk client, search for the bot by its name and send any text message:

```
帮我克隆 https://github.com/owner/repo 并总结这个项目
```

- Trigger: Stream callback with `conversationType=1`
- Thread key: `dingtalk:<appKey>:<senderStaffId>` (via `ThreadIdFactory.fromDingtalkConversation`)
- Routed to: `coding` agent (the `defaultAgentId` from `agentscope.json`)
- The agent replies in the same DM. Follow-up messages stay in the same session.

**2. Start a group chat conversation**

Add the bot to a group (Group settings → Robots → Add), then **@-mention** the bot:

```
@coding-bot 看一下昨天合并到 main 的那个 PR，有没有遗留 TODO
```

- Trigger: Stream callback with `conversationType=2` (DingTalk only dispatches group events when
  the bot is explicitly mentioned)
- Thread key: `dingtalk:<appKey>:<openConversationId>`
- Routed to: `coding` agent
- The agent replies in the same group. Each group has its own independent session.

**Notes**
- Only `msgtype=text` messages are mapped today; other types (image, file, card) are silently
  acked.
- Duplicate `msgId` events (DingTalk retries) are dropped by `IdempotencyStore`.
- A per-peer sliding-window throttle (`BotLoopGuard`, default 20 events / 60 s) prevents runaway
  bot loops.
- To switch the default agent per-conversation, add channel `bindings` (e.g. route a specific
  group to `reviewer`); see `ChannelConfig.Builder.binding(...)` for the rule shape.

### Via Feishu (Lark)

Feishu uses the HTTP callback model, so your service **must be reachable from the public internet**
(e.g. behind ngrok during development).

**Prerequisites — one-time setup**

1. Create a custom app at <https://open.feishu.cn>.
2. Enable **Bot** capability and copy `App ID` (`cli_xxx`) and `App Secret`.
3. Under **Event Subscriptions**, set the **Request URL** to
   `https://<your-public-host>/api/channels/feishu/feishu/callback` (path = `/api/channels/feishu/<channelId>/callback`,
   `<channelId>` matches the key in `agentscope.json`).
4. Subscribe to the event `im.message.receive_v1`.
5. (Recommended) Generate an **Encrypt Key** and a **Verification Token** on the same page.
6. Edit `~/.agentscope/codingagent/agentscope.json`:

   ```json
   {
     "channels": {
       "feishu": {
         "defaultAgentId": "coding",
         "dmScope": "PER_PEER",
         "properties": {
           "appId": "cli_xxxxxxxxxx",
           "appSecret": "xxxxxxxxxxxxxx",
           "encryptKey": "xxxxxxxx",
           "verificationToken": "xxxxxxxx"
         }
       }
     }
   }
   ```

7. Start the service and complete Feishu's URL-verification handshake — the controller echoes the
   `challenge` automatically on first POST.

**Start a chat** — DM the bot, or add it to a group and @-mention it; the routing rules mirror
DingTalk (thread key = `feishu:<tenantKey>:<chatId>`).

### Session continuity at a glance

| Source                     | What triggers a new agent run                                  | Same-session continuation                 |
|----------------------------|----------------------------------------------------------------|-------------------------------------------|
| GitHub issue comment       | Any non-bot comment on an issue                                | Further comments on the same issue        |
| GitHub PR review-request   | Adding the bot as a reviewer on a PR                           | Re-requesting review on the same PR       |
| GitHub PR review comment   | Any non-bot line comment on a PR                               | Further line comments on the same PR      |
| DingTalk DM                | Any text message in the bot DM                                 | Further messages from the same staff id   |
| DingTalk group             | @-mentioning the bot in the group                              | Further @-mentions in the same group      |
| Feishu DM / group          | Any text message (DM) or @-mention (group)                     | Further messages in the same `chat_id`    |

## Environment Variables

See [ENV_VARS.md](ENV_VARS.md) for the full reference.

## Architecture

### Thread Routing

Every GitHub event is mapped to a deterministic **thread ID** via `ThreadIdFactory`:

```
github:issue:owner/repo#42   → SHA-256 → UUID → coding agent thread
github:reviewer:owner/repo#7 → SHA-256 → UUID → reviewer agent thread
```

This ensures that all comments on the same issue/PR are routed to the same agent session,
preserving conversation history.

### Message Queue

When an agent thread is busy (currently executing), incoming events are enqueued in
`SqliteBaseStore` namespace `["queue", thread_id]`. The `MessageQueueHook` drains the queue
and injects its content into the next LLM call's system prompt before reasoning begins.

### Sandboxes

Each coding agent session runs in its own Docker container (`agentscope/coding-sandbox:latest`)
using `IsolationScope.SESSION`. The sandbox is provisioned on first use and reused across
turns in the same session.

### Hook Stack

| Hook                  | Purpose                                    |
|-----------------------|--------------------------------------------|
| `MessageQueueHook`    | Inject queued messages before reasoning    |
| `ThreadBudgetHook`    | Per-thread model call cap                  |
| `ModelCallLimitHook`  | Global model call cap (across all threads) |

`FallbackModel` wraps the primary LLM and transparently retries on rate-limit / overload errors.

## Observability

Spring Boot Actuator exposes:

- `GET /actuator/health` — liveness probe
- `GET /actuator/prometheus` — Prometheus metrics
- `GET /actuator/metrics` — metric browser

Key metrics (all prefixed `coding_agent.*`):

| Metric                          | Description                            |
|---------------------------------|----------------------------------------|
| `webhook.received`              | Total webhooks received                |
| `webhook.duplicate`             | Skipped duplicate deliveries           |
| `dispatch.total`                | Agent dispatches initiated             |
| `dispatch.errors`               | Dispatch failures                      |
| `model.calls`                   | LLM calls across all threads           |
| `findings.added`                | Reviewer findings recorded             |
| `review.published`              | GitHub PR reviews posted               |
| `dispatch.duration`             | End-to-end dispatch latency            |

Set `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` to enable distributed tracing.

## Building the Sandbox Image

```bash
docker build \
  -t agentscope/coding-sandbox:latest \
  agentscope-examples/agents/agentscope-codingagent/src/main/docker/coding-sandbox/
```

## Module Structure

```
src/main/java/io/agentscope/harness/coding/
├── agent/              # CodingAgentFactory, ReviewerAgentFactory
├── channel/            # Channel interface, ChatUiChannel
├── control/            # RunDispatcher, ThreadIdFactory
├── gateway/            # HarnessGateway, Gateway interface
├── hook/               # MessageQueueHook, ThreadBudgetHook, FallbackModel, ...
├── metadata/           # ThreadMetadata, TokenEncryption
├── observability/      # CodingAgentMetrics
├── prompt/             # CodingSystemPrompt, ReviewerSystemPrompt
├── reviewer/           # Finding, ReviewerFindingsService, GitHubReviewPublisher
├── session/            # SessionAgentManager, SessionKind, ...
├── store/              # SqliteBaseStore
├── tools/              # GitHub, HTTP, web tools; finding tools
├── webhook/github/     # GitHubWebhookHandler
├── CodingAgentApplication.java
├── CodingBootstrap.java
└── CodingChatCli.java
```

## Not Yet Implemented

- GitHub App JWT token rotation (uses PAT for now)
