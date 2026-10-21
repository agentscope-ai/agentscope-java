# AgentScope Service Kubernetes installation

Candidate version: @SERVICE_VERSION@. Image repository: @IMAGE_REPOSITORY@.
This bundle contains the Chart, business and migration Secret templates,
`postgres-init.sql` and `postgres-roles.sh`. Prepare external PostgreSQL plus
optional RWX Workspaces/Artifacts. Checkpoints and oversized messages use PostgreSQL.
Set userFilesEnabled=false for replicas without public user-file APIs or shared file volumes.

1. Create a new database and run postgres-init.sql as its owner.
2. Prepare private files from kubernetes-migration.env.example and
   kubernetes.env.example. Use different Secrets; service accounts cannot own
   schemas/tables or have CREATE privileges. Retain the original Vault key.
3. Run initialization-only jobs, then apply business-role grants with the owner.
4. Install ordinary services, using a per-component existingSecret for separate
   credentials. Control uses one DML account for both cp and rt; no Scheduler account is needed.

```bash
kubectl create namespace agentscope
kubectl -n agentscope create secret generic service-migration --from-env-file=/private/migration.env
kubectl -n agentscope create secret generic service-credentials --from-env-file=/private/service.env
helm upgrade --install service-schema ./agentscope-service-@SERVICE_VERSION@.tgz \
  --namespace agentscope --set imageRepository=@IMAGE_REPOSITORY@ \
  --set schema.only=true --set schema.existingSecret=service-migration --wait --timeout 10m
# Run postgres-roles.sh with owner PG* credentials and the five matching business passwords.
helm upgrade --install service ./agentscope-service-@SERVICE_VERSION@.tgz \
  --namespace agentscope --set imageRepository=@IMAGE_REPOSITORY@ \
  --set existingSecret=service-credentials --set schema.existingSecret=service-migration \
  -f production-values.yaml --wait --timeout 10m
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

See the packaged Chart README for Secret keys, scaling and optional Hands. For
registry authentication set imagePullSecrets; configure publicURL, Ingress and TLS.
The Chart initializes before installation/upgrade; it does not provision the
database, create database accounts, or retire old environments. New tables require
business grants before replacing workloads. Drain tasks and back up the database,
archives, journals and keys before upgrades. PVCs are retained on uninstall.

## 中文

本包对应候选版本 @SERVICE_VERSION@，镜像仓库 @IMAGE_REPOSITORY@。先准备新的外部
PostgreSQL；内部 checkpoint 与大消息对象存入数据库。设置 userFilesEnabled=false 可关闭
用户文件功能并免除共享文件卷，创建五个 schema；迁移凭据与业务凭据分别创建 Secret。
先按上面的 schema.only 命令完成初始化，再以数据库 owner 执行业务账号授权，
最后安装服务。初始化任务不创建管理员或启动执行，业务副本没有建表权限。

逐组件 Secret、扩容与 Hands 配置见包内 Chart README。升级新增表时仍需先初始化、
再授权、再替换服务，并提前排空任务。数据库、文件归档、Worker 记录和原密钥一起
备份；卸载保留 PVC。数据库、账号管理、存储 provisioner 与生产切流由部署方安排。
