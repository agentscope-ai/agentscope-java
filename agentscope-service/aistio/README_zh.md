# Aistio Control Plane

> **[Agent Service](../README_zh.md) 背后的 Go 控制面组件。**

[English](README.md)

Aistio 为 Agent Service 提供产品、运行时、舰队与 Kubernetes 控制 API。它管理期望状态和
运行状态，同时将模型推理与工具执行留在 AgentScope Dataplane。

当 Aistio 独立部署到 Kubernetes 时，它也为 Agent Workload 提供类似 Service Mesh 的控制面
能力：声明式生命周期、运行时发现、Session 可观测性、模型与工具治理，以及 Multi-agent
协作。

## 概览

运行单个 Agent 并不困难，运营 Agent 舰队则会引入另一类问题：配置发布、凭据、工具权限、
健康状态、Session State、Context Pressure，以及跨独立 Agent 的协作。Aistio 集中管理这些
问题，但不会把推理循环搬进控制面。

项目围绕三个边界构建：

- **期望状态**通过 `Agent`、`ModelConfig`、`MCPServer`、`AgentTeam` 等 Kubernetes
  Resource 声明。
- **运行状态**包括 Session、Event、Context Snapshot、Metric、Team Message 与 Task，
  存储在 PostgreSQL 或 Memory Store，并通过 REST API 暴露。
- **Agent 执行**保留在 Dataplane。Agent 通过 ASDP 接入，或暴露 AgentScope HTTP Contract，
  并继续自行管理模型循环与工具。

### Aistio 管理什么

| 领域 | 能力 |
| --- | --- |
| Agent 生命周期 | 声明式 Deployment、BYO Workload 接管、副本、健康状态与版本 |
| 运行时运营 | 在线 Agent 清单、Session 观测、Context Pressure、压缩与终止 |
| 模型与凭据 | 通过 `ModelConfig` 配置 Provider；凭据保留在 Kubernetes Secret |
| 工具 | MCP Server Registry、Agent 级白名单、审批要求及 Remote / stdio Transport |
| Agent Teams | Lead / Member 拓扑、动态成员、Task 路由、生命周期策略与恢复 |
| Dataplane 配置下发 | ASDP gRPC 配置推送与状态上报，以及 HTTP Contract Discovery |
| 可观测性 | Prometheus Metric、OpenTelemetry Trace、健康探针、Grafana Dashboard 与告警规则 |
| 隔离 | 可选 SandboxClaim、NetworkPolicy 集成与可配置 Shutdown 行为 |

## 架构

```text
                         kubectl / aistioctl / REST Client
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              aistiod                                        │
│                                                                             │
│  Kubernetes Reconciler      Runtime Service           Product API*          │
│  ┌────────────────────┐     ┌──────────────────┐      ┌────────────────┐    │
│  │ Agent / Model / MCP│     │ Session / Team   │      │ Managed Agent  │    │
│  │ Team / Sandbox     │     │ Context / Metric │      │ Console / Auth │    │
│  └─────────┬──────────┘     └────────┬─────────┘      └───────┬────────┘    │
│            │                         │                        │             │
│            └──────────────┬──────────┴────────────────────────┘             │
│                           │                                                 │
│            ASDP gRPC / AgentScope HTTP Contract / REST API                 │
└───────────────────────────┬─────────────────────────────────────────────────┘
                            │
              ┌─────────────┼────────────────┐
              ▼             ▼                ▼
       Managed Agent   BYO Workload     Agent Service
       Deployment      Deployment       Java Dataplane
              │             │                │
              └─────────────┴────────────────┘
                            │
                   PostgreSQL 或 Memory
```

\* Product API 和 Web Console 在 Helm Chart 中是可选项，由
[Agent Service](../README_zh.md) 部署启用。

### 控制面与数据面

`aistiod` 监听 CRD、协调 Kubernetes Resource、存储运行时状态、组织 Agent Team，并推送
配置。它**不执行**模型 Turn。

Dataplane 就是 Agent 应用。AgentScope Runtime 可以嵌入 Go
[`connector`](connector/)，或使用 Java
[`agentscope-extensions-aistio`](../../agentscope-extensions/agentscope-extensions-aistio/)
集成。其他 Runtime 可以实现 ASDP gRPC Protocol 或 AgentScope HTTP Contract。

### 部署模式

| 模式 | Kubernetes | Product API | 适用场景 |
| --- | --- | --- | --- |
| Kubernetes Control Plane | 启用 | 可选 | 在集群中管理 CRD Agent 与 BYO Workload |
| Agent Service | 本地关闭，生产可选 | 启用 | 包含 Console、Gateway、Java Dataplane 与 Scheduler 的完整托管平台 |
| Runtime-only API | 可选 | 关闭 | 观测和控制由外部管理的 Agent Session / Team |

### Resource Model

CRD 描述拓扑与期望状态；高频运行时数据不会写入 Kubernetes Object。

| Resource | 作用 |
| --- | --- |
| `Agent` | 声明式或 BYO Agent、Runtime、工具、副本与 Sandbox Policy |
| `ModelConfig` | Model Provider、模型名、参数、TLS 与 Secret 引用 |
| `MCPServer` | Remote 或 stdio MCP Endpoint 及凭据 Header |
| `AgentTeam` | Lead / Member Graph、动态成员、Task Strategy 与 Recovery Policy |
| `SandboxClaim` | Agent Session 请求的隔离执行环境 |

## 快速开始

### 前置条件

- Kubernetes 1.28+
- Helm 3
- 已配置目标集群的 `kubectl`

### 1. 安装控制面

在当前目录执行：

```bash
helm install aistio ./helm/aistio \
  --namespace aistio-system \
  --create-namespace

kubectl rollout status deployment/aistio-controller -n aistio-system
kubectl get crd | grep agentscope.io
```

默认 Chart 使用 Memory Runtime Store。需要持久化 Session / Team State，或部署多个控制面
副本时，请使用 [`postgres` profile](helm/aistio/profiles/postgres.yaml)。

### 2. 配置模型

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

### 3. 部署 Agent

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
      systemMessage: "你是一个简洁、可靠的客服助手。"
      modelConfigRef: qwen-max
      maxTurns: 30
    replicas: 1
YAML

kubectl get agents -n agents
kubectl describe agent support-agent -n agents
```

更多示例见 [`config/samples`](config/samples/) 和
[`AgentTeam` 演示](examples/agentteam/README.md)。

### 4. 查看运行状态

本地构建 `aistioctl`，然后转发控制面 API：

```bash
go build -o bin/aistioctl ./cmd/aistioctl

kubectl port-forward service/aistio-controller 8080:8080 -n aistio-system
```

在另一个终端执行：

```bash
./bin/aistioctl verify-install
./bin/aistioctl agent list --namespace agents
./bin/aistioctl session list
```

运行 `./bin/aistioctl --help` 可查看 Agent Revision、Rollback、Session、Team 与 Proxy
Status 等命令。

## 关键特性

### Declarative 与 BYO Agent

使用 `type: Declarative` 时，Aistio 创建并协调 Deployment；使用 `type: BYO` 加 Image 或
`workloadRef` 时，Aistio 接管已有 Runtime。两种模式最终使用相同的 Discovery、Health、
Session 与 Operations API。

### Multi-agent Team

`AgentTeam` 定义 Objective、Lead、Member、Dynamic Member Policy、Task Claim Strategy、
Recovery 和 Lifecycle Limit。Runtime Message 与 Task 独立于 CRD 持久化，使团队可以跨
Controller Restart 继续工作。

### Runtime Session 与 Context

Runtime Store 跟踪 Session Event、Token Metric、Context Snapshot 和 Lifecycle State。
Operator 可以通过 REST 或 `aistioctl` 查看 Session、请求 Context Compression 或终止工作。

### ASDP 配置下发

ASDP 是用于配置推送和实例状态上报的双向 gRPC 控制通道。控制面也会探测 AgentScope HTTP
Contract，因此无需将推理循环绑定到 Kubernetes API，也可以发现和运营 Workload。

### 生产集成能力

Helm Chart 包含：

- PostgreSQL Runtime Store 与 Retention 配置
- Leader Election 与 Admission Webhook
- 可选 REST Bearer Authentication 与 gRPC mTLS
- Prometheus `ServiceMonitor`、`PrometheusRule` 与 Grafana Dashboard
- OpenTelemetry Export 与 Kubernetes `NetworkPolicy`

完整配置见 [`helm/aistio/values.yaml`](helm/aistio/values.yaml)。

## 工程结构

| 路径 | 内容 |
| --- | --- |
| [`cmd/aistiod`](cmd/aistiod/) | 控制面入口 |
| [`cmd/aistioctl`](cmd/aistioctl/) | 运维 CLI |
| [`api/v1alpha1`](api/v1alpha1/) | Kubernetes API Type |
| [`internal/controller`](internal/controller/) | Reconciler 与生命周期 Controller |
| [`internal/httpapi`](internal/httpapi/) | Runtime 与 Fleet REST API |
| [`internal/team`](internal/team/) | Team 协调与生命周期 |
| [`internal/store`](internal/store/) | Memory 与 PostgreSQL Runtime Store |
| [`internal/asdp`](internal/asdp/) | ASDP Protocol 与分发 |
| [`connector`](connector/) | 可嵌入的 Go Dataplane Connector |
| [`helm/aistio`](helm/aistio/) | Helm Chart、CRD、Dashboard 与生产 Profile |
| [`config/samples`](config/samples/) | 示例 Resource |
| [`docs`](docs/) | 用户与设计文档 |

## 开发

完整开发流程需要 Go 1.26+、GNU Make、Helm 3 与 `controller-gen`。

```bash
make install-tools      # 安装 controller-gen
make build              # 构建 bin/aistiod
make test               # 运行带 Coverage 的单元测试
make test-integration   # 运行 controller-runtime envtest
make vet                # 静态检查
make verify             # 检查生成代码、CRD 与 Helm 同步状态
make helm-lint          # 校验 Helm Chart
```

CRD 与 RBAC 以 `api/` 为源，通过 `make sync-helm` 同步到 Helm Chart。不要直接修改 Chart
中的生成副本。

提交修改前请阅读 [CONTRIBUTING_zh.md](CONTRIBUTING_zh.md)。

## 文档

- [中文文档](docs/zh/)
- [English documentation](docs/en/)
- [控制面集成契约](docs/zh/controlplane/contract.md)
- [Framework 集成](docs/zh/controlplane/framework-integration.md)
- [Agent Service](../README_zh.md)

## License

[Apache License 2.0](LICENSE)
