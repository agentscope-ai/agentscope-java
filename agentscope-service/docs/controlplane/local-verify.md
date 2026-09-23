# AgentScope 控制面 · 本地验证指南（kind）

本文档记录在本地用 [kind](https://kind.sigs.k8s.io/) 集群从零验证控制面的完整命令：
安装工具 → 创建临时集群 → 本地构建镜像 → 安装 Chart → 验证 → 跑示例 → 清理。

> 适用环境：macOS（Apple Silicon）。其他平台把 `brew install` 换成对应包管理器即可。
>
> 说明：控制面镜像 `registry.cn-hangzhou.aliyuncs.com/agentscope/controlplane:0.1.0` **尚未发布**，
> 因此本地验证必须自行构建镜像并加载进 kind，再用 `--set` 覆盖镜像地址。

## 0. 前置工具（一次性）

```bash
# 需先启动 Docker Desktop（或 colima）。
brew install kubectl helm kind
# 若没有 Docker：brew install docker colima && colima start
```

## 1. 创建临时本地集群

```bash
kind create cluster --name agentscope
kubectl cluster-info --context kind-agentscope
```

## 2. 构建控制面镜像并加载进 kind

```bash
cd control-plane

docker build -t agentscope-controlplane:dev .
kind load docker-image agentscope-controlplane:dev --name agentscope
```

## 3. 安装控制面

使用安装脚本（覆盖为本地构建的镜像）：

```bash
./install/install.sh \
  --set image.repository=agentscope-controlplane \
  --set image.tag=dev
```

或等价的 Helm 命令：

```bash
helm upgrade --install agentscope ./helm/agentscope-controlplane \
  -n agentscope-system --create-namespace \
  --set image.repository=agentscope-controlplane \
  --set image.tag=dev
```

## 4. 验证安装

```bash
# 控制器 Pod 就绪
kubectl -n agentscope-system rollout status deploy/agentscope-controller

# Chart 安装的 CRD（应为 6 个）
kubectl get crds | grep agentscope.io

# RBAC 已正确下发（含 leader-election 的 leases 权限）
kubectl get clusterrole agentscope-controller-role -o yaml | grep -A2 leases

# REST API 可访问
kubectl -n agentscope-system port-forward svc/agentscope-controller 8080:8080 &
curl -s http://localhost:8080/api/v1/version
# 期望：{"version":"0.1.0","experimental":false,...}
```

## 5. 跑一个示例 Agent

```bash
kubectl apply -f config/samples/modelconfig.yaml
kubectl apply -f config/samples/agent_declarative.yaml

kubectl get agents
kubectl describe agent $(kubectl get agents -o name | head -1)
```

预期与重要说明：

- 控制面会完成 reconcile：创建 `Deployment` / `Service` / `ConfigMap`，并把 Agent 的
  `Accepted` condition 置为 `True`，这说明控制面工作正常。
- 该 Agent 的**数据面 Pod 不会 Ready**：其运行时镜像（`.../runtime-java:latest`）同样未发布，
  会处于 `ImagePullBackOff`。这是数据面镜像问题，**不是控制面缺陷**。

用任意可拉取的镜像验证 **BYO 自动发现** 链路：

```bash
kubectl create deployment demo --image=nginx
kubectl label deployment demo agentscope.io/managed=true
sleep 30
kubectl get agents     # 约 30s 内应出现一个 BYO(workloadRef) Agent "demo"
```

## 6.（可选）不依赖集群校验 Chart

```bash
helm lint helm/agentscope-controlplane
helm template agentscope helm/agentscope-controlplane -n agentscope-system --include-crds | less
```

## 7. 清理

```bash
./install/uninstall.sh --purge-crds         # 或：helm uninstall agentscope -n agentscope-system
kind delete cluster --name agentscope
```

## 备注

- **Webhook** 默认关闭（本地快速验证无需证书）。如需启用需先安装 cert-manager：
  `./install/install.sh -p ha --set api.authToken=$(openssl rand -hex 24)` 并提供证书。
- **Experimental** 能力（gRPC / Team / Sandbox）默认关闭，加 `-p experimental` 才开启。
- 若控制器 Pod 处于 `ImagePullBackOff`：说明镜像未加载进 kind，重做第 2 步并确认
  `image.tag=dev` 与构建标签一致。

## 故障排查

| 现象 | 排查 |
| --- | --- |
| 控制器 `ImagePullBackOff` | 重做第 2 步；确认 `--set image.tag=dev` 与构建标签一致 |
| `kubectl get crds` 看不到 agentscope.io | Chart 的 `crds/` 未安装；确认用的是本仓库 chart，且非 `helm upgrade`（CRD 仅首次安装） |
| `curl /api/v1/version` 连接被拒 | 确认 `port-forward` 仍在运行、端口未被占用 |
| Agent 一直 `Accepted=False` | `kubectl describe agent <name>` 看 condition；常见为 runtime 无对应 adapter |
| 示例 Agent 数据面 Pod 不 Ready | 预期现象，数据面镜像未发布，与控制面无关 |
