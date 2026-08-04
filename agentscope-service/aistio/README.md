# Aistio Control Plane

> **The Go control-plane component behind [Agent Service](../README.md).**

[中文说明](README_zh.md)

Aistio provides the product, runtime, fleet, and Kubernetes control APIs used by Agent Service. It
manages desired state and operational state while leaving model inference and tool execution in
AgentScope data planes.

When deployed independently in Kubernetes, Aistio also brings service-mesh-style control-plane
discipline to agent workloads: declarative lifecycle management, runtime discovery, session
observability, model and tool governance, and multi-agent coordination.

## Overview

Running an agent is straightforward. Operating a fleet of agents introduces a different class of
problems: configuration rollout, credentials, tool access, health, session state, context pressure,
and collaboration across independently running agents. Aistio centralizes those concerns without
putting the inference loop in the control plane.

The project is built around three boundaries:

- **Desired state** is declared with Kubernetes resources such as `Agent`, `ModelConfig`,
  `MCPServer`, and `AgentTeam`.
- **Runtime state**—sessions, events, context snapshots, metrics, team messages, and tasks—is stored
  in PostgreSQL or an in-memory store and exposed through the REST API.
- **Agent execution** stays in the data plane. Agents connect through ASDP or expose the AgentScope
  HTTP contract while retaining ownership of their model loop and tools.

### What Aistio manages

| Area | Capability |
| --- | --- |
| Agent lifecycle | Declarative deployments, BYO workload adoption, replicas, health, and revisions |
| Runtime operations | Live agent inventory, session inspection, context pressure, compression, and termination |
| Models and credentials | Provider configuration through `ModelConfig`; secrets remain in Kubernetes Secrets |
| Tools | MCP server registry, per-agent allow-lists, approval requirements, and remote/stdio transports |
| Agent teams | Lead/member topology, dynamic membership, task routing, lifecycle policy, and recovery |
| Data plane delivery | ASDP gRPC configuration push and status reporting, plus HTTP contract discovery |
| Observability | Prometheus metrics, OpenTelemetry traces, health probes, Grafana dashboard, and alert rules |
| Isolation | Optional sandbox claims, network policy integration, and configurable shutdown behavior |

## Architecture

```text
                         kubectl / aistioctl / REST clients
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              aistiod                                        │
│                                                                             │
│  Kubernetes reconcilers       Runtime services          Product APIs*       │
│  ┌────────────────────┐       ┌──────────────────┐      ┌────────────────┐  │
│  │ Agent / Model / MCP│       │ sessions / teams │      │ managed agents │  │
│  │ Team / Sandbox     │       │ context / metrics│      │ console / auth │  │
│  └─────────┬──────────┘       └────────┬─────────┘      └───────┬────────┘  │
│            │                           │                        │           │
│            └──────────────┬────────────┴────────────────────────┘           │
│                           │                                                 │
│            ASDP gRPC / AgentScope HTTP contract / REST API                 │
└───────────────────────────┬─────────────────────────────────────────────────┘
                            │
              ┌─────────────┼────────────────┐
              ▼             ▼                ▼
       managed agent   BYO workload    Agent Service
       deployments     deployments     Java data plane
              │             │                │
              └─────────────┴────────────────┘
                            │
                   PostgreSQL or memory
```

\* Product APIs and the web console are optional in the Helm chart and are enabled by the
[Agent Service](../README.md) deployment.

### Control plane and data plane

`aistiod` watches CRDs, reconciles Kubernetes resources, stores runtime state, coordinates teams,
and pushes configuration. It does **not** execute model turns.

The data plane is the agent application. AgentScope runtimes can embed the Go
[`connector`](connector/) or the Java
[`agentscope-extensions-aistio`](../../agentscope-extensions/agentscope-extensions-aistio/)
integration. Other runtimes can implement the ASDP gRPC protocol or the AgentScope HTTP contract.

### Deployment modes

| Mode | Kubernetes | Product APIs | Typical use |
| --- | --- | --- | --- |
| Kubernetes control plane | Enabled | Optional | Manage CRD-backed agents and BYO workloads in a cluster |
| Agent Service | Disabled locally; optional in production | Enabled | Full hosted-agent stack with console, gateway, Java data plane, and scheduler |
| Runtime-only API | Optional | Disabled | Session/team observation and control for externally managed agents |

### Resource model

CRDs describe topology and desired state; high-volume runtime data stays out of Kubernetes objects.

| Resource | Purpose |
| --- | --- |
| `Agent` | Declarative or BYO agent, runtime, tools, replicas, and sandbox policy |
| `ModelConfig` | Model provider, model name, options, TLS, and secret references |
| `MCPServer` | Remote or stdio MCP endpoint and credential headers |
| `AgentTeam` | Lead/member graph, dynamic membership, task strategy, and recovery policy |
| `SandboxClaim` | Isolated execution environment requested for an agent session |

## Quick start

### Prerequisites

- Kubernetes 1.28+
- Helm 3
- `kubectl` configured for the target cluster

### 1. Install the control plane

From this directory:

```bash
helm install aistio ./helm/aistio \
  --namespace aistio-system \
  --create-namespace

kubectl rollout status deployment/aistio-controller -n aistio-system
kubectl get crd | grep agentscope.io
```

The default chart uses an in-memory runtime store. Use the
[`postgres` profile](helm/aistio/profiles/postgres.yaml) for durable sessions, team state, or more
than one control-plane replica.

### 2. Configure a model

```bash
kubectl create namespace agents
kubectl create secret generic dashscope-credentials \
  --namespace agents \
  --from-literal=api-key="$DASHSCOPE_API_KEY"

kubectl apply -f - <<'YAML'
apiVersion: agentscope.io/v1alpha1
kind: ModelConfig
metadata:
  name: qwen-max
  namespace: agents
spec:
  provider: DashScope
  model: qwen-max
  apiKeySecret: dashscope-credentials
  apiKeySecretKey: api-key
YAML
```

### 3. Deploy an agent

```bash
kubectl apply -f - <<'YAML'
apiVersion: agentscope.io/v1alpha1
kind: Agent
metadata:
  name: support-agent
  namespace: agents
spec:
  type: Declarative
  runtime: agentscope-java
  displayName: Support Agent
  declarative:
    agentConfig:
      systemMessage: "You are a concise and helpful support assistant."
      modelConfigRef: qwen-max
      maxTurns: 30
    replicas: 1
YAML

kubectl get agents -n agents
kubectl describe agent support-agent -n agents
```

For richer examples, see [`config/samples`](config/samples/) and the
[`AgentTeam` walkthrough](examples/agentteam/README.md).

### 4. Inspect the runtime

Build `aistioctl` locally, then port-forward the control-plane API:

```bash
go build -o bin/aistioctl ./cmd/aistioctl

kubectl port-forward service/aistio-controller 8080:8080 -n aistio-system
```

In another terminal:

```bash
./bin/aistioctl verify-install
./bin/aistioctl agent list --namespace agents
./bin/aistioctl session list
```

Run `./bin/aistioctl --help` for agent revisions, rollbacks, sessions, teams, and proxy status.

## Key features

### Declarative and BYO agents

Use `type: Declarative` when Aistio should create and reconcile the Deployment. Use `type: BYO`
with an image or `workloadRef` to adopt an existing runtime. Both modes converge on the same
discovery, health, session, and operations APIs.

### Multi-agent teams

`AgentTeam` defines an objective, lead, members, dynamic-member policy, task-claim strategy,
recovery, and lifecycle limits. Runtime messages and tasks are persisted separately from the CRD,
allowing the team to continue across controller restarts.

### Runtime sessions and context

The runtime store tracks session events, token metrics, context snapshots, and lifecycle state.
Operators can inspect sessions, request context compression, or terminate work through REST or
`aistioctl`.

### Config delivery with ASDP

ASDP is a bidirectional gRPC control channel for configuration push and instance status. The
control plane also probes the AgentScope HTTP contract, so workloads can be discovered and operated
without coupling their inference loop to Kubernetes APIs.

### Production-ready integration points

The Helm chart includes:

- PostgreSQL-backed runtime storage and retention controls
- leader election and admission webhooks
- optional REST bearer authentication and gRPC mTLS
- Prometheus `ServiceMonitor`, `PrometheusRule`, and Grafana dashboard
- OpenTelemetry export and Kubernetes `NetworkPolicy`

See [`helm/aistio/values.yaml`](helm/aistio/values.yaml) for the complete configuration surface.

## Repository layout

| Path | Contents |
| --- | --- |
| [`cmd/aistiod`](cmd/aistiod/) | Control-plane entry point |
| [`cmd/aistioctl`](cmd/aistioctl/) | Operator CLI |
| [`api/v1alpha1`](api/v1alpha1/) | Kubernetes API types |
| [`internal/controller`](internal/controller/) | Reconcilers and lifecycle controllers |
| [`internal/httpapi`](internal/httpapi/) | Runtime and fleet REST API |
| [`internal/team`](internal/team/) | Team coordination and lifecycle |
| [`internal/store`](internal/store/) | In-memory and PostgreSQL runtime stores |
| [`internal/asdp`](internal/asdp/) | ASDP protocol and distribution |
| [`connector`](connector/) | Embeddable Go data-plane connector |
| [`helm/aistio`](helm/aistio/) | Helm chart, CRDs, dashboards, and production profiles |
| [`config/samples`](config/samples/) | Example resources |
| [`docs`](docs/) | User and design documentation |

## Development

Go 1.26+, GNU Make, Helm 3, and `controller-gen` are required for the full development workflow.

```bash
make install-tools      # install controller-gen
make build              # build bin/aistiod
make test               # unit tests with coverage
make test-integration   # controller-runtime envtest suite
make vet                # static checks
make verify             # generated code, CRDs, and Helm sync
make helm-lint          # validate the chart
```

Generated CRDs and RBAC are sourced from `api/` and mirrored into the Helm chart by
`make sync-helm`; do not edit generated chart copies directly.

See [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a change.

## Documentation

- [English documentation](docs/en/)
- [中文文档](docs/zh/)
- [Control-plane integration contract](docs/zh/controlplane/contract.md)
- [Framework integration](docs/zh/controlplane/framework-integration.md)
- [Agent Service](../README.md)

## License

[Apache License 2.0](LICENSE)
