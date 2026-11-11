# AgentScope Service

> **An Agent as a Service platform for business applications: submit work through APIs, stay involved, and retrieve reviewable results and deliverables.**

[中文说明](README_zh.md)

AgentScope Service publishes Agents that research, use tools, and verify results as callable services. Applications can generate customer proposals in a CRM, verify documents in a pipeline, investigate incidents and create PRs in an engineering product, provide an interactive specialist assistant, or run scheduled background tasks.

Users start work and review delivery inside their existing product. Service runs and coordinates Agents and exposes their interactions. Application teams own business tools, data authorization, the interface, and result acceptance. See [Use cases](../docs/v2/en/service/usecases.md) for integration designs.

## Capabilities

### From task to delivery through APIs

Create or connect an Agent, configure resources, and publish an Endpoint. Applications call it with Application credentials, retain the Invocation ID, and read status, snapshots, events, pending actions, and artifacts. Jobs serve independent background work; Conversations serve session-capable single Agents. Each task or conversation turn has its own Invocation.

Endpoints define input/output contracts and releases. Applications use queries, SSE, or Webhooks to receive changes, involve people in decisions, and return verified results to a business process. Execution completion and business acceptance are separate stages.

Start with the [API quickstart](../docs/v2/en/service/first-session.md), then follow [Endpoint publishing](../docs/v2/en/service/endpoints.md) and [Unified service API](../docs/v2/en/service/service-api.md). Managed native session, file, subagent, and checkpoint APIs provide their respective extensions; their IDs, authentication, and event cursors are not interchangeable with public Invocations.

### Choose execution for the task

- **Managed:** Define a specialist Agent through instructions, models, tools, and resources. Service runs AgentScope Harness, while tools execute in the configured Environment. Deploy the platform on your own infrastructure.
- **External:** Connect an existing AgentScope or other framework application, retaining its process and deployment. Implement task execution, event reporting, and supported controls.
- **Hosted:** Reuse Coding Agents such as Codex or Claude Code through a Runtime Host that manages provider processes and work directories.

Validate delivery with one Agent first. Use a Team for dynamic delegation or a Workflow for explicit steps, conditions, and human gates. Callers continue to consume an Endpoint and follow its Invocation. Teams and Workflows currently provide Jobs; interaction support depends on runtime capabilities.

### Shared management and observation

The Control Plane manages the Agent catalog, bindings, releases, application identity, task coordination, and public invocations. Managed Agents, External Applications, and Runtime Hosts cooperate through durable execution contracts. Console is the visual interface for API configuration, task inspection, and operations; business users can stay in their own product.

Workspaces, Environments, Memory, and Vault organize execution resources. Application identity and business end-user identity require separate handling: different credentials in one Application do not automatically isolate users' tasks. Configure CRM, GitHub, order-system, and other connections in the integration.

## Architecture

### How it works

Business applications submit and observe work through Endpoint / Invocation APIs. Developers configure capabilities through management APIs or Console. The Control Plane coordinates three execution models:

- `managed`: hosted AgentScope Harness execution;
- `external-application`: user applications built with AgentScope, LangChain, Claude Agent SDK, and others, registered through Application SDK / ASDP;
- `hosted-runtime`: task-scoped Codex, Claude Code, and similar processes run by Runtime Host daemons;

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-architecture.png)

### Production deployment architecture

In production, the recommended AgentScope Service deployment looks like this:

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-production-deploy.png)


| Plane | Owns | Does not own |
| --- | --- | --- |
| Gateway | Public entry, authentication, and API routing | Business state and Agent execution |
| Control Plane (`service-controlplane`) | Product resources, publishing and Invocations, public events, task coordination, runtime commands | Harness inference and native Managed Session event generation |
| Dataplane | Managed Harness Runtime, event log, SSE, Turn Lease, HITL, and Work Queue | Direct reads of product Catalog tables |
| Scheduler | Channel, Cron, outbound jobs, and Self-hosted Hands Workers | The inference loop |


## How Agents attach

Application developers can validate a call with the [API quickstart](../docs/v2/en/service/first-session.md). Platform teams prepare models, execution resources, identities, and releases. Existing applications implement the [External execution adapter](../docs/v2/en/service/register-agentscope-agent.md) before publishing a service.

## Quick start with Docker Compose

Use the published `2.1.0-BETA1` prerelease. Install Docker Engine or Docker Desktop
with Compose v2, Bash, curl and OpenSSL; have a model API credential available.
No source checkout, Java, Maven or Go is needed to start the platform.

### 1. Download and initialize

```bash
curl -fLO https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/agentscope-service-2.1.0-BETA1-compose.tar.gz
curl -fLO https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/SHA256SUMS
awk '$2 == "agentscope-service-2.1.0-BETA1-compose.tar.gz"' SHA256SUMS > compose.sha256
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c compose.sha256
else
  shasum -a 256 -c compose.sha256
fi
```

```bash
tar -xzf agentscope-service-2.1.0-BETA1-compose.tar.gz
cd agentscope-service
./init-env.sh 2.1.0-BETA1 sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope
```

Edit the generated `.env` to set `DASHSCOPE_API_KEY`. For a trusted local evaluation,
set `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`; tools then run inside the Dataplane
container. Other installations should prepare an isolated execution Environment.
Initialization preserves an existing `.env`; keep its credentials and Vault key
with your backups.

### 2. Start and use Service

```bash
docker compose pull
docker compose up -d --wait --wait-timeout 600
docker compose ps
curl -fsS http://localhost:18080/actuator/health
```

The stack starts PostgreSQL, Control Plane with Dashboard, Dataplane, Scheduler
and Gateway using published amd64/arm64 images. Open http://localhost:18080 and
sign in as `admin` with `CONTROL_PLANE_BOOTSTRAP_PASSWORD` from `.env`, then change
the password. The release installation creates no demo users.

Follow [Deploy and prepare Service](https://java.agentscope.io/v2/en/service/quickstart)
to configure accounts, a model and an execution Environment, then
[create your first Managed Agent](https://java.agentscope.io/v2/en/service/create-managed-agent).
For the DSH plugin, see its [installation guide](service-controlplane/sdk/dsh/README.md).

Stop with `docker compose down`. Data volumes are retained; do not use `-v` for an
ordinary shutdown. See [operations](https://java.agentscope.io/v2/en/service/operations)
before upgrading or restoring data.

## Install CLI and Runtime Host with Go

To connect a Coding Agent on a separate Linux/macOS host, install Go 1.26+ and
both commands at the same version:

```bash
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/as@v2.1.0-BETA1
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/agentscope-runtime-host@v2.1.0-BETA1
AS_CLI_BIN_DIR="$(go env GOBIN)"
if [ -z "$AS_CLI_BIN_DIR" ]; then
  AS_CLI_BIN_DIR="$(go env GOPATH)/bin"
fi
export PATH="$AS_CLI_BIN_DIR:$PATH"
as version
agentscope-runtime-host -help
as connect http://localhost:18080
as runtime status
```

Use your actual Service URL when the Host is on another machine. Install and
authenticate the Coding Agent provider separately. The CLI connects to an existing
Service and starts Runtime Host; it does not deploy the platform. Persist the PATH
setting in your shell configuration. See the
[Runtime Host guide](https://java.agentscope.io/v2/en/service/runtime-host).

## Production installation with Helm

Prepare external PostgreSQL, shared RWX Workspace storage, Artifact storage,
a configuration Secret, domain and TLS. Add the published Chart repository:

```bash
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
```

Follow the [production installation guide](https://java.agentscope.io/v2/en/service/kubernetes)
to install `agentscope/agentscope-service` with `--version 2.1.0-BETA1` and the ACR
namespace `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`. The Chart runs one
replica per component with Recreate updates; allow a maintenance window.

## Development

### Start the source development stack

Source development needs JDK 17+, Maven, Go 1.26+, Node.js 22 and Docker.
From the repository root:

```bash
export DASHSCOPE_API_KEY=YOUR_MODEL_CREDENTIAL
cd agentscope-service
scripts/dev-down.sh
BUILDER_REBUILD=1 scripts/dev-up.sh
# Stop later with scripts/dev-down.sh.
```

The development stack seeds demo users and uses development secrets. A full
rebuild resets the disposable `cp`, `rt` and `dp` schemas by default; set
`BUILDER_RESET_DB=0` only to retain an existing compatible development database.
It is separate from the release Compose installation above.

### Build the backend

Run the Maven build from the monorepo root so all AgentScope snapshots used by the service jars are current:

```bash
mvn install -DskipTests

cd agentscope-service/service-controlplane
make build
make test
```

### Build or run the console

```bash
cd agentscope-service/frontend
npm install
npm run build   # emits static assets into ../service-controlplane/ui

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
| Gateway | 18080 | Public (container port 8080 with Docker Compose) |
| `service-controlplane` | 8081 | Internal |
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
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | Allows new `local` Environment bindings. Defaults to `false` in `service-controlplane`; `scripts/dev-up.sh` and the development Compose stack opt in. Keep disabled in production. |
| `CONTROL_PLANE_PRODUCT_DSN` | Product database used by `service-controlplane` |
| `CONTROL_PLANE_ENABLE_KUBERNETES` | Enables Control Plane CRD reconcilers and Kubernetes integration |
| `BUILDER_REBUILD=1` | Rebuilds the monorepo/service-controlplane and, by default, recreates the disposable local `cp`/`rt`/`dp` schemas |
| `BUILDER_RESET_DB=0` | Preserves an already-v4 local database during a full binary rebuild |
| `BUILDER_SMOKE_TEST=1` | Runs `scripts/smoke.sh` automatically after health and SQL-schema verification |

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
