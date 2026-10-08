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
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
helm upgrade --install service agentscope/agentscope-service \
  --version @SERVICE_VERSION@ \
  --namespace agentscope \
  --set imageRepository=@IMAGE_REPOSITORY@ \
  --set existingSecret=agentscope-service --wait --timeout 10m
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

For an offline installation, replace `agentscope/agentscope-service` and
`--version @SERVICE_VERSION@` with this bundle's `./agentscope-service-@SERVICE_VERSION@.tgz`.
Configure storage classes or existing claims in your values before production use.
See the [production installation guide](https://java.agentscope.io/v2/en/service/kubernetes)
for a complete values example and verification steps. If the registry requires authentication, create an image-pull Secret and
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
按上面的命令复制并填写私有 `service.env`，再创建 Secret，从公开 Helm 仓库安装指定版本。
离线时，用本包的 Chart `.tgz` 替换仓库引用和版本参数；完整 values 示例与验证步骤见
[生产安装指南](https://java.agentscope.io/v2/zh/service/kubernetes)。
保留数据库密码和原 Vault 密钥，与数据库及文件备份一起保存。

私有镜像仓库需要配置 `imagePullSecrets`；对外访问需要设置 `publicURL`
以及 Ingress/TLS。安装采用每组件单副本和 Recreate 更新，卸载保留 PVC，
以 standalone HTTP 模式运行。数据库、存储 provisioner 和模型凭据由用户提供。
