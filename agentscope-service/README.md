# AgentScope Service

> **An Agent control and orchestration platform built on AgentScope Harness — a unified control plane for the enterprise.**

[中文说明](README_zh.md)

AgentScope Service is not meant to replace your existing Agent frameworks. It adds a unified control plane so you can govern and coordinate Agents built with different frameworks and stacks — Claude, OpenClaw, QwenPaw, and more — in one place.

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-architecture.png)

- **AgentScope Service is a control plane.** It provides agent registration, discovery, and distributed coordination for every Agent in the enterprise, and works with mainstream Agent runtimes including AgentScope, LangChain, ADK, and Claude / Qoder. Enterprises get a single place to inspect Agent metrics and operate on live Sessions — for example, compressing session context.
- **AgentScope Service provides low-code Agent creation and deployment.** Built on the AgentScope Harness runtime, it lets you run multiple Agents on one Managed Agents platform under unified operations. The platform hosts Harness capabilities, while tool execution can be delegated to a Sandbox that you control.
- **Agents registered with AgentScope Service can be assembled into one or more Teams.** Whether the Agent is a self-hosted AgentScope runtime or a low-code Managed Agent Harness runtime, Agents can be orchestrated together to tackle more complex work.

These paths are not mutually exclusive. Inside one company, R&D may use Coding Agents, a business platform may run AgentScope, and a new project may want hosted Harness from day one — that mix is common. AgentScope Service does not lock you into any single agent framework or platform; it provides unified control-plane capabilities across agent runtimes.

## Capabilities

### Control Plane

The Control Plane (component name: Aistio) is the core of AgentScope Service. Every Agent application registers through it. Via SDK or Sidecar, it supports mainstream Agent Frameworks (AgentScope, LangChain, ADK) as well as Claude, Qoder, and similar runtimes.

The Dashboard is the Control Plane's visual console. It gives the whole fleet a live view of online agents, deployment instances, active sessions, token usage, and other global signals so operators can see how the cluster is doing.

![dashboard](/docs/imgs/agentservice/agentscope-service-dashboard.png)

From the Dashboard you can also inspect session details, view the live context state of an active session (including how different parts of the context contribute), dynamically adjust or compress session context, and intervene in a running conversation.

### Managed Agents

Managed Agents evolve from the `agentscope-builder` platform. They remain a low-code Agent platform that gives developers SaaS-style Agent definition and hosted execution. The upgrade further emphasizes the split between reasoning and tool execution: Harness capabilities are hosted more thoroughly, while tool execution stays under greater user control.

![managed agents](/docs/imgs/agentservice/agentscope-service-managedagents-arc.png)

Agent definition follows the core design of AgentScope Harness. You first define foundational concepts such as Workspace and Memory, then associate a workspace and memory with an agent to create it.

The recommended path is: create Agent → create Environment → create Session → send the first message → watch the event stream in the Dashboard. Creating a Session alone does not start the Agent. For long-running work, Managed Agents especially emphasize **recoverability**: events are persisted, state can be rebuilt, and HITL can pause and resume. A front-end refresh or a service replica change should not mean starting over.

Runtime design is closely aligned with Claude Managed Agents. Harness infrastructure and runtime are fully hosted (backed by AgentScope Harness Runtime). The Brain/Hands split gives users more control over where tools actually run. Deployment separates into a Control Plane and a managed Dataplane — see the production deployment section below.

### Agent Teams

Every agent registered with the AgentScope Service Control Plane — whether self-deployed and registered through a framework (LangChain, AgentScope, ADK, Claude SDK, and so on), or created as a Managed Agent through the low-code path — can be orchestrated into Agent Teams to collaborate on complex work.

![agent-service-teams.png](/docs/imgs/agentservice/agent-service-teams.png)

In AgentScope Service, a Team is not a chat room. It is an operable collaboration unit: tasks can be claimed, plans can be approved, members can be woken, and state does not vanish just because a Session ends. A common pattern is a Lead that decomposes and accepts work, with Members claiming research, coding, verification, and other subtasks by capability. The platform owns message routing, the task board, and lifecycle — business code should not have to hand-roll temporary multi-process communication.

One point worth calling out: AgentScope Framework natively supports Agent Teams. That mechanism uses the AgentScope Service Control Plane for distributed task management and scheduling. So you can either use AgentScope Framework's native Teams capability during main-agent development to compose multi-agent collaboration, or dynamically assemble independent Agents in the console for a specific complex task. Which path you choose depends on the scenario.

## Architecture

### How it works

Humans reach the Control Plane through the Dashboard (browser) or the REST API (SDK / curl / third-party integration). Under the control plane, four Agent attachment models are managed together:

- Native AgentScope attachment
- LangChain via `instrument()`
- Claude via Sidecar
- QwenPaw via Sidecar

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-architecture.png)

### Production deployment architecture

In production, the recommended AgentScope Service deployment looks like this:

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-production-deploy.png)


| Plane | Owns | Does not own |
| --- | --- | --- |
| Gateway | Public entry, authentication, and API routing | Business state and Agent execution |
| Control Plane (`aistiod`) | Product resources, console, Agent state, Sessions, Teams, and runtime commands | Harness inference and Session stream transport |
| Dataplane | Managed Harness Runtime, event log, SSE, Turn Lease, HITL, and Work Queue | Direct reads of product Catalog tables |
| Scheduler | Channel, Cron, outbound jobs, and Self-hosted Hands Workers | The inference loop |


## How Agents attach

AgentScope Service serves two kinds of users at once:

1. **Platform / platform-services teams**: create Managed Agents through the Console / API and build hosted agents quickly.
2. **Business engineering teams**: already have Agents built with different stacks and want them under unified governance — attach to the control plane through extensions / SDKs / Sidecar.

Currently supports Agent Framework, Coding Agent,

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
git clone https://github.com/agentscope-ai/agentscope-java.git
cd agentscope-java

export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
scripts/dev-down.sh && BUILDER_REBUILD=1 scripts/dev-up.sh
```

This starts PostgreSQL, `aistiod`, the data plane, scheduler, and gateway. Local development sets `AISTIO_ENABLE_KUBERNETES=false`; CRD reconcilers and ASDP gRPC are not required for the hosted product flow.

| Item | Value |
| --- | --- |
| Console and public API | http://localhost:8080 |
| Default login | `admin` / `admin` |
| Additional seed users | `alice` / `alice`, `bob` / `bob` |
| Logs and local state | `.dev-stack/` |

Default users and development secrets are for local use only.

### 2. Run your first session

1. Open http://localhost:8080 and sign in (`admin` / `admin`).
2. In **Managed Agents**, create an Agent.
3. Create a `local` Environment.
4. Open **Sessions**, create a session bound to the Agent and Environment, and send the first message.
5. In **Dashboard**, inspect online status, events, and runtime state.
6. For collaboration, open **Agent Teams**, create a team, and watch tasks and member state.

To try BYO Agent registration, use the sample at `agentscope-samples/agents/agentscope-paw` in the repository. After it starts, you should see the agent registered successfully in the Dashboard.


### 3. Stop the stack

```bash
scripts/dev-down.sh
```

## Development

### Build the backend

Run the Maven build from the monorepo root so all AgentScope snapshots used by the service jars are current:

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

### Configuration

Java services use `builder.*` properties and `BUILDER_*` environment variables. All planes must agree on authentication secrets and internal URLs.

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

Production deployments must replace all development credentials and use durable PostgreSQL.


## Roadmap

AgentScope Service brings Agents built in different modes — Framework, Coding Agent, Managed Agents — onto one control plane, and gives Agent-to-Agent collaboration a unified view. Whether you create your first Agent from the Console and host the Harness runtime on the platform, or attach existing AgentScope / LangChain / Claude applications to the control plane, the goal is the same: **give the enterprise a one-stop Agent control and governance center**.

Near-term focus includes:

1. **Continue iterating on AgentScope Framework-native capabilities**
2. **Support more Agent frameworks and Coding Agents** — deepen adapters for LangChain, ADK, Claude, Qoder, OpenAI Agents, and more, and lower BYO attachment cost
3. **Automation** — extend automatic triggers and closed-loop execution around Deployment, Cron, Webhook, and Channel, so Agents move toward event-driven task handling
4. **More event-driven integrations** — attach GitHub / GitLab, DingTalk, WeCom, and other entry points, turning code changes, tickets, and group messages directly into Agent Turns or Team Tasks

For enterprise cloud offerings, also see Alibaba Cloud [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview) and [Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html).

## Documentation

For a deeper walkthrough of AgentScope Service, see the blog post [AgentScope Service — Enterprise Agent Control and Governance Center](https://java.agentscope.io/v2/en/blogs/agentscope-service-release.html).
