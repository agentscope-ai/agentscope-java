# AgentScope Service

> **面向业务应用的 Agent as a Service 平台：通过 API 提交工作、持续交互，并获取可复核的结果与交付物。**

[English](README.md)

AgentScope Service 将能够研究、使用工具和验证结果的 Agent 发布为服务。业务应用可以在 CRM 中生成客户方案，在文档流水线中核验材料，在研发平台中调查故障并生成 PR，也可以提供连续对话的专业助手或定期运行后台任务。

用户在原有产品中发起工作和检查交付；Service 承接 Agent 的运行、协调与交互。应用团队负责业务工具、数据授权、界面和结果验收。完整接入设计见[场景案例](../docs/v2/zh/service/usecases.md)。

## 产品能力

### 通过 API 从任务走向交付

创建或接入 Agent，配置资源并发布 Endpoint。应用使用 Application 凭证调用服务，保存 Invocation ID，读取状态、快照、事件、待办与产物。Job 用于独立后台任务，Conversation 用于支持会话的单 Agent；每个任务或会话轮次都有自己的 Invocation。

Endpoint 定义输入输出契约和发布版本。应用可以通过查询、SSE 或 Webhook 获取变化，参与人工确认，并把经过验证的结果接回业务流程。执行完成与业务验收分别处理。

从 [API 快速开始](../docs/v2/zh/service/quickstart.md)开始，按[发布 Endpoint](../docs/v2/zh/service/api-reference.md)与[统一服务 API](../docs/v2/zh/service/service-api.md)完成接入。Managed 原生会话、文件、子 Agent 和 checkpoint 接口用于相应扩展能力，不能与公共 Invocation 的 ID、认证和事件游标混用。

### 按任务选择执行方式

- **Managed：** 用指令、模型、工具和资源定义专业 Agent，由 Service 运行 AgentScope Harness；工具在配置的 Environment 中执行。平台可部署在自己的基础设施中。
- **External：** 接入已有 AgentScope 或其他框架应用，保留其进程与部署；实现任务执行适配、事件回报和支持的控制操作。
- **Hosted：** 通过 Runtime Host 复用 Codex、Claude Code 等 Coding Agent，按任务管理 provider 进程与工作目录。

先用一个 Agent 验证交付。需要动态分工时组合 Team；需要明确步骤、条件和人工关卡时使用 Workflow。调用方仍通过 Endpoint 使用能力，通过 Invocation 跟踪结果。Team 与 Workflow 当前提供 Job；具体交互以 runtime capabilities 为准。

### 统一管理与观察

Control Plane管理 Agent 目录、绑定、发布、应用身份、任务协调与公共调用。Managed Agent、External Application 和 Runtime Host 通过持久执行契约协作。Console 提供 API 能力的可视化配置、任务查看与运维入口；业务用户可以留在自己的产品中。

Workspace、Environment、Memory 和 Vault 组织执行资源。应用身份与业务终端用户身份需要分别处理；共享 Application 的不同凭证不会自动提供用户级任务隔离。CRM、GitHub、订单系统等连接由接入方配置。

### Managed Session 的运行时归属

公共 Managed Session 流量进入 Session Portal，由 Portal 负责准入、查询和 SSE。Brain 从
RocketMQ LiteTopic 消费 Turn，运行 Harness，直接提交原生日志、checkpoint、挂起和完整终态。
Brain 先保存不可执行的调用提案并取得 Execute 的 Broker ACK，再在 checkpoint 事务中授权工具任务、保存回执，
运行期间不扫描 Execute Outbox 发布消息。Hands 先确认共享提交事实；尚未提交时短暂 Suspend，仅在旧执行被新 epoch
隔离或 Turn 已终结后废弃未提交命令。接管从完整提案恢复，已提交操作继续复用原身份。Hands 在自己的表中保存物理
执行、输出覆盖与不可变结果，只发布工具事件。Brain 提交工具结果，并在当前 pending set 齐备后生成持久恢复意图。
如果等待的任务全部为 `mcp.batch`，continuation loop 从持久记录直接恢复；其他工具操作和输入等待继续使用
Session 级 Resume 消息。两条路径都校验挂起身份并恢复 checkpoint。Brain 在推理前直接获取并固定 MCP 工具描述；
发现不创建 Hands 任务，也不挂起 Turn。真实 MCP 调用继续交由 Hands 异步执行。

挂起释放活动 Harness、Redis token 与 MQ 回调，返回 SDK Suspend，原消息继续保留 FIFO 队头；
只有完整终态持久化后才由 SDK ACK，后续 Turn 才推进。根与子 Session 共享一个 Turn Topic 和回调池，
冻结深度元数据与 16 层上限仍作为业务约束。Redis 保存临时所有权和传递加速数据，
数据库保存去重、执行历史及 Outbox/Inbox 事实；Redis 丢失不能授权重启未知的外部副作用。
工作区 checkpoint 与生命周期协调器位于 Brain，物理操作通过同一 MQ 边界交给 Hands。
Hands 不创建运行日志写入器或 checkpoint 协调器。SSE 断开不会终止执行。
Channel 会话与 Deployment/Cron 适配器通过控制面和 Portal 提交原生 Turn。重试保留消息 ID 或计划触发时间；回复按该 Turn 的公开事件重放，适配器不再投递旧 Dataplane 事件。原生 Session 在创建时冻结 Environment 和定义，不再额外创建控制面的运行会话。

Managed AgentTask 派发先将不可变 Attempt 和输入写入持久 Outbox，再受理原生 Turn。传输重试复用同一 Attempt、调用身份和请求键。取消通过原生 Input 受理；控制面查询原生执行事实，不再依据自身心跳超时重排 Managed 工作。其他后端保留各自执行路径。AgentTask 的完整接入与故障验收仍在进行。[Managed 运行时改造说明](docs/managed-runtime-dataplane.md)包含数据流程、存储条件及迁移边界。

组件职责与完整时序见 [Redis 与 Turn 生命周期](docs/redis-usage-and-turn-lifecycle.md)和 [RocketMQ Topic 与 Turn 生命周期](docs/rocketmq-topics-and-turn-lifecycle.md)。

## 整体架构

当前源码部署五个组件：Go Control Plane、Gateway、Session Portal、Brain Dataplane 和平台 Hands。
单个 `service-controlplane` 二进制和镜像在 8081 端口承载管理 API、Workflow / Team 编排、Hosted
协调、Cron、渠道接入、消息发送和 Webhook 投递，不再独立部署 scheduler 或 orchestration。

所有 control 副本运行有界 worker。PostgreSQL 负责消息和任务去重、领取、Stream 租约、重试与
fence 校验，无需粘性会话或固定单副本。Helm 的 `control.executionSlots`、`control.workerBatch`、
`control.channelSlots`、`control.webhookSlots` 设置单副本并发。保留 `cp`、`rt` schema 及现有签名、
加密密钥；新增 `0007_integrated_scheduler` 产品迁移创建共享任务和 Stream 租约表。

升级时先暂停渠道入口并排空待回复消息，停止旧 Java Scheduler 和 Go orchestration 部署，执行
schema job 并重新授权，再启动合并后的 control 副本。Compose 使用 `--remove-orphans` 清理旧容器；
Helm 升级前先将旧 Deployment 缩容为 0。切换后再停用旧 runtime / scheduler 数据库凭据。
该部署结构需要重新构建源码镜像，已发布的 BETA1 镜像保持原样。

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
| Control Plane（`service-controlplane`） | 资源定义、发布、身份与配置准入、Workflow/Team 编排、Hosted 协调、Cron、渠道和投递 | Harness 执行 |
| Session Portal | 公共 Session/Turn 准入、事件、查询和 SSE | 原生执行状态 |
| Dataplane | Harness 推理、原生日志/checkpoint/终态提交、工具事件 Inbox 与恢复校验 | 资源 CRUD、物理工具执行 |
| Hands | Sandbox/MCP 执行事实、输出/结果 Outbox、可靠 Resume 与 Sandbox 资源 | Turn 准入、原生运行状态提交 |


## Agent 如何接入

业务开发者从 [API 快速开始](../docs/v2/zh/service/quickstart.md)验证调用；平台团队准备模型、执行环境、身份与发布资源。已有 Agent 应用按 [External 接入指南](../docs/v2/zh/service/register-agentscope-agent.md)实现执行适配，再发布为服务。

## 发布部署与文档

发布版使用已构建镜像，参见 [Docker / Helm 部署](deploy/README.md) 和 [中文发布维护手册](release/README_zh.md)。完整用户文档位于 [AgentScope Service 专区](../docs/v2/zh/service/index.md)。以下快速开始从源码启动免鉴权本地开发模式；发布安装见生产指南。

## Docker Compose 快速开始

从当前源码构建免鉴权本地开发模式。准备 Docker Compose v2 和模型凭据，在仓库根目录运行：

```bash
cd agentscope-service
export DASHSCOPE_API_KEY="YOUR_DASHSCOPE_API_KEY"
./deploy/init-env.sh 2.1.0-local agentscope-local
# 在 deploy/.env 填写可达的 Redis URI / RocketMQ Cluster Proxy 地址，
# 并先初始化 Turn 深度分道及另三个 Lite 父 Topic 和 FIFO / Shared / wildcard 消费组。
export BUILDER_LOCAL_DEV=true
docker compose --env-file deploy/.env up -d --build --wait --wait-timeout 600
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

手工调试 Session 的指令、snapshot 和 SSE，可以使用[独立 Session Playground](tests/session-playground/README.md)。它在本地 18082 端口运行，默认代理 18081 网关；控制面资源仍通过终端创建。

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

开发栈默认启用 `BUILDER_LOCAL_DEV=true`，使用单一开发身份与开发密钥，无需登录。完整重建默认重置可丢弃的 `cp`、`rt`、`portal`、`dp` schema；
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
docker compose --env-file agentscope-service/deploy/.env -f agentscope-service/docker-compose.yml up --build
```

### 服务端口

| 服务 | 端口 | 暴露方式 |
| --- | ---: | --- |
| Gateway | 18080 | 对外（Docker Compose 容器内仍为 8080） |
| `service-controlplane` | 8081 | 内部 |
| Dataplane | 8082 | 内部 |
| Session Portal | 8084 | 内部 |
| Hands | 8085 | 内部 |
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
| `BUILDER_REBUILD=1` | 重建 Monorepo/service-controlplane，并默认重建可丢弃的本地 `cp`/`rt`/`portal`/`dp` schema |
| `BUILDER_RESET_DB=0` | 完整重建二进制时保留已经是 v4 的本地数据库 |
| `AGENTSCOPE_SMOKE_TEST=1` | 健康检查和 SQL schema 校验通过后自动运行 `scripts/smoke.sh` |

生产部署必须替换全部开发密钥，并使用持久化 PostgreSQL。


## Roadmap

后续演进围绕业务应用的接入与交付：完善终端用户授权和文件输入契约，让自动触发复用公开调用链路，并持续验证长任务、复杂编排和费用治理。

企业级云产品亦可关注阿里云 [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview)、[Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html)。

## 文档

- [Service 定位与介绍](../docs/v2/zh/service/index.md)
- [场景案例与接入设计](../docs/v2/zh/service/usecases.md)
- [API 快速开始](../docs/v2/zh/service/quickstart.md)
- [统一服务 API](../docs/v2/zh/service/service-api.md)
- [Managed 原生会话与任务](../docs/v2/zh/service/session-event-log.md)
- [API 参考](../docs/v2/zh/service/api-reference.md)


进程测试使用专用 PostgreSQL 数据库，并设置本地 `AGENTSCOPE_TEST_REDIS_URI` 和
`AGENTSCOPE_TEST_MQ_ENDPOINTS`。Host / Hosted / Workflow / Team 测试传入 `--fixture-dsn`；
native 测试可通过 `AGENTSCOPE_TEST_POSTGRES_DSN` 指定数据库。完整部署进程测试使用
`AGENTSCOPE_TEST_MQ_BROKER_CONTAINER` 初始化独立 Topic / 消费组，避免消费其他测试的消息。
Team 的 Java SDK 验证前执行
`mvn -T1 -pl agentscope-service/service-client dependency:build-classpath -Dmdep.outputFile=target/client-classpath.txt`。

重构开发中的 Go 运行角色使用 PostgreSQL 时，还需配置至少 32 字节且副本间稳定一致的
`BUILDER_VAULT_MASTER_KEY`，用于加密 Portal 配置、命令和事实 payload。
`tests/orchestration_runtime_portal_smoke.py --runtime-url <本地运行角色地址>`
已经验证真实 Portal/Go/PostgreSQL 的配置与 Inbox 投递及重启回执。
`tests/orchestration_runtime_native_smoke.py` 已跑通 Hosted Job 与 Conversation 的原生执行、
Portal 重启、最终用量、停止证明与 SSE 续传。默认使用认证 Host 替身；增加
`--exercise-daemon` 可运行真实 Host daemon 和本地 fake Codex app-server CLI，
`--daemon-cancel` 验证停止 provider 后完成取消。这两种模式还验证协作 MCP、文本及工具
增量、用量去重、快照和持久 journal 投递，不调用外部模型。
`--daemon-approve` 和 `--daemon-deny` 验证人工工具确认、跨租约周期等待、
Portal 重启，以及公开 Turn actions 的应用回执。
Go 运行角色通过 `SESSION_PORTAL_URL` 或 `--session-portal-url`
访问 Portal，`--execution-slots` 控制本实例并行推进 Turn 的数量，默认 32、范围 1..128。
公开 Session 创建已接通 Managed、Hosted、Workflow 和 Team；External 及混合后端仍按接入进度保留门禁。
增加 `--public-create` 可用隔离配置 HTTP fixture 验证 Portal 的 Hosted 创建与重复请求。

`tests/orchestration_runtime_hosted_smoke.py --fixture-dsn <专用本地测试DSN>`
启动真实 Portal、Go 运行角色与 Host daemon，使用假 Codex app-server；增加
`--approve`、`--deny` 或 `--cancel` 检查交互。运行前创建专用 PostgreSQL schema，
不使用外部供应商凭据。Hosted 任务与报告凭据有效期为 15 分钟，通过 Portal 使用
当前 Host 身份及物理 lease 证明刷新。每个 Attempt 的本地 HTTP 转发提供稳定本地
凭据，将 MCP 和任务 CLI 请求换成最新带 fence 的 token。Host 入驻凭据的到期与
轮换仍独立管理。Gateway 将新 Session、Host 机器协议、协作 MCP、带任务 token
的 CLI 和 ToolTask 路径转至 `BUILDER_SESSION_PORTAL_URL`，默认
`http://localhost:8084`；Compose / Helm 已将 Go 执行协议接入合并后的 control 组件。


`tests/orchestration_runtime_workflow_smoke.py --fixture-dsn <专用本地测试DSN>`
验证公开 Workflow 创建、审批、Portal 与 Go 运行角色重启、应用回执、快照和 SSE 续传，
实际使用 PostgreSQL。增加 `--deny`、`--parallel` 或 `--signal` 验证其他根交互流程。
配置准入源为隔离 HTTP fixture，执行前停止资源准备进程，再由合并后的 control 二进制执行。使用专用 schema，并顺序运行这些
进程场景。确认和信号（包括嵌套 Workflow 子图）通过公开 Turn `/actions` 答复；增加
`--nested` 验证两层使用重复节点名的子图。人工等待分支不会暂停独立的可执行分支。
Agent 发出信号、独立 Issue 子 Run 及其余 Team 控制仍在实施。

`tests/orchestration_runtime_team_smoke.py --fixture-dsn <专用本地测试DSN>`
验证公开 Hosted Team Job 创建、带 fence 的 MCP 结论、Portal 重启、实际停止、快照与
SSE 续传；增加 `--cancel` 验证取消。使用认证 Host 替身，不运行 provider CLI；配置源
为 fixture，执行前停止资源准备进程，再由合并后的 control 二进制执行。原生内存和 PostgreSQL 检查还覆盖动态并行节点，所有
Worker 的实际停止证明齐备后才结束公开 Turn。子任务委派及其余图控制仍在接入。

## 2026-10-21 运行时升级

本轮同步持久化 MCP 批次、工具提交/结果提交门、统一 Turn FIFO 路由、可恢复确认、副本安全的 outbox 发布，以及 Session 事件协议。更新服务前先运行独立数据库迁移 Job；迁移账本继续保留 `portal`、`dataplane`、`managed` 组件 ID 和已有 SQL 历史。

默认继续使用自建部署。额外的数据/MQ Secret 覆盖项默认为空，需显式启用。保留 `BUILDER_*` 配置、AgentScope 包名、组件名、镜像名与发布渠道。参见[恢复说明](docs/mcp-execution-recovery.md)、[API 快速入门](docs/api-quickstart.md)和[同步记录](docs/master-sync-20261021.md)。
