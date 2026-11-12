# Managed MQ / Redis Helm deployment

The Chart installs five internal components: Control, Gateway, Agent Service, Agent Harness Runtime
Dataplane and platform Tool Worker. Orchestration and Scheduler run inside the Go Control Plane. Build every
Service image at the same source version. PostgreSQL, Redis, RocketMQ and optional shared user-file storage are
operated separately. This contract applies to newly built images; it does not upgrade running
legacy Service processes or convert Managed data.

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

Prepare cp, rt, portal, dp and ma schemas. Use the separate migration Secret from
[deployment inputs](../../deploy/kubernetes-migration.env.example), and ordinary credentials
from [the workload template](../../deploy/kubernetes.env.example). Redis URI and reachable
RocketMQ Cluster Proxy endpoints belong in workload Secrets. Per-component existingSecret
values can separate database accounts; Tool Worker uses MANAGED_AGENTS_TOOL_WORKER_DB_* rather than MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_*.

Initialize the five Lite channel families (21 parent Topics/groups) with
[managed-mq-init.sh](../../deploy/managed-mq-init.sh). The default names under managed.mq are
ma_turns_v3 / ma_brain_v3,
ma_tool_events_v3 / ma_brain_tool_events_v3, ma_turn_resumes_v3 / ma_brain_resumes_v3,
ma_tool_execute_v1 / ma_hands_execute_v1 and ma_tool_control_v1 / ma_hands_control_v1.
Agent Harness Runtime consumes Turns/Resumes/tool events and commits native execution facts; Tool Worker consumes
Execute/Control and publishes tool facts plus reliable Resume notifications. Agent Harness Runtime uses the
`ma_brain_tool_events_v3` group; initialize it before startup. Initialize a fresh Managed schema
and apply database roles before starting the components; see the deployment notes.
Suspension returns SDK Suspend without retaining a callback thread; the original message remains
the FIFO head until terminal state. No callback completion mailbox is used.
MQ consumers start by default; an explicit false consumer setting only drains MQ and
does not activate another execution path. The script sets Shared bindings, minimum-offset initialization and idle retention.
RocketMQ 5.5.1 with standalone Cluster Proxy is the tested server configuration. Match
managed.mq.ssl to the Proxy's TLS configuration; these images never auto-select a fake.

The Managed schema baseline directly creates current Agent Harness Runtime and Tool Worker tables. It does not create
retired runtime receipt, writer-seal, callback-mailbox or shared Sandbox-binding tables, and
provides no backfill or conversion from previous Managed schemas. Use a fresh Managed data space;
existing migration ledgers with different checksums or versions are rejected.

Run schema preparation first. Four owner-only Jobs initialize Control (cp + rt), Agent Service,
Dataplane and Managed (ma), without service pods or PVCs. The Managed job rejects unversioned
existing ma tables. Apply postgres-roles.sh with the four matching business passwords, then
install workloads. Ordinary accounts only validate migration ledgers at startup.

```bash
# Pre-create service-credentials and service-migration from private cloud env files.
python3 ../../deploy/managed-cloud-data-config.py --namespace managed-agents \
  --workload-secret service-credentials --migration-secret service-migration --apply
helm upgrade --install service-schema ./managed-agents \
  --namespace managed-agents --create-namespace \
  --set imageRepository=REGISTRY/NAMESPACE --set imageTag=CANDIDATE_VERSION \
  --set schema.only=true --set schema.existingSecret=service-migration \
  --wait --timeout 10m
# Apply postgres-roles.sh with owner PG* variables and four business passwords.
helm upgrade --install service ./managed-agents \
  --namespace managed-agents \
  --set imageRepository=REGISTRY/NAMESPACE --set imageTag=CANDIDATE_VERSION \
  --set existingSecret=service-credentials --set schema.existingSecret=service-migration \
  -f production-values.yaml --wait --timeout 10m
```

Regular installs repeat the idempotent migration Jobs. schema.enabled=false is only for
operator-managed initialization. All five components default to two replicas with RollingUpdate;
platform Tool Worker listens on internal 8085 and Gateway forwards /v1/tool-workers/... APIs there.
It needs no environmentID, optional enable flag or per-pod journal directory.

For a single-user POC, set `localDev: true` to enable `MANAGED_AGENTS_LOCAL_DEV` consistently
across the five components. Public API calls use the `local-developer` identity without login
or a user token. The default is `localDev: false`.

```yaml
agent-service:
  replicas: 2
agent-harness-runtime:
  replicas: 2
  concurrency: 32
  ddlAuto: validate
tool-worker:
  replicas: 2
  toolConcurrency: 700 # short blocking transitions, not outstanding tools
  toolCallbacks: 16
  toolRenewalThreads: 16
  mcpWorkerThreads: 700
  mcpCachedClients: 1024 # retained idle clients; active calls are never evicted
  databasePoolSize: 16
  existingSecret: tool-worker-credentials
userFilesEnabled: false
control-plane: {replicas: 2}
gateway: {replicas: 2}
```

Brain embeds Session Store; Agent Service hosts asynchronous projections against TableStore or an independent query PostgreSQL database. Normal projection delivery does not use an execution database pool. Configure required `queryStore.existingSecret` and `queryStore.provider`; `queryStore.initialize=true` supports either backend.
Checkpoint/state, native-log segments and large MQ payloads use the configured `objectStore` backend.
Configure S3/OSS endpoint, bucket and credentials in business Secrets, or set provider=filesystem
with a durable shared RWX `objectStore.existingClaim`. PG retains execution metadata and platform facts.
Public user-file storage remains governed by userFilesEnabled; private recovery storage is always required.
Use a fresh target schema; no legacy object import or compatibility mode is provided. See
[Session Store configuration](../../docs/session-store.en.md). No template change deploys existing installations.
Include all component database pools in the connection budget. Configure publicURL, TLS,
imagePullSecrets and SSE ingress timeouts according to the installation.

Back up the database (including private objects), enabled user-file volumes, Vault key and service secrets
together. Restore persistent protocol facts before accepting new execution. Redis hints can
be rebuilt from those facts; unknown external tool effects cannot be safely replayed merely
because Redis or a Worker restarted. Full deployment and recovery acceptance remain separate
from Helm rendering or component unit tests.

## 中文

Chart 部署五个 Service 组件，平台 Tool Worker 为必选 HTTP/MQ Deployment，内部端口 8085。
五个组件均默认部署 2 个 Pod，使用 RollingUpdate 更新；各组件的 replicas 可独立调高。
单用户 POC 可设置 `localDev: true`，为五个组件统一开启 `MANAGED_AGENTS_LOCAL_DEV`。
公开 API 调用使用 `local-developer` 身份，无需登录或用户 Token；默认值仍为 `localDev: false`。
PostgreSQL、Redis、RocketMQ 和可选的用户文件共享存储由运维提供；普通组件 Secret 包含 Redis URI 与
可达的 Cluster Proxy 地址，Tool Worker 使用独立 MANAGED_AGENTS_TOOL_WORKER_DB_* 账号。

先创建三个 Lite 父 Topic 与 Shared 消费组，再运行 schema.only 的四个初始化 Job
（cp/rt、portal、dp、ma）；授权四个业务账号后启动服务。ma 初始化拒绝接管已有无版本表，
普通账号只读校验版本，不在副本启动时建表。迁移 Secret 与业务 Secret 分离。

Brain 内嵌 Session Store，Agent Service 将异步投影写入 TableStore 或独立查询 PostgreSQL，正常消费不使用执行数据库连接池。配置必需的 `queryStore.existingSecret` 与 `queryStore.provider`；`queryStore.initialize=true` 支持两种后端。
checkpoint/state、原生日志段和大 MQ payload 使用 objectStore 后端，PG 保留执行元数据与平台事实。
S3/OSS 的地址、bucket 与凭据通过业务 Secret 配置；filesystem 必须指定共享耐久 RWX 的
objectStore.existingClaim。用户文件开关只影响公开文件，不关闭私有恢复存储。
开发环境按目标 schema 新建，不提供旧对象导入或兼容模式；详见
[Session Store 配置](../../docs/session-store.md)。模板调整不会自动部署现有安装。

Tool-event and child-control concurrency are configured under `dataplane`; Tool Worker has separate
`toolConcurrency` and `toolControlConcurrency`. Move prior Tool Worker tool-event/child-control overrides
to Dataplane when upgrading.

All Turn depths share one consumer pool (`turnCallbacks`); descendant depth is business metadata only.
The retired `childTurnCallbacks` setting is no longer used. Follow the coordinated drain/cutover
procedure in [the deployment guide](../../deploy/README.md#consolidated-turn-topic-upgrade);
old root consumers must not coexist with new producers sending descendant Turns to the root Topic.

## ACS cloud data connections / ACS 云端数据连接

`data.existingSecret` defaults to `managed-agents-cloud-data` and loads last in all
non-Gateway workloads. `schema.dataExistingSecret` defaults to the separate
`managed-agents-cloud-data-migration`, loaded last in schema jobs only. Missing
Secrets prevent startup. Prepare/validate them with `deploy/managed-cloud-data-config.py`;
see [Kubernetes deployment instructions](../../release/KUBERNETES_README.md#acs-cloud-postgresql-and-redis).
An intentional alternative installation must explicitly clear both settings.

业务连接 Secret 默认最后加载，防止旧 workload Secret 将连接覆盖回自建 PostgreSQL/Redis。
迁移 owner 连接使用单独 Secret，仅供 schema Job。缺失时启动失败；通过部署脚本准备和校验，
明确部署其他环境时才同时清空这两个设置。账号密码不写入 Helm values 或镜像。

Tool Worker uses completion notifications for MCP calls and batches. `toolConcurrency`
limits short worker transitions, not the number of outstanding tools. Pending calls do
not hold a worker thread. SQL/Redis ownership, cancellation, and durable recovery remain
active; providers without completion notifications use scheduled, nonblocking-between-polls
queries. The optional `tests/pts/values-async-hands.yaml` overlay raises transition workers
from the old benchmark's 768 to 1024 without changing replica or database sizes.
