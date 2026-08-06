# AgentScope 控制面 · 快速开始

本指南介绍如何在 Kubernetes 集群中安装 AgentScope 控制面（control-plane），并部署第一个 Agent。安装方式参考了 Istio 的使用习惯：提供 **agentscopectl CLI**、**一键脚本**、**Helm** 与 **原始 manifest** 四种路径，并支持安装 **profile**。

> v0.2 说明：默认启用第一期（Declarative / BYO 发现纳管 / REST API / Webhook / 指标）和第二期（会话可观测 / Finalizer 级联清理 / REST TLS / K8s 鉴权 / Grafana Dashboard）能力。ASDP gRPC 运行时协调、分布式 AgentTeam、Sandbox 供给等仍为 **experimental**，默认关闭。
>
> 从 v0.1 升级？请参阅 [升级指南](./upgrade-v0.2.md)。
>
> 想在本地用 kind 从零（含本地构建镜像）跑通验证，见 [本地验证指南](./local-verify.md)。

## 1. 前置条件

- Kubernetes 1.27+（已配置好 `kubectl` 上下文）
- [Helm 3.12+](https://helm.sh/docs/intro/install/)
- 启用 Webhook 时需要可签发证书的 [cert-manager](https://cert-manager.io/)（或自备证书）
- 启用 `metrics.serviceMonitor` 时需要 Prometheus Operator

## 2. 安装

### 方式 A：agentscopectl CLI（v0.2 新增，推荐）

```bash
# 安装控制面（自动创建 namespace、安装 CRD、部署控制器）
agentscopectl install --namespace agentscope-system

# 使用 experimental profile
agentscopectl install --namespace agentscope-system --profile experimental

# 验证安装
agentscopectl verify-install
```

### 方式 B：一键脚本（推荐用于试用）

```bash
cd control-plane

# 默认 profile
./install/install.sh

# experimental profile（启用实验能力）
./install/install.sh -p experimental

# ha profile（多副本 + Webhook + 鉴权 + ServiceMonitor）
./install/install.sh -p ha --set api.authToken=$(openssl rand -hex 24)
```

### 方式 C：Helm

```bash
cd control-plane
helm upgrade --install agentscope ./helm/agentscope-controlplane \
  --namespace agentscope-system --create-namespace
```

可用的 profile 位于 `helm/agentscope-controlplane/profiles/`，通过 `-f` 叠加：

```bash
helm upgrade --install agentscope ./helm/agentscope-controlplane \
  -n agentscope-system --create-namespace \
  -f ./helm/agentscope-controlplane/profiles/experimental.yaml
```

CRD 随 Chart 的 `crds/` 目录在首次安装时自动创建。

### 方式 D：原始 manifest（无 Helm）

```bash
cd control-plane
# 仅安装 CRD
kubectl apply -f config/crd
# RBAC（ClusterRole 由 controller-gen 生成）
kubectl apply -f config/rbac
# 之后自行部署 Deployment/Service（参考 helm 模板或 ha profile）
```

## 3. 验证安装

```bash
kubectl -n agentscope-system rollout status deploy/agentscope-controller
kubectl get crds | grep agentscope.io

# 访问 REST API
kubectl -n agentscope-system port-forward svc/agentscope-controller 8080:8080 &
curl http://localhost:8080/api/v1/version
```

## 4. 部署一个示例 Agent

```bash
kubectl apply -f config/samples/modelconfig.yaml
kubectl apply -f config/samples/agent_declarative.yaml

kubectl get agents
kubectl describe agent <name>
```

BYO（纳管已有 Deployment）：给目标 Deployment 打标签即可被发现：

```bash
kubectl label deployment <your-agent-deploy> agentscope.io/managed=true
# 约 30s 内会自动创建 BYO(workloadRef) Agent，并探测出 contractLevel
```

## 5. 查看会话（v0.2）

对于 contractLevel >= 2 的 Agent，控制面会自动拉取会话数据并写入运行时 Store（默认 memory，生产请配置 PostgreSQL）。

```bash
# 通过 aistioctl 查看会话
aistioctl sessions list --agent <agent-name>

# 查看会话详情（含 context）
aistioctl sessions get <session-id> --agent <agent-name> --context

# 通过 REST API 查看
curl "http://localhost:8080/api/v1/sessions?agent=<agent-name>&namespace=default"
curl "http://localhost:8080/api/v1/sessions/<session-id>?agent=<agent-name>&namespace=default"
```

> 关于数据面契约等级（contractLevel）的详细说明，见 [数据面契约文档](./contract.md)。

## 6. 触发会话操作（v0.2，需 contractLevel = 3）

```bash
# 触发会话上下文压缩
aistioctl sessions compress <session-id> --agent <agent-name>
# 或
curl -X POST "http://localhost:8080/api/v1/sessions/<session-id>/compress?agent=<agent-name>&namespace=default"

# 终止会话
aistioctl sessions terminate <session-id> --agent <agent-name>
# 或
curl -X POST "http://localhost:8080/api/v1/sessions/<session-id>/terminate?agent=<agent-name>&namespace=default"
```

## 7. 配置项速查

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `experimental.enabled` | `false` | 启用实验能力（gRPC / Team / Sandbox） |
| `webhook.enabled` | `false` | 启用 Agent 校验 Webhook（需证书） |
| `api.authToken` | `""` | REST API Bearer Token（留空则不鉴权） |
| `metrics.serviceMonitor.enabled` | `false` | 创建 Prometheus ServiceMonitor |
| `replicaCount` / `leaderElection.enabled` | `1` / `true` | 多副本与选主 |
| `api.tls.enabled` | `false` | 启用 REST API TLS（v0.2） |
| `api.auth.mode` | `token` | 鉴权模式：`token` / `kubernetes`（v0.2） |
| `webhook.defaulting.enabled` | `false` | 启用 Defaulting Webhook（v0.2） |
| `metrics.grafanaDashboard.enabled` | `false` | 安装 Grafana Dashboard（v0.2） |
| `metrics.prometheusRule.enabled` | `false` | 安装 PrometheusRule 告警（v0.2） |

## 8. 升级

```bash
helm upgrade agentscope ./helm/agentscope-controlplane -n agentscope-system
```

> 注意：按 Helm 设计，`helm upgrade` 不会更新 `crds/` 中的 CRD。CRD 变更需显式执行
> `kubectl apply -f config/crd`。

## 9. 卸载

```bash
cd control-plane
./install/uninstall.sh                 # 保留 CRD 与自定义资源
./install/uninstall.sh --purge-crds    # 同时删除 CRD 及其下所有资源
```

## 10. 维护者：生成物同步

CRD / RBAC 均由 `controller-gen` 通过类型上的 marker 生成，**不要手改** `config/` 或
`helm/.../crds`、`helm/.../templates/clusterrole.yaml`：

```bash
make generate      # deepcopy
make manifests     # CRD + RBAC + webhook manifest
make sync-helm     # 将生成物同步进 Helm chart
make verify        # 校验生成物无漂移（CI 会执行）
```
