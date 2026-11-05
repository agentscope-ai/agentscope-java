# AgentScope Service Kubernetes installation

Candidate version: @SERVICE_VERSION@. Image repository: @IMAGE_REPOSITORY@.
This bundle installs the current five-component architecture. PostgreSQL, Redis and
RocketMQ Cluster Proxy are external dependencies. Platform Hands is required; Scheduler
and orchestration run in Control Plane. Checkpoints and oversized objects use PostgreSQL.

1. Replace placeholders in private copies of `kubernetes.env.example`,
   `kubernetes-migration.env.example` and `postgres-grants.env.example`.
   Workload accounts must be distinct from the owner; use the same four business passwords
   in workload connections and grant inputs. Keep original signing/encryption keys on upgrades.
2. Initialize exactly five Lite Topics/groups with `managed-mq-init.sh` on your MQ admin
   host, or use `managed-cloud-mq-init.py` for Alibaba Cloud. Match their names to Chart values,
   configure reachable Redis/MQ endpoints and any required MQ credentials/TLS in workload Secrets.
   Qualify first-message, offline backlog and restart delivery on your Broker/provider.
3. Copy `production-values.yaml.example` to a private `production-values.yaml` and set the
   public URL, imagePullSecrets and Ingress/TLS as needed. It starts two replicas per role,
   disables development authentication bypass and user-file capabilities, and needs no file PVCs.
   Enabling user files requires shared RWX Workspaces and Artifacts.

From this extracted directory, with Kubernetes, Helm and psql available:

```bash
set -a
. /private/postgres-grants.env
set +a
psql -X -v ON_ERROR_STOP=1 -f postgres-init.sql
kubectl create namespace agentscope
kubectl -n agentscope create secret generic service-migration --from-env-file=/private/migration.env
kubectl -n agentscope create secret generic service-credentials --from-env-file=/private/service.env
helm upgrade --install service-schema ./agentscope-service-@SERVICE_VERSION@.tgz \
  --namespace agentscope -f /private/production-values.yaml \
  --set imageRepository=@IMAGE_REPOSITORY@ --set imageTag=@SERVICE_VERSION@ \
  --set schema.only=true --wait --timeout 10m
sh postgres-roles.sh
helm upgrade --install service ./agentscope-service-@SERVICE_VERSION@.tgz \
  --namespace agentscope -f /private/production-values.yaml \
  --set imageRepository=@IMAGE_REPOSITORY@ --set imageTag=@SERVICE_VERSION@ \
  --wait --timeout 10m
kubectl -n agentscope get pods
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

The four owner-only Jobs migrate `cp/rt`, `portal`, `dp` and `ma`. Grant current tables before
installing workloads; Helm migration hooks do not automatically run `postgres-roles.sh`.
Ordinary replicas validate versions/checksums without DDL. After port-forwarding, configure an Environment and export its ID as `ENVIRONMENT_ID`, then verify:

```bash
python3 smoke.py --base http://127.0.0.1:18080 --env-file /private/service.env --environment-id "$ENVIRONMENT_ID" --state /tmp/service-smoke.json
# Add --model-turn with a configured model. After restart, reuse its state file with --resume.
```

Use the Chart README for per-component Secrets, scaling and connection budgets. On incompatible
upgrades, stop admission and drain work, stop retired Scheduler/orchestration workloads, run
schema Jobs and grants with the new images, then replace service workloads. The current `dp`/`ma`
baselines reject incompatible historical ledgers and unversioned Managed state; no automatic
conversion or filesystem blob import is provided. Back up PostgreSQL, enabled file volumes and
original keys together. Image rollback cannot reverse database changes; uninstall retains PVCs.

## 中文

本包对应 @SERVICE_VERSION@，镜像仓库 @IMAGE_REPOSITORY@，包含五组件 Chart、业务与迁移
Secret 模板、授权输入模板、生产 values 示例、SQL、两类 MQ 初始化脚本和 Session API 冒烟脚本。
先准备外部 PostgreSQL、Redis 和 RocketMQ Cluster Proxy，初始化五个业务 Topic / 消费组，配置
服务可达的地址、TLS 和成对 MQ 凭据。实际 Broker 需验证首次投递、积压与重启行为。

替换所有私有配置占位符，四个业务密码与授权输入保持一致，迁移 owner 与业务账号分离。
按以上命令先建五个 schema、运行四个迁移 Job，再授权、安装业务副本。Helm hook 不会自动
重新授权，新增表的升级也必须遵循此顺序。普通副本只读校验版本，不执行建表。

生产示例默认五角色各两副本、鉴权开启、用户文件关闭，无需共享文件卷；开放文件时需为
Workspace 和 Artifact 配置 RWX。平台 Hands 为必选，Scheduler 与编排由 Control Plane 承担。
安装后配置 Environment，将 ID 导出为 ENVIRONMENT_ID 后运行包内 smoke.py；有效模型配置下加 --model-turn，重启后加 --resume 检查持久记录。

升级先备份并排空执行，停用旧 Scheduler/orchestration，再迁移、授权、替换。旧 `dp`/`ma`
不兼容数据不自动转换，文件对象不自动导入；原密钥、数据库与文件一起恢复，不能只回退镜像。
