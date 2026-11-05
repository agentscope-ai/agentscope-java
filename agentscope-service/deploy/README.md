# Install AgentScope Service

This deployment contract applies to the current source: **five components** (Control Plane,
Gateway, Session Portal, Brain Dataplane and platform Hands), plus PostgreSQL, Redis and
RocketMQ. Scheduler, Team / Workflow coordination, Cron and channels run in the Go Control Plane.
Build or obtain all five images from the same source version. Previously published
`2.1.0-BETA1` images do not contain this architecture; changing a tag in configuration does
not upgrade their contents.

## 1. Prepare Redis and RocketMQ

Use addresses reachable **from service containers**, not container localhost. Redis and MQ
are operated separately; this Compose file starts PostgreSQL but does not provision Redis or MQ.
RocketMQ must support the LitePushConsumer protocol and use a standalone Cluster Proxy.
For self-hosted RocketMQ 5.5.1, enable Broker `enableLmq=true`, `enableMultiDispatch=true`,
Proxy `enableProxyAutoRenew=true` and `renewMaxTimeMillis=10800000`.

The unmodified 5.5.1 Broker has a known first-message offset issue on fresh LiteTopics.
Qualify first delivery, offline backlog and restart behavior on the exact Broker/provider
before production use. The explicitly patched local fixture in
`tests/managed-mq-redis/README.md` is for isolated development acceptance, not a production distribution.

On a host/container with RocketMQ administration tools and access to NameServer, run:

```bash
ROCKETMQ_HOME=/opt/rocketmq MANAGED_MQ_NAMESRV=namesrv:9876 \
  MANAGED_MQ_CLUSTER=YOUR_CLUSTER sh managed-mq-init.sh
```

When running from the source `agentscope-service` directory, use `deploy/managed-mq-init.sh`.
The script creates exactly **five business parent Topics and five consumer groups**, plus
the self-hosted Proxy heartbeat Topic. There are no per-depth Turn Topics.

| Topic default | Consumer group default | Producer → consumer |
| --- | --- | --- |
| `ma_turns_v3` | `ma_brain_v3` | Portal → Brain |
| `ma_tool_execute_v1` | `ma_hands_execute_v1` | Brain → Hands |
| `ma_tool_control_v1` | `ma_hands_control_v1` | Brain → Hands |
| `ma_tool_events_v3` | `ma_brain_tool_events_v3` | Hands / Brain → Brain |
| `ma_turn_resumes_v3` | `ma_brain_resumes_v3` | Brain → Brain |

Groups require FIFO, Shared/wildcard Lite subscriptions and minimum-offset consumption.
Topic/group overrides use the same `BUILDER_MANAGED_MQ_*` variables as the services; export
them for initialization too. Queue count and idle retention default to 8 and 20160 minutes.
For Alibaba Cloud LITE, use `managed-cloud-mq-init.py --instance INSTANCE --region REGION`
with an authenticated `aliyun` CLI instead of NameServer administration. Set the matching
Proxy endpoint, TLS setting and both MQ credentials in private configuration.

## 2. Configure and start the image distribution

Extract the Compose archive for your candidate/release, enter its `agentscope-service`
directory, and use the version and repository from that build's release manifest:

```bash
./init-env.sh YOUR_VERSION REGISTRY/NAMESPACE
# Edit .env: Redis URI, MQ Proxy endpoints, optional MQ auth/TLS and model credentials.
# Keep BUILDER_LOCAL_DEV=false for authenticated installation.
docker compose config --quiet
docker compose pull
docker compose up -d --wait --wait-timeout 600
docker compose ps -a
curl -fsS http://127.0.0.1:18080/actuator/health
```

`YOUR_VERSION` must be a concrete SemVer, for example `2.1.0-candidate.1`, with all five
images available in your registry. `init-env.sh` creates a mode-600 `.env` with random
credentials and preserves an existing file. For upgrades, explicitly update its image
version/repository and add missing configuration; preserve passwords, JWT/internal secrets
and the Vault key. Only Gateway publishes a host port, on loopback by default.

Compose first runs four owner-only migration jobs: Control (`cp` + `rt`), Portal (`portal`),
Dataplane (`dp`) and Managed (`ma`). `schema-grants` then provisions four distinct business
accounts and applies permissions to all current tables. Ordinary services validate migration
versions/checksums and do not migrate tables. Hands writes its physical `ma.hands_*` ledger;
Brain and Portal cannot read that ledger. Do not start new services after a failed migration
or grant job; inspect `docker compose logs schema-control schema-portal schema-data schema-managed schema-grants`.

Open the Console and sign in using the bootstrap account from `.env`. Validate installation:

```bash
python3 smoke.py --base http://127.0.0.1:18080 --env-file .env --environment-id "$ENVIRONMENT_ID" --state /tmp/service-smoke.json
# With a working model, also submit a native Turn:
python3 smoke.py --base http://127.0.0.1:18080 --env-file .env --environment-id "$ENVIRONMENT_ID" --state /tmp/service-turn.json --model-turn
# After restarting services, repeat the matching command with --resume.
```

Before running the authenticated check, configure an Environment and export its ID as
`ENVIRONMENT_ID`. The checker uses the native `/v1/sessions` API and submits `user.message` through `/events`; even tool-free
Sessions require an Environment binding. Use your configured cloud/self_hosted Environment
in production, or explicitly enable Local on an isolated evaluation installation. A resource-only check does not qualify model execution or tool execution.

## 3. Build and run from source

From the repository's `agentscope-service` directory:

```bash
./deploy/init-env.sh 2.1.0-local agentscope-local
# Fill reachable Redis/MQ addresses in deploy/.env; initialize the same five Topic/group pairs.
export BUILDER_LOCAL_DEV=true
export BUILDER_ALLOW_LOCAL_ENVIRONMENT=true
docker compose --env-file deploy/.env config --quiet
docker compose --env-file deploy/.env up -d --build --wait --wait-timeout 600
```

This builds the same five images from the root Java reactor and Go/frontend source.
Explicit development mode uses one developer identity without login. Local tools run in
Hands, not Brain; configure an Environment before using them. See
[the first Managed Agent](https://java.agentscope.io/v2/en/service/create-managed-agent).
Use the same `--env-file deploy/.env` for `ps`, `logs`, `stop` and `down`.
Native `scripts/dev-up.sh` also requires Redis/MQ, runs all four migrations and grants,
and preserves database contents during rebuilds. `BUILDER_RESET_DB=1` explicitly destroys
only the selected disposable development schemas; do not use it against retained data.

## Storage, upgrades and Kubernetes

PostgreSQL stores checkpoints and oversized objects in `ma.private_object`; there is no
managed-blobs volume or node-local fallback. With user files enabled, Compose shares
Workspace/Artifact volumes. For Helm without file/Shell/Artifact capabilities, set
`userFilesEnabled=false`; no shared file PVCs are needed. Enabling those features requires
RWX Workspace and Artifact storage. Platform Hands is required and has no fixed Environment
ID, legacy Worker token or per-pod execution journal. Customer `self_hosted` Workers are separate.

Back up PostgreSQL (including private objects), enabled file volumes and original keys.
Stop admission and drain active Turns, tools and channel replies before incompatible upgrades.
Stop old Scheduler/orchestration deployments before starting the combined Control Plane.
Run the new image's schema jobs, reapply grants, then replace all service images together;
use `--remove-orphans` to remove retired Compose services after draining them.
The current `dp`/`ma` baselines reject incompatible historical versions/checksums and unversioned
Managed state. They do not convert that state or import filesystem blobs. Use a fresh runtime
data space or an explicitly verified migration; never delete a ledger to bypass validation.
An image rollback alone cannot undo schema changes.

For Kubernetes, use the current Chart and follow
[production installation](https://java.agentscope.io/v2/en/service/kubernetes).
The Kubernetes bundle includes migration/workload/grant templates, both MQ initialization
helpers and sample production values. Initialize schemas, apply business grants, then install
workloads with separate migration and service Secrets.

## 中文

当前安装统一使用五个同版本组件：Control Plane、Gateway、Session Portal、Brain Dataplane、
平台 Hands。Scheduler、Team / Workflow、Cron 和渠道职责已合并到 Go Control Plane；
历史 `2.1.0-BETA1` 镜像不包含这套实现，必须构建当前源码或使用包含它的新发布制品。

按以上顺序准备容器可达的 Redis 和 RocketMQ Cluster Proxy。自建 MQ 初始化脚本创建五个业务
父 Topic / 消费组，以及 Proxy 心跳 Topic，不创建深度分道；生产需验证所选 Broker 的首次投递、
离线积压和重启行为，不能把存在已知 offset 问题的原版 5.5.1 当成已验收版本。云 MQ 使用对应的
初始化脚本，并配置匹配的地址、TLS 和成对认证信息。

镜像安装先用 `init-env.sh` 创建私有配置，填写依赖地址后执行 Compose。四个迁移任务覆盖
`cp/rt`、`portal`、`dp`、`ma`，随后为四个业务账号授权，普通组件只校验版本。升级时保留原密码
和密钥，显式更新版本；失败先查看迁移及授权日志。安装后用包内 `smoke.py` 检查 Session API，
有效模型配置下加 `--model-turn`，重启后加 `--resume`。生产检查需用 `--environment-id` 指定已配置环境；即使关闭用户文件，Session 仍需环境绑定。正式环境使用已配置的 cloud/self_hosted，隔离本地演练可显式开放 Local。

源码启动使用上面的 `--env-file deploy/.env`，显式开启 `BUILDER_LOCAL_DEV=true` 和
`BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`；Local 工具在 Hands 执行。原生开发脚本重建时默认
保留数据，只有显式 `BUILDER_RESET_DB=1` 才重置所选开发 schema。后续查询、停止和重启都使用
相同配置文件。完整本地操作见[本地安装](https://java.agentscope.io/v2/zh/service/quickstart)。

checkpoint 和大对象位于 PostgreSQL，无私有 managed-blobs 卷。Helm 关闭用户文件时无需 NAS；
开放文件、Shell、Artifact 时配置 RWX 共享存储。平台 Hands 为必选组件，客户侧 Worker 与它分离。
升级先备份数据库、文件及密钥，再停受理并排空执行；停用旧 Scheduler/orchestration，执行迁移、
重新授权，最后统一替换组件。旧 `dp`/`ma` 不兼容数据不会自动转换，不能删除迁移记录来绕过校验。
生产 Helm 按[生产安装](https://java.agentscope.io/v2/zh/service/kubernetes)先迁移、授权，再启动业务副本。
