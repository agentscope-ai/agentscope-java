# AgentScope Builder

> 🇨🇳 中文版：[README_zh.md](README_zh.md)

## Overview

Builder is the multi-tenant cousin of [claw](../agentscope-claw/) — the same
self-evolving agent, but wrapped into a hosted platform that a whole team or
company can share. People log in through a browser, build their own agents
without writing code, and each gets their own workspace to evolve them in.
When someone has built something good, they can share it — privately with a
teammate, with a group, or with everyone in the org.

A few things this means in practice:

- Building an agent is a few clicks in a UI, not a Maven build. Pick the
  skills, sub-agents, tools and MCP servers you want; save; you have an agent.
- One person's tweaks to a skill never leak into anyone else's workspace.
  Each `(user, agent)` pair has its own slice — the same starting agent
  grows up differently in different hands.
- Sharing comes with tiers: run-only, edit, or fork. An owner can let
  others use an agent without letting them rewrite it.
- The runtime underneath is the same self-evolving agent claw uses; Builder
  just adds the auth, the tenancy, and the operations console around it.

If it's just for you, use claw. When the same idea needs to scale to a team
or a company, that's Builder.

### At a glance

| | Builder |
|---|---|
| **Use it when** | A whole team or organisation needs to build and run self-evolving agents |
| **Users** | Many — every authenticated user has their own workspace |
| **Isolation** | Per-`(userId, agentId)` workspace namespaces; Managed Environment `sandbox` (E2B) or `self_hosted` Worker |
| **Self-evolution** | ✅ Same as claw — but inside each user's own workspace |
| **Sharing** | ✅ With specific users, groups, or globally — and with run / edit / fork tiers |
| **Distribution** | ✅ Horizontally scalable with shared JDBC (+ optional remote FS); Hands Worker is outbound-only |
| **Execution** | Environment types: `local` / `sandbox` (E2B) / `remote` / `self_hosted` (outbound Worker) |

### Architecture

Builder runs Managed Agents through **HarnessAgent** with Hands resolved by **Environment type**:
local host FS, **E2B** cloud sandboxes (`type=sandbox`), remote KV FS, or event-driven
**self_hosted** Workers. Brain and Hands stay split — Workers never need inbound ports or a shared disk.

```
┌─────────────────────────────────────────────────────────────────────┐
│  AgentScope Builder (Spring Boot, port 8080)                        │
│                                                                     │
│   React SPA ──▶  REST API (JWT)                                     │
│                  │                                                  │
│                  ▼                                                  │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  Managed Sessions (Brain)                                    │  │
│   │   Agent × Environment → HarnessAgent + event log / SSE       │  │
│   └──────────────────────────────────┬───────────────────────────┘  │
│                                      ▼                              │
│   ┌──────────────┬──────────────┬──────────────┬─────────────────┐  │
│   │ local        │ sandbox      │ remote       │ self_hosted     │  │
│   │ (host FS)    │ (E2B cloud)  │ (BaseStore)  │ (outbound Worker)│ │
│   └──────────────┴──────────────┴──────────────┴─────────────────┘  │
│                                                                     │
│   Catalog + sessions (H2 by default; MySQL/PG for prod)             │
└─────────────────────────────────────────────────────────────────────┘
```

Product surface is **Environment type** on Sessions (not legacy Docker). E2B config:
`builder.e2b.*` / `BUILDER_E2B_API_KEY`. Details: **[Filesystem Modes](#filesystem-modes)**
and [docs/guide/05-environments.md](docs/guide/05-environments.md).

---

## Managed Agents resources

Builder now exposes a Claude Managed Agents–aligned resource model on top of
the existing HarnessAgent runtime:

| Resource | API | Role |
|---|---|---|
| **Agent** (versioned) | `/api/agents`, `/api/agents/{id}/versions` | Optimistic-lock updates (`version` required); archive; immutable config snapshots (globals materialize under owner `__global__`) |
| **Environment** | `/api/environments` | Execution template: `local` (host) / `sandbox` (E2B cloud hands) / `remote` (distributed KV FS, no shell) / `self_hosted` (outbound Worker hands); shareable via `/shares`. Worker API: `/api/environments/{id}/work/*` |
| **Session** | `/api/sessions` | Agent × Environment run; append-only `{domain}.{action}` event log; SSE stream; IM channels also bridge here |
| **Memory store** | `/api/memory-stores` | Cross-session JPA documents mounted as live FS routes at `memory-stores/{name}/` (not host materialize); orthogonal to harness-native `MEMORY.md` / `memory/` LTM |
| **Vault** | `/api/vaults` | Per-user encrypted credentials (AES-GCM); injected into MCP `tools.json` `${ENV}` / server env at session build |
| **Deployment** | `/api/deployments` | Cron / webhook / manual triggers that create a managed session and run a turn |
| **Multiagent** | `/api/multiagent/run` | Sequential fan-out across agents (subagents also via SessionsTool on UCAs) |

Chat UI defaults to **managed session** mode (create session → post `user.message` → stream events). Legacy `/api/agents/{id}/chat/*` is **deprecated** and kept as a fallback toggle for one release cycle.

Session turns resolve a HarnessAgent keyed by `(owner, agent, version, environment, mounts)` so pinned versions and environments actually affect runtime filesystem topology and prompts. Tool calls with `permissionPolicies` of `always_ask` pause the session (`requires_action`) until the UI posts `user.tool_confirmation`. `user.interrupt` cancels the Reactor subscription and calls `HarnessAgent.interrupt()`.

Set `BUILDER_VAULT_MASTER_KEY` in production for vault encryption.

Production follow-ups that do **not** block trial completeness (multi-replica interrupt, Files mounts, Worker custom-tool SPI, legacy chat retirement, etc.) are tracked in [docs/FOLLOW_UP_PRODUCTION.md](docs/FOLLOW_UP_PRODUCTION.md).

Managed Agents HTTP surface (control plane / data plane / gaps vs Claude): [docs/MANAGED_AGENTS_API.md](docs/MANAGED_AGENTS_API.md).

**Product guide**: [docs/guide/README.md](docs/guide/README.md).  
**Deploy**: [docs/guide/13-operations.md](docs/guide/13-operations.md).  
**Trial checklist** (local / E2B / self_hosted / HITL): [docs/guide/14-validation.md](docs/guide/14-validation.md).

API shape refactor (Agent body consolidation, etc.): [docs/API_REFACTOR.md](docs/API_REFACTOR.md).

Data-plane contract (events, deltas, worker, errors): [docs/DATA_PLANE_CONTRACT.md](docs/DATA_PLANE_CONTRACT.md).
Event type reference: [docs/events/README.md](docs/events/README.md).

---

## Quick Start

```bash
# Set your model API key
export DASHSCOPE_API_KEY=sk-xxx

# Build and run
mvn -pl agentscope-examples/agents/agentscope-builder -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-builder/target/agentscope-builder-*.jar
```

The server starts on `http://localhost:8080`. On first launch, a default agent config is auto-generated at `~/.agentscope/builder/agentscope.json`.

## Configuration

All configuration uses the `builder.*` property prefix. Properties can be set in `application.yml`, as JVM system properties (`-Dbuilder.xxx`), or as environment variables (`BUILDER_XXX`).

### Model

```yaml
builder:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY:}
    model-name: qwen-max
    stream: true
```

Alternatively, provide your own `Model` Spring bean to use any supported model (OpenAI, Anthropic, Gemini, Ollama, etc.).

### Workspace

```yaml
builder:
  workspace: ${BUILDER_WORKSPACE:}   # Working directory; defaults to JVM cwd
```

The agent config file is read from `~/.agentscope/builder/agentscope.json` — a fixed per-app
location, independent of `builder.workspace`, so different harness apps (builder, dataagent,
codingagent, claw) cannot collide on shared cwd. Each agent's workspace defaults to
`~/.agentscope/builder/workspace` unless overridden per-agent via the `workspace` field in
`agentscope.json`.

### JWT

```yaml
builder:
  jwt:
    secret: ${BUILDER_JWT_SECRET:builder-default-dev-secret-change-in-production-32chars}
```

**Must be overridden in production** with a secret of at least 32 characters.

---

## Filesystem Modes

For **Managed Agents trials**, pick an Environment type (`local` / `sandbox` / `self_hosted` / `remote`) — see [14-validation.md](docs/guide/14-validation.md).  
The `builder.workspace-store.fs-spec` switch below is the older workspace-store backing knobs; Managed `type=sandbox` always uses **E2B**, not local Docker.

### Local Mode (default)

```yaml
builder:
  workspace-store:
    fs-spec: local
    local:
      max-file-size-mb: 10
```

Agents run directly on the host with `LocalFilesystemWithShell`. Each user's workspace is isolated via namespace-scoped directories under the agent workspace root (`users/{userId}/agents/{agentId}/`). Shell commands execute on the host OS.

**When to use:** Single-node deployments, local development, trusted environments.

### Sandbox Mode (Managed Environment `type=sandbox` → E2B)

Managed Agents sessions with `environment.type=sandbox` run hands tools inside **E2B cloud sandboxes** via `agentscope-extensions-sandbox-e2b` (`E2bFilesystemSpec`). Builder does **not** use local Docker or Daytona for this path.

```yaml
builder:
  e2b:
    api-key: ${BUILDER_E2B_API_KEY:}   # or set E2B_API_KEY; per-env config.apiKey overrides
    template-id: base
    workspace-root: /home/user
    sandbox-timeout-seconds: 300
    persistence-mode: TAR
```

**Prerequisites:**
- Valid E2B API key
- Optional custom E2B template for pre-installed runtimes (prefer templates over Claude-style `packages` — not enforced yet; see `docs/SANDBOX_GAPS.md`)

**Configuration reference:**

| Property | Env Var | Default | Description |
|---|---|---|---|
| `builder.e2b.api-key` | `BUILDER_E2B_API_KEY` (fallback `E2B_API_KEY`) | empty | E2B API key (required for `type=sandbox`) |
| `builder.e2b.template-id` | `BUILDER_E2B_TEMPLATE_ID` | `base` | Default E2B template |
| `builder.e2b.workspace-root` | `BUILDER_E2B_WORKSPACE_ROOT` | `/home/user` | Workspace path inside sandbox |
| `builder.e2b.sandbox-timeout-seconds` | `BUILDER_E2B_SANDBOX_TIMEOUT_SECONDS` | `300` | Sandbox timeout |
| `builder.e2b.api-base-url` | `BUILDER_E2B_API_BASE_URL` | empty | Optional API base override |
| `builder.e2b.domain` | `BUILDER_E2B_DOMAIN` | empty | Optional domain override |
| `builder.e2b.persistence-mode` | `BUILDER_E2B_PERSISTENCE_MODE` | `TAR` | `TAR` or `NATIVE_SNAPSHOT` |

**Isolation scopes** (environment `config.isolationScope`, default `SESSION`):
- `SESSION` — one sandbox isolation key per chat session (Claude-aligned default)
- `USER` / `AGENT` / `GLOBAL` — coarser sharing (advanced)

Create example: see [docs/guide/05-environments.md](docs/guide/05-environments.md).

**When to use:** Multi-tenant deployments where agents execute untrusted code, or when OS-level isolation between users is required.

### Remote Mode

```yaml
builder:
  workspace-store:
    fs-spec: remote
```

Both agent runtime and workspace management use a distributed `BaseStore` store. Agent filesystem operations go through `RemoteFilesystem` / `CompositeFilesystem`, and the web API workspace store also uses `RemoteFilesystem`.

**Prerequisites:**
- A `BaseStore` Spring bean must be provided (e.g. Redis, OSS, or a custom implementation)
- A distributed `Session` bean is required (e.g. `RedisSession`)

**When to use:** Horizontally scaled deployments where workspace data must be shared across multiple application replicas.

---

## Persistence

Builder ships with embedded H2 for instant local quick-start — users, agent definitions, share grants, short-term agent brain state (`AgentStateStore` → `builder_agent_state`), and multi-replica coordination tables (`builder_coord_*` for turn leases, HITL tickets, hands work queue, cron fire leases) are persisted automatically; default `admin/admin`, `bob/bob` and `alice/alice` accounts are seeded so you can log in immediately. For production, switch to MySQL or PostgreSQL by activating the bundled `jdbc` Spring profile and overriding the JDBC URL / credentials (`BUILDER_DB_URL`, `BUILDER_DB_USER`, `BUILDER_DB_PASSWORD`); both drivers are already on the classpath. Pointing replicas at the same DataSource shares catalog data, conversation state, and coordination without a separate Redis requirement (override the `AgentStateStore` / `CoordinationStore` beans if you prefer Redis). Set `builder.hands.in-process-worker=false` and run `io.agentscope.builder.worker.HandsWorkerMain` for an out-of-process Hands worker.

---

## Agent Configuration

Agents are defined in `~/.agentscope/builder/agentscope.json`:

```json
{
  "main": "default",
  "agents": {
    "default": {
      "name": "my-agent",
      "sysPrompt": "You are a helpful assistant.",
      "maxIters": 10,
      "model": "anthropic/claude-sonnet-4-6",
      "sandbox": {
        "mode": "all",
        "scope": "user"
      }
    }
  }
}
```

Omit the `workspace` field to use the per-app default (`~/.agentscope/builder/workspace`).

Per-agent sandbox config (`sandbox.mode` / `sandbox.scope`) is metadata on the agent definition. Managed session execution uses the Session's Environment (`type=sandbox` → E2B via `builder.e2b.*` / env `config`).

---

## Environment Variables Reference

| Variable | Property | Default | Description |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | `builder.dashscope.api-key` | (none) | DashScope API key |
| `BUILDER_MODEL_NAME` | `builder.dashscope.model-name` | `qwen-max` | Model name |
| `BUILDER_WORKSPACE` | `builder.workspace` | (JVM cwd) | Working directory |
| `BUILDER_JWT_SECRET` | `builder.jwt.secret` | (dev default) | JWT signing secret |
| `BUILDER_WORKSPACE_FS_SPEC` | `builder.workspace-store.fs-spec` | `local` | Filesystem mode |
| `BUILDER_E2B_API_KEY` | `builder.e2b.api-key` | empty | E2B API key for `type=sandbox` |
| `BUILDER_E2B_TEMPLATE_ID` | `builder.e2b.template-id` | `base` | Default E2B template |
| `BUILDER_E2B_SANDBOX_TIMEOUT_SECONDS` | `builder.e2b.sandbox-timeout-seconds` | `300` | E2B sandbox timeout |
| `BUILDER_AGENT_NAME` | `builder.agent.name` | `builder-agent` | Default agent name |
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | (none) | Set to `jdbc` to switch the database from H2 to MySQL / PostgreSQL |
| `BUILDER_DB_URL` / `BUILDER_DB_USER` / `BUILDER_DB_PASSWORD` | `spring.datasource.*` | H2 file under `${user.home}/.agentscope-builder/` | Production database connection — see [Persistence](#persistence) |
