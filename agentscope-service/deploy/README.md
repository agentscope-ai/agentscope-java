# Deploy AgentScope Service

Quick startup uses the published Docker Compose bundle. Follow the
[download and SHA-256 verification steps](https://java.agentscope.io/v2/en/service/quickstart)
and run these commands from the extracted `agentscope-service` directory.
The current example uses the `2.1.0-BETA1` prerelease and its published ACR images;
no Java, Maven, Go or source build is required.

```bash
./init-env.sh 2.1.0-BETA1 sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

Open http://localhost:18080. Sign in as `admin` with `CONTROL_PLANE_BOOTSTRAP_PASSWORD` from your local `.env`, then change the password in your profile. No demo users are created. The bootstrap values apply only when the users table is empty; restarting never resets accounts. `init-env.sh` preserves an existing `.env`.

The stack persists PostgreSQL, shared workspaces, and artifacts in three named volumes. Stop with `docker compose down`; do not add `-v` unless you intend to delete application data. Keep `.env`, especially the vault key, with your backups. `POSTGRES_DB` defaults to `agentscope`; change it when pointing this stack at a restored database on the same PostgreSQL instance. Generated database passwords are URL-safe hex; manually supplied passwords must also be URL-safe because Compose interpolates them into DSNs.

Configure a model and an Environment before your first Session. For a trusted local evaluation, set `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true` and recreate the control container. Local tools execute inside the dataplane container. For other installations, configure a sandbox or a self-hosted worker. The service does not include model credentials or third-party coding tools.

Only the gateway is published, on loopback by default. Remote access requires your HTTPS reverse proxy and a matching `BUILDER_OAUTH_PUBLIC_URL`; preserve long-lived SSE responses. Internal APIs and PostgreSQL should remain on the private network.

## Kubernetes

Use the published Helm repository for a Kubernetes production installation. Operate PostgreSQL separately. Create `cp`, `rt`, and `dp` schemas with `postgres-init.sql` as the application database owner. Copy `kubernetes.env.example` to a private file, replace the values, and create the Secret:

```bash
kubectl create namespace agentscope
kubectl -n agentscope create secret generic agentscope-service --from-env-file=/private/path/service.env
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
helm upgrade --install service agentscope/agentscope-service \
  --version 2.1.0-BETA1 \
  --namespace agentscope \
  --set imageRepository=sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope \
  --set existingSecret=agentscope-service --wait --timeout 10m
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

The workspace claim must support shared mounts across the control, data and scheduler pods (RWX storage, or an existing shared claim). The Chart keeps claims on uninstall. It uses one replica per component and Recreate updates; plan a maintenance window. It does not claim zero-downtime database upgrades or HA. The legacy `controlplane` Chart remains available for Kubernetes-native control-plane/CRD integration; the complete Service Chart runs standalone HTTP mode and does not expose ASDP gRPC.

Set Workspace and Artifact storage classes or existing claims, `publicURL`,
Ingress and TLS in your values file before production use. See the
[production Helm guide](https://java.agentscope.io/v2/en/service/kubernetes)
for the complete configuration and verification procedure.

## CLI and Runtime Host

For a separate Coding Agent host, install Go 1.26+ and use `go install` for both
commands at the same version, following the
[Runtime Host guide](https://java.agentscope.io/v2/en/service/runtime-host).
The CLI connects to a running Service; it is not required to start Compose or Helm.

## 中文

本机快速启动默认使用已发布的 Docker Compose 安装包。按
[快速开始](https://java.agentscope.io/v2/zh/service/quickstart)下载和校验安装包，
进入解压后的 `agentscope-service` 目录，再运行上面的初始化和 Compose 命令。
填写模型凭据；可信本机体验时才启用 Local 执行环境。
打开 `http://localhost:18080`，以 `admin` 和 `.env` 中的初始密码登录，然后修改密码。
保留数据卷、`.env` 和原 Vault 密钥，日常停止不要使用 `docker compose down -v`。

生产环境按 [Helm 安装指南](https://java.agentscope.io/v2/zh/service/kubernetes)
准备外部 PostgreSQL、RWX Workspace 存储、Artifact 存储、配置 Secret、域名和 TLS，
再从公开仓库安装指定版本。接入 Coding Agent 时，通过
[Go 安装 CLI 与 Runtime Host](https://java.agentscope.io/v2/zh/service/runtime-host)；
启动 Compose 或 Helm 不需要预先安装 CLI。
