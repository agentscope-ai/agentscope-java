# Managed Agents

> **An Agent as a Service platform for business applications: submit work through APIs, stay involved, and retrieve reviewable results and deliverables.**

[中文说明](README_zh.md)

Managed Agents publishes Agents that research, use tools, and verify results as callable services. Applications can generate customer proposals in a CRM, verify documents in a pipeline, investigate incidents and create PRs in an engineering product, provide an interactive specialist assistant, or run scheduled background tasks.

Users start work and review delivery inside their existing product. Service runs and coordinates Agents and exposes their interactions. Application teams own business tools, data authorization, the interface, and result acceptance. See [Use cases](../docs/v2/en/service/usecases.md) for integration designs.

## Capabilities

### From task to delivery through APIs

Create or connect an Agent, configure resources, and publish an Endpoint. Applications call it with Application credentials, retain the Invocation ID, and read status, snapshots, events, pending actions, and artifacts. Jobs serve independent background work; Conversations serve session-capable single Agents. Each task or conversation turn has its own Invocation.

Endpoints define input/output contracts and releases. Applications use queries, SSE, or Webhooks to receive changes, involve people in decisions, and return verified results to a business process. Execution completion and business acceptance are separate stages.

Start with the [API quickstart](../docs/v2/en/service/first-session.md), then follow [Endpoint publishing](../docs/v2/en/service/endpoints.md) and [Unified service API](../docs/v2/en/service/service-api.md). Managed native session, file, subagent, and checkpoint APIs provide their respective extensions; their IDs, authentication, and event cursors are not interchangeable with public Invocations.

### Choose execution for the task

- **Managed:** Define a specialist Agent through instructions, models, tools, and resources. Service runs AgentScope Harness, while tools execute in the configured Environment. Deploy the platform on your own infrastructure.
- **External:** Connect an existing Managed Agents or other framework application, retaining its process and deployment. Implement task execution, event reporting, and supported controls.
- **Hosted:** Reuse Coding Agents such as Codex or Claude Code through a Runtime Host that manages provider processes and work directories.

Validate delivery with one Agent first. Use a Team for dynamic delegation or a Workflow for explicit steps, conditions, and human gates. Callers continue to consume an Endpoint and follow its Invocation. Teams and Workflows currently provide Jobs; interaction support depends on runtime capabilities.

### Shared management and observation

The Control Plane manages the Agent catalog, bindings, releases, application identity, task coordination, and public invocations. Managed Agents, External Applications, and Runtime Hosts cooperate through durable execution contracts. Console is the visual interface for API configuration, task inspection, and operations; business users can stay in their own product.

Workspaces, Environments, Memory, and Vault organize execution resources. Application identity and business end-user identity require separate handling: different credentials in one Application do not automatically isolate users' tasks. Configure CRM, GitHub, order-system, and other connections in the integration.

### Managed Session runtime ownership

Public Managed Session traffic enters Agent Service for admission, queries and SSE. Agent Harness Runtime
consumes ordered Turn commands from RocketMQ LiteTopics, runs HarnessAgent and directly commits
native logs, checkpoints, tool intents, waiting states and terminal results in PostgreSQL.
Before committing a tool intent, Agent Harness Runtime preserves its inert proposal and obtains an Execute Broker ACK.
The checkpoint transaction authorizes the task and records the receipt; there is no Runtime Execute Outbox publisher scan.
Tool Worker confirms this shared authorization before admitting operation-scoped Execute commands. A pending commit
causes a short MQ Suspend; only a durable replacement epoch or terminal state permits discarding an uncommitted attempt.
Recovery reuses the complete proposal under the new epoch, while already committed operations retain their identities. Tool Worker persists physical execution, covered output and immutable results, and publishes
only tool events. Agent Harness Runtime applies the complete source prefix and atomically records a Resume
Outbox intent when all results required by the current suspension are ready. Runtime publishes and consumes
Session-scoped Resume messages, validates the suspension and restores the checkpoint; it does not resume
inference inside the tool event callback. Runtime obtains and pins MCP tool descriptors directly before inference. Discovery does not create a Worker task or suspend the Turn; actual MCP calls remain asynchronous Tool Worker operations.
A suspended Turn releases its Harness, Redis token and MQ callback, returning SDK Suspend while
its original message retains the FIFO head. Root and descendant Sessions share one Turn Topic and
consumer pool; frozen depth metadata and the maximum depth of 16 remain business constraints. Resume continues the same logical Turn; the original
message can return SUCCESS only after durable terminal state. Subsequent Turns remain in MQ.
These components share PostgreSQL for checkpoints and oversized objects and run with multiple
replicas. SSE disconnects do not stop execution. With user files disabled, no NAS is required.

Redis contains renewable ownership tokens and transient delivery acceleration. PostgreSQL owns
deduplication, execution history and Outbox/Inbox facts; losing Redis never authorizes a fresh
external side effect. Unknown RPC outcomes remain unknown until the original result is confirmed.
Workspace checkpoint/lifecycle coordinators run in Agent Harness Runtime and send physical operations through the
same Execute/Control boundary. Tool Worker has no runtime journal or checkpoint coordinator bean.

Channel chat and Deployment/Cron adapters submit native Turns through the control plane and Agent Service. Provider message IDs and scheduled fire coordinates remain stable across retries. Replies replay public events for the admitted Turn; adapters do not post legacy Dataplane events. Native Session configuration freezes its Environment and definition without creating a second control-plane runtime Session.

Managed AgentTask dispatch records an immutable Attempt and admission input in a durable Outbox, then admits a native Turn. Transport retries reuse the same Attempt, caller and request key. Cancellation uses native Inputs; the control plane observes native execution facts instead of timing out Managed work from its own heartbeat. Other backends keep their execution paths. AgentTask integration and failure acceptance are still in progress. See [Managed runtime design](docs/managed-runtime-dataplane.md) for data flows, storage requirements and the migration boundary.

## Architecture

The current source deploys five components: the Go Control Plane, Gateway, Agent Service,
Agent Harness Runtime Dataplane and platform Tool Worker. One `control-plane` binary/image owns management,
Workflow/Team orchestration, Hosted coordination, Cron, channels, outbound messages and webhook
sending on port 8081. There are no separate scheduler or orchestration deployments.

Every control replica runs bounded workers. PostgreSQL owns message/job deduplication, claims,
stream leases, retries and fencing; no sticky sessions or single control replica are required.
`control-plane.executionSlots`, `control-plane.workerBatch`, `control-plane.channelSlots` and `control-plane.webhookSlots`
set per-replica concurrency in Helm. Keep `cp` and `rt` schemas and signing/encryption keys stable.
The new `0007_integrated_scheduler` product migration adds the shared jobs and stream leases.

For an upgrade, drain incoming channel traffic and outstanding replies, stop the old Java
Scheduler and Go orchestration deployments, run the schema jobs and reapply grants, then start
the combined control replicas. Remove old Compose containers with `--remove-orphans`; scale down
old Helm deployments before upgrading. Retire unused runtime/scheduler credentials only after
cutover. This source layout requires newly built images; published BETA1 images are unchanged.


### How it works

Business applications submit and observe work through Endpoint / Invocation APIs. Developers configure capabilities through management APIs or Console. The Control Plane coordinates three execution models:

- `managed`: hosted AgentScope Harness execution;
- `external-application`: user applications built with Managed Agents, LangChain, Claude Agent SDK, and others, registered through Application SDK / Runtime Protocol;
- `hosted-runtime`: task-scoped Codex, Claude Code, and similar processes run by Runtime Host daemons;

![Managed Agents](/docs/imgs/agentservice/managed-agents-architecture.png)

### Production deployment architecture

In production, the recommended Managed Agents deployment looks like this:

![Managed Agents](/docs/imgs/agentservice/managed-agents-production-deploy.png)


| Plane | Owns | Does not own |
| --- | --- | --- |
| Gateway | Public entry, authentication, and API routing | Business state and Agent execution |
| Control Plane (`control-plane`) | Resource definitions, identity, publishing, Workflow/Team orchestration, Hosted coordination, Cron, channels and outbound delivery | Harness execution |
| Agent Service | Public Session/Turn admission, events, queries and SSE | Native execution state |
| Dataplane | Harness reasoning, native logs/checkpoints/terminal commits, tool event Inbox and validated recovery | Resource CRUD and physical tool execution |
| Tool Worker | Sandbox/MCP execution facts, output/result Outbox and Sandbox resources | Turn admission and native runtime commits |


## How Agents attach

Application developers can validate a call with the [API quickstart](../docs/v2/en/service/first-session.md). Platform teams prepare models, execution resources, identities, and releases. Existing applications implement the [External execution adapter](../docs/v2/en/service/register-managed-agents-agent.md) before publishing a service.

## Quick start with Docker Compose

Build the current source in local development mode. Prepare Docker Compose v2 and a model credential, then run from the repository root:

```bash
cd managed-agents
export DASHSCOPE_API_KEY="YOUR_DASHSCOPE_API_KEY"
./deploy/init-env.sh 2.1.0-local managed-agents-local
# Configure reachable MANAGED_AGENTS_RUNTIME_REDIS_URI / MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS
# in deploy/.env; initialize the Turn depth family and other three Lite parents with FIFO / Shared / wildcard groups first.
export MANAGED_AGENTS_LOCAL_DEV=true
docker compose --env-file deploy/.env up -d --build --wait --wait-timeout 600
```

Open http://localhost:18080. Console automatically uses the development identity and default scope; APIs require no login, user token, Application key, or grants. The explicit `MANAGED_AGENTS_LOCAL_DEV=true` setting enables this development flow; source Compose binds host ports to loopback. Models and external tools still need provider credentials.

Published `2.1.0-BETA1` images do not support this mode. See [production deployment](../docs/v2/en/service/kubernetes.md) for released Compose, Helm, accounts, and permissions; continue with [your first Agent](../docs/v2/en/service/create-managed-agent.md).

## Install CLI and Runtime Host with Go

To connect a Coding Agent to local development, install Go 1.26+ and build both commands from the current source. Run from the repository root:

```bash
cd managed-agents/control-plane
go install ./cmd/mactl ./cmd/managed-agents-runtime-host
AS_CLI_BIN_DIR="$(go env GOBIN)"
if [ -z "$AS_CLI_BIN_DIR" ]; then
  AS_CLI_BIN_DIR="$(go env GOPATH)/bin"
fi
export PATH="$AS_CLI_BIN_DIR:$PATH"
mactl version
managed-agents-runtime-host -help
mactl connect http://localhost:18080
mactl runtime status
```

Use your actual Service URL when the Host is on another machine. Install and
authenticate the Coding Agent provider separately. The CLI connects to an existing
Service and starts Runtime Host; it does not deploy the platform. Persist the PATH
setting in your shell configuration. See the
[Runtime Host guide](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/en/service/runtime-host.md).

## Production installation with Helm

Prepare external PostgreSQL, shared RWX Workspace storage, Artifact storage,
a configuration Secret, domain and TLS. Add the published Chart repository:

```bash
helm repo add managed-agents https://chickenlj.github.io/helm-charts
helm repo update managed-agents
```

Follow the [production installation guide](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/en/service/kubernetes.md)
to install `managed-agents/managed-agents` with `--version 2.1.0-BETA1` and the ACR
namespace `registry.example.com/agentcore`. The Chart runs one
replica per component with Recreate updates; allow a maintenance window.

The current source Gateway expires pooled connections after 5 seconds idle or
60 seconds lifetime and checks idle pools every 2 seconds. These limits apply
between requests and do not cut active SSE streams. The source Helm chart drains
Gateway and Agent Service for 10 seconds before SIGTERM, then allows 20 seconds
for graceful shutdown within a 45-second Pod termination budget.

For `POST /v1/sessions` and `POST /api/v1/agent-sessions`, send a unique
`Idempotency-Key` for each intended Session and preserve both key and body when
retrying. Gateway retries a connection failure once after 100 ms only for these
creation endpoints, with a single nonblank key of at most 256 characters and a
known body length of at most 2 MiB. Unkeyed, chunked and larger requests retain
streaming behavior without automatic replay. Business errors, other writes and
committed SSE responses are not retried. Unrecovered transport failures return
JSON `502 upstream_unavailable` or `504 upstream_timeout` with `X-Request-ID`;
timeouts are not automatically retried. A failed response can still mean the
write succeeded, so recover it using the same key and body. Connection resets
cannot be eliminated during crashes; bounded replay relies on durable backend
idempotency rather than assuming that a failed response means no write occurred.

## Development

For manual Session instructions, snapshots and SSE, use the [standalone Session Playground](tests/session-playground/README.md). It runs locally on port 18082 and proxies the Gateway on port 18081 by default; create control-plane resources in your terminal.

### Start the source development stack

Source development needs JDK 17+, Maven, Go 1.26+, Node.js 22 and Docker.
From the repository root:

```bash
export DASHSCOPE_API_KEY=YOUR_MODEL_CREDENTIAL
cd managed-agents
scripts/dev-down.sh
MANAGED_AGENTS_REBUILD=1 scripts/dev-up.sh
# Stop later with scripts/dev-down.sh.
```

The development stack enables `MANAGED_AGENTS_LOCAL_DEV=true`, automatically uses one development identity, and uses development secrets. A full
rebuild resets the disposable `cp`, `rt`, `portal` and `dp` schemas by default; set
`MANAGED_AGENTS_RESET_DB=0` only to retain an existing compatible development database.
It is separate from the release Compose installation above.

### Build the backend

Run the Maven build from the monorepo root so all Managed Agents snapshots used by the service jars are current:

```bash
mvn install -DskipTests

cd managed-agents/control-plane
make build
make test
```

### Build or run the console

```bash
cd managed-agents/console
npm install
npm run build   # emits static assets into ../control-plane/ui

npm run dev     # Vite HMR; /api proxies to the gateway
```

### Run with Docker Compose

Build the Java artifacts first, then start the containerized stack:

```bash
mvn install -DskipTests
docker compose --env-file managed-agents/deploy/.env -f managed-agents/docker-compose.yml up --build
```

### Service ports

| Service | Port | Exposure |
| --- | ---: | --- |
| Gateway | 18080 | Public (container port 8080 with Docker Compose) |
| `control-plane` | 8081 | Internal |
| Data plane | 8082 | Internal |
| Agent Service | 8084 | Internal |
| Tool Worker | 8085 | Internal |
| PostgreSQL | 5432 | Local infrastructure |

### Configuration

Java services use `managed-agents.*` properties and `MANAGED_AGENTS_*` environment variables. All planes must agree on authentication secrets and internal URLs.

| Variable | Purpose |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope model credential for local turns |
| `MANAGED_AGENTS_JWT_SECRET` | JWT signing secret shared by gateway/control components |
| `MANAGED_AGENTS_INTERNAL_TOKEN` | Secret for trusted plane-to-plane requests |
| `MANAGED_AGENTS_VAULT_MASTER_KEY` | Encryption key for vault credentials |
| `MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_URL`, `MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_USER`, `MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_PASSWORD` | Java data-plane database |
| `MANAGED_AGENTS_CONTROL_URL`, `MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_URL`, `MANAGED_AGENTS_AGENT_SERVICE_URL` | Internal service endpoints |
| `MANAGED_AGENTS_E2B_API_KEY` | E2B credential for `sandbox` environments |
| `MANAGED_AGENTS_LOCAL_DEV` | Defaults to `false`; source Compose and development scripts default to `true`, bypassing platform authentication and authorization; release Compose / Helm keep it disabled. |
| `MANAGED_AGENTS_ALLOW_LOCAL_ENVIRONMENT` | Allows new `local` Environment bindings. Defaults to `false` in `control-plane`; `scripts/dev-up.sh` and the development Compose stack opt in. Keep disabled in production. |
| `CONTROL_PLANE_PRODUCT_DSN` | Product database used by `control-plane` |
| `CONTROL_PLANE_ENABLE_KUBERNETES` | Enables Control Plane CRD reconcilers and Kubernetes integration |
| `MANAGED_AGENTS_REBUILD=1` | Rebuilds the monorepo/control-plane and, by default, recreates the disposable local `cp`/`rt`/`portal`/`dp` schemas |
| `MANAGED_AGENTS_RESET_DB=0` | Preserves an already-v4 local database during a full binary rebuild |
| `MANAGED_AGENTS_SMOKE_TEST=1` | Runs `scripts/smoke.sh` automatically after health and SQL-schema verification |

Production deployments must replace all development credentials and use durable PostgreSQL.


## Roadmap

Further development focuses on application integration and delivery: end-user authorization, file input contracts, automatic triggers through the public invocation path, and continued validation of long tasks, complex orchestration, and cost governance.

For enterprise cloud offerings, also see Alibaba Cloud [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview) and [Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html).

## Documentation

- [Service overview](../docs/v2/en/service/index.md)
- [Use cases and integration designs](../docs/v2/en/service/usecases.md)
- [API quickstart](../docs/v2/en/service/first-session.md)
- [Unified service API](../docs/v2/en/service/service-api.md)
- [Managed native sessions and tasks](../docs/v2/en/service/session-event-log.md)
- [API reference](../docs/v2/en/service/api-reference.md)


## Agent Service refactoring development

The new Managed Session path uses `agent-service`, the Harness dataplane and `tool-worker`. The implementation is still in progress; [the phase record](docs/agent-service-implementation-status.md) lists runnable paths and remaining integration work.

`make build` builds the combined `bin/control-plane`, CLI and Runtime Host. Execution
workers are enabled by default with a product database; SDK-only servers keep them disabled. Agent Service uses `MANAGED_AGENTS_CONTROL_URL` for Go execution protocols;
`MANAGED_AGENTS_ORCHESTRATION_RUNTIME_URL` remains an optional compatibility override. The old
`--enable-runtime-workers=false` setting is only for resource-seeding test fixtures.

Process fixtures require a dedicated PostgreSQL database and explicit local
`MANAGED_AGENTS_TEST_REDIS_URI` / `MANAGED_AGENTS_TEST_MQ_ENDPOINTS`. Pass `--fixture-dsn` to
Host/Hosted/Workflow/Team fixtures, or set `MANAGED_AGENTS_TEST_POSTGRES_DSN` for the native fixture.
The deployment fixture additionally uses `MANAGED_AGENTS_TEST_MQ_BROKER_CONTAINER` to create an
isolated Topic/group family. For the Team Java SDK check, first run
`mvn -T1 -pl managed-agents/java-client dependency:build-classpath -Dmdep.outputFile=target/client-classpath.txt`.

The focused Host smoke is `python3 managed-agents/tests/orchestration_runtime_host_smoke.py --fixture-dsn <dedicated-local-dsn>`. If the local OS cannot launch the new Go binary through Python, start the local fixture runtime separately and pass `--runtime-url`; use the test-only credentials declared in the script. The smoke uses the combined binary without a product database and checks Agent Service restart and Host identity/generation. It does not run provider tasks or verify durable runtime storage.


The Go runtime PostgreSQL role also requires `MANAGED_AGENTS_VAULT_MASTER_KEY` (at least
32 bytes, stable across its replicas) for encrypted Agent Service configuration,
command, and fact payloads. Its private bootstrap/inbox contracts now have a
PostgreSQL replica test and `tests/orchestration_runtime_agent_service_smoke.py
--runtime-url <local-runtime-url>` process check. Native execution from that
inbox is connected to native Hosted Job and Conversation execution. The focused
`tests/orchestration_runtime_native_smoke.py` check uses real Agent Service/Go/PostgreSQL
processes and an authenticated fake Host, including Agent Service restart, final usage,
physical-stop proof and SSE continuation. Add `--exercise-daemon` to run the real
Host daemon against a local fake Codex app-server CLI; `--daemon-cancel` exercises
provider termination and cancellation. These modes verify collaboration MCP,
streamed text/tool output, usage deduplication, snapshots and durable journal
delivery without external model calls. `--daemon-approve` and `--daemon-deny`
exercise a human tool confirmation, a lease interval spent waiting, Agent Service
restart and the applied receipt through the public Turn actions API. Configure
the Go role's `AGENT_SERVICE_URL` or `--agent-service-url` to reach Agent Service and
set `--execution-slots` for its local Turn advancement concurrency (default 32,
range 1..128). Public Session creation accepts Managed, Hosted, Workflow and Team backends;
External and mixed backends remain gated until their integrations are complete.
Add `--public-create` to verify Hosted creation and duplicate requests through
Agent Service using an isolated configuration HTTP fixture.

`tests/orchestration_runtime_hosted_smoke.py --fixture-dsn <dedicated-local-dsn>`
starts the real Agent Service, Go runtime and Host daemon with a fake Codex app-server.
Use `--approve`, `--deny` or `--cancel` for interaction checks. Create the dedicated
PostgreSQL schema before running. No external provider credentials are used.
Hosted task and reporting credentials last 15 minutes and refresh through Agent Service
using the current Host identity and physical lease proof. An attempt-local HTTP
relay gives the provider a stable local credential for MCP and task CLI requests,
and forwards them with the latest fenced token. The Host enrollment credential
has its own expiry and is still managed separately.
Gateway sends the new Session, Host machine, collaboration MCP, task-token CLI and
ToolTask paths to `MANAGED_AGENTS_AGENT_SERVICE_URL` (default `http://localhost:8084`).
Compose and Helm route these protocols to the combined control component.


`tests/orchestration_runtime_workflow_smoke.py --fixture-dsn <dedicated-local-dsn>`
checks public Workflow creation, approval, restart of Agent Service and the Go runtime,
applied receipts, snapshots and SSE replay with real PostgreSQL. Use `--deny`,
`--parallel` or `--signal` for the other root interaction flows. The configuration
admission source is an isolated HTTP fixture; the resource-seeding process stops before
the combined binary executes. Use a dedicated schema and run these process scenarios sequentially.
Pending confirmations and signals, including nested Workflow subgraphs, use the
public Turn `/actions` endpoint. Add `--nested` to the workflow fixture for two
subgraphs with repeated node keys. A waiting human branch does not pause independent
runnable branches. Agent-generated signals, independent Issue child runs and the
remaining Team controls are still being implemented.

`tests/orchestration_runtime_team_smoke.py --fixture-dsn <dedicated-local-dsn>`
checks public Hosted Team Job creation, fenced MCP conclusion, Agent Service restart,
physical stop, snapshots and SSE replay. Add `--cancel` for cancellation. It uses
an authenticated Host fixture without running a provider CLI; the configuration
source is a fixture and the resource-seeding process stops before the combined binary executes. Native memory
and PostgreSQL checks additionally cover dynamic parallel nodes and require every
worker's physical stop before the public Turn concludes. Child delegation and
the remaining graph controls are still being connected.

### ACS RocketMQ deployment

ACS uses cloud instance `rmq-cn-mhb50f8n501`. Helm defaults `runtime.mq.existingSecret` to `managed-agents-cloud-mq`, containing the endpoint and credentials; the Secret must exist before deployment. Credentials are never packaged into images or source. See [cloud RocketMQ configuration](docs/cloud-rocketmq.en.md). For an intentional self-hosted installation, explicitly set `runtime.mq.existingSecret=""` and supply that installation's MQ endpoint in its workload Secret.

### ACS PostgreSQL and Redis deployment

ACS uses PostgreSQL `pgm-bp10083t3nwu553v` and Redis `r-bp1m16nw61zguewk63`. Helm defaults to the separate `managed-agents-cloud-data` business connection Secret and `managed-agents-cloud-data-migration` owner connection Secret. They override old workload connection values; missing cloud Secrets prevent startup. Passwords remain in Secrets. Use `deploy/managed-cloud-data-config.py --kubeconfig /private/acs-kubeconfig --namespace agencore-ma --apply` once to prepare these from the migrated Secrets, then omit `--apply` to validate before upgrades. Keep existing Vault/JWT keys. See [deployment instructions](release/KUBERNETES_README.md#acs-cloud-postgresql-and-redis).

The ACS deployment uses database pools of 16 connections per service replica (Control Plane storage, Agent Service, Agent Harness Runtime and Tool Worker). Control Plane requests and limits are 2 CPU / 4 GiB. The MCP fixture also uses a pool capped at 16, awaits local execution completion without database polling, and checks remote executions once per second. Dedicated native-log connections remain separately bounded.
