# AgentScope Service Helm Chart

This Chart deploys five current-source components: Gateway, Go Control Plane,
Session Portal, Brain Dataplane and platform Hands. Scheduler, orchestration, Cron and
channels run inside Control Plane. All five images must use a compatible source version;
old published BETA1 images do not gain this architecture by using a newer Chart.

## Dependencies and installation inputs

Operate PostgreSQL, Redis and RocketMQ separately. The Broker/Cluster Proxy must support
LitePushConsumer, FIFO and Shared/wildcard Lite subscriptions with minimum-offset delivery.
Initialize exactly five business Topic/group pairs using `deploy/managed-mq-init.sh` or the
cloud helper from the matching source/bundle. Their names must match `managed.mq` values.
There are no per-depth parents. Brain consumes Turns, Tool Events and Resumes; Hands consumes
Execute/Control and publishes Tool Events. MCP batch continuation can recover directly from
durable records without publishing Resume. Qualify first delivery and offline/restart behavior
on the exact provider; see [production installation](https://java.agentscope.io/v2/en/service/kubernetes).

The Kubernetes bundle contains these inputs; source copies are in `agentscope-service/deploy/`:

| Input | Purpose |
| --- | --- |
| `kubernetes.env.example` | Workload-only PostgreSQL accounts, Redis URI, MQ endpoint and service keys |
| `kubernetes-migration.env.example` | Owner connections used only by schema Jobs |
| `postgres-grants.env.example` | Owner `PG*` settings and four matching raw business passwords for grants |
| `production-values.yaml.example` | Two replicas per role, authentication enabled, user files disabled |
| `postgres-init.sql`, `postgres-roles.sh` | Create five schemas, then grant current tables to four business accounts |
| `managed-mq-init.sh`, `managed-cloud-mq-init.py` | Initialize self-hosted or cloud Lite resources |

Prepare private copies, replace placeholders, and create distinct `service-migration` and
`service-credentials` Secrets. Keep database, JWT/internal and Vault keys stable across upgrades.
Use `control.existingSecret`, `portal.existingSecret`, `dataplane.existingSecret` and
`hands.existingSecret` when credentials are split by role. Hands uses `BUILDER_HANDS_DB_*`.
Optional `data.existingSecret`, `schema.dataExistingSecret` and `managed.mq.existingSecret`
are empty by default; business/MQ Secrets must remain separate from migration credentials.

## Migrate, grant, install

From the source `agentscope-service` directory, set the candidate's actual registry and tag.
Run `postgres-init.sql` as owner first. Configure the sample values and private Secrets before
these commands (for a bundle, use its pinned `.tgz` as CHART):

```bash
export CHART=helm/agentscope-service
export IMAGE_REPOSITORY=registry.example.com/agentscope
export SERVICE_VERSION=2.1.0-candidate.1
helm upgrade --install service-schema "$CHART" --namespace agentscope --create-namespace \
  -f /private/production-values.yaml --set imageRepository="$IMAGE_REPOSITORY" \
  --set imageTag="$SERVICE_VERSION" --set schema.only=true --wait --timeout 10m
# Export the owner PG* settings and four business passwords from the private grants file.
sh deploy/postgres-roles.sh
helm upgrade --install service "$CHART" --namespace agentscope \
  -f /private/production-values.yaml --set imageRepository="$IMAGE_REPOSITORY" \
  --set imageTag="$SERVICE_VERSION" --wait --timeout 10m
```

Four independent owner-only Jobs migrate Control (`cp` + `rt`), Portal (`portal`),
Dataplane (`dp`) and Managed (`ma`). `schema.only=true` renders no workloads or PVCs.
New tables require business grants **before** replacing workloads. Normal installation
repeats idempotent migration hooks; it does not run `postgres-roles.sh` automatically.
`schema.enabled=false` is only for externally managed, already completed migrations.
Ordinary services validate versions/checksums and do not execute DDL.

All five roles are Deployments using RollingUpdate, with at least one replica per role.
Platform Hands is required on internal port 8085; it needs no optional enable flag, fixed
Environment ID, platform Worker token or legacy journal directory. Gateway routes Worker
traffic to Hands and Session traffic to Portal. Customer Environment Workers are separate.

## Storage and scaling

Checkpoints and oversized objects use PostgreSQL `ma.private_object`; no private runtime
PVC exists. `userFilesEnabled=false` disables public file, Shell and Artifact capabilities,
uses Session-isolated PostgreSQL KV data and per-pod scratch directories, and creates no file
PVCs. Text execution in this mode requires no Sandbox. With user files enabled, configure
shared **ReadWriteMany** Workspace and Artifact claims or storage classes.

Portal, Brain, Hands and Control can scale independently. Include every database pool,
Dataplane native-log connections and migration jobs in the connection budget. Brain capacity
and callbacks use `dataplane.concurrency`, `turnCallbacks`, `resumeCallbacks`,
`toolEventConcurrency`, `childControlConcurrency`; physical tools use Hands
`toolConcurrency` and `toolControlConcurrency`. Control's bounded workers use
`executionSlots`, `workerBatch`, `channelSlots` and `webhookSlots` with PostgreSQL fencing.
`localDev` is false by default. Configure imagePullSecrets for both schema jobs and workloads,
publicURL, Gateway exposure, Ingress/TLS and SSE timeouts for your installation.

## Upgrades and recovery

Stop admission and drain incompatible in-flight Turns, tools and channel replies. Stop retired
Scheduler/orchestration deployments; run all schema jobs with the new images, reapply grants,
then update compatible roles together. Preserve `cp`/`rt` data and signing/encryption keys.
The current `dp`/`ma` baselines reject incompatible old ledgers and unversioned Managed data;
they do not import filesystem objects or silently convert previous runtime state. Use a fresh
runtime data space or a verified migration. Back up PostgreSQL including private objects,
enabled file volumes and keys together. Image rollback cannot undo database changes.
PVCs are retained on uninstall. Test restore and unfinished-work recovery on your exact stack;
Helm rendering alone does not qualify execution or availability.

## 中文

Chart 部署当前源码的五个组件，平台 Hands 为必选 HTTP/MQ Deployment，Scheduler、编排、Cron
和渠道职责并入 Go Control Plane。PostgreSQL、Redis、RocketMQ 由运维提供，先初始化五个业务
Topic / 消费组，不再按子深度建 Topic。Brain 消费 Turn、工具事件和 Resume；Hands 消费
Execute/Control，只发布工具事件；MCP 批次可直接从持久记录恢复。MQ 需要验证首次投递、离线
积压和重启行为，原版 5.5.1 的已知初始 offset 问题不能用模板渲染验收代替。

安装包提供业务、迁移、授权配置模板及 production values 示例。私有配置替换所有占位符，迁移
Secret 与业务 Secret 分开。按以上命令先执行四个 schema Job，覆盖 `cp/rt`、`portal`、`dp`、`ma`，
再以 owner 执行 `postgres-roles.sh`，最后安装业务副本。普通副本只校验版本，不执行 DDL；常规
Helm 安装的迁移 hook 不会自动重授权，因此升级新增表仍需先迁移、授权，再替换工作负载。

五个角色均为 RollingUpdate Deployment，可独立扩容。平台 Hands 无固定 Environment ID 或旧
Worker 日志卷。checkpoint 和大对象保存在 PostgreSQL，关闭用户文件时无需私有或用户文件 PVC；
文件、Shell、Artifact 关闭，文本执行不依赖 Sandbox。启用用户文件时，Workspace 和 Artifact 均需
RWX 存储。并发、连接池及资源配置见 values.yaml；`localDev=false`，私有镜像的拉取配置同时用于
迁移 Job 与业务副本。完整步骤见[生产安装](https://java.agentscope.io/v2/zh/service/kubernetes)。

升级先停止受理并排空执行、停用旧 Scheduler/orchestration，再迁移、授权和统一更新组件。
不兼容的旧 `dp`/`ma` 数据不会自动转换，不能删除迁移记录来绕过校验。数据库、文件与原密钥
一起备份并验证恢复；回滚镜像不等于回滚数据库，卸载保留 PVC。
