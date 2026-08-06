# Agent Service

> **Build managed agents, coordinate agent teams, and operate the entire fleet from one dashboard.**

[中文说明](README_zh.md)

Agent Service is a hosted-agent platform built on AgentScope Harness. It gives teams one product
surface for creating managed agents, running durable sessions, coordinating multi-agent work, and
operating live agent infrastructure.

## Overview

The product is organized around three core capabilities.

### Dashboard

Dashboard is the Control Plane for the agent fleet. It shows what is running now, what needs
attention, and what happened previously.

- Live agent and healthy-instance counts, with offline and historical agents separated from the
  default view
- Dataplane health, revisions, replicas, and active sessions
- Session timelines, context pressure, token usage, errors, and runtime commands
- Agent Team activity, member state, task progress, and lifecycle visibility
- Managed and BYO AgentScope runtimes in the same operational model

### Managed Agents

Managed Agents provides a complete hosted lifecycle from definition to durable conversation.

- Versioned agent definitions with models, system prompts, built-in tools, MCP servers, and skills
- Explicit execution environments: `local`, `sandbox`, `remote`, and `self_hosted`
- Top-level managed sessions with static creation, event-driven turns, resumable history, and SSE
- Tool approval and human-in-the-loop continuation
- Session-mounted memory stores, encrypted vault credentials, workspaces, and resource overrides
- Manual, webhook, cron, and channel-based entry points

### Agent Teams

Agent Teams turns independent agents into a coordinated unit with durable collaboration state.

- Lead/member roles, team objectives, and reusable team composition
- Direct and broadcast messages, shared tasks, claim/assign workflows, and plan approval
- Dynamic membership with policy limits and allowed-agent controls
- Member wakeups, graceful shutdown, lifecycle deadlines, and restart recovery
- Persistent messages and tasks that survive individual process or session restarts

## Architecture

Agent Service is the product; the components below are its implementation. Only the gateway is
intended to be public. Internal components communicate with a shared token and have explicit data
ownership.

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                              Agent Service                                 │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Web Console: Dashboard · Managed Agents · Agent Teams               │  │
│  └───────────────────────────────┬──────────────────────────────────────┘  │
│                                  │                                         │
│                        Browser / SDK / CLI                                 │
│                                  │                                         │
│                                  ▼                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ service-gateway :8080 · authentication and public API routing       │  │
│  └───────────────────┬──────────────────────────────────┬───────────────┘  │
│                      │                                  │                  │
│                      ▼                                  ▼                  │
│  ┌──────────────────────────────┐    ┌─────────────────────────────────┐  │
│  │ aistiod :8081               │    │ service-dataplane :8082         │  │
│  │ product + runtime control   │    │ AgentScope Brain                │  │
│  │ fleet registry + teams      │    │ turns · events · SSE · HITL     │  │
│  └──────────────┬───────────────┘    └────────────────┬────────────────┘  │
│                 │                                     │                   │
│                 │         ┌───────────────────────────┘                   │
│                 ▼         ▼                                               │
│  ┌──────────────────────────────┐    ┌─────────────────────────────────┐  │
│  │ PostgreSQL                  │◀───│ service-scheduler :8083         │  │
│  │ cp · rt · dp schemas        │    │ channels · cron · Hands workers │  │
│  └──────────────────────────────┘    └─────────────────────────────────┘  │
│                                                                            │
│     Managed HarnessAgent runtimes · BYO AgentScope runtimes · Sandboxes   │
└────────────────────────────────────────────────────────────────────────────┘
```

### Plane responsibilities

| Plane | Owns | Does not own |
| --- | --- | --- |
| Gateway | Public routing and edge concerns | Business state or agent execution |
| Control (`aistiod`) | Product resources, console, fleet state, sessions, teams, runtime commands | Model turns |
| Data plane | Harness runtime, event log, SSE, turn leases, HITL, and work queue | Direct access to the `cp` schema |
| Scheduler | Channels, cron, outbound delivery, and self-hosted Hands workers | Inference loop |

### Data ownership

All planes may use the same PostgreSQL server, but they do not share tables:

| Schema | Owner | Data |
| --- | --- | --- |
| `cp` | `aistiod` product API | Users, agents, versions, environments, sessions, vaults, memory, deployments |
| `rt` | Aistio runtime store | Fleet instances, runtime sessions, contexts, teams, tasks, and messages |
| `dp` | Java data plane | Session events, coordination, state, HITL, work items, and data-plane projections |

The data plane resolves a managed session through the control-plane internal API and builds the
agent from the returned snapshot. It never uses a local product-catalog fallback.

### One turn, end to end

1. The client appends a `user.message` event to an existing session.
2. The data plane acquires a turn lease and marks the session `running`.
3. The control plane resolves the pinned agent snapshot, environment, workspace, memory, and vaults.
4. `SessionTurnRunner` runs `HarnessAgent.streamEvents`.
5. Durable events such as `agent.message`, `agent.tool_use`, and `span.model_request_*` are appended
   to PostgreSQL; optional preview deltas are streamed without being persisted.
6. The session becomes `idle`, pauses for HITL/tool results, or terminates with a typed error.

Clients restore from the durable event sequence and continue with
`GET /api/sessions/{id}/events/stream?after={seq}`. In-memory agent objects and preview streams are
not the source of truth.

### Brain and Hands

The **Brain** owns context, reasoning, tool selection, and the event log. **Hands** determine where
tools execute:

| Environment | Execution model |
| --- | --- |
| `local` | Runs filesystem and shell tools in the data-plane host; intended for development |
| `sandbox` | Uses a managed E2B sandbox |
| `remote` | Uses a remote/distributed filesystem without a local shell |
| `self_hosted` | Queues schema-only tool calls for an outbound customer worker |

## Quick start

### Prerequisites

- Docker
- JDK 17+
- Maven
- Go 1.26+
- A model API key; the example below uses DashScope

Node.js is only required when rebuilding the web console.

### 1. Start the local stack

From the monorepo:

```bash
export DASHSCOPE_API_KEY=sk-xxx

cd agentscope-service
BUILDER_REBUILD=1 scripts/dev-up.sh
```

This starts PostgreSQL, `aistiod`, the data plane, scheduler, and gateway. Local development sets
`AISTIO_ENABLE_KUBERNETES=false`; CRD reconcilers and ASDP gRPC are not required for the hosted
product flow.

| Endpoint | Value |
| --- | --- |
| Console and public API | http://localhost:8080 |
| Default login | `admin` / `admin` |
| Additional seed users | `alice` / `alice`, `bob` / `bob` |
| Logs and local state | `.dev-stack/` |

Default users and development secrets are for local use only.

### 2. Run your first session

1. Open http://localhost:8080 and sign in.
2. In **Managed Agents**, create an Agent.
3. Create a `local` Environment.
4. Open **Sessions**, create a session bound to the Agent and Environment, and send a message.
5. In **Dashboard**, inspect the live agent, session events, and runtime state.

For a complete API-only walkthrough:

```bash
scripts/smoke.sh
```

The step-by-step curl flow is documented in
[`docs/guide/03-quickstart.md`](docs/guide/03-quickstart.md).

### 3. Stop the stack

```bash
scripts/dev-down.sh
```

## Product model

| Resource | Purpose |
| --- | --- |
| Agent | Versioned system prompt, model, tools, MCP servers, skills, and collaboration settings |
| Environment | Tool execution boundary and sandbox/worker configuration |
| Session | Stateful binding of an agent version, environment, memory, vaults, and event stream |
| Memory store | Documents shared across sessions |
| Vault | Encrypted credentials resolved into tools at runtime |
| Deployment | Manual, cron, or webhook trigger for an agent turn |
| Channel | Messaging integration and outbound delivery |
| Team | Lead/member collaboration with messages, tasks, plans, and lifecycle state |

Session creation is intentionally static: creating a session records its bindings but does not run
the agent. The first `user.message` starts the turn.

## Features

### Event-native sessions

Inbound events drive work; outbound events describe progress and results. Every durable event has a
monotonic session sequence, so clients can reconnect without replaying the whole stream. Optional
`event_start` and `event_delta` frames add responsive streaming while the final event remains
authoritative.

### Human-in-the-loop tools

Tools configured with an ask policy suspend the turn and emit a confirmation request. A
`user.tool_confirmation` event resumes or denies execution without losing the session history.

### Self-hosted execution

For private infrastructure, `self_hosted` environments expose tools as schemas in the Brain while
an outbound worker polls, acknowledges, heartbeats, executes, and returns tool results. The worker
does not require inbound connectivity.

### Managed and BYO agents

Managed agents are built from control-plane snapshots in the Java data plane. Existing AgentScope
applications can register through the Aistio extension and appear in Dashboard alongside managed
agents. Fleet views count live agents by default and retain offline/historical views separately.

### Multi-agent teams

Teams provide lead/member roles, task claim and assignment, direct and broadcast messaging,
plan approval, dynamic members, wakeups, shutdown policy, and recovery across session restarts.

## Project layout

| Path | Role |
| --- | --- |
| [`aistio/`](aistio/) | Go control plane, Kubernetes integration, runtime store, and built console assets |
| [`frontend/`](frontend/) | React/Vite console source; builds into `aistio/ui` |
| [`service-common/`](service-common/) | Shared Java contracts, persistence, auth, events, and coordination |
| [`service-gateway/`](service-gateway/) | Public Spring Cloud Gateway |
| [`service-dataplane/`](service-dataplane/) | Managed-session Brain and AgentScope Harness runtime |
| [`service-scheduler/`](service-scheduler/) | Channels, cron, outbound jobs, and Hands worker |
| [`scripts/`](scripts/) | Local lifecycle and smoke-test scripts |
| [`docs/`](docs/) | Architecture, API, event, operations, and validation guides |

## Development

### Build the backend

Run the Maven build from the monorepo root so all AgentScope snapshots used by the service jars are
current:

```bash
mvn install -DskipTests

cd agentscope-service/aistio
make build
make test
```

### Build or run the console

```bash
cd agentscope-service/frontend
npm install
npm run build   # emits static assets into ../aistio/ui

npm run dev     # Vite HMR; /api proxies to the gateway
```

### Run with Docker Compose

Build the Java artifacts first, then start the containerized stack:

```bash
mvn install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build
```

### Service ports

| Service | Port | Exposure |
| --- | ---: | --- |
| Gateway | 8080 | Public |
| `aistiod` | 8081 | Internal |
| Data plane | 8082 | Internal |
| Scheduler | 8083 | Internal |
| PostgreSQL | 5432 | Local infrastructure |

## Configuration

Java services use `builder.*` properties and `BUILDER_*` environment variables. All planes must
agree on authentication secrets and internal URLs.

| Variable | Purpose |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope model credential for local turns |
| `BUILDER_JWT_SECRET` | JWT signing secret shared by gateway/control components |
| `BUILDER_INTERNAL_TOKEN` | Secret for trusted plane-to-plane requests |
| `BUILDER_VAULT_MASTER_KEY` | Encryption key for vault credentials |
| `BUILDER_DB_URL`, `BUILDER_DB_USER`, `BUILDER_DB_PASSWORD` | Java data-plane database |
| `BUILDER_CONTROL_URL`, `BUILDER_DATA_URL`, `BUILDER_SCHEDULER_URL` | Internal service endpoints |
| `BUILDER_E2B_API_KEY` | E2B credential for `sandbox` environments |
| `AISTIO_PRODUCT_DSN` | Product database used by `aistiod` |
| `AISTIO_ENABLE_KUBERNETES` | Enables Aistio CRD reconcilers and Kubernetes integration |
| `BUILDER_REBUILD=1` | Forces a full local rebuild before `dev-up` |

Production deployments must replace all development credentials and use durable PostgreSQL
storage. See the [operations guide](docs/guide/13-operations.md).

## Documentation

| Guide | Contents |
| --- | --- |
| [Product guide](docs/guide/README.md) | Concepts and documentation index |
| [Architecture](docs/guide/02-architecture.md) | Plane boundaries, Brain/Hands, and turn lifecycle |
| [Quick start](docs/guide/03-quickstart.md) | First agent and session using curl |
| [Agents](docs/guide/04-agents.md) | Agent definitions and versions |
| [Environments](docs/guide/05-environments.md) | Local, sandbox, remote, and self-hosted execution |
| [Sessions](docs/guide/06-sessions.md) | Session model and lifecycle |
| [Events](docs/events/README.md) | Event types and error contract |
| [Operations](docs/guide/13-operations.md) | Deployment and production configuration |
| [Validation](docs/guide/14-validation.md) | End-to-end acceptance scenarios |
| [Control/data contract](docs/aistio-cp-contract.md) | Internal control-plane ↔ data-plane API |

## License

Agent Service is distributed under the
[Apache License 2.0](aistio/LICENSE).
