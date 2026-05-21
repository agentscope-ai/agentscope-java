# agentscope-codingagent

A Java re-implementation of the [open-swe](https://github.com/open-swe/open-swe) coding agent,
built on top of **HarnessAgent** from the AgentScope Java library.

## Overview

The coding agent listens for GitHub webhook events, dispatches long-running coding or review tasks
to isolated Docker sandboxes, and posts results back to GitHub as PR comments or reviews.

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

## Quick Start (Local CLI)

```bash
# Set required env vars
export ANTHROPIC_API_KEY=sk-ant-...
export GITHUB_TOKEN=ghp_...

# Run the local REPL (no webhooks needed)
cd agentscope-java
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

Type a message to chat with the coding agent. Use `review <pr_url>` to trigger the reviewer.

## Webhook Service

```bash
# Required env vars
export GITHUB_WEBHOOK_SECRET=your-webhook-secret
export ANTHROPIC_API_KEY=sk-ant-...
export GITHUB_TOKEN=ghp_...          # or use GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY

# Optional
export TAVILY_API_KEY=tvly-...       # enables web_search tool
export CODING_SANDBOX_TYPE=docker    # use Docker sandboxes (default: local)

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

| Hook                  | Mirrors open-swe                | Purpose                                    |
|-----------------------|---------------------------------|--------------------------------------------|
| `MessageQueueHook`    | `check_message_queue`           | Inject queued messages before reasoning    |
| `ThreadBudgetHook`    | `ModelCallLimitMiddleware`      | Per-thread model call cap                  |
| `ModelCallLimitHook`  | `ModelCallLimitMiddleware`      | Global model call cap (across all threads) |

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

- Slack webhook handler (deferred)
- Linear webhook handler (deferred)
- GitHub App JWT token rotation (uses PAT for now)
