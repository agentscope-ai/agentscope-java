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

发布版使用已构建镜像，参见 [Docker / Helm 部署](deploy/README.md) 和 [中文发布维护手册](release/README_zh.md)。完整用户文档位于 [AgentScope Service 专区](../docs/v2/zh/service/index.md)。以下快速开始从源码启动免鉴权本地开发模式；发布安装见生产指南。

## Docker Compose 快速开始

从当前源码构建免鉴权本地开发模式。准备 Docker Compose v2 和模型凭据，在仓库根目录运行：

```bash
cd agentscope-service
export DASHSCOPE_API_KEY="YOUR_DASHSCOPE_API_KEY"
docker compose up -d --build --wait --wait-timeout 600
```

打开 http://localhost:18080，Console 自动进入开发身份和默认空间；API 无需登录、用户 token、应用 key 或授权配置。源码 Compose 默认 `BUILDER_LOCAL_DEV=true`，端口仅绑定本机。模型和外部工具仍需要提供方凭据。

已发布的 `2.1.0-BETA1` 镜像不支持该模式。发布版 Compose、Helm、认证与权限配置见[生产部署](../docs/v2/zh/service/kubernetes.md)；继续[创建第一个 Agent](../docs/v2/zh/service/create-managed-agent.md)。

## 使用 Go 安装 CLI 与 Runtime Host

接入本地开发模式时，准备 Go 1.26+，在仓库根目录从当前源码安装两个命令：

```bash
cd agentscope-service/service-controlplane
go install ./cmd/as ./cmd/agentscope-runtime-host
AS_CLI_BIN_DIR="$(go env GOBIN)"
if [ -z "$AS_CLI_BIN_DIR" ]; then
  AS_CLI_BIN_DIR="$(go env GOPATH)/bin"
fi
export PATH="$AS_CLI_BIN_DIR:$PATH"
as version
agentscope-runtime-host -help
as connect http://localhost:18080
as runtime status
```

Host 在另一台机器时，请使用实际 Service 地址。Coding Agent provider 需要另行安装和登录。
CLI 连接已有 Service 并启动 Runtime Host，不负责部署平台。将 PATH 设置写入 shell 配置文件。
更多说明见 [Runtime Host 安装与运维](https://java.agentscope.io/v2/zh/service/runtime-host)。

## 使用 Helm 安装生产环境

先准备外部 PostgreSQL、Workspace 的共享 RWX 存储、Artifact 存储、配置 Secret、
域名和 TLS，然后添加已发布的 Chart 仓库：

```bash
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
```

按[生产安装指南](https://java.agentscope.io/v2/zh/service/kubernetes)安装
`agentscope/agentscope-service`，固定 `--version 2.1.0-BETA1`，镜像命名空间使用
`sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`。
Chart 每组件单副本并采用 Recreate 更新，请安排维护窗口。

## 开发

### 从源码启动开发环境

源码开发需要 JDK 17+、Maven、Go 1.26+、Node.js 22 和 Docker。
从仓库根目录运行：

```bash
export DASHSCOPE_API_KEY=YOUR_MODEL_CREDENTIAL
cd agentscope-service
scripts/dev-down.sh
BUILDER_REBUILD=1 scripts/dev-up.sh
# 日后用 scripts/dev-down.sh 停止。
```

开发栈默认启用 `BUILDER_LOCAL_DEV=true`，使用单一开发身份与开发密钥，无需登录。完整重建默认重置可丢弃的 `cp`、`rt`、`dp` schema；
仅在需要保留已有兼容开发库时设置 `BUILDER_RESET_DB=0`。
这与上面的发布版 Compose 安装是不同的环境。

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
| `BUILDER_LOCAL_DEV` | 默认 `false`；源码 Compose 和开发脚本默认 `true`，跳过平台认证与权限校验；发布 Compose / Helm 保持关闭。 |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | 是否允许新的 `local` Environment 绑定。`service-controlplane` 默认 `false`，`scripts/dev-up.sh` 和开发用 Compose 显式开启；生产环境应保持关闭。 |
| `CONTROL_PLANE_PRODUCT_DSN` | `service-controlplane` 使用的产品数据库 |
| `CONTROL_PLANE_ENABLE_KUBERNETES` | 是否启用 Control Plane CRD Reconciler 与 Kubernetes 集成 |
| `BUILDER_REBUILD=1` | 重建 Monorepo/service-controlplane，并默认重建可丢弃的本地 `cp`/`rt`/`dp` schema |
| `BUILDER_RESET_DB=0` | 完整重建二进制时保留已经是 v4 的本地数据库 |
| `BUILDER_SMOKE_TEST=1` | 健康检查和 SQL schema 校验通过后自动运行 `scripts/smoke.sh` |

生产部署必须替换全部开发密钥，并使用持久化 PostgreSQL。


## Roadmap

后续演进围绕业务应用的接入与交付：完善终端用户授权和文件输入契约，让自动触发复用公开调用链路，并持续验证长任务、复杂编排和费用治理。

企业级云产品亦可关注阿里云 [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview)、[Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html)。

## 文档

- [Service 定位与介绍](../docs/v2/zh/service/index.md)
- [场景案例与接入设计](../docs/v2/zh/service/usecases.md)
- [API 快速开始](../docs/v2/zh/service/first-session.md)
- [统一服务 API](../docs/v2/zh/service/service-api.md)
- [Managed 原生会话与任务](../docs/v2/zh/service/session-event-log.md)
- [API 参考](../docs/v2/zh/service/api-reference.md)
