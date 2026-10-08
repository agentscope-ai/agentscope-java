# AgentScope Service Kubernetes installation

This bundle contains the Helm Chart, `kubernetes.env.example`, and
`postgres-init.sql`. Use the version and image repository from the release notes.
For @SERVICE_VERSION@, the repository is `@IMAGE_REPOSITORY@`.

1. Prepare an external PostgreSQL database. Run `postgres-init.sql` against that
   database as its application owner to create `cp`, `rt` and `dp` schemas.
2. Provide RWX storage for shared Workspaces and RWO storage for Artifacts.
3. Copy the environment template to a private file, replace all placeholders, and
   keep the database password and original Vault key with your backups.

```bash
umask 077
cp kubernetes.env.example service.env
# Edit service.env before creating the Secret.
kubectl create namespace agentscope
kubectl -n agentscope create secret generic agentscope-service --from-env-file=service.env
helm upgrade --install service ./agentscope-service-@SERVICE_VERSION@.tgz \
  --namespace agentscope \
  --set imageRepository=@IMAGE_REPOSITORY@ \
  --set existingSecret=agentscope-service --wait --timeout 10m
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

For another version, replace the Chart filename and repository with the release
values. If the registry requires authentication, create an image-pull Secret and
configure `imagePullSecrets`. Set `publicURL` and Ingress/TLS for external access.
Open http://localhost:18080 for the port-forwarded installation and sign in with
the bootstrap administrator credentials from `service.env`.

The Chart uses one replica per component and Recreate updates. It retains PVCs
on uninstall and runs standalone HTTP mode. PostgreSQL, a storage provisioner,
model credentials, HA, and zero-downtime database upgrades are not included.

## 中文

本包包含 Helm Chart、Secret 环境配置模板及数据库 schema 初始化 SQL。
先准备外部 PostgreSQL，用应用数据库 owner 执行 `postgres-init.sql`；
同时准备 Workspace 的 RWX 存储和 Artifact 的 RWO 存储。
按上面的命令复制并填写私有 `service.env`，再创建 Secret 和安装 Chart。
保留数据库密码和原 Vault 密钥，与数据库及文件备份一起保存。

私有镜像仓库需要配置 `imagePullSecrets`；对外访问需要设置 `publicURL`
以及 Ingress/TLS。安装采用每组件单副本和 Recreate 更新，卸载保留 PVC，
以 standalone HTTP 模式运行。数据库、存储 provisioner 和模型凭据由用户提供。
