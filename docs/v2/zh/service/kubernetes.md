---
title: "生产安装"
en_link: /v2/en/service/kubernetes
---

<Note>
当前发布版本为 `2.1.0-BETA1`，属于预发布版本。用于生产前请验证实际部署。
</Note>

本页介绍面向生产环境的 Kubernetes 与 Helm 安装。正式 Service Chart 安装 Gateway、Control、Dataplane 和 Scheduler。PostgreSQL、持久存储、入口域名和 TLS 由你管理。应用每组件默认单副本并采用 Recreate 更新，部署和升级需要维护窗口。

## 1. 准备依赖

准备 Kubernetes、Helm、可达的 PostgreSQL，以及 Workspace 所需的 RWX StorageClass 或已有共享 PVC。Artifact 默认使用 RWO。工作目录会被多个组件挂载；只在单节点可用的 RWO 卷不能替代跨节点共享存储。

可以直接从公开 Helm 仓库安装 Chart，无需下载源码。先下载与版本配套的配置模板和初始化 SQL：

```bash
curl -fLO https://chickenlj.github.io/helm-charts/examples/2.1.0-BETA1/kubernetes.env.example
curl -fLO https://chickenlj.github.io/helm-charts/examples/2.1.0-BETA1/postgres-init.sql
```

用应用数据库所有者在目标数据库执行 SQL，创建 `cp`、`rt`、`dp` 三个 schema。为数据库、文件和密钥建立备份策略。如需离线安装，从 [GitHub Release](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.1.0-BETA1) 下载 `agentscope-service-VERSION-kubernetes.tar.gz` 和 `SHA256SUMS`，核对校验和后解压；包中包含 Chart 和相同的配置文件。

## 2. 创建 Secret

复制 `kubernetes.env.example` 到私有文件并替换全部占位值：数据库连接、随机 JWT/internal/Vault 密钥、初始管理员密码及需要的模型凭据。URI 中的密码 URL 编码，JDBC 密码单独提供原值。根据数据库证书设置 TLS。

```bash
kubectl create namespace agentscope
kubectl -n agentscope create secret generic agentscope-service --from-env-file=/private/path/service.env
```

Secret 是运行配置；不要把明文文件或含 Secret 的渲染结果提交到仓库。

## 3. 准备 values

以下是 `production-values.yaml` 起点，替换域名、StorageClass、Ingress class 和 TLS Secret。TLS Secret 必须预先存在或由你的证书控制器创建。

```yaml
existingSecret: agentscope-service
allowLocalEnvironment: false
publicURL: https://agentscope.example.com
persistence:
  workspaces:
    storageClass: shared-rwx
    size: 20Gi
  artifacts:
    storageClass: standard
    size: 20Gi
ingress:
  enabled: true
  className: nginx
  host: agentscope.example.com
  tls:
    - hosts: [agentscope.example.com]
      secretName: agentscope-service-tls
```

已有 PVC 时设置对应 `existingClaim`。私有镜像配置 `imagePullSecrets`；Ingress annotations 按实际控制器设置 SSE 超时和缓冲行为。资源 requests/limits 可分别通过 `control`、`dataplane`、`scheduler`、`gateway` 调整，按实际任务负载压测定容。

## 4. 安装指定版本

添加公开 Helm 仓库并更新索引，无需登录仓库：

```bash
helm repo add agentscope https://java.agentscope.io/helm
helm repo update agentscope
helm search repo agentscope/agentscope-service --versions --devel
```

索引会跳转到托管 Chart 的 [GitHub Pages](https://chickenlj.github.io/helm-charts/index.yaml)。也可以直接使用 `https://chickenlj.github.io/helm-charts` 执行 `helm repo add`。

安装指定 Chart 版本，并配置对应的镜像命名空间：

```bash
helm upgrade --install service agentscope/agentscope-service \
  --version 2.1.0-BETA1 \
  --namespace agentscope \
  --set imageRepository=sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope \
  -f production-values.yaml \
  --wait --timeout 10m
```

离线安装时，将 `agentscope/agentscope-service` 和 `--version 2.1.0-BETA1` 替换为下载的 `./agentscope-service-2.1.0-BETA1.tgz`。保持 Chart 与组件镜像版本配套。Chart 会在你的集群创建工作负载；发布 Helm 仓库本身不会部署运行中的 Service。

## 5. 验证用户路径

```bash
kubectl -n agentscope get pods,pvc,svc,ingress
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

确认 PVC Bound、Pod Ready，使用初始管理员登录公开域名并修改密码。验证模型连接、执行 Environment、第一次 Chat、Issue 交付和长连接。port-forward 用于排障，不替代公开回调地址验证。

## 升级、卸载与运行模式

Secret 更新后重启相关 Deployment；升级前按[运维手册](/v2/zh/service/operations)备份，并保留原 Chart、values 和镜像版本。Chart 保留 PVC；重新安装时显式指定保留的 existingClaim。

此 Chart 提供完整 Service standalone HTTP。Kubernetes-native ControlPlane/ASDP 是另外的部署模式，应按 SDK 网络契约规划，不把两个 Chart 直接叠装为同一服务。当前 Chart 的单副本安装不提供无停机迁移或多副本 HA 保证。
