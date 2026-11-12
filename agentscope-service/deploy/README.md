# Deploy Managed MQ / Redis services

Build the five Service images from one source version: Control, Gateway, Agent Service,
Agent Harness Runtime Dataplane and platform Tool Worker. Orchestration and Scheduler run inside the Go Control Plane.
Platform Tool Worker is a required HTTP/MQ service on internal port 8085. Customer self_hosted
Environment Workers connect through Gateway; they are separate processes, not the platform
Tool Worker deployment. Other backend execution paths are outside this Managed refactoring.

The current source deploys five components: the Go Control Plane, Gateway, Agent Service,
Agent Harness Runtime Dataplane and platform Tool Worker. One `control-plane` binary/image owns management,
Workflow/Team orchestration, Hosted coordination, Cron, channels, outbound messages and webhook
sending on port 8081. There are no separate scheduler or orchestration deployments.

Every control replica runs bounded workers. PostgreSQL owns message/job deduplication, claims,
stream leases, retries and fencing; no sticky sessions or single control replica are required.
`control-plane.executionSlots`, `control-plane.workerBatch`, `control-plane.channelSlots` and `control-plane.webhookSlots`
set per-replica concurrency in Helm. Keep `cp` and `rt` schemas and signing/encryption keys stable.
The new `0007_integrated_scheduler` product migration adds the shared jobs and stream leases.

For an upgrade, drain incoming channel traffic and outstanding replies, stop the old Java
Scheduler and Go orchestration deployments, run the schema jobs and reapply grants, then start
the combined control replicas. Remove old Compose containers with `--remove-orphans`; scale down
old Helm deployments before upgrading. Retire unused runtime/scheduler credentials only after
cutover. This source layout requires newly built images; published BETA1 images are unchanged.

PostgreSQL, Redis and RocketMQ are operated separately. RocketMQ must support LitePushConsumer (verified here on 5.5.1 with a standalone Cluster Proxy and client 5.2.2).
Do not use the embedded Local Proxy or classic FIFO as a fallback. See Apache's
[Cluster Proxy deployment guide](https://rocketmq.apache.org/docs/deploymentOperations/01deploy/).
Addresses must be reachable from containers; container localhost is not the dependency host.

Enable Proxy automatic renewal for active execution callbacks with `enableProxyAutoRenew=true`
and `renewMaxTimeMillis=10800000`. The SDK owns MQ renewal and ACK. Agent Harness Runtime directly commits its
native journal and complete handoff before releasing execution ownership. A suspended Turn
returns SDK Suspend and releases its callback thread; independent Resume callbacks continue
that Turn. Only durable terminal state lets the original message return SUCCESS.
Harness capacity is independently bounded; capacity pressure returns Suspend without a local
Turn queue. No callback-completion mailbox or callback-hold setting is required.

For this ownership change, drain existing Managed Turns and workspace/lifecycle operations before
stopping old replicas. Apply the additive `managed-tool-worker.sql` migration and reapply
`postgres-roles.sh` before starting the new Agent Harness Runtime/Tool Worker together. Existing Sandbox identities are
copied into Tool Worker bindings; unresolved old tool executions must be reconciled, never relaunched
with a new identity. Agent Harness Runtime consumes tool events using `ma_brain_tool_events_v3`.
Initialize this group before starting Agent Harness Runtime; no legacy group alias or offset migration is provided.
Tool Worker can access only `ma.hands_*`, the schema ledger and append-only private
objects; Agent Harness Runtime/Agent Service cannot access the physical `ma.hands_*` tables. No live migration is run by
ordinary service startup.

Prepare the five Lite channel families and FIFO / Shared / wildcard groups before startup:

| Parent Topic | Group | Consumer |
| --- | --- | --- |
| ma_turns_v3 | ma_brain_v3 | Agent Harness Runtime |
| ma_tool_events_v3 | ma_brain_tool_events_v3 | Agent Harness Runtime |
| ma_tool_execute_v1 | ma_hands_execute_v1 | Tool Worker |
| ma_tool_control_v1 | ma_hands_control_v1 | Tool Worker |
| ma_turn_resumes_v3 | ma_brain_resumes_v3 | Agent Harness Runtime |

Agent Harness Runtime publishes durable tool Execute/Control intents. Tool Worker publishes only tool events.
Runtime atomically applies results and stages a Resume when all pending conditions are satisfied; its publisher
retries that durable intent, and its independent Resume consumer validates and restores the suspension.
There are five static parent Topic/group bindings. Turns and Runtime-owned Resumes use Session-scoped LiteTopics;
physical tool events and commands use operation-scoped LiteTopics. Control has an independent
consumer pool, so an occupied execution lane cannot prevent cancellation.

Configure the Broker's LMQ/multi-dispatch
support. The provided initialization script creates the Proxy heartbeat Topic, eight queues
per parent, fourteen-day idle retention and groups bound to exactly one parent with FIFO, Shared and wildcard
subscriptions and minimum-offset initialization. Queue count, retention, names and groups
can be configured through its environment variables. Run it on a RocketMQ administration
host/container using its installation and private NameServer address:

```bash
ROCKETMQ_HOME=/opt/rocketmq MANAGED_MQ_NAMESRV=namesrv:9876 \
  MANAGED_MQ_CLUSTER=YOUR_CLUSTER sh managed-mq-init.sh
./init-env.sh CANDIDATE_VERSION REGISTRY/NAMESPACE
# Set MANAGED_AGENTS_RUNTIME_REDIS_URI and MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS in private .env.
# Add provider/Environment credentials as needed.
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

Compose runs four independent migration jobs: Control (`cp` + `rt`), Agent Service (`portal`),
Dataplane (`dp`) and Managed (`ma`). After every job succeeds, schema-grants provisions
four distinct business accounts. Agent Service, Agent Harness Runtime and Tool Worker access the shared ma schema;
ordinary service accounts cannot create tables or modify migration ledgers. Service startup
validates the ma checksum and version without executing DDL. A pre-existing, unversioned ma
schema is rejected: this unpublished Service refactoring uses a fresh data space, without
silently adopting or converting legacy Managed state. Initialization preserves other data.

`init-env.sh` creates private configuration and preserves existing files. If a private env file
already exists, operators must add TOOL_WORKER_DB_PASSWORD and the two infrastructure addresses
before starting a newly built stack. Keep database credentials, JWT/internal secrets and the
Vault key stable. Only Gateway exposes a host port, on loopback by default.

Agent Harness Runtime consumes Turn, tool event and Resume Topics and owns reliable Resume publication.
Tool Worker consumes Execute/Control and repairs tool event/result publication. A suspended Turn releases
its callback after a persisted checkpoint while retaining the Session's logical ordering position. Dataplane
starts MQ consumers by default; the old DB dispatch loop and its enable/renewal settings are removed.

Agent Service, Agent Harness Runtime and Tool Worker mount the same private managed-blobs volume. Batches larger than
128 KiB use hashed references there; it is not a public Artifact volume. Workspaces and
Artifacts retain their existing shared volumes. Platform Tool Worker requires no fixed Environment
ID, platform Worker token or per-replica legacy journal directory. Its Sandbox state and
stable tool execution handles are stored in ma and the selected Sandbox.

## Source development and Kubernetes

Source Compose extends the same deployment contract. From managed-agents:

```bash
./deploy/init-env.sh CANDIDATE_VERSION REGISTRY/NAMESPACE
# Configure reachable Redis / Cluster Proxy addresses in deploy/.env.
MANAGED_AGENTS_LOCAL_DEV=true docker compose --env-file deploy/.env up -d --build --wait
```

Native scripts/dev-up.sh also requires the two infrastructure environment variables, runs
all four migration jobs and starts platform Tool Worker on 8085. It never starts a fake dependency.
Use scripts/dev-down.sh to stop those processes; external Redis / MQ remain operated separately.
For explicitly isolated real Docker fixtures, use tests/managed-mq-redis/README.md.

The Helm Chart operates external PostgreSQL / Redis / RocketMQ and requires RWX storage
for workspaces, artifacts and private runtime blobs. Follow the
[Chart README](../helm/managed-agents/README.md): initialize with a separate migration
Secret, apply business grants, then install ordinary service Deployments. Scale Agent Service,
Agent Harness Runtime and Tool Worker independently; Gateway routes the customer Worker API to platform Tool Worker.
This repository change does not redeploy an existing stack.

## 中文

当前 Managed 方案使用独立 Agent Service、Agent Harness Runtime、平台 Tool Worker，加上已有 Control、Gateway、
Control 内含 Scheduler 和 Go 编排运行时，共五个 Service 镜像。平台 Tool Worker 是必选 HTTP/MQ 服务，内部端口
8085；self_hosted Environment Worker 是客户侧独立进程，经 Gateway 接入，不替代平台 Tool Worker。

Redis 与 RocketMQ 由运维提供。已验证 RocketMQ 5.5.1、独立 Cluster Proxy 和客户端 5.2.2；
先用 managed-mq-init.sh 创建四个 Lite 父 Topic 与 FIFO / Shared / wildcard 消费组，再在私有 .env 填写可从
容器访问的 Redis URI 和 Proxy 地址。生产不自动回退到 Fake、经典 FIFO 或 Local Proxy。

Compose / Helm 先执行四个独立初始化任务（cp/rt、portal、dp、ma），再授权和启动普通服务。
Agent Service、Agent Harness Runtime、Tool Worker 共用 ma；普通账号不建表、不写迁移版本表。初始化拒绝接管已有无版本
ma 数据。本轮直接使用新运行数据空间，不提供旧 Managed 数据兼容转换，也不自动删除旧数据。

Agent Service、Agent Harness Runtime、Tool Worker 共用私有 managed-blobs 卷；它不对外提供 Artifact 下载。平台 Tool Worker
无需固定 Environment ID 或旧 Worker 记录目录。源码 Compose 与本机 dev-up.sh 使用同一协议，
都必须先配置真实 Redis / MQ；故障验收参考 tests/managed-mq-redis/README.md。修改源码和部署
模板不会自动升级现有安装。

以下历史本地安装记录保留原证据；其旧 Worker / 运行协议命令不适用于新建 MQ/Redis 部署。

## Historical local stack (2026-10-12, earlier runtime protocol)

The local build from `/Users/ken/agentcore/agentcore-managed-agents` uses project
`managed-agents-portal-local`, Gateway `http://127.0.0.1:18081`, and seven
`local/agentscope/as-*:2.1.0-local-20261012` arm64 images. The previous installation
on port 18080 is stopped, with its volumes preserved. Java Service modules and their dependencies were
installed into the local Maven repository at revision `2.1.0-SNAPSHOT`.

This stack uses `MANAGED_AGENTS_LOCAL_DEV=true`: the Console initializes the fixed
`local-developer` identity automatically, and the Agent Service/Dataplane use the matching
development mode. The Go runtime also uses `--local-dev=true`. Private
`deploy/local-portal.env` holds stable configuration and
`deploy/local-portal.credentials.env` retains the bootstrap login for later testing
with dev mode disabled. Both files are
ignored by Git and mode 600. `deploy/local-portal.local.yaml` applies local memory
limits and configures the Tool Worker service for a local Environment. The installation
includes **Local Managed Test** (`dashscope:qwen-plus`) and **Local Tool Worker Docker**;
Tool Worker executes inside its Docker container and stores its journal on a named
volume. It uses four execution slots. E2B remains available through a separately
configured cloud Environment using ACS native E2B. The Environment selects execution; an Environment-key Worker cannot claim cloud tasks.

Run from the repository root:

```bash
managed-agents/scripts/local-stack.sh ps
managed-agents/scripts/local-stack.sh logs --tail 100 portal data hands
managed-agents/scripts/local-stack.sh --profile hands up -d --wait
managed-agents/scripts/local-stack.sh stop
```

Edit the private env file to change model credentials; run the `up` command again
to recreate affected containers. Keep database passwords and signing/encryption
keys stable. `stop` preserves all volumes. Initialization uses the same separate
migration jobs and DML accounts as the deployment configuration. The manifest and
prepared resource IDs are in `managed-agents/target/local-deployment/`; these
are build output, so retain the private env files when cleaning Maven targets.

Fast local Java images use `docker/Dockerfile.plane` with a `JAR_FILE` build
argument. The Go image uses the `control` target of `docker/Dockerfile.go-plane`, after compiling `control-plane` with `CGO_ENABLED=0 GOOS=linux`
into `control-plane/target/local-linux/` and building the console. Use the
normal source Dockerfiles for release builds. Container health, login, resources,
Managed metadata, route isolation, development identity and Tool Worker heartbeat were checked; no external
model or E2B invocation was made as part of this deployment.

The Session protocol upgrade on 2026-10-12 rebuilt and restarted Gateway, Agent Service
and Dataplane, applied Agent Service migration `V0007__session_api.sql`, and granted the
Agent Service business account access to its three new tables. `/v1/sessions` now reaches
Agent Service and returns JSON. Check both HTTP status and Content-Type: the previous
Gateway image returned the Console HTML fallback with HTTP 200 for this path.
Session creation, retrieval, event history and structured validation errors passed
on this stack without a model call. The database backup and prepared Session are
under `target/local-deployment/session-api-upgrade-20261012/`; the three previous
images retain the `pre-session-api-20261012` tag.

For the complete Managed curl test sequence, use
[`scripts/managed-curl-test.sh`](../scripts/managed-curl-test.sh):

```bash
bash managed-agents/scripts/managed-curl-test.sh --prepare-only
bash managed-agents/scripts/managed-curl-test.sh --run
```

The first command validates resource creation, Session readiness, budget revision
checks and file/artifact round trips without inference. `--run` creates its own
resources and calls the configured model for multi-turn context, Tool Worker tools,
inject, checkpoint restoration and fork, then checks event export and lifecycle.
It uses the existing **Local Tool Worker Docker** Environment, so tools have an active
Worker. Override `MODEL`, `BASE` or `ENV_ID` when needed. Each run preserves IDs
and responses under `target/curl-tests/`, with `latest.env` for subsequent manual
commands. Resource-only verification passed on the actual dev stack; all stages
also passed against a local HTTP protocol fixture. The fixture does not qualify
real model behavior or native checkpoint recovery.

## 中文

使用同一候选版本的五个新架构镜像。Compose 先执行 cp/rt、portal、dp 初始化任务，
全部成功后再以独立业务账号授权，最后启动六个服务组件。业务账号没有建表权限，
迁移版本表只读。初次使用新数据空间；旧 .env 需补上各组件数据库密码和 Tool Worker token。
init-env.sh 保留已有配置，不覆盖密钥或管理员密码。

创建平台托管 Environment，填写其 ID 后启用 hands profile；单个 Compose Worker
使用独立持久化记录卷，多副本使用 Helm StatefulSet。Sandbox 默认空闲 5 分钟回收，
文件归档保留。只有 Gateway 对外开放；数据库、归档、Worker 记录和原密钥一起备份。
Kubernetes 安装按 Chart README 先初始化、再授权、再部署，Workspace/Artifact 使用
RWX。源码启动可直接构建上一级 Compose，免鉴权开发显式设置 MANAGED_AGENTS_LOCAL_DEV=true。

本次独立本地部署使用 `managed-agents-portal-local` 项目与 18081 端口，原 18080 服务已停止，
数据卷保留。新栈已开启dev模式：Console自动取得local-developer身份，AgentService和数据面
使用一致的MANAGED_AGENTS_LOCAL_DEV=true，Go运行角色使用--local-dev=true，不需要手动登录。
七个本地 arm64 镜像标签为 `2.1.0-local-20261012`；Java Service 与依赖已安装至本地
Maven 仓库，版本仍为 `2.1.0-SNAPSHOT`。以后关闭dev测试鉴权时可使用私有local-portal.credentials.env，
完整配置见 `local-portal.env`，均已忽略且权限为600。已准备 Local Managed Test 与
Local Tool Worker Docker；Worker 在自身容器中执行工具，4并发，不使用真实E2B。
仓库根目录通过 `scripts/local-stack.sh` 的完整路径执行上述查询、日志、启动和停止
命令；改模型凭据后重新执行up，保持数据库密码与签名/加密密钥稳定。stop保留卷。
部署清单和资源ID在 `target/local-deployment/`；已验证容器健康、登录、资源、Managed
元数据和ToolWorker心跳，真实模型/E2B调用留待本地测试。

2026-10-12 的 Session 协议更新已重新构建并启动 Gateway、Agent Service、Dataplane，执行
Agent Service 的 `V0007__session_api.sql` 迁移，并为 Agent Service 业务账号授予三个新表的权限。
`/v1/sessions` 现已转发到 Agent Service 并返回 JSON。检查接口时同时核对 HTTP 状态与
Content-Type；旧 Gateway 对该路径返回的是 HTTP 200 的控制台 HTML 页面。
已实际验证 Session 创建、查询、事件历史和结构化校验错误，未调用模型。
数据库备份与准备好的 Session 位于 `target/local-deployment/session-api-upgrade-20261012/`，
三个旧镜像保留为 `pre-session-api-20261012` 标签。

完整curl测试脚本为scripts/managed-curl-test.sh。--prepare-only已在实际dev实例验证
资源、预算版本和文件/产物往返，不调用模型；--run会新建测试资源并实际调用模型，
依次跑多轮上下文、Tool Worker、inject、checkpoint恢复、fork、事件导出及归档恢复。
它复用已在线的Local Tool Worker Docker，可通过MODEL/BASE/ENV_ID覆盖配置；每轮响应与
ID保存到target/curl-tests，latest.env供后续手工命令使用。完整脚本的本地HTTP协议
fixture通过，不代表真实模型行为和原生恢复验收。

## Environment execution modes upgrade

Build all changed planes and Tool Worker from the same source. Back up the database, drain old Workers, run the existing control/data migration jobs, then restart the updated services. The new product migration renames previous sandbox to cloud and previous self_hosted local execution to local; encrypted runtime snapshots are normalized on read. The local override enables Local for development. This does not automatically redeploy an existing stack.

Cloud creates ACS instances using native E2B routing; configure domain, templateId and a tenant API key. self_hosted connects a user-owned existing instance and requires explicit provider credentials plus config.sandboxId or Session managed-agents.sandbox_id. local runs in Tool Worker, not Dataplane. Worker authentication scopes access; it does not select execution mode. See the bilingual [Environment guide](../../docs/v2/en/service/environments.md) / [环境操作指南](../../docs/v2/zh/service/environments.md) for requests and long-task interruption checks.

升级时先备份数据库并停止旧 Worker，运行控制面和数据面的现有迁移任务，再启动同版本组件。cloud 管理 ACS 实例，self_hosted 连接用户已有实例且不自动删除或重建，local 统一在 Tool Worker 执行。当前本地部署覆盖配置启用 Local；修改源码和配置不会自动升级正在运行的容器。

Runtime-owned Resume upgrades retain the existing SQL migrations and retired `hands_resume_outbox` /
`tool_resume_inbox` tables. New Workers neither read nor write the old completion outbox and need only
tool-event publication permission. Runtime accepts old in-flight Worker completion payloads only to
validate durable results and enqueue a canonical Runtime Resume; those payloads do not run inference.
Upgrade Runtime consumers/publishers before removing the Worker publisher so that ordinary tool results
can reliably create and publish recovery intents throughout the rollout. This change retains the Resume
Topic; local direct recovery and tool-call batching are not enabled.

### Consolidated Turn Topic upgrade

Root and descendant Sessions now publish to `ma_turns_v3` and share the `ma_brain_v3` consumer
pool, bounded by `managed-agents.runtime.turn-callbacks`. The frozen business depth (maximum 16)
remains in `ma_depth` and is validated against Session configuration; it no longer selects a Topic,
group or callback pool. The separate child callback setting has been removed from deployment files.

This requires a coordinated cutover, not a mixed-version rollout: pause new user/root Turn admission
while keeping existing child admission, input/cancellation and recovery available. Let the old
stack finish all accepted Turns (including descendants, tools and Resumes), and confirm all old
root/depth queues and active executions are drained. Then stop old consumers, initialize the five
current bindings, start the new stack and reopen admission. Old root consumers reject nonzero depth
messages on the shared Topic, so they must not run beside new producers. The initializer does not
delete or forward old `_d1` through `_d16` Topics/groups. Retain their data for audit/retention and
remove them separately only after confirming they contain no required work.
