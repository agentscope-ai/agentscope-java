# AgentScope Service Helm Chart

Installs the Gateway, Control Plane with Dashboard, Dataplane and Scheduler as a single Service deployment. PostgreSQL and storage provisioning are managed separately.

Before installation:

1. Create a PostgreSQL database with `cp`, `rt` and `dp` schemas owned by the application database user.
2. Create a Secret from the release package's `kubernetes.env.example`, replacing all placeholders with private values. Retain these keys with your backups.
3. Provide storage supporting the shared Workspace volume's `ReadWriteMany` access mode and the Artifact volume's `ReadWriteOnce` mode.

```bash
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
helm upgrade --install service agentscope/agentscope-service \
  --version 2.1.0-BETA1 \
  --namespace agentscope --create-namespace \
  --set imageRepository=sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope \
  --set existingSecret=service-credentials \
  -f production-values.yaml --wait --timeout 10m
```

The Chart uses its `appVersion` for image tags unless `imageTag` is supplied. Configure `publicURL` for the externally reachable gateway. Ingress is optional; service defaults to ClusterIP. Local execution is disabled by default and should only be enabled on a trusted installation.

Each component runs one replica with Recreate updates. The Chart runs standalone HTTP mode, without Kubernetes-native controllers or ASDP gRPC. It does not provide high availability or zero-downtime migrations. Back up the database, both volumes and original keys before upgrades. PVCs created by the Chart are retained on uninstall.

Prepare `production-values.yaml` with your storage classes or existing claims,
public URL, Ingress and TLS. The [production installation guide](https://java.agentscope.io/v2/en/service/kubernetes)
includes the versioned Secret/SQL downloads, values example and verification steps.
For offline installation, use the Chart archive included in the Kubernetes Release
bundle instead of the repository reference and version flag.

## 中文

生产安装使用公开 Helm 仓库，按上面的命令固定 `2.1.0-BETA1` 版本和配套的 ACR 镜像命名空间。
先准备 PostgreSQL 的 `cp`、`rt`、`dp` schema、配置 Secret，以及 Workspace 的 RWX 和
Artifact 的 RWO 存储；在 `production-values.yaml` 配置实际 StorageClass、域名、Ingress 和 TLS。
完整下载、配置和验证步骤见[生产安装指南](https://java.agentscope.io/v2/zh/service/kubernetes)。
离线安装时，用 Kubernetes Release 包中的 Chart `.tgz` 替换仓库引用和版本参数。
安装采用每组件单副本与 Recreate 更新，升级前备份数据库、数据卷和原密钥。
