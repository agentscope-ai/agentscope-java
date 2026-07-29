# AgentScope Service

> AgentScope 2.0 上的 **Managed Agents** 参考实现  
> 🇬🇧 English version: [README.md](README.md)

## 这是什么

**Managed Agents** 让 Agent 跑在托管环境里：推理、编排与 Harness 由平台统一托管，长周期任务不依赖本机一直在线；业务侧主要定义 Skills、Tools、Subagents 与权限策略，而不必自己拼装记忆压缩、状态恢复和工具治理。

本仓库基于 AgentScope 2.0 的 `HarnessAgent` 与可插拔 Sandbox，提供一套可本地试用、可横向扩展的企业级 Managed Agents 平台：

- 把已经工程化的 **HarnessAgent 作为 Brain 运行时**，负责推理、上下文与会话恢复；文件系统、workspace 与工具执行按 Environment 隔离到不同 Hands。
- **平台层**负责租户、权限、版本、事件和执行面选型。控制面（Agent、Environment、Memory、Vault、Deployment）与数据面（Session、Events、SSE）把这些能力组织成可多租、可审计、可运维的托管产品。

相对 CLI / 单机应用与「把 Harness 嵌进某个业务应用」，Managed Agents 把状态进一步上收到平台：

| 形态 | 主要状态在哪里 | 谁负责隔离 | 适合谁 |
|---|---|---|---|
| CLI / [claw](../agentscope-examples/agents/agentscope-paw/) 等单机 | 本机目录与本地会话 | 操作系统用户 | 个人提效 |
| SDK / 嵌入式 Harness | 应用自己的 Session / StateStore | 应用开发者 | 单个企业应用 |
| **Managed Agents（本项目）** | 控制面资源、共享状态库、Session 事件日志 | 平台按 User / Agent / Environment | 多团队、多租户平台 |

两个产品要点（与业界 Managed Agents 一致）：

1. **不再让业务开发者拼装 Harness。** 压缩、恢复、工具结果淘汰、长期记忆刷新等交给统一 Harness；你主要配置角色提示、Skills、MCP、工具权限和 Environment。
2. **拆开 Brain 与 Hands 的信任边界。** Brain 决定「要调用什么」；Hands 真正接触文件、网络与业务系统——可落在托管集群（`local`）、云沙箱（`sandbox` / E2B），或客户 VPC 内的 Self-hosted Worker（`self_hosted`）。

> 若你用过早期的开源 Agent Builder，可以把本项目理解为它的**产品化升级**：底层运行时与主要代码路径仍是 Harness，变化的是资源模型、API 契约、执行面边界以及多租户治理。设计说明可参考仓库博文 [Managed Agents · AgentScope Runtime](../docs/v2/zh/blogs/managed-agents-agentscope-rumtime.md)。

---

## 架构

部署上拆成 **四个平面**，对应 Managed Agents 的控制面 / 数据面，再加上边缘网关与异步调度。**控制面已迁到 Go（`aistiod`）**；数据面 / 调度 / 网关仍为 Java。各平面共用**同一个 PostgreSQL 实例**（schema `cp` / `rt` / `dp`）。对外只暴露 **gateway :8080**。

`aistiod` 是**单进程单端口**：同一个监听同时提供 Managed Agents API（`/api/*`，控制台 JWT）、Kubernetes 原生管控 API（`/api/v1/*`）与控制台 SPA。没有 Kubernetes 时用 `AISTIO_ENABLE_KUBERNETES=false` 降级运行，此时不启动 reconciler、CRD 路由与 ASDP gRPC。

```
                 ┌────────────────────────────────────────────┐
 浏览器 / CLI ─▶│  service-gateway（Spring Cloud Gateway）   │ :8080
                 └───────┬───────────────────┬────────────────┘
              控制面 API │                  │ 数据面 API
              资源 / 建会话│                  │ Session 事件 / SSE / Worker
                         ▼                   ▼
              ┌──────────────────────┐  ┌──────────────────────────┐
              │ aistiod (Go)         │  │ service-dataplane        │ :8082
              │ :8081                │  │ Brain：HarnessAgent、    │
              │ /api/*    产品资源   │  │ 事件日志、turn 租约、    │
              │ /api/v1/* 多框架管控 │  │ work 队列、HITL         │
              │ 控制台 SPA           │  │ （经 CP internal API     │
              │ reconciler / ASDP    │  │  读取产品资源）          │
              └────────┬─────────────┘  └───────────┬──────────────┘
                       │                            │
                       ▼                            ▼
              ┌──────────────────────────────────────────────────┐
              │  PostgreSQL（cp = 产品，rt = 运行时观测，dp = DP）│
              └──────────────────────────────────────────────────┘
                                    ▲
                       ┌────────────┴───────────┐
                       │ service-scheduler      │ :8083
                       │ IM / cron / Hands      │
                       └────────────────────────┘
```

| 平面 | 在 Managed Agents 中的角色 | 不做什么 |
|---|---|---|
| **Gateway** | 唯一公网入口；按 API 面路由；剥离伪造内部头；拒绝 `/api/internal/**` | 无业务逻辑、无 DB |
| **Control (`aistiod`)** | 租户资源治理：Agent 版本、Environment、Memory/Vault、Deployment；session **创建 / 列表 / 归档**；多框架 agent 管控（运行态 session、context、压缩）；控制台 SPA | **不**构建 Brain、**不**跑推理 turn |
| **Data** | 托管数据面：`user.message` → turn；事件落库与 SSE；turn 租约 / 中断；HITL 与 work 队列 | **不**直读 `cp` schema；产品资源走 CP API |
| **Scheduler** | 请求路径之外的执行：IM 桥接、出站、**cron 到期调度**、Hands Worker 接入 | **不**跑模型推理循环 |

契约草稿见 [docs/aistio-cp-contract.md](docs/aistio-cp-contract.md)。控制面为 Go **`aistiod`**（[`aistio/`](aistio/)）；原 Java `service-controlplane` 与独立的 `aistio-cp` 进程均已移除。

### 模块

| 模块 | 说明 |
|---|---|
| `aistio/` (`aistiod`) | Go 控制面：Managed Agents API + 多框架管控 API + 控制台 SPA（`aistio/ui`） |
| `service-common` | 共享库（无独立进程） |
| `service-gateway` | 边缘路由 |
| `service-dataplane` | 数据面 / Brain 运行时 |
| `service-scheduler` | 渠道、cron、Hands worker |

### 一次托管会话怎么走

1. 控制台经 gateway 登录（JWT）。
2. Control：创建版本化 Agent、Environment、Session（产品资源）。
3. 用户发消息 → gateway → **Data**（信封 `{events:[{type,payload}]}`）。
4. Data 持 turn 租约，构建 / 缓存 `HarnessAgent`（Brain），事件写入共享库。
5. 浏览器 `GET …/events/stream?after=`；SSE 用 **DB 游标轮询** fan-out（多副本无需 Redis pub/sub）。
6. 工具按 Environment 走 local / E2B / Self-hosted 队列；中断经本机或 `CoordinationStore` 票证跨进程消费。

Brain（`HarnessAgent` 对象）与 Session（稳定 ID + 事件序列）生命周期不同：节点可丢弃 Java 对象，对话须从共享状态恢复。详见 [02-architecture.md](docs/guide/02-architecture.md)、[05-environments.md](docs/guide/05-environments.md)、[13-operations.md](docs/guide/13-operations.md)。

---

## 使用方式

### 本地最快路径（试用 Managed Agents）

```bash
export DASHSCOPE_API_KEY=sk-xxx

cd agentscope-service

# 首次或刚改过代码：强制重编译再启动
BUILDER_REBUILD=1 scripts/dev-up.sh
```

| 项 | 值 |
|---|---|
| 控制台 | http://localhost:8080 |
| 默认账号 | `admin` / `admin`（另有 `bob`/`bob`、`alice`/`alice`） |
| 停止 | `scripts/dev-down.sh` |
| 状态 / 日志 | `.dev-stack/` |

脚本起 Postgres（Docker）+ **`aistiod`**（standalone，无 Kubernetes）/ data / scheduler / gateway。本地默认 `SPRING_PROFILES_ACTIVE=jdbc`。

冒烟：`scripts/smoke.sh`（栈起来后）。

### 控制台验通

1. 打开 http://localhost:8080 ，登录。
2. 创建 Agent → 绑定 Environment（试用用 `local`）→ 创建 Session。
3. 发 `user.message`，确认事件流 / 回复。

同一 Agent、换 Environment 类型即可切换 Hands（local / sandbox / self_hosted），定义本身不变。curl 示例：[03-quickstart.md](docs/guide/03-quickstart.md)；验收清单：[14-validation.md](docs/guide/14-validation.md)。

### Docker / 前端热更新

```bash
mvn -pl agentscope-service -am install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build

# 前端 HMR
cd frontend && npm run dev   # /api → :8080
```

### 生产注意（摘要）

四平面共享：`BUILDER_JWT_SECRET`、`BUILDER_INTERNAL_TOKEN`、`BUILDER_VAULT_MASTER_KEY`、`BUILDER_DB_*`。非 `dev`/`test` 校验内部 token。完整运维见 [13-operations.md](docs/guide/13-operations.md)。

---

## 资源模型

对齐常见 Managed Agents 资源面：

| 资源 | API | 说明 |
|---|---|---|
| **Agent**（版本化） | `/api/agents`、`/versions` | 业务定义快照；乐观锁；archive |
| **Environment** | `/api/environments` | Hands 执行面：`local` / `sandbox`（E2B）/ `remote` / `self_hosted` |
| **Session** | `/api/sessions` | Agent×Environment 运行实例；append-only 事件 + SSE（`?after=`） |
| **Memory store** | `/api/memory-stores` | 跨 session 文档挂载；与 harness 原生 `MEMORY.md` 正交 |
| **Vault** | `/api/vaults` | 加密凭据；注入 MCP `${ENV}` |
| **Deployment** | `/api/deployments` | cron（scheduler 调度 → control fire）/ webhook / 手动 |

产品路径只有 managed session：创建 → 发消息 → 订事件。遗留 `/api/agents/{id}/chat/*` 已移除。`always_ask` 走 HITL；`user.interrupt` 支持跨进程中断。

---

## 配置

前缀 `builder.*` / `BUILDER_*`。

### 模型

```yaml
builder:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY:}
    model-name: qwen-max
    stream: true
```

也可自备 `Model` Bean。

### 工作目录与 JWT

```yaml
builder:
  workspace: ${BUILDER_WORKSPACE:}
  jwt:
    secret: ${BUILDER_JWT_SECRET:builder-default-dev-secret-change-in-production-32chars}
```

生产必须更换 JWT（≥32 字符）。`~/.agentscope/builder/agentscope.json` 为固定配置路径。

---

## 执行面（Hands）与文件系统

试用优先选 **Environment 类型**，而不是改 Agent 提示词：

| Environment | Hands 在哪 | 典型用途 |
|---|---|---|
| `local` | Brain 宿主机命名空间 | 开发与可信内网 |
| `sandbox` | E2B（或兼容协议）云沙箱，Brain 主动调用 | 平台托管隔离执行 |
| `self_hosted` | 客户 Worker 出站 poll，Brain 侧 schema-only | 企业数据留在客户 VPC |
| `remote` | 分布式 KV FS（无 shell） | workspace 跨副本共享 |

`type=sandbox` 配置示例：

```yaml
builder:
  e2b:
    api-key: ${BUILDER_E2B_API_KEY:}
    template-id: base
    workspace-root: /home/user
    sandbox-timeout-seconds: 300
    persistence-mode: TAR
```

已有 Session **不要中途换** Environment；换信任边界应建新 Session。细节：[05-environments.md](docs/guide/05-environments.md)、[14-validation.md](docs/guide/14-validation.md)。

下方 `builder.workspace-store.fs-spec` 是更早的 workspace 后端开关；Managed `sandbox` **固定走 E2B**。

---

## 持久化

`dev-up.sh` 使用 H2 TCP，持久化用户、目录、共享授权、`builder_agent_state` 与 `builder_coord_*`（turn 租约、HITL、中断票、work 队列、cron fire 等）。

生产覆盖 `BUILDER_DB_*` 指向**同一** MySQL / PostgreSQL。Schema 种子由 **control** 负责。多副本靠共享库做状态恢复与协调，不必为协调单独上 Redis（可覆盖 Store bean）。

Self-hosted：出站运行 `io.agentscope.builder.worker.HandsWorkerMain`（`service-scheduler` jar）。

---

## 环境变量参考

| 变量 | 说明 |
|---|---|
| `DASHSCOPE_API_KEY` | 模型 key（试用必填） |
| `BUILDER_MODEL_NAME` | 默认模型名 |
| `BUILDER_JWT_SECRET` | JWT（各平面一致） |
| `BUILDER_INTERNAL_TOKEN` | 面间密钥；非 dev/test 启动校验 |
| `BUILDER_VAULT_MASTER_KEY` | Vault 主密钥 |
| `BUILDER_DB_URL` / `USER` / `PASSWORD` / `DRIVER` | 共享库 |
| `BUILDER_WORKSPACE` | 工作区根 |
| `BUILDER_E2B_API_KEY` 等 | `sandbox` Hands |
| `BUILDER_CONTROL_URL` / `DATA_URL` / `SCHEDULER_URL` | 平面互调 |
| `BUILDER_REBUILD=1` | 脚本强制重编译 |
| `SPRING_PROFILES_ACTIVE` | 本地默认 `dev` |

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [Managed Agents 博文](../docs/v2/zh/blogs/managed-agents-agentscope-rumtime.md) | 产品背景、Brain/Hands、三种执行面 |
| [docs/guide/README.md](docs/guide/README.md) | 产品指南 |
| [03-quickstart.md](docs/guide/03-quickstart.md) | curl 第一轮会话 |
| [13-operations.md](docs/guide/13-operations.md) | 部署运维 |
| [14-validation.md](docs/guide/14-validation.md) | 试用验收 |
| [MANAGED_AGENTS_API.md](docs/WIP/MANAGED_AGENTS_API.md) | HTTP API |
| [DATA_PLANE_CONTRACT.md](docs/WIP/DATA_PLANE_CONTRACT.md) | 事件 / Worker 契约 |
| [events/README.md](docs/events/README.md) | 事件类型 |
| [FOLLOW_UP_PRODUCTION.md](docs/WIP/FOLLOW_UP_PRODUCTION.md) | 生产后续项 |
