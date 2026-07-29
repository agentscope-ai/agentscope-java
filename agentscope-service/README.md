# AgentScope Service

> **Managed Agents** reference implementation on AgentScope 2.0  
> 🇨🇳 Chinese version: [README_zh.md](README_zh.md)

## What this is

**Managed Agents** run agents in a hosted environment: inference, orchestration, and the Harness are owned by the platform, so long-running work does not depend on a laptop staying online. Product teams mainly define Skills, Tools, Subagents, and permission policies—without assembling compaction, session restore, and tool governance themselves.

This repo builds an enterprise-ready Managed Agents platform on AgentScope 2.0’s `HarnessAgent` and pluggable sandboxes:

- Use the engineered **HarnessAgent as the Brain runtime** (reasoning, context, session restore). Filesystem, workspace, and tool execution land on Hands selected by Environment.
- The **platform layer** owns tenancy, ACL, versions, events, and execution-surface choice. The control plane (Agent, Environment, Memory, Vault, Deployment) and data plane (Session, Events, SSE) turn those capabilities into a multi-tenant, auditable, operable product.

Compared with a CLI / single-machine app or “embed Harness in one business app,” Managed Agents moves state up onto the platform:

| Shape | Where state lives | Who isolates | Best for |
|---|---|---|---|
| CLI / [claw](../agentscope-examples/agents/agentscope-paw/) | Local dirs & sessions | OS user | Personal productivity |
| SDK / embedded Harness | App Session / StateStore | Application developer | A single enterprise app |
| **Managed Agents (this project)** | Control-plane resources, shared DB, Session event log | Platform by User / Agent / Environment | Multi-team, multi-tenant platforms |

Two product pillars (aligned with industry Managed Agents):

1. **Do not make builders assemble the Harness.** Compaction, restore, tool-result eviction, LTM refresh stay inside a shared Harness; you configure prompts, Skills, MCP, tool permissions, and Environment.
2. **Split Brain and Hands trust boundaries.** Brain decides *what* to call; Hands touch files, networks, and systems—on the managed host (`local`), a cloud sandbox (`sandbox` / E2B), or a customer-VPC Self-hosted Worker (`self_hosted`).

> If you used the earlier open-source Agent Builder, treat this as its **productization upgrade**: the Harness runtime and main code paths remain; what changed is the resource model, API contract, execution-surface boundary, and multi-tenant governance. Design narrative: [Managed Agents · AgentScope Runtime](../docs/v2/zh/blogs/managed-agents-agentscope-rumtime.md) (Chinese).

---

## Architecture

Four planes: **Go control plane (`aistiod`)** + Java data / scheduler / gateway. They share **one PostgreSQL instance** (`cp` / `rt` / `dp` schemas). Only **gateway :8080** is public.

`aistiod` is a single process on a single port: one listener serves the Managed Agents API (`/api/*`, console JWT), the Kubernetes-native management API (`/api/v1/*`), and the console SPA. Set `AISTIO_ENABLE_KUBERNETES=false` to run without a cluster, which skips the reconcilers, CRD-backed routes, and the ASDP gRPC listener.

```
                 ┌────────────────────────────────────────────┐
 Browser / CLI ─▶│  service-gateway  (Spring Cloud Gateway)   │ :8080
                 └───────┬───────────────────┬────────────────┘
           control APIs  │                   │  data APIs
                         ▼                   ▼
              ┌──────────────────────┐  ┌──────────────────────────┐
              │ aistiod (Go)         │  │ service-dataplane        │ :8082
              │ :8081                │  │ Brain / events / SSE     │
              │ /api/*    products   │  │ (loads product data via  │
              │ /api/v1/* management │  │  CP internal APIs)       │
              │ SPA / reconcilers    │  └───────────┬──────────────┘
              └────────┬─────────────┘              │
                       ▼                            ▼
              ┌────────────────────────────────────────────────────┐
              │  PostgreSQL (cp = product, rt = runtime, dp = data)│
              └────────────────────────────────────────────────────┘
                                    ▲
                       ┌────────────┴───────────┐
                       │ service-scheduler      │ :8083
                       └────────────────────────┘
```

| Plane | Role | Does not |
|---|---|---|
| **Gateway** | Public front door; route by API surface | Business logic, DB |
| **Control (`aistiod`)** | Product resources, session lifecycle, cross-framework agent management, SPA | Brain / turns |
| **Data** | Turns, events, SSE, leases, HITL, work queue | Direct `cp` schema reads |
| **Scheduler** | IM, outbound, cron, Hands workers | Model inference |

Contract draft: [docs/aistio-cp-contract.md](docs/aistio-cp-contract.md). The control plane is Go **`aistiod`** ([`aistio/`](aistio/)); both the Java `service-controlplane` module and the separate `aistio-cp` binary have been removed.

### Modules

| Module | Role |
|---|---|
| `aistio/` (`aistiod`) | Go control plane: Managed Agents API + management API + console SPA (`aistio/ui`) |
| `service-common` | Shared library |
| `service-gateway` | Edge routing |
| `service-dataplane` | Data plane / Brain runtime |
| `service-scheduler` | Channels, cron, Hands workers |

### One hosted session

1. Console signs in through the gateway (JWT).
2. Control creates a versioned Agent, Environment, and Session (product resources).
3. User message → gateway → **Data** (`{events:[{type,payload}]}`).
4. Data takes the turn lease, builds / caches `HarnessAgent` (Brain), appends events.
5. Browser `GET …/events/stream?after=`; SSE **fans out via DB cursor poll**.
6. Tools follow Environment (local / E2B / self-hosted queue); interrupts use local or cross-process `CoordinationStore` tickets.

Brain (`HarnessAgent` objects) and Session (stable ID + event sequence) have different lifecycles: nodes may drop Java objects; conversations restore from shared state. See [02-architecture.md](docs/guide/02-architecture.md), [05-environments.md](docs/guide/05-environments.md), [13-operations.md](docs/guide/13-operations.md).

---

## How to use

### Fastest local path (try Managed Agents)

```bash
export DASHSCOPE_API_KEY=sk-xxx

cd agentscope-service

# First time or after code changes
BUILDER_REBUILD=1 scripts/dev-up.sh
```

| | |
|---|---|
| Console | http://localhost:8080 |
| Default login | `admin` / `admin` (also `bob`/`bob`, `alice`/`alice`) |
| Stop | `scripts/dev-down.sh` |
| State / logs / H2 | `.dev-stack/` |

Starts Postgres (Docker) + **`aistiod`** / data / scheduler / gateway. Local default: `SPRING_PROFILES_ACTIVE=jdbc`.

Smoke: `scripts/smoke.sh` after the stack is up.

### Console smoke test

1. Open http://localhost:8080 and sign in.
2. Create Agent → Environment (`local` for trials) → Session.
3. Send a message and confirm the event stream / reply.

Same Agent, different Environment types = different Hands. curl: [03-quickstart.md](docs/guide/03-quickstart.md). Checklist: [14-validation.md](docs/guide/14-validation.md).

### Docker / frontend HMR

```bash
mvn -pl agentscope-service -am install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build

cd frontend && npm run dev   # /api → :8080
```

### Production (short)

Share across planes: `BUILDER_JWT_SECRET`, `BUILDER_INTERNAL_TOKEN`, `BUILDER_VAULT_MASTER_KEY`, `BUILDER_DB_*`. Internal token is validated outside `dev`/`test`. Full ops: [13-operations.md](docs/guide/13-operations.md).

---

## Resource model

| Resource | API | Role |
|---|---|---|
| **Agent** (versioned) | `/api/agents`, `/versions` | Business definition snapshots; optimistic lock; archive |
| **Environment** | `/api/environments` | Hands surface: `local` / `sandbox` (E2B) / `remote` / `self_hosted` |
| **Session** | `/api/sessions` | Agent × Environment run; append-only events + SSE (`?after=`) |
| **Memory store** | `/api/memory-stores` | Cross-session docs; orthogonal to harness `MEMORY.md` |
| **Vault** | `/api/vaults` | Encrypted credentials → MCP `${ENV}` |
| **Deployment** | `/api/deployments` | cron (scheduler → control fire) / webhook / manual |

Product path is managed session only. Legacy `/api/agents/{id}/chat/*` is gone. `always_ask` → HITL; `user.interrupt` supports cross-process cancel.

---

## Configuration

Prefix: `builder.*` / `BUILDER_*`.

### Model

```yaml
builder:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY:}
    model-name: qwen-max
    stream: true
```

Or supply your own `Model` bean.

### Workspace and JWT

```yaml
builder:
  workspace: ${BUILDER_WORKSPACE:}
  jwt:
    secret: ${BUILDER_JWT_SECRET:builder-default-dev-secret-change-in-production-32chars}
```

Override JWT in production (≥32 chars). Config path: `~/.agentscope/builder/agentscope.json`.

---

## Hands / filesystem surfaces

Prefer **Environment type** over rewriting prompts:

| Environment | Where Hands run | Typical use |
|---|---|---|
| `local` | Brain host namespace | Dev / trusted intranet |
| `sandbox` | E2B (or compatible) cloud sandbox; Brain initiates | Hosted isolated execution |
| `self_hosted` | Customer Worker outbound poll; schema-only on Brain | Keep enterprise data in customer VPC |
| `remote` | Distributed KV FS (no shell) | Shared workspace across replicas |

`sandbox` config:

```yaml
builder:
  e2b:
    api-key: ${BUILDER_E2B_API_KEY:}
    template-id: base
    workspace-root: /home/user
    sandbox-timeout-seconds: 300
    persistence-mode: TAR
```

Do **not** switch Environment mid-Session; create a new Session for a new trust boundary. Details: [05-environments.md](docs/guide/05-environments.md), [14-validation.md](docs/guide/14-validation.md).

`builder.workspace-store.fs-spec` is the older workspace-store switch; Managed `sandbox` always uses **E2B**.

---

## Persistence

`dev-up.sh` uses H2 TCP for users, catalog, shares, `builder_agent_state`, and `builder_coord_*` (leases, HITL, interrupt tickets, work queue, cron fire, …).

Production: point `BUILDER_DB_*` at the **same** MySQL / PostgreSQL. **Control** seeds schema. Replicas share the DB for restore and coordination (optional Store bean overrides).

Self-hosted: outbound `io.agentscope.builder.worker.HandsWorkerMain` from the `service-scheduler` jar.

---

## Environment variables

| Variable | Description |
|---|---|
| `DASHSCOPE_API_KEY` | Model key (required for trials) |
| `BUILDER_MODEL_NAME` | Default model name |
| `BUILDER_JWT_SECRET` | JWT (same on all planes) |
| `BUILDER_INTERNAL_TOKEN` | Plane-to-plane secret; validated outside `dev`/`test` |
| `BUILDER_VAULT_MASTER_KEY` | Vault master key |
| `BUILDER_DB_URL` / `USER` / `PASSWORD` / `DRIVER` | Shared DB |
| `BUILDER_WORKSPACE` | Workspace root |
| `BUILDER_E2B_API_KEY` (and related) | `sandbox` Hands |
| `BUILDER_CONTROL_URL` / `DATA_URL` / `SCHEDULER_URL` | Inter-plane URLs |
| `BUILDER_REBUILD=1` | Script: force rebuild |
| `SPRING_PROFILES_ACTIVE` | Local default `dev` |

---

## Docs index

| Doc | Contents |
|---|---|
| [Managed Agents blog](../docs/v2/zh/blogs/managed-agents-agentscope-rumtime.md) | Product background, Brain/Hands, three Hands modes (ZH) |
| [docs/guide/README.md](docs/guide/README.md) | Product guide |
| [03-quickstart.md](docs/guide/03-quickstart.md) | curl first session |
| [13-operations.md](docs/guide/13-operations.md) | Deploy & ops |
| [14-validation.md](docs/guide/14-validation.md) | Trial checklist |
| [MANAGED_AGENTS_API.md](docs/WIP/MANAGED_AGENTS_API.md) | HTTP API |
| [DATA_PLANE_CONTRACT.md](docs/WIP/DATA_PLANE_CONTRACT.md) | Events / Worker contract |
| [events/README.md](docs/events/README.md) | Event types |
| [FOLLOW_UP_PRODUCTION.md](docs/WIP/FOLLOW_UP_PRODUCTION.md) | Production follow-ups |
