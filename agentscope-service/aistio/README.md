# Aistio

**Istio is for microservices. Aistio is for AI agents.**

Aistio is a Kubernetes-native control plane that brings the service-mesh philosophy to AI agent workloads. Just as Istio gives platform teams a uniform way to manage traffic, security, and observability for microservices — without changing application code — Aistio does the same for AI agents: lifecycle management, session tracking, model routing, tool governance, multi-agent collaboration, and observability, all declared as Kubernetes CRDs.

## Why Aistio

Running a handful of agents in a notebook is easy. Running dozens in production is not:

| Challenge | Without Aistio | With Aistio |
| --- | --- | --- |
| Deploying & scaling agents | Hand-rolled Deployments, one-off scripts | `kubectl apply` an `Agent` CR, autoscaling built in |
| Model credentials | Scattered env vars, easy to leak | `ModelConfig` CR + K8s Secrets, rotated centrally |
| Tool access control | Every agent wires its own MCP clients | `MCPServer` CR, tool allow-lists per agent |
| Multi-agent collaboration | Custom orchestration code per scenario | `AgentTeam` CR with lead/member roles, task routing, fault recovery |
| Session & context management | App-level bookkeeping | Runtime DB + `aistioctl sessions`, context-pressure monitoring, auto-compression |
| Observability | printf debugging | Prometheus metrics, OpenTelemetry tracing, Grafana dashboards |

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Kubernetes Cluster                        │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                    Aistio Control Plane                     │ │
│  │                                                             │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐ │ │
│  │  │ Agent    │  │ Session  │  │ Team     │  │ MCP / Model│ │ │
│  │  │Controller│  │Controller│  │Controller│  │ Controller │ │ │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘  └─────┬──────┘ │ │
│  │       │              │             │              │        │ │
│  │  ┌────┴──────────────┴─────────────┴──────────────┴─────┐  │ │
│  │  │            ASDP (Agent Service Discovery Protocol)   │  │ │
│  │  │          gRPC bi-directional config push/status      │  │ │
│  │  └────┬──────────────┬─────────────┬──────────────┬─────┘  │ │
│  │       │              │             │              │        │ │
│  └───────┼──────────────┼─────────────┼──────────────┼────────┘ │
│          │              │             │              │          │
│  ┌───────▼──────┐ ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼──────┐  │
│  │  Agent Pod   │ │ Agent Pod │ │ Agent Pod │ │  Agent Pod │  │
│  │  (data plane)│ │           │ │           │ │            │  │
│  │  ┌─────────┐ │ │           │ │           │ │            │  │
│  │  │Connector│ │ │   ...     │ │   ...     │ │    ...     │  │
│  │  │ (ASDP)  │ │ │           │ │           │ │            │  │
│  │  └─────────┘ │ │           │ │           │ │            │  │
│  └──────────────┘ └───────────┘ └───────────┘ └────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

**Control plane** (`aistiod`) watches CRDs, reconciles agent deployments, persists runtime session/team data in a store (PostgreSQL or in-memory), coordinates teams, and pushes configuration to data planes via ASDP.

**Data plane** is your agent application. Embed the [Connector](connector/) library (or implement the ASDP gRPC contract) to receive config pushes and report status — no vendor lock-in on the agent framework.

**CRDs at a glance** (declarative topology only — runtime session/message/task data lives in the DB):

| CRD | Purpose |
| --- | --- |
| `Agent` | Declare an agent: image, model, tools, replicas, sandbox |
| `AgentTeam` | Orchestrate multi-agent collaboration with lead/member roles |
| `MCPServer` | Register an MCP tool server (remote HTTP or stdio) |
| `ModelConfig` | Define model provider + credentials (DashScope, OpenAI, Anthropic, …) |
| `SandboxClaim` | Request a sandbox for an agent session |

Runtime data (sessions, events, context snapshots, team messages/tasks) is queried via the REST API / `aistioctl`, not via kubectl CRDs.

## Quick Start

### Prerequisites

- Kubernetes cluster (1.28+)
- Helm 3.x
- kubectl

### Install via Helm

```bash
helm install aistio helm/aistio -n aistio-system --create-namespace
```

### Define a model

```yaml
apiVersion: agentscope.io/v1alpha1
kind: ModelConfig
metadata:
  name: qwen-max
spec:
  provider: DashScope
  model: qwen-max
  apiKeySecret: dashscope-credentials   # a K8s Secret with key "api-key"
```

### Register an MCP tool server

```yaml
apiVersion: agentscope.io/v1alpha1
kind: MCPServer
metadata:
  name: knowledge-base
spec:
  type: Remote
  remote:
    url: https://kb.internal/mcp
    timeout: "30s"
```

### Deploy an agent

```yaml
apiVersion: agentscope.io/v1alpha1
kind: Agent
metadata:
  name: customer-support
spec:
  type: Declarative
  runtime: agentscope-java
  declarative:
    agentConfig:
      systemMessage: "You are a customer support assistant."
      modelConfigRef: qwen-max
      maxTurns: 50
    tools:
      - type: McpServer
        mcpServer:
          name: knowledge-base
          toolNames: ["search_docs", "get_faq"]
    replicas: 3
```

```bash
kubectl apply -f model.yaml -f mcp.yaml -f agent.yaml
kubectl get agents
# NAME               TYPE          RUNTIME           READY   REPLICAS   AGE
# customer-support   Declarative   agentscope-java   True    3          30s
```

### Assemble a team

```yaml
apiVersion: agentscope.io/v1alpha1
kind: AgentTeam
metadata:
  name: code-review-team
spec:
  objective: "Review PR #42 for security, performance, and test coverage"
  lead:
    agentRef:
      name: senior-reviewer
    prompt: "Coordinate the review and synthesize findings."
  members:
    - name: security
      agentRef:
        name: security-agent
      prompt: "Focus on auth, injection, and data exposure."
    - name: performance
      agentRef:
        name: perf-agent
      prompt: "Focus on N+1 queries, memory leaks, and hot paths."
  recovery:
    reschedulePolicy: Auto
    maxRestarts: 3
  lifecycle:
    maxDuration: "2h"
```

```bash
kubectl apply -f team.yaml
kubectl get agentteams
# NAME               PHASE     LEAD              AGE
# code-review-team   Running   senior-reviewer   10s
```

## Features

**Agent Lifecycle** — Declarative (control plane creates Deployments) or BYO (adopt existing workloads). Replica scaling, rolling updates, health probing.

**Session Management** — Per-session state tracking, token usage metering, context-pressure monitoring with automatic compression when the context window fills up.

**Multi-Agent Teams** — Lead/member topology, dynamic membership, task claim strategies (self-claim / lead-assign), fault recovery with configurable restart policies.

**Model Governance** — Centralized model provider configuration. Credentials stay in K8s Secrets, never in agent code. Supports DashScope, OpenAI, Anthropic, Gemini, Ollama, and custom providers.

**MCP Tool Registry** — Register MCP servers as cluster resources. Bind tools to agents with allow-lists and approval gates. Supports remote HTTP (Streamable HTTP / SSE) and stdio transports.

**ASDP Data Plane** — Bi-directional gRPC protocol for config push and status reporting. Framework-agnostic: works with any agent runtime that embeds the connector.

**Observability** — Built-in Prometheus metrics (`agentscope_*`), OpenTelemetry tracing, Grafana dashboard, and PrometheusRule alerts.

**Sandbox Isolation** — Optional per-agent sandbox with network policies, idle timeouts, and configurable shutdown behavior.

## Development

```bash
make build          # build aistiod
make test           # run unit tests
make test-integration  # run envtest integration tests
make manifests      # regenerate CRDs and RBAC
make helm-lint      # lint the Helm chart
make docker-build   # build multi-arch image
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full guide.

## License

[Apache License 2.0](LICENSE)
