# Managed MQ / Redis Helm deployment

The Chart installs five internal components: Control, Gateway, Session Portal, Brain
Dataplane and platform Hands. Orchestration and Scheduler run inside the Go Control Plane. Build every
Service image at the same source version. PostgreSQL, Redis, RocketMQ and optional shared user-file storage are
operated separately. This contract applies to newly built images; it does not upgrade running
legacy Service processes or convert Managed data.

The current source deploys five components: the Go Control Plane, Gateway, Session Portal,
Brain Dataplane and platform Hands. One `service-controlplane` binary/image owns management,
Workflow/Team orchestration, Hosted coordination, Cron, channels, outbound messages and webhook
sending on port 8081. There are no separate scheduler or orchestration deployments.

Every control replica runs bounded workers. PostgreSQL owns message/job deduplication, claims,
stream leases, retries and fencing; no sticky sessions or single control replica are required.
`control.executionSlots`, `control.workerBatch`, `control.channelSlots` and `control.webhookSlots`
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
values can separate database accounts; Hands uses BUILDER_HANDS_DB_* rather than BUILDER_DB_*.

Initialize the five Lite channel families (21 parent Topics/groups) with
[managed-mq-init.sh](../../deploy/managed-mq-init.sh). The default names under managed.mq are
ma_turns_v3 / ma_brain_v3 and depth groups,
ma_tool_events_v3 / ma_brain_tool_events_v3, ma_turn_resumes_v3 / ma_brain_resumes_v3,
ma_tool_execute_v1 / ma_hands_execute_v1 and ma_tool_control_v1 / ma_hands_control_v1.
Brain consumes Turns/Resumes/tool events and commits native execution facts; Hands consumes
Execute/Control and publishes tool facts plus reliable Resume notifications. Brain uses the
`ma_brain_tool_events_v3` group; initialize it before startup. Initialize a fresh Managed schema
and apply database roles before starting the components; see the deployment notes.
Suspension returns SDK Suspend without retaining a callback thread; the original message remains
the FIFO head until terminal state. No callback completion mailbox is used.
MQ consumers start by default; an explicit false consumer setting only drains MQ and
does not activate another execution path. The script sets Shared bindings, minimum-offset initialization and idle retention.
RocketMQ 5.5.1 with standalone Cluster Proxy is the tested server configuration. Match
managed.mq.ssl to the Proxy's TLS configuration; these images never auto-select a fake.

The Managed schema baseline directly creates current Brain and Hands tables. It does not create
retired runtime receipt, writer-seal, callback-mailbox or shared Sandbox-binding tables, and
provides no backfill or conversion from previous Managed schemas. Use a fresh Managed data space;
existing migration ledgers with different checksums or versions are rejected.

Run schema preparation first. Four owner-only Jobs initialize Control (cp + rt), Portal,
Dataplane and Managed (ma), without service pods or PVCs. The Managed job rejects unversioned
existing ma tables. Apply postgres-roles.sh with the four matching business passwords, then
install workloads. Ordinary accounts only validate migration ledgers at startup.

```bash
helm upgrade --install service-schema ./agentscope-service \
  --namespace agentscope --create-namespace \
  --set imageRepository=REGISTRY/NAMESPACE --set imageTag=CANDIDATE_VERSION \
  --set schema.only=true --set schema.existingSecret=service-migration \
  --wait --timeout 10m
# Apply postgres-roles.sh with owner PG* variables and four business passwords.
helm upgrade --install service ./agentscope-service \
  --namespace agentscope \
  --set imageRepository=REGISTRY/NAMESPACE --set imageTag=CANDIDATE_VERSION \
  --set existingSecret=service-credentials --set schema.existingSecret=service-migration \
  -f production-values.yaml --wait --timeout 10m
```

Regular installs repeat the idempotent migration Jobs. schema.enabled=false is only for
operator-managed initialization. All five components are Deployments with RollingUpdate;
platform Hands listens on internal 8085 and Gateway forwards /v1/hands/... APIs there.
It needs no environmentID, optional enable flag or per-pod journal directory.

```yaml
portal:
  replicas: 2
dataplane:
  replicas: 2
  concurrency: 32
  ddlAuto: validate
hands:
  replicas: 2
  toolConcurrency: 16
  databasePoolSize: 16
  existingSecret: hands-credentials
userFilesEnabled: false
control: {replicas: 2}
gateway: {replicas: 2}
```

Portal, Brain and Hands store immutable checkpoints and oversized log/result batches in
PostgreSQL `ma.private_object`. Run the new managed-private-objects.sql migration before
starting replicas and reapply business-role grants. No private runtime PVC is needed.
With userFilesEnabled=false, public file/workspace/artifact APIs are disabled and Session
capabilities advertise files as unavailable. Workspaces use per-pod scratch storage; the
Chart creates no user-file PVCs. With user files enabled, Workspaces and Artifacts still
require shared RWX storage. In this mode Harness internal workspace data uses the existing
PostgreSQL KV store per Session, and file/Shell/Artifact tools are disabled; text turns do not
require external sandbox credentials. Existing filesystem objects are not imported automatically;
use a fresh runtime database or import every referenced private object before upgrading.
Include all component database pools in the connection budget. Configure publicURL, TLS,
imagePullSecrets and SSE ingress timeouts according to the installation.

Back up the database (including private objects), enabled user-file volumes, Vault key and service secrets
together. Restore persistent protocol facts before accepting new execution. Redis hints can
be rebuilt from those facts; unknown external tool effects cannot be safely replayed merely
because Redis or a Worker restarted. Full deployment and recovery acceptance remain separate
from Helm rendering or component unit tests.

## 中文

Chart 部署五个 Service 组件，平台 Hands 为必选 HTTP/MQ Deployment，内部端口 8085。
PostgreSQL、Redis、RocketMQ 和可选的用户文件共享存储由运维提供；普通组件 Secret 包含 Redis URI 与
可达的 Cluster Proxy 地址，Hands 使用独立 BUILDER_HANDS_DB_* 账号。

先创建三个 Lite 父 Topic 与 Shared 消费组，再运行 schema.only 的四个初始化 Job
（cp/rt、portal、dp、ma）；授权四个业务账号后启动服务。ma 初始化拒绝接管已有无版本表，
普通账号只读校验版本，不在副本启动时建表。迁移 Secret 与业务 Secret 分离。

Portal、Brain、Hands 独立扩容，checkpoint 与大消息对象保存在 PostgreSQL ma.private_object，
不再挂载私有 RWX 卷。关闭用户文件时，Harness 内部工作数据使用 PostgreSQL KV 表，
文件、Shell 与 Artifact 工具关闭，文本对话不依赖外部沙箱。先执行新增迁移并重新授权业务账号。设置 userFilesEnabled=false 后，
关闭公开文件、工作区文件和 Artifact API，不创建用户文件 PVC，各副本只使用临时工作目录。
开放用户文件功能时，Workspace、Artifact 仍需共享存储。旧文件对象不会自动导入数据库，
升级已有运行数据前必须导入全部被引用对象，或使用新的运行数据库。平台 Hands 无固定 Environment ID、可选 enabled 开关或旧 Pod 日志目录。
self_hosted Worker 经 Gateway 连接平台 Hands。该模板变化不自动部署现有安装，不转换旧
Managed 数据；完整恢复和执行验收不能用 Helm 渲染成功替代。

Tool-event and child-control concurrency are configured under `dataplane`; Hands has separate
`toolConcurrency` and `toolControlConcurrency`. Move prior Hands tool-event/child-control overrides
to Dataplane when upgrading.

## Runtime upgrade and portable defaults

Self-hosted credentials remain the default. Optional `managed.mq.existingSecret`, `data.existingSecret` and `schema.dataExistingSecret` overrides are empty; `localDev` defaults to false. MQ/data business Secrets must remain separate from migration credentials. `hands.imageTag` can update the worker independently when a compatible protocol is already deployed.

For the submission-gate upgrade, stop admission, drain old per-depth Turn Topics and unsent Execute work, run schema jobs and `deploy/postgres-roles.sh`, then upgrade Brain and Hands together. See [migration notes](../../docs/master-sync-20261021.en.md).

默认继续自建部署，可选 Secret 覆盖项为空，`localDev=false`。业务/MQ Secret 与迁移凭据保持分离。提交门升级需要先停止受理、排空旧 Execute，再执行 schema Jobs 和 `deploy/postgres-roles.sh`，并同步升级 Brain 与 Hands。参见[同步记录](../../docs/master-sync-20261021.md)。
