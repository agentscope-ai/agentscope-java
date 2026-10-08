# AgentScope Service

> **面向业务应用的 Agent as a Service 平台：通过 API 提交工作、持续交互，并获取可复核的结果与交付物。**

[English](README.md)

AgentScope Service 将能够研究、使用工具和验证结果的 Agent 发布为服务。业务应用可以在 CRM 中生成客户方案，在文档流水线中核验材料，在研发平台中调查故障并生成 PR，也可以提供连续对话的专业助手或定期运行后台任务。

用户在原有产品中发起工作和检查交付；Service 承接 Agent 的运行、协调与交互。应用团队负责业务工具、数据授权、界面和结果验收。完整接入设计见[场景案例](../docs/v2/zh/service/usecases.md)。

## 产品能力

### 通过 API 从任务走向交付

创建或接入 Agent，配置资源并发布 Endpoint。应用使用 Application 凭证调用服务，保存 Invocation ID，读取状态、快照、事件、待办与产物。Job 用于独立后台任务，Conversation 用于支持会话的单 Agent；每个任务或会话轮次都有自己的 Invocation。

Endpoint 定义输入输出契约和发布版本。应用可以通过查询、SSE 或 Webhook 获取变化，参与人工确认，并把经过验证的结果接回业务流程。执行完成与业务验收分别处理。

从 [API 快速开始](../docs/v2/zh/service/first-session.md)开始，按[发布 Endpoint](../docs/v2/zh/service/endpoints.md)与[统一服务 API](../docs/v2/zh/service/service-api.md)完成接入。Managed 原生会话、文件、子 Agent 和 checkpoint 接口用于相应扩展能力，不能与公共 Invocation 的 ID、认证和事件游标混用。

### 按任务选择执行方式

- **Managed：** 用指令、模型、工具和资源定义专业 Agent，由 Service 运行 AgentScope Harness；工具在配置的 Environment 中执行。平台可部署在自己的基础设施中。
- **External：** 接入已有 AgentScope 或其他框架应用，保留其进程与部署；实现任务执行适配、事件回报和支持的控制操作。
- **Hosted：** 通过 Runtime Host 复用 Codex、Claude Code 等 Coding Agent，按任务管理 provider 进程与工作目录。

先用一个 Agent 验证交付。需要动态分工时组合 Team；需要明确步骤、条件和人工关卡时使用 Workflow。调用方仍通过 Endpoint 使用能力，通过 Invocation 跟踪结果。Team 与 Workflow 当前提供 Job；具体交互以 runtime capabilities 为准。

### 统一管理与观察

Control Plane管理 Agent 目录、绑定、发布、应用身份、任务协调与公共调用。Managed Agent、External Application 和 Runtime Host 通过持久执行契约协作。Console 提供 API 能力的可视化配置、任务查看与运维入口；业务用户可以留在自己的产品中。

Workspace、Environment、Memory 和 Vault 组织执行资源。应用身份与业务终端用户身份需要分别处理；共享 Application 的不同凭证不会自动提供用户级任务隔离。CRM、GitHub、订单系统等连接由接入方配置。

## 整体架构

### 基本工作原理

业务应用通过 Endpoint / Invocation API 提交与观察工作，开发者通过管理 API 或 Console 配置能力；控制面协调三类数据面：

- `managed`：AgentScope Harness 托管运行；
- `external-application`：AgentScope、LangChain、Claude Agent SDK 等用户应用通过 Application SDK / ASDP 注册；
- `hosted-runtime`：Runtime Host daemon 按任务拉起 Codex、Claude Code 等运行时；

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-architecture.png)


### 生产部署架构

在生产环境中，推荐的 AgentScope Service 部署架构如下：

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-production-deploy.png)


| 平面 | 负责 | 不负责 |
| --- | --- | --- |
| Gateway | 公共入口、认证与 API 路由 | 业务状态与 Agent 执行 |
| Control Plane（`service-controlplane`） | 产品资源、发布与 Invocation、公共事件、任务协调和运行时命令 | Harness 推理、原生 Managed Session 事件生成 |
| Dataplane | Managed Harness Runtime、事件日志、SSE、Turn Lease、HITL 与 Work Queue | 直读产品 Catalog 表 |
| Scheduler | Channel、Cron、出站任务与 Self-hosted Hands Worker | 推理循环 |


## Agent 如何接入

业务开发者从 [API 快速开始](../docs/v2/zh/service/first-session.md)验证调用；平台团队准备模型、执行环境、身份与发布资源。已有 Agent 应用按 [External 接入指南](../docs/v2/zh/service/register-agentscope-agent.md)实现执行适配，再发布为服务。

## 发布部署与文档

发布版使用已构建镜像，参见 [Docker / Helm 部署](deploy/README.md) 和 [中文发布维护手册](release/README_zh.md)。完整用户文档位于 [AgentScope Service 专区](../docs/v2/zh/service/index.md)。以下本地启动流程用于开发，包含演示账号和可选数据库重置。

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
git clone https://github.com/agentscope-ai/agentscope-java.git
cd agentscope-java

export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
scripts/dev-down.sh && BUILDER_REBUILD=1 scripts/dev-up.sh
```

脚本会启动 PostgreSQL、`service-controlplane`、Dataplane、Scheduler 和 Gateway。本地开发设置 `CONTROL_PLANE_ENABLE_KUBERNETES=false`，Hosted Product 流程无需 CRD Reconciler 或 ASDP gRPC。
项目尚未发布，v4 又明确替换了旧执行 schema，因此 `BUILDER_REBUILD=1` 会同时重建可丢弃的本地 `cp`、`rt`、`dp` schema。只有在需要保留已经是 v4 的本地数据时才设置 `BUILDER_RESET_DB=0`。启动脚本在报告成功前会检查三个 schema 和终态协作/编排 migration；执行 `scripts/smoke.sh` 可运行 API 级端到端验收。

| 项目 | 值 |
| --- | --- |
| Console 与公共 API | http://localhost:18080 |
| 默认账号 | `admin` / `admin` |
| 其他种子账号 | `alice` / `alice`、`bob` / `bob` |
| 日志与本地状态 | `.dev-stack/` |

默认账号和开发密钥只能用于本地环境。

### 2. 连接本机 Coding Agent

`agentscope` CLI 会自动发现 Codex、Claude Code 和 Qoder，签发仅限当前 Host 的运行凭证，
并在后台启动 Runtime Host。开发环境可直接从源码安装两个相邻的可执行文件：

```bash
cd service-controlplane
make install-runtime-cli PREFIX="$HOME/.local"
agentscope connect
```

`connect` 会自动发现本地服务并提示输入 AgentScope 用户名和密码；也可以为自动化设置
`AGENTSCOPE_API_TOKEN`，或使用控制台生成的 `AGENTSCOPE_RUNTIME_TOKEN`。凭证、稳定 Host ID、
PID 与日志保存在 `~/.agentscope/runtime-host/`，权限仅限当前用户。日常运维只需要：

```bash
agentscope runtime status
agentscope runtime logs -f
agentscope runtime restart
agentscope runtime stop
agentscope runtime probe
```

控制面会自动创建默认 Runtime Pool 和 `auto-<provider>` Runtime Profile；Host 在线后，创建
Agent 时直接选择 `Codex (<host-key>)` 等本地 Runtime，无需手工填写 daemon 参数。

执行任务时，Runtime Host 会同时为 Coding Agent 注入 `agentscope-collaboration` MCP 和
task-scoped `agentscope` CLI。Agent 可以用 `agentscope task context` 读取当前任务，使用
`agentscope task progress/respond --content-file ...` 写回进展或结果，通过
`agentscope task child`、`agentscope task run graph/replan/node-complete` 参与 Team 协作。
这些命令只持有当前 Attempt 的短期凭据，不能访问其他 Issue，也不会继承用户或 Runtime Host Token。

### 3. 运行第一个 Session

1. 打开 http://localhost:18080 并登录（`admin` / `admin`）。
2. 在 **Managed Agents** 中创建 Agent。
3. 本地开发栈会自动为新建的 Managed Agent 绑定共享的 `default-local` Environment；之后可在 Agent 设置中切换。
4. 打开 **Sessions**，创建 Session 并发送第一条消息。
5. 在 **Dashboard** 查看在线状态、事件与运行时信息。
6. 如需协作，创建持久 **Team**、分配 Issue，并观察 discussion route 与 AgentTask。

体验 BYO Agent 注册时，可使用仓库示例 `agentscope-examples/agents/agentscope-paw`；启动后即可在 Dashboard 中看到智能体注册成功。

把 **DeepSeek Harness** 作为独立运行时接入时，使用 `agentscope-service/service-controlplane/sdk/dsh`（`@agentscope/dsh-controlplane`）Cordis 插件：向 service-controlplane 自注册、提供 `/agentscope/*` 契约、接收 AgentTask，并与其他 runtime 使用同一 Issue/Comment/Artifact 协议。安装与配置见该目录 [README_zh.md](service-controlplane/sdk/dsh/README_zh.md)。


### 4. 停止环境

```bash
scripts/dev-down.sh
```

## 开发

### 构建后端

请从 Monorepo 根目录执行 Maven，确保 Service JAR 使用的 AgentScope Snapshot 都是最新版本：

```bash
mvn install -DskipTests

cd agentscope-service/service-controlplane
make build
make test
```

### 构建或开发 Console

```bash
cd agentscope-service/frontend
npm install
npm run build   # 静态资源输出到 ../service-controlplane/ui

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
| Gateway | 18080 | 对外（Docker Compose 容器内仍为 8080） |
| `service-controlplane` | 8081 | 内部 |
| Dataplane | 8082 | 内部 |
| Scheduler | 8083 | 内部 |
| PostgreSQL | 5432 | 本地基础设施 |

### 配置

Java Service 使用 `builder.*` 属性与 `BUILDER_*` 环境变量。各平面必须使用一致的认证密钥和内部 URL。

| 变量 | 作用 |
| --- | --- |
| `DASHSCOPE_API_KEY` | 本地 Turn 使用的 DashScope 模型凭据 |
| `BUILDER_JWT_SECRET` | Gateway 与控制组件共享的 JWT 签名密钥 |
| `BUILDER_INTERNAL_TOKEN` | 平面间可信调用密钥 |
| `BUILDER_VAULT_MASTER_KEY` | Vault 凭据加密密钥 |
| `BUILDER_DB_URL`、`BUILDER_DB_USER`、`BUILDER_DB_PASSWORD` | Java Dataplane 数据库 |
| `BUILDER_CONTROL_URL`、`BUILDER_DATA_URL`、`BUILDER_SCHEDULER_URL` | 内部服务地址 |
| `BUILDER_E2B_API_KEY` | `sandbox` Environment 的 E2B 凭据 |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | 是否允许新的 `local` Environment 绑定。`service-controlplane` 默认 `false`，`scripts/dev-up.sh` 和开发用 Compose 显式开启；生产环境应保持关闭。 |
| `CONTROL_PLANE_PRODUCT_DSN` | `service-controlplane` 使用的产品数据库 |
| `CONTROL_PLANE_ENABLE_KUBERNETES` | 是否启用 Control Plane CRD Reconciler 与 Kubernetes 集成 |
| `BUILDER_REBUILD=1` | 重建 Monorepo/service-controlplane，并默认重建可丢弃的本地 `cp`/`rt`/`dp` schema |
| `BUILDER_RESET_DB=0` | 完整重建二进制时保留已经是 v4 的本地数据库 |
| `BUILDER_SMOKE_TEST=1` | 健康检查和 SQL schema 校验通过后自动运行 `scripts/smoke.sh` |

生产部署必须替换全部开发密钥，并使用持久化 PostgreSQL。


## Roadmap

后续演进围绕业务应用的接入与交付：完善终端用户授权和文件输入契约，让自动触发复用公开调用链路，并持续验证长任务、复杂编排和费用治理。具体设计与优先级见[场景与实现评估](../internal/implementation/service-aaas-scenario-assessment-20261005.md)；其中建议尚不代表已发布能力。

企业级云产品亦可关注阿里云 [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview)、[Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html)。

## 文档

- [Service 定位与介绍](../docs/v2/zh/service/index.md)
- [场景案例与接入设计](../docs/v2/zh/service/usecases.md)
- [API 快速开始](../docs/v2/zh/service/first-session.md)
- [统一服务 API](../docs/v2/zh/service/service-api.md)
- [Managed 原生会话与任务](../docs/v2/zh/service/session-event-log.md)
- [API 参考](../docs/v2/zh/service/api-reference.md)
