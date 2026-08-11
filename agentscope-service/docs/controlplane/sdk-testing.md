# SDK / 框架适配 · 源码部署与测试指南

> 目的：从零验证「Python SDK 旁路上报 ↔ ASDP/HTTP 合约 ↔ 控制面 Store/REST」整条链路。
> 设计说明见 [sdk-design.md](./sdk-design.md)；集群安装总览见 [getting-started.md](./getting-started.md)。

本文分四层，由浅入深。**多数能力在第 1 层单测即可覆盖**；联调真实 aistiod 从第 2/3 层开始。

| 层 | 内容 | 是否需要 K8s |
|----|------|--------------|
| 1 | Go / Python 单测与集成测 | 否（envtest 除外） |
| 2 | 源码编译并本地跑 `aistiod` | 需要 kubeconfig（可用 kind） |
| 3 | 镜像 + Helm 装进集群 | 是 |
| 4 | 手工联调：SDK agent → 控制面 → `aistioctl`/curl | 建议有 |

---

## 0. 前置

```bash
# 仓库根目录
cd /path/to/aistio

# Go（与 go.mod 一致，当前为 1.26+）
go version

# Python 3.9+
python3 --version

# 可选：集群联调
# brew install kubectl helm kind
# Docker Desktop / colima 已启动
```

关键端口（避免本地冲突）：

| 组件 | 默认地址 | 说明 |
|------|----------|------|
| aistiod REST | `:8080` | `/api/v1/...` |
| aistiod ASDP gRPC | `:15010` | SDK `control_plane=host:15010` |
| aistiod metrics / health | `:8081` / `:8082` | |
| SDK 合约 HTTP | `:8080`（可改） | **与 aistiod REST 同机时请改成 `18080` 等** |

---

## 1. 自动化测试（推荐先跑）

### 1.1 控制面 Go

```bash
# 单元测试（含 asdp、store、prober、asdp_sink 等）
make test

# 静态检查
make vet

# controller-runtime envtest（需要下载 K8s test binaries）
make test-integration
```

与 SDK 上报直接相关的 Go 用例（抽查）：

| 包 / 文件 | 覆盖点 |
|-----------|--------|
| `internal/asdp/asdp_test.go` | 握手、`SessionReport` / `EventReport` / `ContextReport` / `InventoryReport` |
| `internal/controller/asdp_sink_test.go` | 事件幂等、`context_hash` 去重写 Store |
| `internal/store/...` | memory /（可选）postgres |
| `connector/` | Go 侧 connector + HTTP 合约 |

可选 Postgres store 套件：

```bash
export AISTIO_TEST_POSTGRES_DSN='postgres://user:pass@localhost:5432/aistio?sslmode=disable'
go test ./internal/store/... -count=1
```

### 1.2 Python SDK（假控制面，不启 aistiod）

```bash
cd sdk/python
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
# OpenClaw Gateway 路径可选：pip install -e ".[dev,openclaw]"

pytest -v
# 或：
# pytest -v tests/test_models.py tests/test_adapters.py tests/test_bridge.py
```

当前主要覆盖：

| 文件 | 覆盖点 |
|------|--------|
| `tests/test_models.py` | `SessionEvent` / `ContextTracker` / hash / Inventory 编解码 |
| `tests/test_adapters.py` | Claude / LangChain / ADK / OpenClaw / OpenAI Agents 旁路与 `extract_context` |
| `tests/test_bridge.py` | Fake ASDP 双向流：Level 1/2/4、Inventory、HTTP 合约、compress/terminate、旁路失败不抛 |

`test_bridge` 会起真实 gRPC fake CP + 真实 HTTP 合约服务，是**无集群的端到端冒烟**。

---

## 2. 源码编译与本地跑 aistiod

### 2.1 编译 CLI / 控制面

```bash
# 仓库根目录
make build                          # → bin/aistiod
go build -o bin/aistioctl ./cmd/aistioctl
```

### 2.2 准备 kubeconfig

`aistiod` 依赖 Kubernetes API（CRD reconcile）。本地最快用 kind：

```bash
kind create cluster --name aistio
kubectl cluster-info --context kind-aistio

# 安装 CRD（至少 Agent 相关）
kubectl apply -f config/crd/
```

### 2.3 启动（memory store + ASDP）

```bash
./bin/aistiod \
  --http-bind-address=:8080 \
  --grpc-bind-address=:15010 \
  --metrics-bind-address=:8081 \
  --health-probe-bind-address=:8082 \
  --enable-asdp=true \
  --storage-driver=memory \
  --log-format=console
```

另开终端自检：

```bash
curl -s http://127.0.0.1:8080/api/v1/version
curl -s http://127.0.0.1:8082/readyz
```

持久化可改用 Postgres（示例见 `config/samples/cnpg-cluster.yaml` / `helm/aistio/profiles/postgres.yaml`）：

```bash
export AISTIO_STORAGE_DSN='postgres://aistio:aistio@127.0.0.1:5432/aistio?sslmode=disable'
./bin/aistiod --storage-driver=postgres --storage-dsn="$AISTIO_STORAGE_DSN" --enable-asdp=true --log-format=console
```

> `memory` 重启丢数据，且多副本不共享；联调会话历史请用 postgres 或接受进程内有效。

---

## 3. 镜像 + Helm 装进集群

与 [local-verify.md](./local-verify.md) 同类，当前 Chart 路径为 `helm/aistio`：

```bash
# 构建并导入 kind
docker build -t aistio:dev .
kind load docker-image aistio:dev --name aistio

# 安装（memory 即可做 SDK 联调；生产用 -f profiles/postgres.yaml）
helm upgrade --install aistio ./helm/aistio \
  --namespace aistio-system --create-namespace \
  --set image.repository=aistio \
  --set image.tag=dev \
  --set image.pullPolicy=IfNotPresent

kubectl -n aistio-system rollout status deploy/aistio
kubectl -n aistio-system get svc

# 本机访问 REST + ASDP
kubectl -n aistio-system port-forward svc/aistio 8080:8080 15010:15010
```

可选 profile：

```bash
helm upgrade --install aistio ./helm/aistio -n aistio-system \
  -f ./helm/aistio/profiles/postgres.yaml \
  --set image.repository=aistio --set image.tag=dev
```

---

## 4. 手工联调：SDK → 控制面 → 查询/命令

前提：第 2 或第 3 层已有可连的 aistiod（REST `:8080`，gRPC `:15010`）。

### 4.1 最小数据面进程（不依赖真实 Claude 包）

SDK 单测里用 mock 目标即可驱动适配器。下面用 **手动 `SessionBridge` + 直接 `on_event`**，验证控制面写 Store，无需真实框架：

```bash
cd sdk/python && source .venv/bin/activate
```

保存为 `/tmp/aistio_smoke_agent.py`：

```python
#!/usr/bin/env python3
"""最小旁路：不挂真实框架，直接向 Bridge 灌事件，验证 ASDP + HTTP 合约。"""
import time
from aistio.bridge import SessionBridge
from aistio.events import EVENT_MESSAGE, EVENT_COMPACTION, SessionEvent

bridge = SessionBridge(
    control_plane="127.0.0.1:15010",
    agent_name="smoke-agent",
    namespace="default",
    instance_id="smoke-1",
    enable_events=True,          # 打开 Level 2
    contract_http_port=18080,    # 避开 aistiod :8080
    start_http=True,
    start_grpc=True,
)
bridge.start()

sid = "sess-smoke-1"
bridge.on_event(SessionEvent(
    session_id=sid, event_type=EVENT_MESSAGE,
    role="user", content="hello from smoke",
))
bridge.on_event(SessionEvent(
    session_id=sid, event_type=EVENT_MESSAGE,
    role="assistant", content="hi",
))
# 触发 Level 4（compaction 立即推 ContextReport）
bridge.on_event(SessionEvent(
    session_id=sid, event_type=EVENT_COMPACTION,
    content="summary: smoke chat",
))

print("contract http on :18080 ; reporting to :15010 ; Ctrl+C to stop")
try:
    while True:
        time.sleep(5)
finally:
    bridge.stop()
```

```bash
python /tmp/aistio_smoke_agent.py
```

### 4.2 验数据面合约 HTTP

```bash
curl -s http://127.0.0.1:18080/agentscope/info | jq .
curl -s http://127.0.0.1:18080/agentscope/health
curl -s http://127.0.0.1:18080/agentscope/sessions | jq .
curl -s http://127.0.0.1:18080/agentscope/sessions/sess-smoke-1/context | jq .
curl -s 'http://127.0.0.1:18080/agentscope/sessions/sess-smoke-1/messages?offset=0&limit=10' | jq .
# 命令（无真实框架时可能仅记录/空操作，视 Bridge 是否挂了 adapter）
curl -s -X POST http://127.0.0.1:18080/agentscope/sessions/sess-smoke-1/compress
```

### 4.3 验控制面 Store / REST / CLI

等 Level 1 周期（约 10s）或事件 flush（约 5s）后：

```bash
# REST
curl -s 'http://127.0.0.1:8080/api/v1/sessions?namespace=default' | jq .
curl -s 'http://127.0.0.1:8080/api/v1/sessions/<uuid-or-id>/events?namespace=default' | jq .
curl -s 'http://127.0.0.1:8080/api/v1/sessions/<uuid-or-id>/context?namespace=default' | jq .

# CLI（指向同一 API）
./bin/aistioctl session list --namespace default
./bin/aistioctl session get <session-id> --namespace default --context
```

若 REST 需要 token，给 aistiod 加 `--api-auth-token=...`，CLI / curl 带对应 Header（见 `aistioctl` 现有 auth 参数）。

### 4.4 真实框架接入（可选）

```python
import aistio
# 例：Claude Agent SDK（需自行 pip install claude-agent-sdk）
from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions

client = ClaudeSDKClient(ClaudeAgentOptions(...))
bridge = aistio.instrument(
    client,
    control_plane="127.0.0.1:15010",
    agent_name="my-claude-agent",
    namespace="default",
    enable_events=True,
    contract_http_port=18080,
)
# 正常跑对话；结束后 bridge.stop() 或 with 退出
```

集群内 Agent Pod 时：`control_plane` 用 Service DNS，例如 `aistio.aistio-system:15010`；`instance_id` 默认读 `HOSTNAME`。

BYO 发现仍可按 getting-started：给 Deployment 打 `agentscope.io/managed=true`（或当前仓库约定标签），并保证 Pod 上合约端口可被 SessionPoller 访问。

---

## 5. 能力验收清单

按 [sdk-design.md](./sdk-design.md) 对照手工勾选：

| 能力 | 怎么验 | 期望 |
|------|--------|------|
| ASDP 握手 | `test_handshake_*` 或 aistiod 日志 | `ConnectRequest` 带 runtime / capabilities |
| Level 1 摘要 | fake CP / REST `GET /sessions` | 有 `framework`、`context_hash`、`isCompacted` 等 |
| Level 2 事件 | `enable_events=True` + REST `.../events` | seq 单调；默认关闭时不上报 |
| Level 4 Context | compaction 后 REST `.../context` | Store `PutIfChanged`；同 hash 不重复插 |
| Level 3 全文 | `GET .../messages`（数据面合约） | 分页；摘要与全文分离 |
| compress / terminate | 合约 POST 或 `aistioctl session compress` | 适配器收到命令；控制面经 prober 可达时生效 |
| Inventory | OpenClaw 等实现了的适配器 | `GET /subagents` / `workspaces`；未实现返回 501 |
| 旁路安全 | `test_bypass_failure_never_raises` | 断网/上报失败不打断主对话 |

---

## 6. 常见问题

| 现象 | 处理 |
|------|------|
| SDK 连不上 gRPC | 确认 `--enable-asdp=true`、端口 `15010`、本机/port-forward 通；防火墙 |
| `:8080` 被占用 | aistiod 与合约 HTTP 错开：`contract_http_port=18080` |
| REST 有 session、无 events | 未开 `enable_events`；或未到 flush 周期；查 sink 日志 |
| REST 无 context | 需至少一次 compaction / hash 变更推送，或主动打合约 `GET .../context` 并由控制面拉取落库（视当前 poller 是否已接） |
| `make test` 里 envtest 失败 | 跑 `make test-integration` 会装 assets；纯 `go test ./...` 中部分包会 skip |
| Helm 装不上 | 用 `helm/aistio`；旧文档里的 `agentscope-controlplane` / `install/install.sh` Chart 路径可能未同步，以 Makefile 为准 |
| Claude `can_handle` 为假 | 未安装真实 SDK 时用 mock 目标或单测里的 stub；类名/模块名需能被适配器识别 |

清理 kind：

```bash
helm uninstall aistio -n aistio-system || true
kind delete cluster --name aistio
```

---

## 7. 推荐日常节奏

1. 改 SDK / 协议：`cd sdk/python && pytest -v` + `go test ./internal/asdp/... ./internal/controller/ -count=1`
2. 改 Store / REST：`make test`，必要时带 `AISTIO_TEST_POSTGRES_DSN`
3. 发版前：`make vet test` +（有集群时）Helm 安装 + §4 smoke agent 跑通 list/get/context
