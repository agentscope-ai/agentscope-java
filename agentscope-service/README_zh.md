# Agent Service

> **构建 Managed Agents、协同 Agent Teams，并通过统一 Dashboard 运营整个 Agent 舰队。**

[English](README.md)

Agent Service 是基于 AgentScope Harness 构建的托管 Agent 平台。它提供统一的产品界面，
用于创建托管 Agent、运行可恢复的有状态会话、组织多 Agent 协作，以及运营在线 Agent
基础设施。

## 产品概览

Agent Service 围绕三项核心能力组织。

### Dashboard

Dashboard 是 Agent 舰队的运营入口，用于回答“当前运行了什么、哪里需要关注、历史上发生了
什么”。

- 在线 Agent 与健康实例统计；离线和历史 Agent 与默认视图分开呈现
- Dataplane 健康状态、版本、实例、副本和活跃会话
- Session 时间线、上下文压力、Token 用量、错误和运行时操作
- Agent Team 活动、成员状态、任务进度和生命周期
- 在同一运营模型中查看 Managed Agent 与 BYO AgentScope Runtime

### Managed Agents

Managed Agents 提供从定义到持久化对话的完整托管生命周期。

- 可版本化的 Agent 定义：模型、System Prompt、内置工具、MCP Server 与 Skills
- 明确的执行环境：`local`、`sandbox`、`remote`、`self_hosted`
- 顶级 Sessions：静态创建、事件驱动 Turn、历史恢复与 SSE 流式输出
- 工具审批与 Human-in-the-loop 续跑
- Session 级 Memory、Vault、Workspace 和资源覆盖
- Manual、Webhook、Cron 与 Channel 等触发入口

### Agent Teams

Agent Teams 将独立 Agent 组织为具备持久化协作状态的团队。

- Lead / Member 角色、团队目标和可复用的团队组成
- 单播与广播消息、共享任务、Claim / Assign 工作流与 Plan Approval
- 带数量和白名单约束的动态成员
- 成员 Wakeup、优雅关闭、生命周期时限和故障恢复
- 跨进程、跨 Session 保留的团队消息与任务

## 架构

Agent Service 是产品品牌，下图中的模块是具体实现组件。对外只需暴露 Gateway；内部组件
通过共享 Token 通信，并拥有清晰的数据边界。

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                              Agent Service                                 │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Web Console：Dashboard · Managed Agents · Agent Teams               │  │
│  └───────────────────────────────┬──────────────────────────────────────┘  │
│                                  │                                         │
│                        Browser / SDK / CLI                                 │
│                                  │                                         │
│                                  ▼                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ service-gateway :8080 · 认证与公共 API 路由                         │  │
│  └───────────────────┬──────────────────────────────────┬───────────────┘  │
│                      │                                  │                  │
│                      ▼                                  ▼                  │
│  ┌──────────────────────────────┐    ┌─────────────────────────────────┐  │
│  │ aistiod :8081               │    │ service-dataplane :8082         │  │
│  │ 产品与运行时控制面          │    │ AgentScope Brain                │  │
│  │ 舰队注册与 Agent Teams      │    │ Turn · Event · SSE · HITL       │  │
│  └──────────────┬───────────────┘    └────────────────┬────────────────┘  │
│                 │                                     │                   │
│                 │         ┌───────────────────────────┘                   │
│                 ▼         ▼                                               │
│  ┌──────────────────────────────┐    ┌─────────────────────────────────┐  │
│  │ PostgreSQL                  │◀───│ service-scheduler :8083         │  │
│  │ cp · rt · dp schemas        │    │ Channel · Cron · Hands Worker   │  │
│  └──────────────────────────────┘    └─────────────────────────────────┘  │
│                                                                            │
│     Managed HarnessAgent Runtime · BYO AgentScope Runtime · Sandbox       │
└────────────────────────────────────────────────────────────────────────────┘
```

### 各平面职责

| 平面 | 负责 | 不负责 |
| --- | --- | --- |
| Gateway | 公共入口、认证与 API 路由 | 业务状态与 Agent 执行 |
| Control（`aistiod`） | 产品资源、控制台、舰队状态、Session、Team 和运行时命令 | 模型 Turn |
| Dataplane | Harness Runtime、事件日志、SSE、Turn Lease、HITL 和 Work Queue | 直读 `cp` Schema |
| Scheduler | Channel、Cron、出站任务和 Self-hosted Hands Worker | 推理循环 |

### 数据归属

各平面可以共用同一个 PostgreSQL Server，但不会共享表：

| Schema | Owner | 数据 |
| --- | --- | --- |
| `cp` | `aistiod` 产品 API | 用户、Agent、版本、Environment、Session、Vault、Memory、Deployment |
| `rt` | Aistio Runtime Store | 舰队实例、运行时 Session、Context、Team、Task 与 Message |
| `dp` | Java Dataplane | Session Event、协调状态、HITL、Work Item 和数据面投影 |

Dataplane 通过控制面内部 API 解析 Managed Session，并仅使用返回的 Agent Snapshot 构建
运行时，不读取本地产品 Catalog 作为回退。

### 一次 Turn 的完整路径

1. 客户端向已有 Session 追加 `user.message`。
2. Dataplane 获取 Turn Lease，并把 Session 标记为 `running`。
3. 控制面解析已固定版本的 Agent Snapshot、Environment、Workspace、Memory 与 Vault。
4. `SessionTurnRunner` 执行 `HarnessAgent.streamEvents`。
5. `agent.message`、`agent.tool_use`、`span.model_request_*` 等权威事件写入 PostgreSQL；
   可选 Preview Delta 只用于流式展示，不落库。
6. Session 回到 `idle`、因 HITL / Tool Result 暂停，或以类型化错误终止。

客户端以持久化事件序列恢复，并通过
`GET /api/sessions/{id}/events/stream?after={seq}` 增量续传。进程内 Agent 对象和 Preview
Stream 都不是权威数据源。

### Brain 与 Hands

**Brain** 管理上下文、推理、工具决策和事件日志；**Hands** 决定工具在哪里执行。

| Environment | 执行方式 |
| --- | --- |
| `local` | 在 Dataplane 宿主机运行文件系统与 Shell 工具，仅建议开发使用 |
| `sandbox` | 使用托管 E2B Sandbox |
| `remote` | 使用远程或分布式文件系统，不提供本地 Shell |
| `self_hosted` | 将 Schema-only Tool Call 入队，由客户侧出站 Worker 执行 |

## 快速开始

### 前置条件

- Docker
- JDK 17+
- Maven
- Go 1.26+
- 模型 API Key；以下示例使用 DashScope

仅在重新构建 Web Console 时需要 Node.js。

### 1. 启动本地环境

从 Monorepo 执行：

```bash
export DASHSCOPE_API_KEY=sk-xxx

cd agentscope-service
BUILDER_REBUILD=1 scripts/dev-up.sh
```

脚本会启动 PostgreSQL、`aistiod`、Dataplane、Scheduler 和 Gateway。本地开发设置
`AISTIO_ENABLE_KUBERNETES=false`，Hosted Product 流程无需 CRD Reconciler 或 ASDP gRPC。

| 项目 | 值 |
| --- | --- |
| Console 与公共 API | http://localhost:8080 |
| 默认账号 | `admin` / `admin` |
| 其他种子账号 | `alice` / `alice`、`bob` / `bob` |
| 日志与本地状态 | `.dev-stack/` |

默认账号和开发密钥只能用于本地环境。

### 2. 运行第一个 Session

1. 打开 http://localhost:8080 并登录。
2. 在 **Managed Agents** 中创建 Agent。
3. 创建一个 `local` Environment。
4. 打开 **Sessions**，创建绑定 Agent 与 Environment 的 Session，并发送消息。
5. 在 **Dashboard** 查看在线 Agent、Session Event 与运行时状态。

也可以执行完整的 API 冒烟测试：

```bash
scripts/smoke.sh
```

完整 curl 步骤见
[`docs/guide/03-quickstart.md`](docs/guide/03-quickstart.md)。

### 3. 停止环境

```bash
scripts/dev-down.sh
```

## 产品资源模型

| 资源 | 作用 |
| --- | --- |
| Agent | 可版本化的 System Prompt、模型、工具、MCP Server、Skill 与协作配置 |
| Environment | 工具执行边界及 Sandbox / Worker 配置 |
| Session | Agent 版本、Environment、Memory、Vault 与事件流的有状态绑定 |
| Memory Store | 跨 Session 共享的文档 |
| Vault | 运行时解析到工具中的加密凭据 |
| Deployment | Agent Turn 的 Manual、Cron 或 Webhook 触发器 |
| Channel | 消息平台集成与出站投递 |
| Team | 包含 Message、Task、Plan 和生命周期状态的 Lead / Member 协作单元 |

Session 创建是静态操作：创建时只记录绑定关系，不会运行 Agent；第一条 `user.message`
才会启动 Turn。

## 关键特性

### Event-native Session

入站 Event 驱动工作，出站 Event 描述进度和结果。每个持久化 Event 都有 Session 内单调递增
的序号，客户端可以从游标断点续传。可选 `event_start` / `event_delta` 提供即时打字机效果，
最终持久化 Event 始终是权威结果。

### Human-in-the-loop Tool

配置为 Ask Policy 的工具会暂停 Turn 并发出确认请求。`user.tool_confirmation` 可以继续或
拒绝执行，同时保留完整 Session 历史。

### Self-hosted 执行

对于私有基础设施，`self_hosted` Environment 在 Brain 中只暴露 Tool Schema；客户侧出站
Worker 负责 Poll、Ack、Heartbeat、执行并返回 Tool Result，无需开放入站网络。

### Managed 与 BYO Agent

Managed Agent 在 Java Dataplane 中根据控制面 Snapshot 构建。已有 AgentScope 应用可以通过
Aistio Extension 注册，并与 Managed Agent 一同出现在 Dashboard。舰队默认只统计在线
Agent，同时保留离线和历史视图。

### Multi-agent Team

Team 支持 Lead / Member、Task Claim 与 Assign、单播与广播消息、Plan Approval、动态成员、
Wakeup、Shutdown Policy，以及跨 Session Restart 的恢复。

## 工程结构

| 路径 | 作用 |
| --- | --- |
| [`aistio/`](aistio/) | Go 控制面、Kubernetes 集成、Runtime Store 与 Console 构建产物 |
| [`frontend/`](frontend/) | React/Vite Console 源码，构建到 `aistio/ui` |
| [`service-common/`](service-common/) | Java 公共契约、持久化、认证、事件与协调 |
| [`service-gateway/`](service-gateway/) | 对外 Spring Cloud Gateway |
| [`service-dataplane/`](service-dataplane/) | Managed Session Brain 与 AgentScope Harness Runtime |
| [`service-scheduler/`](service-scheduler/) | Channel、Cron、出站任务与 Hands Worker |
| [`scripts/`](scripts/) | 本地生命周期和冒烟测试脚本 |
| [`docs/`](docs/) | 架构、API、Event、运维和验收文档 |

## 开发

### 构建后端

请从 Monorepo 根目录执行 Maven，确保 Service JAR 使用的 AgentScope Snapshot 都是最新版本：

```bash
mvn install -DskipTests

cd agentscope-service/aistio
make build
make test
```

### 构建或开发 Console

```bash
cd agentscope-service/frontend
npm install
npm run build   # 静态资源输出到 ../aistio/ui

npm run dev     # Vite HMR，/api 代理到 Gateway
```

### Docker Compose

先构建 Java Artifact，再启动容器化环境：

```bash
mvn install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build
```

### 服务端口

| 服务 | 端口 | 暴露方式 |
| --- | ---: | --- |
| Gateway | 8080 | 对外 |
| `aistiod` | 8081 | 内部 |
| Dataplane | 8082 | 内部 |
| Scheduler | 8083 | 内部 |
| PostgreSQL | 5432 | 本地基础设施 |

## 配置

Java Service 使用 `builder.*` 属性与 `BUILDER_*` 环境变量。各平面必须使用一致的认证密钥
和内部 URL。

| 变量 | 作用 |
| --- | --- |
| `DASHSCOPE_API_KEY` | 本地 Turn 使用的 DashScope 模型凭据 |
| `BUILDER_JWT_SECRET` | Gateway 与控制组件共享的 JWT 签名密钥 |
| `BUILDER_INTERNAL_TOKEN` | 平面间可信调用密钥 |
| `BUILDER_VAULT_MASTER_KEY` | Vault 凭据加密密钥 |
| `BUILDER_DB_URL`、`BUILDER_DB_USER`、`BUILDER_DB_PASSWORD` | Java Dataplane 数据库 |
| `BUILDER_CONTROL_URL`、`BUILDER_DATA_URL`、`BUILDER_SCHEDULER_URL` | 内部服务地址 |
| `BUILDER_E2B_API_KEY` | `sandbox` Environment 的 E2B 凭据 |
| `AISTIO_PRODUCT_DSN` | `aistiod` 使用的产品数据库 |
| `AISTIO_ENABLE_KUBERNETES` | 是否启用 Aistio CRD Reconciler 与 Kubernetes 集成 |
| `BUILDER_REBUILD=1` | `dev-up` 前强制完整重建 |

生产部署必须替换全部开发密钥，并使用持久化 PostgreSQL。详见
[运维指南](docs/guide/13-operations.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [产品指南](docs/guide/README.md) | 概念与文档索引 |
| [架构](docs/guide/02-architecture.md) | 平面边界、Brain / Hands 与 Turn 生命周期 |
| [快速开始](docs/guide/03-quickstart.md) | 使用 curl 创建第一个 Agent 和 Session |
| [Agents](docs/guide/04-agents.md) | Agent 定义与版本 |
| [Environments](docs/guide/05-environments.md) | Local、Sandbox、Remote 与 Self-hosted 执行 |
| [Sessions](docs/guide/06-sessions.md) | Session 模型与生命周期 |
| [Events](docs/events/README.md) | Event 类型与错误契约 |
| [运维](docs/guide/13-operations.md) | 部署与生产配置 |
| [验收](docs/guide/14-validation.md) | 端到端验收场景 |
| [控制面与数据面契约](docs/aistio-cp-contract.md) | 内部 Control Plane ↔ Data Plane API |

## License

Agent Service 基于
[Apache License 2.0](aistio/LICENSE) 发布。
