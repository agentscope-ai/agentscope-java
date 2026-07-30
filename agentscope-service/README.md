# AgentScope Service

> Hosted agent platform (control plane + data plane) on AgentScope  
> 🇨🇳 Chinese: [README_zh.md](README_zh.md)

This directory is a locally runnable hosted-agent stack: the **control plane** owns product resources (Agent, Environment, Session, …); the **data plane** runs conversation turns, events, and SSE; a gateway is the only public entry. Control is the Go process `aistiod`; data / scheduler / gateway are Java.

---

## Design

Four planes share **one PostgreSQL** instance (schemas `cp` / `rt` / `dp`). Only **gateway `:8080`** is public.

```
                 ┌────────────────────────────────────────────┐
 Browser / CLI ─▶│  service-gateway                           │ :8080
                 └───────┬───────────────────┬────────────────┘
           control APIs  │                   │  data APIs
                         ▼                   ▼
              ┌──────────────────────┐  ┌──────────────────────────┐
              │ aistiod (Go)         │  │ service-dataplane        │ :8082
              │ :8081                │  │ Brain / events / SSE     │
              │ /api/*    products   │  │ (loads product data via  │
              │ /api/v1/* runtime    │  │  CP internal APIs; never │
              │ console SPA          │  │  reads cp schema)        │
              └────────┬─────────────┘  └───────────┬──────────────┘
                       ▼                            ▼
              ┌────────────────────────────────────────────────────┐
              │  PostgreSQL (cp = product, rt = runtime, dp = data)│
              └────────────────────────────────────────────────────┘
                                    ▲
                       ┌────────────┴───────────┐
                       │ service-scheduler      │ :8083
                       │ channels / cron / Hands│
                       └────────────────────────┘
```

| Plane | Does | Does not |
|-------|------|----------|
| **Gateway** | Public routing to control or data | Business logic, DB |
| **Control (`aistiod`)** | Product resources; session lifecycle; console; runtime query/commands (context, compress, …) | Model turns |
| **Data** | `user.message` → turn; event log + SSE; leases / HITL / work queue | Direct `cp` schema access |
| **Scheduler** | IM, outbound, cron, Hands workers | Inference loop |

`aistiod` is **one process, one port**:

- `/api/*` — product API (console JWT)
- `/api/v1/*` — runtime / fleet (overview, dataplanes, session observation, …)
- Console SPA (`aistio/ui`)

Local `dev-up` sets `AISTIO_ENABLE_KUBERNETES=false`: no reconciler / CRD / ASDP gRPC; product APIs and the Java data plane still work. Third-party agents (e.g. paw + `agentscope-extensions-aistio`) can appear in Operate via HTTP self-registration (`POST /api/v1/dataplanes/register`) without Kubernetes.

CP↔DP contract: [docs/aistio-cp-contract.md](docs/aistio-cp-contract.md).

### Modules

| Path | Role |
|------|------|
| [`aistio/`](aistio/) | Go control plane `aistiod` + built console `ui/` |
| `service-common` | Shared Java library |
| `service-gateway` | Edge gateway |
| `service-dataplane` | Data plane / Brain |
| `service-scheduler` | Channels, cron, Hands workers |
| `frontend/` | Console source (builds into `aistio/ui`) |

### One session

1. Sign in through the gateway (JWT).
2. Control creates Agent, Environment, Session.
3. User message → gateway → **data** (`{events:[{type,payload}]}`).
4. Data takes a turn lease, runs `HarnessAgent`, appends events.
5. Client uses `GET …/events/stream?after=` (DB-cursor SSE fan-out).
6. Tools follow the Environment (`local` / `sandbox` / `self_hosted`, …).

In-process Brain objects may be dropped; Session restore is driven by the shared event log.

---

## Local use

```bash
export DASHSCOPE_API_KEY=sk-xxx

cd agentscope-service

# First time or after code changes
BUILDER_REBUILD=1 scripts/dev-up.sh
```

| | |
|--|--|
| Console | http://localhost:8080 |
| Login | `admin` / `admin` (also `bob`/`bob`, `alice`/`alice`) |
| Stop | `scripts/dev-down.sh` |
| Logs / state | `.dev-stack/` |

Starts Postgres (Docker) + `aistiod` + data + scheduler + gateway. Java planes default to `SPRING_PROFILES_ACTIVE=jdbc`.

Optional API smoke after the stack is up:

```bash
scripts/smoke.sh
```

### Console walkthrough

1. Open http://localhost:8080 and sign in.
2. Create Agent → Environment (`local` for trials) → Session.
3. Send a message and confirm the event stream / reply.

curl examples: [docs/guide/03-quickstart.md](docs/guide/03-quickstart.md).

### Docker / frontend HMR

```bash
# From the monorepo root
mvn -pl agentscope-service -am install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build

cd agentscope-service/frontend && npm run dev   # /api → :8080
```

### Ports

| Service | Port |
|---------|------|
| Gateway (public) | 8080 |
| aistiod | 8081 |
| Dataplane | 8082 |
| Scheduler | 8083 |
| Postgres | 5432 |

---

## Resources (control plane)

| Resource | API prefix | Notes |
|----------|------------|-------|
| Agent (versioned) | `/api/agents` | Definitions and versions |
| Environment | `/api/environments` | Hands: `local` / `sandbox` / `self_hosted` / `remote` |
| Session | `/api/sessions` | Agent × Environment; events + SSE |
| Memory store | `/api/memory-stores` | Cross-session documents |
| Vault | `/api/vaults` | Encrypted credentials |
| Deployment | `/api/deployments` | cron / webhook / manual |

Product chat path: create Session → append events on the data plane → subscribe to SSE. The data plane also serves `/agentscope/*` so the control plane can pull sessions/context or issue compress / terminate.

---

## Configuration

Java planes use `builder.*` / `BUILDER_*`. They must share JWT, internal token, and DB settings with `aistiod`.

| Variable | Purpose |
|----------|---------|
| `DASHSCOPE_API_KEY` | Model key (needed for local chat) |
| `BUILDER_JWT_SECRET` | JWT (same on all planes; change in production) |
| `BUILDER_INTERNAL_TOKEN` | Inter-plane secret |
| `BUILDER_VAULT_MASTER_KEY` | Vault master key |
| `BUILDER_DB_*` | Shared DB (`dev-up` points at Docker Postgres) |
| `BUILDER_CONTROL_URL` / `DATA_URL` / `SCHEDULER_URL` | Inter-plane URLs |
| `BUILDER_E2B_API_KEY` (and related) | `sandbox` Hands |
| `BUILDER_REBUILD=1` | Force rebuild in `dev-up` |
| `AISTIO_ENABLE_KUBERNETES` | Fixed to `false` by `dev-up` |
| `AISTIO_PRODUCT_DSN` | Control-plane product DSN (`cp`) |

Production ops: [docs/guide/13-operations.md](docs/guide/13-operations.md).

---

## Docs

| Doc | Contents |
|-----|----------|
| [docs/guide/README.md](docs/guide/README.md) | Guide index |
| [02-architecture.md](docs/guide/02-architecture.md) | Architecture |
| [03-quickstart.md](docs/guide/03-quickstart.md) | curl first session |
| [05-environments.md](docs/guide/05-environments.md) | Environment / Hands |
| [13-operations.md](docs/guide/13-operations.md) | Operations |
| [aistio-cp-contract.md](docs/aistio-cp-contract.md) | Control ↔ data contract |
| [events/README.md](docs/events/README.md) | Event types |
