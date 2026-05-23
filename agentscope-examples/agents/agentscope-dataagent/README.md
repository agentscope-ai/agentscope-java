# agentscope-claw

**DataAgent** — a multi-tenant, distributable data-analysis assistant built on
**HarnessAgent**. This module is the deployable shell that wraps a single
business agent (the bundled example targets internal data analysis) and serves
it to many users at once.

Run it and users log in through a React chat UI to converse with a fully-wired
DataAgent (Skills, sub-agents, optional sandbox filesystem). Each user gets
their own isolated workspace, sessions, skills, and knowledge directory.

---

## Architecture

```
agentscope-claw (port 8080)
┌──────────────────────────────────┐
│  Spring Boot WebFlux             │
│  ┌──────────────────────────┐    │
│  │ ClawBootstrap            │    │
│  │ HarnessAgent             │    │
│  │ ChatUiChannel            │    │
│  │ Sub-agents               │    │
│  │ Skills                   │    │
│  │ (Sandbox FS)             │    │
│  └──────────────────────────┘    │
│  React SPA                       │
│  ├ Chat module (any user)        │
│  └ Admin console (ROLE_ADMIN)    │
│  JWT Auth (BCrypt)               │
│                                  │
│  .agentscope/                    │
│    agentscope.json               │
│    users.json                    │
│    sessions.json                 │
│    workspace/                    │
│      AGENTS.md                   │
│      skills/                     │
│      subagents/                  │
│      agents/                     │
└──────────────────────────────────┘
```

**Deployment shape:** a single executable JAR. Both the chat module and the
read-only admin console are React routes inside the same SPA — login role
(`ROLE_ADMIN` vs. regular user) decides the landing page.

---

## Sample agent (bundled)

On first start, claw materialises `classpath:/workspace-template/` into
`.agentscope/` (idempotent — existing files are never overwritten). The bundled
template ships a working agent named **`data-agent`** with:

- **System prompt:** `workspace/AGENTS.md` — defines DataAgent's operating
  principles (Skills first, delegate to sub-agents, cite sources, structured
  output).
- **Skills** (loaded automatically from `workspace/skills/`):
  - `sql-analysis` — SOP for turning a business question into a verified SQL
    answer with a small result table and the query that produced it.
  - `chart-rendering` — SOP for visualising results (line / bar / area /
    scatter) with sane defaults and matching commentary.
- **Sub-agents** (loaded automatically from `workspace/subagents/`):
  - `data-explorer` — Multi-source schema/data exploration when the right
    source isn't obvious (30 iterations).
  - `report-writer` — Polished written deliverable on top of already-computed
    numbers (25 iterations).
- **Channel preferences:** Each user can store per-channel preferences
  (`displayLabel`, `sessionScope`, `language`, `enabledSkills`) at
  `/api/user/bindings`. DataAgent always answers — preferences only shape *how*
  it answers (e.g. force replies in `zh-CN`, restrict to a subset of skills).

You can freely edit any file under `.agentscope/` to customise the agent —
changes are preserved across restarts.

---

## Getting started

### 1. Configure a model

```bash
export DASHSCOPE_API_KEY=sk-...
```

Or provide your own `Model` Spring bean to use a different provider.

### 2. (Optional) Enable sandbox mode

Sandbox mode runs every agent's file I/O and shell commands inside an isolated
Docker container instead of the host. Edit `application.yml`:

```yaml
claw:
  sandbox:
    enabled: true
    image: agentscope/python-sandbox:py311-slim
    isolation: USER          # one sandbox per user
```

Requires the Docker CLI on `PATH` and a reachable Docker daemon.

### 3. (Optional) Enable Redis for distributed deployment

For horizontal scale (multiple claw instances), back the agent session state
with Redis:

```yaml
claw:
  session:
    redis:
      enabled: true
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD:}
      database: 0
```

When `claw.session.redis.enabled=true`:

- **Sandbox state** — `RedisSession` is auto-wired into `SandboxDistributedOptions`;
  sandbox state survives across instances.
- **Tool-event SSE** — `RedisToolEventBus` replaces the in-process bus, so an SSE
  consumer on R2 sees tool-call events fired by R1 in the same session.
- **User channel preferences** — `UserBindingStore` switches to `RedisKvStore`
  (key prefix `claw:bindings:`); updates on R1 are immediately visible on R2.
- **Workspace and other JSON stores** (`users.json`, `usage`, `sessions.json`)
  remain file-backed — point `claw.workspace` at a **shared volume** (NFS/EFS)
  so every replica sees the same data. Startup will fail loudly if it detects
  Redis enabled with `claw.workspace` on local-ephemeral paths
  (`/tmp/`, `/var/tmp/`, …).

When disabled, claw runs single-node with file-based session persistence.

See [`docs/cluster-deploy.md`](docs/cluster-deploy.md) for a docker-compose
walkthrough of a 3-replica + Redis + shared NFS deployment, including the
pre-flight checks that intentionally refuse misconfigured setups.

### 4. Run

```bash
java -jar target/agentscope-claw-*-exec.jar
```

Open **http://localhost:8080** and log in (default admin account is auto-created
on first start — check the startup logs for credentials).

---

## Configuration reference (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `claw.jwt.secret` | dev placeholder | JWT signing secret. **Refuses to boot in non-dev profiles when left at the default.** |
| `claw.workspace` | `$CWD` *(dev only)* | Working directory; `.agentscope/` is created here. **Required** in non-dev profiles — startup fails if blank. |
| `claw.workspace-evolution.retention-days` | `30` | How long per-session workspace mutation logs are kept under `.agentscope/workspace/agents/*/sessions/*.workspace.jsonl`. Set to `0` to disable cleanup. |
| `claw.dashscope.api-key` | _(empty)_ | DashScope API key. |
| `claw.dashscope.model-name` | `qwen-max` | Model name. |
| `claw.sandbox.enabled` | `false` | Run agents inside Docker sandboxes. |
| `claw.sandbox.image` | `agentscope/python-sandbox:py311-slim` | Sandbox image. |
| `claw.sandbox.network` | `none` | Docker network mode. `none` disables outbound networking. |
| `claw.sandbox.isolation` | `USER` | `SESSION` \| `USER` \| `AGENT` \| `GLOBAL` — one sandbox per scope. |
| `claw.sandbox.projection-roots` | `AGENTS.md,skills,subagents,knowledge` | Host paths copied into the sandbox. |
| `claw.sandbox.cpu-count` | `0` | CPU cap (0 = unlimited). |
| `claw.sandbox.memory-bytes` | `0` | Memory cap in bytes (0 = unlimited). |
| `claw.session.redis.enabled` | `false` | Use Redis for distributed agent state. |
| `claw.session.redis.host/port/password/database` | `localhost:6379/0` | Redis connection. |
| `claw.session.redis.key-prefix` | `claw:session:` | Redis key namespace. |
| `server.port` | `8080` | HTTP port. |

---

## API endpoints

### Public

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Login, returns JWT |

### User-scoped (any authenticated user)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/auth/me` | Current user info |
| `GET` | `/api/me/agent-info` | Metadata for the single product agent (id / name / description / maxIters) |
| `POST` | `/api/chat/stream` | SSE streaming chat (request body: `{ message }`) |
| `POST` | `/api/chat/send` | Synchronous chat (request body: `{ message }`) |
| `GET` | `/api/sessions` | List own sessions |
| `GET` | `/api/sessions/{key}/history` | Session message history |
| `GET` | `/api/sessions/{key}/turns` | Per-turn transcript |
| `GET` | `/api/sessions/{key}/tree` | Sub-agent fan-out tree (user-scoped) |
| `GET` | `/api/sessions/{key}/workspace/events?since=&limit=` | Workspace mutation timeline for this session |
| `POST` | `/api/sessions/{key}/reset` | Reset a session |
| `GET` | `/api/user/profile` | View own profile |
| `POST` | `/api/user/change-password` | Change password |
| `GET` `POST` | `/api/user/bindings` | List / add channel preferences |
| `PUT` `DELETE` | `/api/user/bindings/{index}` | Update / remove a preference |
| `GET` | `/api/user/identity-links` | List identity links |
| `GET` | `/api/skills` | List available Skills |
| `GET` | `/api/usage/me/summary` | Own usage summary |
| `GET` | `/api/usage/me/daily?days=7` | Own daily turn counts |

### Admin-scoped (`ROLE_ADMIN`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/users` | List all users |
| `POST` | `/api/admin/users` | Create user |
| `GET` | `/api/admin/runtime/overview` | Platform overview |
| `GET` | `/api/admin/runtime/instances` | Registered agent instances |
| `GET` | `/api/admin/runtime/sessions` | All sessions (flat) |
| `GET` | `/api/admin/runtime/sessions/{key}` | Single session detail |
| `GET` | `/api/admin/runtime/sessions/{key}/tree` | Sub-agent tree rooted at session |
| `GET` | `/api/admin/runtime/sessions/{key}/workspace/events?since=&limit=` | Workspace mutation timeline |
| `GET` | `/api/admin/runtime/channels` | Channel state |
| `GET` | `/api/admin/runtime/logs` | Live log stream (SSE) |
| `GET` | `/api/admin/usage/summary` | Platform totals |
| `GET` | `/api/admin/usage/hourly?hours=24` | Hourly turn counts |
| `GET` | `/api/admin/usage/daily?days=30` | Daily turn counts |
| `GET` | `/api/admin/usage/top-users?days=7&n=10` | Top users |
| `GET` | `/api/admin/usage/top-agents?days=7&n=10` | Top agents |
| `GET` | `/api/admin/usage/users-rollup?days=30` | Per-user rollup |
| `GET` | `/api/admin/usage/agents-rollup?days=30` | Per-agent rollup |
| `GET` | `/api/admin/usage/user/{userId}/daily?days=30` | One user's daily turns |

### Admin console (read-only workspace + config inspection, `ROLE_ADMIN`)

These endpoints back the `/admin/*` SPA routes and read directly from the
on-disk `.agentscope/` workspace.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/agents/{id}/workspace` | Workspace summary for one agent |
| `GET` | `/api/admin/agents/{id}/workspace/agents-md` | Read `AGENTS.md` |
| `GET` | `/api/admin/agents/{id}/workspace/memory` | Memory index + daily files |
| `GET` | `/api/admin/agents/{id}/workspace/skills` | List skills |
| `GET` | `/api/admin/agents/{id}/workspace/skills/{name}` | Read a skill file |
| `GET` | `/api/admin/config/agentscope` | Raw `agentscope.json` |
| `GET` | `/api/admin/channels/{channelId}/bindings` | Routing bindings configured for a channel |
| `GET` | `/api/admin/debug/info` | Debug info (returns `{ logStreamUrl }`) |

---

## Building

```bash
mvn package -pl agentscope-claw -am -DskipTests
```

Outputs:
- `target/agentscope-claw-<version>-exec.jar` — executable fat JAR
- `target/agentscope-claw-<version>.jar` — thin library JAR (for reuse as a
  Maven dependency)
