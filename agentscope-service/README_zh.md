# AgentScope Service

> AgentScope 托管 Agent 平台（控制面 + 数据面）  
> 🇬🇧 English: [README.md](README.md)

本目录提供一套可本地运行的托管 Agent 服务：业务在**控制面**定义 Agent / Environment / Session 等资源；**数据面**负责对话 turn、事件与 SSE；网关对外统一入口。控制面为 Go 进程 `aistiod`，数据面 / 调度 / 网关为 Java。

---

## 总体设计

四个平面共用**同一个 PostgreSQL**（schema `cp` / `rt` / `dp`）。对外只暴露 **gateway `:8080`**。

```
                 ┌────────────────────────────────────────────┐
 浏览器 / CLI ─▶│  service-gateway                           │ :8080
                 └───────┬───────────────────┬────────────────┘
              控制面 API │                   │ 数据面 API
                         ▼                   ▼
              ┌──────────────────────┐  ┌──────────────────────────┐
              │ aistiod (Go)         │  │ service-dataplane        │ :8082
              │ :8081                │  │ Brain / 事件 / SSE       │
              │ /api/*    产品资源   │  │ （产品数据走 CP           │
              │ /api/v1/* 运行态管控 │  │  internal API，不直读 cp）│
              │ 控制台 SPA           │  └───────────┬──────────────┘
              └────────┬─────────────┘              │
                       ▼                            ▼
              ┌──────────────────────────────────────────────────┐
              │  PostgreSQL（cp = 产品，rt = 运行时观测，dp = DP）│
              └──────────────────────────────────────────────────┘
                                    ▲
                       ┌────────────┴───────────┐
                       │ service-scheduler      │ :8083
                       │ 渠道 / cron / Hands    │
                       └────────────────────────┘
```

| 平面 | 职责 | 不做 |
|------|------|------|
| **Gateway** | 公网入口；按路径路由到控制面或数据面 | 业务逻辑、数据库 |
| **Control（`aistiod`）** | Agent / Env / Session 等产品资源；会话生命周期；控制台；运行态查询与命令（context、compress 等） | 不跑模型 turn |
| **Data** | `user.message` → turn；事件落库与 SSE；租约 / HITL / work 队列 | 不直读 `cp` schema |
| **Scheduler** | IM、出站、cron、Hands Worker | 不跑推理循环 |

`aistiod` **单进程单端口**：

- `/api/*` — 产品 API（控制台 JWT）
- `/api/v1/*` — 运行态 / 舰队管控（overview、dataplanes、session 观测等）
- 静态控制台 SPA（`aistio/ui`）

本地 `dev-up` 使用 `AISTIO_ENABLE_KUBERNETES=false`：不启 reconciler / CRD / ASDP gRPC，产品 API 与数据面联调仍可用。第三方 Agent（如 paw + `agentscope-extensions-aistio`）可通过 HTTP 自注册（`POST /api/v1/dataplanes/register`）出现在 Operate，无需 Kubernetes。

控制面与数据面的契约见 [docs/aistio-cp-contract.md](docs/aistio-cp-contract.md)。

### 模块

| 路径 | 说明 |
|------|------|
| [`aistio/`](aistio/) | Go 控制面 `aistiod` + 控制台构建产物 `ui/` |
| `service-common` | Java 共享库 |
| `service-gateway` | 边缘网关 |
| `service-dataplane` | 数据面 / Brain |
| `service-scheduler` | 渠道、cron、Hands worker |
| `frontend/` | 控制台源码（构建输出到 `aistio/ui`） |

### 一次会话怎么走

1. 经 gateway 登录，拿到 JWT。
2. 控制面创建 Agent、Environment、Session。
3. 发消息 → gateway → **数据面**（`{events:[{type,payload}]}`）。
4. 数据面持 turn 租约，运行 `HarnessAgent`，写事件。
5. 客户端 `GET …/events/stream?after=` 拉 SSE（DB 游标 fan-out）。
6. 工具执行面由 Environment 决定（`local` / `sandbox` / `self_hosted` 等）。

Brain 进程内对象可丢弃；Session 以共享库中的事件序列为准恢复。

---

## 本地使用

```bash
export DASHSCOPE_API_KEY=sk-xxx

cd agentscope-service

# 首次或改过代码后强制重编译再启动
BUILDER_REBUILD=1 scripts/dev-up.sh
```

| 项 | 值 |
|----|-----|
| 控制台 | http://localhost:8080 |
| 账号 | `admin` / `admin`（另有 `bob`/`bob`、`alice`/`alice`） |
| 停止 | `scripts/dev-down.sh` |
| 日志 / 状态 | `.dev-stack/` |

脚本启动：Postgres（Docker）+ `aistiod` + data + scheduler + gateway。Java 平面默认 `SPRING_PROFILES_ACTIVE=jdbc`。

栈起来后可选 API 冒烟：

```bash
scripts/smoke.sh
```

### 控制台走通一轮

1. 打开 http://localhost:8080 并登录。
2. 创建 Agent → Environment（试用选 `local`）→ Session。
3. 发消息，确认事件流与回复。

curl 示例见 [docs/guide/03-quickstart.md](docs/guide/03-quickstart.md)。

### Docker / 前端热更新

```bash
# 在仓库根目录
mvn -pl agentscope-service -am install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build

# 前端 HMR（/api 代理到 :8080）
cd agentscope-service/frontend && npm run dev
```

### 端口一览

| 服务 | 端口 |
|------|------|
| Gateway（对外） | 8080 |
| aistiod | 8081 |
| Dataplane | 8082 |
| Scheduler | 8083 |
| Postgres | 5432 |

---

## 资源模型（控制面）

| 资源 | API 前缀 | 说明 |
|------|----------|------|
| Agent（版本化） | `/api/agents` | 定义与版本 |
| Environment | `/api/environments` | 执行面：`local` / `sandbox` / `self_hosted` / `remote` |
| Session | `/api/sessions` | Agent × Environment；事件 + SSE |
| Memory store | `/api/memory-stores` | 跨 session 文档 |
| Vault | `/api/vaults` | 加密凭据 |
| Deployment | `/api/deployments` | cron / webhook / 手动 |

产品对话路径：创建 Session → 向数据面追加事件 → 订 SSE。数据面另暴露 `/agentscope/*` 合约，供控制面拉取 sessions / context 或下发 compress、terminate。

---

## 配置要点

各 Java 平面共用前缀 `builder.*` / `BUILDER_*`；须与 `aistiod` 约定同一 JWT、内部 token 与库。

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 模型 key（本地对话需要） |
| `BUILDER_JWT_SECRET` | JWT（各平面一致，生产须更换） |
| `BUILDER_INTERNAL_TOKEN` | 面间调用密钥 |
| `BUILDER_VAULT_MASTER_KEY` | Vault 主密钥 |
| `BUILDER_DB_*` | 共享库（本地由 `dev-up` 指到 Docker Postgres） |
| `BUILDER_CONTROL_URL` / `DATA_URL` / `SCHEDULER_URL` | 平面互调地址 |
| `BUILDER_E2B_API_KEY` 等 | `sandbox` 执行面 |
| `BUILDER_REBUILD=1` | `dev-up` 强制重编译 |
| `AISTIO_ENABLE_KUBERNETES` | `dev-up` 固定为 `false` |
| `AISTIO_PRODUCT_DSN` | 控制面产品库 DSN（`cp`） |

生产部署需在各平面共享上述密钥与库连接；细节见 [docs/guide/13-operations.md](docs/guide/13-operations.md)。

---

## 文档

| 文档 | 内容 |
|------|------|
| [docs/guide/README.md](docs/guide/README.md) | 产品指南目录 |
| [02-architecture.md](docs/guide/02-architecture.md) | 架构 |
| [03-quickstart.md](docs/guide/03-quickstart.md) | curl 第一轮会话 |
| [05-environments.md](docs/guide/05-environments.md) | Environment / Hands |
| [13-operations.md](docs/guide/13-operations.md) | 运维 |
| [aistio-cp-contract.md](docs/aistio-cp-contract.md) | 控制面 ↔ 数据面契约 |
| [events/README.md](docs/events/README.md) | 事件类型 |
