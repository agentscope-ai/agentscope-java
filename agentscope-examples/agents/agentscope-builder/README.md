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
| **Isolation** | Per-`(userId, agentId)` workspace namespaces; optional Docker sandbox per user |
| **Self-evolution** | ✅ Same as claw — but inside each user's own workspace |
| **Sharing** | ✅ With specific users, groups, or globally — and with run / edit / fork tiers |
| **Distribution** | ✅ Horizontally scalable once the remote filesystem and a distributed session backend are configured |
| **Filesystem** | `CompositeFilesystem` — composes per-user namespaced storage with optional sandbox / remote stores |

### Architecture

Builder runs every agent through a **HarnessAgent on a `CompositeFilesystem`**.
The composite splits each agent's filesystem into namespaced layers, so the
same `WorkspaceManager` API serves a tenant locally, in a Docker sandbox, or
against a distributed `BaseStore` — all driven by the `builder.workspace-store.fs-spec`
switch.

```
┌─────────────────────────────────────────────────────────────────────┐
│  AgentScope Builder (Spring Boot, port 8080)                        │
│                                                                     │
│   React SPA ──▶  REST API (JWT)                                     │
│                  │                                                  │
│                  ▼                                                  │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  HarnessGateway                                              │  │
│   │   ├─ Agent (alice, agent-A) ──┐                              │  │
│   │   ├─ Agent (alice, agent-B)   │  HarnessAgent per (user,id)  │  │
│   │   └─ Agent (bob,   agent-A) ──┘                              │  │
│   └──────────────────────────────────┬───────────────────────────┘  │
│                                      ▼                              │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  CompositeFilesystem  (per-(userId, agentId) namespace)      │  │
│   │   ┌──────────────┬──────────────┬─────────────────────────┐  │  │
│   │   │  local       │  sandbox     │  remote                 │  │  │
│   │   │  (default)   │  (Docker)    │  (BaseStore: Redis/OSS) │  │  │
│   │   └──────────────┴──────────────┴─────────────────────────┘  │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│   User & agent records  (H2 by default; switch to MySQL/PG for prod)│
└─────────────────────────────────────────────────────────────────────┘
```

The three `fs-spec` modes (`local` / `sandbox` / `remote`) all reuse the
**same** `CompositeFilesystem` shape — only the underlying storage engine
changes. See **[Filesystem Modes](#filesystem-modes)** below for picking one.

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

Builder supports three filesystem modes that control how per-(user, agent) workspaces are backed. Set via `builder.workspace-store.fs-spec`.

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

### Sandbox Mode

```yaml
builder:
  workspace-store:
    fs-spec: sandbox
  sandbox:
    enabled: true
    image: agentscope/python-sandbox:py311-slim
    network: none
    workspace-root: /workspace
    isolation: USER
    projection-roots: AGENTS.md,skills,subagents,knowledge
    cpu-count: 1
    memory-bytes: 1073741824   # 1 GiB
```

Agents run inside Docker containers, providing OS-level isolation per user. Workspace files (skills, subagents, AGENTS.md, knowledge) are projected from the host into the container. The web API continues managing projected files on the host filesystem.

**Prerequisites:**
- Docker daemon accessible to the application
- The sandbox image built and available locally (or pullable)

**Configuration reference:**

| Property | Env Var | Default | Description |
|---|---|---|---|
| `builder.sandbox.enabled` | `BUILDER_SANDBOX_ENABLED` | `false` | Enable sandbox mode |
| `builder.sandbox.image` | `BUILDER_SANDBOX_IMAGE` | `agentscope/python-sandbox:py311-slim` | Docker image |
| `builder.sandbox.network` | `BUILDER_SANDBOX_NETWORK` | `none` | Docker network mode |
| `builder.sandbox.workspace-root` | `BUILDER_SANDBOX_WORKSPACE_ROOT` | `/workspace` | Mount path inside container |
| `builder.sandbox.isolation` | `BUILDER_SANDBOX_ISOLATION` | `USER` | Isolation scope: `SESSION`, `USER`, `AGENT`, `GLOBAL` |
| `builder.sandbox.projection-roots` | `BUILDER_SANDBOX_PROJECTION_ROOTS` | `AGENTS.md,skills,subagents,knowledge` | Host files projected into container |
| `builder.sandbox.cpu-count` | `BUILDER_SANDBOX_CPU_COUNT` | `0` (no limit) | CPU limit per container |
| `builder.sandbox.memory-bytes` | `BUILDER_SANDBOX_MEMORY_BYTES` | `0` (no limit) | Memory limit per container (bytes) |

**Isolation scopes:**
- `SESSION` — one container per chat session
- `USER` — one container per user, shared across sessions
- `AGENT` — one container per agent, shared across users
- `GLOBAL` — single container shared globally

**Distributed sandbox:** By default, sandbox runs in single-node mode. For multi-replica deployments, provide a distributed `Session` bean (e.g. Redis-backed) so sandbox state is shared across instances.

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

Builder ships with embedded H2 for instant local quick-start — users, agent definitions and share grants are persisted automatically; default `admin/admin`, `bob/bob` and `alice/alice` accounts are seeded so you can log in immediately. For production, switch to MySQL or PostgreSQL by activating the bundled `jdbc` Spring profile and overriding the JDBC URL / credentials (`BUILDER_DB_URL`, `BUILDER_DB_USER`, `BUILDER_DB_PASSWORD`); both drivers are already on the classpath.

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

Per-agent sandbox config (`sandbox.mode` / `sandbox.scope`) is metadata stored with the agent definition. The runtime sandbox behavior is currently controlled globally via `builder.sandbox.*` properties.

---

## Environment Variables Reference

| Variable | Property | Default | Description |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | `builder.dashscope.api-key` | (none) | DashScope API key |
| `BUILDER_MODEL_NAME` | `builder.dashscope.model-name` | `qwen-max` | Model name |
| `BUILDER_WORKSPACE` | `builder.workspace` | (JVM cwd) | Working directory |
| `BUILDER_JWT_SECRET` | `builder.jwt.secret` | (dev default) | JWT signing secret |
| `BUILDER_WORKSPACE_FS_SPEC` | `builder.workspace-store.fs-spec` | `local` | Filesystem mode |
| `BUILDER_SANDBOX_ENABLED` | `builder.sandbox.enabled` | `false` | Enable sandbox |
| `BUILDER_SANDBOX_IMAGE` | `builder.sandbox.image` | `agentscope/python-sandbox:py311-slim` | Sandbox Docker image |
| `BUILDER_SANDBOX_ISOLATION` | `builder.sandbox.isolation` | `USER` | Sandbox isolation scope |
| `BUILDER_AGENT_NAME` | `builder.agent.name` | `builder-agent` | Default agent name |
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | (none) | Set to `jdbc` to switch the database from H2 to MySQL / PostgreSQL |
| `BUILDER_DB_URL` / `BUILDER_DB_USER` / `BUILDER_DB_PASSWORD` | `spring.datasource.*` | H2 file under `${user.home}/.agentscope-builder/` | Production database connection — see [Persistence](#persistence) |
