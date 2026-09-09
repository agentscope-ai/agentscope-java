# Helm 部署

完整 Service Chart 安装 Gateway、Control、Dataplane 和 Scheduler。PostgreSQL 与存储提供程序由部署者管理。

## 前置条件

准备 Kubernetes 集群、Helm 3.17+ 或兼容版本，以及可被各组件访问的 PostgreSQL。以应用数据库所有者身份执行发布包中的 `postgres-init.sql`，建立 `cp`、`rt` 和 `dp` 三个 schema。

Workspace 默认请求 ReadWriteMany 持久卷，需要 RWX StorageClass 或已有共享 PVC。Artifact 默认请求 ReadWriteOnce。单节点测试可以使用 RWO Workspace；多节点部署不能据此假定共享存储可用。

## 1. 创建配置 Secret

复制发布包中的 `kubernetes.env.example` 到私有文件，填写数据库连接、随机密钥及初始管理员密码。Go DSN 中的密码需要 URL 编码，JDBC 密码使用原始值。生产数据库连接按你的证书和网络配置启用 TLS。

```bash
kubectl create namespace agentscope
kubectl -n agentscope create secret generic agentscope-service   --from-env-file=/private/path/service.env
```

不要把填好的文件或渲染后的 Secret 提交到 Git。

## 2. 安装

使用 Release 下载的 Chart 包：

```bash
helm upgrade --install service ./agentscope-service-VERSION.tgz   --namespace agentscope   --set imageRepository=REGISTRY/NAMESPACE   --set existingSecret=agentscope-service   --wait --timeout 10m
```

发布到 OCI 后，也可把包路径换成 `oci://REGISTRY/NAMESPACE/charts/agentscope-service` 并加 `--version VERSION`。私有仓库同时配置 Helm 登录和 Kubernetes `imagePullSecrets`。

自定义存储通过 `persistence.workspaces.storageClass`、`persistence.workspaces.existingClaim` 及对应 Artifact 字段设置。启用 Ingress 时填写 `ingress.host`、`ingress.className`、TLS 与控制器支持的 SSE 参数，并设置 `publicURL`。

## 3. 验证

```bash
kubectl -n agentscope get pods,pvc,svc
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

登录后按[第一个 Session](first-session.md)验证业务流程。检查 PVC 为 Bound，组件为 Ready，重启后历史和文件仍可读取。

## 运行边界

Chart 默认每组件单副本，采用 Recreate 更新，需安排维护窗口。当前没有承诺无停机数据库迁移或多副本 HA。Secret 更新后需要重新启动相关 Deployment。

卸载保留 PVC；重新安装时显式指定保留的 existingClaim。完整 Chart 使用 standalone HTTP 模式；旧 `aistio` Chart 服务于 Kubernetes-native 控制面场景，两者不能不加区分地叠装成同一套产品服务。
