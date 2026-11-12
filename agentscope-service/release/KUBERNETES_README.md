# Managed Agents Kubernetes installation

Candidate version: @SERVICE_VERSION@. Image repository: @IMAGE_REPOSITORY@.
This bundle contains the Chart, business and migration Secret templates,
`postgres-init.sql` and `postgres-roles.sh`. Prepare external PostgreSQL plus
optional RWX Workspaces/Artifacts. Checkpoints and oversized messages use PostgreSQL.
Set userFilesEnabled=false for replicas without public user-file APIs or shared file volumes.

1. For a new installation only, prepare the database and run postgres-init.sql as its owner.
2. Prepare private files from kubernetes-migration.env.example and
   kubernetes.env.example. Use different Secrets; service accounts cannot own
   schemas/tables or have CREATE privileges. Retain the original Vault key.
3. Prepare the cloud data connection Secrets with managed-cloud-data-config.py (see below),
   then run initialization-only jobs and apply business-role grants with the owner.
4. Prepare a private cloud-mq.env from cloud-mq.env.example and create the MQ-only
   managed-agents-cloud-mq Secret. Its endpoint defaults to the ACS cloud instance.
   Initialize LITE Topics/groups with managed-cloud-mq-init.py using the aliyun CLI.
5. Install ordinary services, using a per-component existingSecret for separate
   credentials. Control uses one DML account for both cp and rt; no Scheduler account is needed.

```bash
kubectl create namespace managed-agents
kubectl -n managed-agents create secret generic service-migration --from-env-file=/private/migration.env
kubectl -n managed-agents create secret generic service-credentials --from-env-file=/private/service.env
kubectl -n managed-agents create secret generic managed-agents-cloud-mq --from-env-file=/private/cloud-mq.env
python3 managed-cloud-data-config.py --namespace managed-agents \
  --workload-secret service-credentials --migration-secret service-migration --apply
helm upgrade --install service-schema ./managed-agents-@SERVICE_VERSION@.tgz \
  --namespace managed-agents --set imageRepository=@IMAGE_REPOSITORY@ \
  --set schema.only=true --set schema.existingSecret=service-migration --wait --timeout 10m
# Run postgres-roles.sh with owner PG* credentials and the five matching business passwords.
helm upgrade --install service ./managed-agents-@SERVICE_VERSION@.tgz \
  --namespace managed-agents --set imageRepository=@IMAGE_REPOSITORY@ \
  --set existingSecret=service-credentials --set schema.existingSecret=service-migration \
  -f production-values.yaml --wait --timeout 10m
kubectl -n managed-agents port-forward service/service-managed-agents-gateway 18080:8080
```

See the packaged Chart README for Secret keys, scaling and optional Tool Worker. For
registry authentication set imagePullSecrets; configure publicURL, Ingress and TLS.
The Chart initializes before installation/upgrade; it does not provision the
database, create database accounts, or retire old environments. New tables require
business grants before replacing workloads. Drain tasks and back up the database,
archives, journals and keys before upgrades. PVCs are retained on uninstall.

Helm defaults `runtime.mq.existingSecret=managed-agents-cloud-mq`. Missing Secrets
prevent startup; credentials are not embedded in images. For intentional self-hosted
MQ, set this value empty explicitly and configure the workload Secret endpoint.
See cloud-rocketmq.en.md for verified wildcard configuration.

## 中文

本包对应候选版本 @SERVICE_VERSION@，镜像仓库 @IMAGE_REPOSITORY@。先准备新的外部
PostgreSQL；内部 checkpoint 与大消息对象存入数据库。设置 userFilesEnabled=false 可关闭
用户文件功能并免除共享文件卷，创建五个 schema；迁移凭据与业务凭据分别创建 Secret。
先按上面的 schema.only 命令完成初始化，再以数据库 owner 执行业务账号授权，
最后安装服务。初始化任务不创建管理员或启动执行，业务副本没有建表权限。

逐组件 Secret、扩容与 Tool Worker 配置见包内 Chart README。升级新增表时仍需先初始化、
再授权、再替换服务，并提前排空任务。数据库、文件归档、Worker 记录和原密钥一起
备份；卸载保留 PVC。数据库、账号管理、存储 provisioner 与生产切流由部署方安排。

MQ 默认使用 managed-agents-cloud-mq 独立 Secret。先复制 cloud-mq.env.example 到私有文件，
填入实例用户名/密码并创建该 Secret，再部署服务；缺失时会启动失败，不回退旧 MQ。
使用 managed-cloud-mq-init.py 初始化 LITE Topic/消费组，详见 cloud-rocketmq.md。
仅明确使用自部署 MQ 时将 runtime.mq.existingSecret 显式设为空并配置其 workload 接入点。

## ACS cloud PostgreSQL and Redis

ACS defaults use PostgreSQL `pgm-bp10083t3nwu553v` at
`pgm-bp10083t3nwu553v.pg.rds.aliyuncs.com:5432` (database `managed_agents`, TLS required)
and Redis `r-bp1m16nw61zguewk63` at
`r-bp1m16nw61zguewk63.redis.rds.aliyuncs.com:6379/0` (authenticated VPC connection).
The Kubernetes env examples contain these endpoints and password placeholders only.
Keep the deployed JWT, internal token and Vault master key; do not recreate live Secrets
from placeholder files or rerun database initialization for an already migrated database.

Helm defaults `data.existingSecret=managed-agents-cloud-data` and
`schema.dataExistingSecret=managed-agents-cloud-data-migration`. Business connections
load last in the four database clients, after workload and MQ Secrets; Gateway receives
neither. Schema jobs load only the separate owner connection overlay after their migration
Secret. Missing cloud Secrets fail startup; an old workload env file cannot override them.
Existing per-component workload Secrets still supply other component settings.

On the existing ACS installation, prepare the overlays from the already migrated Secrets:

```bash
python3 managed-cloud-data-config.py --kubeconfig /private/acs-kubeconfig \
  --namespace agencore-ma --apply
python3 managed-cloud-data-config.py --kubeconfig /private/acs-kubeconfig \
  --namespace agencore-ma
```

The helper copies only connection keys, never JWT/Vault/MQ keys, and validates both sources
before applying. Old in-cluster hosts, missing authentication, wrong schemas or PostgreSQL
without TLS are rejected. It does not create accounts, migrate data or restart services.
For a fresh installation, first create the source workload/migration Secrets from private
files based on the updated examples (override their names with `--workload-secret` and
`--migration-secret` when using the generic installation commands above). Preserve the
separate cloud Secrets across image releases; validate them before each Helm upgrade.
Only for an intentional alternative installation explicitly set both
`data.existingSecret=""` and `schema.dataExistingSecret=""` and provide its connection Secrets.
Local Docker Compose remains a separate development deployment.

## ACS 云 PostgreSQL 和 Redis

ACS 默认连接 PostgreSQL `pgm-bp10083t3nwu553v`，地址
`pgm-bp10083t3nwu553v.pg.rds.aliyuncs.com:5432`，数据库 `managed_agents`，要求 TLS；
Redis 为 `r-bp1m16nw61zguewk63`，地址
`r-bp1m16nw61zguewk63.redis.rds.aliyuncs.com:6379/0`，使用 VPC 内账号认证连接。
Kubernetes env 示例已写入新地址，密码仍是占位符。保留现有 JWT、内部 Token 和 Vault 主密钥，
不要从占位符文件重建线上 Secret，也不要对已迁移的数据库重新执行首次初始化。

Helm 默认 `data.existingSecret=managed-agents-cloud-data`，
`schema.dataExistingSecret=managed-agents-cloud-data-migration`。四个数据库客户端最后加载
云端业务连接 Secret，优先于通用 workload 和 MQ Secret；Gateway 不加载它。
Schema Job 则在迁移 Secret 后加载独立的云端 owner 连接 Secret，业务服务不加载 owner 凭据。
缺少云 Secret 时启动失败，旧 workload env 文件无法覆盖云连接；逐组件 Secret 继续提供其他配置。

现有 ACS 安装使用上面的两条命令，从已经迁移完成的 Secret 创建并校验这两个独立 Secret。
脚本只复制连接字段，不复制 JWT/Vault/MQ 字段；两个来源均校验通过后才写入。
旧集群地址、缺少认证、错误 schema、未启用 TLS 的 PostgreSQL 连接都会被拒绝。
脚本不创建账号、不搬迁数据、不重启服务。新安装先按更新后的示例准备私有文件并创建
workload 和 migration 来源 Secret；使用前面通用安装示例的名称时，通过 `--workload-secret`
和 `--migration-secret` 指定。镜像升级时保留独立云连接 Secret，每次 Helm 升级前先校验。
仅明确部署其他环境时，同时显式清空 `data.existingSecret` 与 `schema.dataExistingSecret`，
并提供该环境的连接 Secret。Docker Compose 保持独立的本地开发部署方式。
