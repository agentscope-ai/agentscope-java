# agentscope-dataagent

> 🇨🇳 中文版：[README_zh.md](README_zh.md)

## Overview

dataagent is a place where every analyst in your company gets their own data
agent — one that learns the team's data sources, picks up the team's
reporting conventions, remembers the team's gotchas, and gets sharper the
more they use it.

Three things shape the design:

- **Side-by-side evolution.** Each user has their own private workspace.
  Whatever skills they teach, whatever sub-agents they shape, whatever
  memory their agent builds up — it stays theirs, and doesn't bleed into
  anyone else's. Everyone starts from the same agent; how it grows up is
  personal.
- **A capability marketplace, not a free-for-all.** When someone grows
  something genuinely useful — a SQL skill, a sub-agent, a note in memory —
  they can nominate it for sharing. An admin reviews it, and if approved,
  it lands in a shared library that every other user's agent picks up
  automatically. Knowledge moves bottom-up, but with a gate.
- **You decide what the sandbox is.** Every script the agent runs goes
  through an isolated execution sandbox. Unlike codingagent — which spins
  containers up and down per session on its own — dataagent leaves
  sandbox lifecycle to *you*: provision it the way your data security and
  ops teams want, recycle it on whatever cadence makes sense, plug in
  whatever drivers and notebook tooling fit the stack.

dataagent is built to be deployed distributed from day one — flip on the
shared-state switch and any replica can serve any user, no sticky sessions
required.

### At a glance

| | dataagent |
|---|---|
| **Use it when** | A team of analysts each needs their own evolving SQL / chart / report agent, with a shared library of in-house know-how |
| **Users** | Many — each gets a private workspace, plus optional side channels |
| **Isolation** | Private workspace per `(user, agent)`; scripts run in an isolated sandbox |
| **Self-evolution** | ✅ Per user — **plus** approved contributions are shared with everyone |
| **Sandbox lifecycle** | **Owned by the application** — provisioned, sized and recycled outside the agent runtime |
| **Channels** | Built-in web UI · DingTalk · generic HTTP webhook |
| **Distribution** | ✅ First-class — turn on shared state and any replica serves any user |
| **Filesystem** | `SandboxFilesystem` for script execution + `OverlayFilesystem` for blending shared / private skills |

### Architecture

dataagent is built on **HarnessAgent + `SandboxFilesystem`** with sandbox
lifecycle owned by the application (you decide when, where, and how the
sandbox lives — not the runtime). Above the sandbox sits an `OverlayFilesystem`
that blends a per-user `RemoteFilesystem` (top, writable) with the global
`shared/` directory (bottom, read-only, populated by the marketplace approval
flow).

```
┌───────────────────────────────────────────────────────────────────────┐
│  agentscope-dataagent (port 8080, Spring Boot WebFlux)                │
│                                                                       │
│   React SPA ──▶ REST API (JWT)                                        │
│                  │                                                    │
│                  ▼                                                    │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  HarnessGateway                                                 │ │
│   │   ├ data-agent              (built-in skeleton, GLOBAL)         │ │
│   │   └ uda-{userId}-{agentId}  (per-user fork, per-tenant)         │ │
│   └────────────────────────┬────────────────────────────────────────┘ │
│                            ▼                                          │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  Per-(userId, agentId) Filesystem stack                         │ │
│   │   ┌──────────────────────────────────────────────────────────┐  │ │
│   │   │ OverlayFilesystem (skills/, subagents/)                  │  │ │
│   │   │   ┌── upper: per-user RemoteFilesystem  (writable) ──┐   │  │ │
│   │   │   └── lower: shared/{skills,subagents}    (RO)        │   │  │ │
│   │   └──────────────────────────────────────────────────────────┘  │ │
│   │   memory/, MEMORY.md, sessions/, tasks/  ← per-user RemoteFS    │ │
│   │   knowledge/, AGENTS.md                  ← shared (RO)          │ │
│   │                                                                 │ │
│   │   SandboxFilesystem (script execution)                          │ │
│   │     ▲ lifecycle managed by the application (not the runtime)    │ │
│   └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│   Marketplace:  user contributes ─▶ admin approves ─▶ shared/ grows   │
│   Channels:     chatui · dingtalk · generic webhook                   │
│   Storage:      embedded H2 by default (swap for MySQL/PG in prod)    │
└───────────────────────────────────────────────────────────────────────┘
```

With shared state enabled, the per-user filesystem, sessions and tool-event
bus are all backed by Redis, so any replica can serve any user. See
[`docs/cluster-deploy.md`](docs/cluster-deploy.md).

---

## What is included

- **Built-in `data-agent`** — global tenant; ships with SQL-analysis and
  chart-rendering skills plus `data-explorer` / `report-writer` sub-agents.
- **Per-user data agents** — every authenticated user can fork the built-in or
  draft a new one. Each user-agent gets its own `CompositeFilesystem` keyed by
  `(userId, agentId)`; `skills/` and `subagents/` are an `OverlayFilesystem`
  with the shared on-disk content as the lower layer.
- **Channels (v1)**: `chatui` (always-on, primary), `dingtalk` (opt-in,
  ported intact from agentscope-builder), and a new **generic webhook** channel
  (HMAC-signed HTTP-in, callback or long-poll HTTP-out).
- **Capability marketplace** — users nominate skills, sub-agents, or memory
  snippets from their own workspace as *contributions*. Admins approve via the
  Approvals page; approved files land under `shared/` and become visible to
  every tenant on the next agent build (via the overlay's lower layer).
- **DataAgent toolkit slot** — `list_data_sources`, `describe_table`,
  `run_sql_preview`, `render_chart` are registered on every data agent. v1
  ships interface-only stubs plus an `InMemoryDataSourceRegistry` so admins
  can seed sources via `agentscope.json`; concrete JDBC connectors are out of
  scope and slot in via `DataSourceRegistry` / `ChartRenderer` Spring beans.

---

## Per-user isolation

Every `HarnessAgent` runs on a `CompositeFilesystem` built by
`WorkspaceManagerFactory.forAgent(ownerId, agentId)`:

| Path | Mount |
|---|---|
| `memory/`, `MEMORY.md`, `sessions/`, `tasks/` | `RemoteFilesystem` namespaced by `(ownerId, agentId)` |
| `skills/`, `subagents/` | `OverlayFilesystem` — per-user `RemoteFilesystem` on top of the shared `shared/{skills,subagents}/` directory |
| `knowledge/`, `AGENTS.md` | Shared on-disk root, mounted read-only for tenants (mutations only go through the marketplace contribution flow) |

The same `RemoteFilesystem` is backed by Redis (and survives replica
restarts) when `dataagent.session.redis.enabled=true`; otherwise it is a local
file store. See [`docs/cluster-deploy.md`](docs/cluster-deploy.md).

---

## Marketplace flow

1. A user (or the agent itself, via the `contribute_to_workspace` tool) submits
   a file from their workspace as a contribution:
   ```http
   POST /api/me/contributions
   { "targetType": "skill",
     "targetPath": "cohort-builder/SKILL.md",
     "rationale": "...",
     "payload": "<file contents>" }
   ```
2. The contribution is persisted with status `PENDING` and shown to admins on
   `/admin/approvals`.
3. Admin approves → the payload is materialised under
   `~/.agentscope/dataagent/workspace/shared/skills/cohort-builder/SKILL.md`.
4. Every per-`(userId, agentId)` overlay picks it up on the next agent build,
   immediately visible to every tenant without restart.

The marketplace is intentionally finer-grained than per-agent ACL sharing — the
unit of contribution is one skill / sub-agent / memory snippet, not an entire
agent.

---

## Getting started

### 1. Configure a model

```bash
export DASHSCOPE_API_KEY=sk-...
```

Or provide your own `Model` Spring bean to use a different provider.

### 2. (Optional) Enable Redis for distributed deployment

```yaml
dataagent:
  session:
    redis:
      enabled: true
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD:}
      database: 0
      key-prefix: "dataagent:session:"
```

When enabled:

- `RemoteFilesystem` writes route through Redis, so a tenant routed to replica
  R2 sees what R1 wrote to `memory/`, `sessions/`, etc.
- `ToolEventBus` becomes Redis Pub/Sub, so an SSE consumer on R2 sees tool-call
  events fired by R1 in the same session.
- Pre-flight refuses to start if `dataagent.workspace` points at an ephemeral
  path (`/tmp/`, `/var/tmp/`, …).

See [`docs/cluster-deploy.md`](docs/cluster-deploy.md) for a 3-replica
walkthrough.

### 3. (Optional) Enable the webhook side-channel

In `~/.agentscope/dataagent/agentscope.json`:

```json
{
  "channels": {
    "ops-webhook": {
      "type": "webhook",
      "defaultAgentId": "data-agent",
      "dmScope": "MAIN",
      "properties": {
        "sharedSecret": "${WEBHOOK_SECRET}",
        "allowedIps": ["10.0.0.0/8"]
      }
    }
  }
}
```

Then external systems call:

```http
POST /api/webhook/ops-webhook/inbound
X-DataAgent-Sig: <HMAC-SHA256 of body, hex>
{ "externalUserId": "alice@corp",
  "externalSessionId": "ticket-1234",
  "message": "how many users signed up yesterday?",
  "replyMode": "callback",
  "callbackUrl": "https://ops.internal/webhook/dataagent" }
```

Replies are POSTed to `callbackUrl` (same HMAC), or parked for long-poll at
`GET /api/webhook/ops-webhook/outbound/{inboundId}` when `replyMode=poll`.

### 4. Run

```bash
java -jar target/agentscope-dataagent-*-exec.jar
```

Open **http://localhost:8080** and log in. The H2 demo seed creates two demo
accounts: `bob` / `bob` and `alice` / `alice`. The first user with
`ROLE_ADMIN` is also seeded — check the startup banner.

---

## Configuration reference (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `dataagent.jwt.secret` | dev placeholder | JWT signing secret (>= 32 chars). **Refuses to boot in non-`dev` profiles when left at the default.** |
| `dataagent.workspace` | `$CWD` *(dev only)* | Working directory for agent runtime state (not config — config lives at `~/.agentscope/dataagent/agentscope.json`). **Required** in non-`dev` profiles — startup fails if blank. |
| `dataagent.workspace-store.local.max-file-size-mb` | `10` | Per-file cap for the `RemoteFilesystem` local store. |
| `dataagent.dashscope.api-key` | _(empty)_ | DashScope API key (fallback when no `Model` bean is registered). |
| `dataagent.dashscope.model-name` | `qwen-max` | DashScope model id. |
| `dataagent.agent.name` | `data-agent` | Used when auto-generating `~/.agentscope/dataagent/agentscope.json`. |
| `dataagent.agent.sys-prompt` | _(built-in)_ | Used when auto-generating `~/.agentscope/dataagent/agentscope.json`. |
| `dataagent.channels.chatui.enabled` | `true` | Primary web channel. Always-on by default. |
| `dataagent.session.redis.enabled` | `false` | Use Redis for distributed agent state. |
| `dataagent.session.redis.host/port/password/database` | `localhost:6379/0` | Redis connection. |
| `dataagent.session.redis.key-prefix` | `dataagent:session:` | Redis key namespace. |
| `dataagent.marketplace.enabled` | `true` | Disable to hide the contribution + approval API. |
| `dataagent.marketplace.max-contribution-bytes` | `1048576` | Max payload accepted by `POST /api/me/contributions`. |
| `server.port` | `8080` | HTTP port. |

User accounts, agents and contributions are persisted to embedded H2 by
default for instant local quick-start. For production deployments, activate
the `jdbc` Spring profile and set `DATAAGENT_DB_URL` / `DATAAGENT_DB_USER`
/ `DATAAGENT_DB_PASSWORD` to switch to MySQL or PostgreSQL.

---

## API endpoints

### Public

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Login, returns JWT |
| `POST` | `/api/webhook/{channelId}/inbound` | Generic webhook ingress (HMAC-required) |
| `GET` | `/api/webhook/{channelId}/outbound/{inboundId}` | Long-poll for the reply in `poll` mode |

### User-scoped (any authenticated user)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/auth/me` | Current user info |
| `GET` | `/api/me/agent-info` | Metadata for the built-in `data-agent` |
| `POST` | `/api/chat/stream` | SSE streaming chat (request body: `{ message }`) |
| `POST` | `/api/chat/send` | Synchronous chat (request body: `{ message }`) |
| `GET` | `/api/sessions` | List own sessions |
| `GET` | `/api/sessions/{key}/history` | Session message history |
| `GET` | `/api/sessions/{key}/turns` | Per-turn transcript |
| `GET` | `/api/sessions/{key}/tree` | Sub-agent fan-out tree |
| `POST` | `/api/sessions/{key}/reset` | Reset a session |
| `GET` `POST` | `/api/me/agents` | List / create per-user data agents |
| `GET` `POST` | `/api/me/agents/{id}/skills` | List / write a skill in your workspace |
| `GET` `POST` | `/api/me/agents/{id}/tools` | List / register custom tools |
| `POST` | `/api/me/agents/{sourceId}/clone` | Fork a built-in or your own agent |
| `GET` `POST` | `/api/user/bindings` | List / add channel preferences |
| `PUT` `DELETE` | `/api/user/bindings/{index}` | Update / remove a preference |
| `GET` `POST` | `/api/me/contributions` | List own contributions / submit a new one |

### Admin-scoped (`ROLE_ADMIN`)

| Method | Path | Description |
|---|---|---|
| `GET` `POST` | `/api/admin/users` | List / create users |
| `GET` | `/api/admin/runtime/overview` | Platform overview |
| `GET` | `/api/admin/runtime/instances` | Registered agent instances |
| `GET` | `/api/admin/runtime/sessions` | All sessions |
| `GET` | `/api/admin/runtime/channels` | Channel state |
| `GET` | `/api/admin/contributions?status=PENDING\|APPROVED\|REJECTED` | List contributions |
| `POST` | `/api/admin/contributions/{id}/approve` | Approve → materialise under `shared/` |
| `POST` | `/api/admin/contributions/{id}/reject` | Reject with reviewer note |
| `GET` | `/api/admin/config/agentscope` | Raw `agentscope.json` |
| `GET` | `/api/admin/channels/{channelId}/bindings` | Routing bindings for a channel |
| `GET` | `/api/admin/usage/...` | Usage rollups (per-user / per-agent / hourly / daily) |

---

## Building

```bash
mvn -pl agentscope-examples/agents/agentscope-dataagent -am package -DskipTests
```

Outputs:

- `target/agentscope-dataagent-<version>-exec.jar` — executable fat JAR
- `target/agentscope-dataagent-<version>.jar` — thin library JAR

Format check (CI gates on this):

```bash
mvn -pl agentscope-examples/agents/agentscope-dataagent spotless:check
```
