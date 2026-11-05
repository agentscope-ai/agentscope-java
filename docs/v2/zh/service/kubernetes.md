---
title: "生产安装"
en_link: /v2/en/service/kubernetes
---

<Note>
本页遵循当前源码架构。统一构建和发布五个镜像，或使用明确包含此实现的新制品。
历史 `2.1.0-BETA1` 镜像与 Chart 保持旧架构，不能用来安装当前运行时。
</Note>

## 当前源码：Managed MQ / Redis 部署

Service 部署 Gateway、Go Control Plane、Session Portal、Brain Dataplane 和平台 Hands 五个组件。
Team / Workflow、Scheduler、Cron、渠道与消息投递已合并到 Control Plane。应用使用 Session、Turn、
Events，物理工具由 Hands 执行。PostgreSQL、Redis、RocketMQ 由运维提供。镜像仓库和版本需固定到
同一次源码构建，见[发布手册](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-service/release/README_zh.md)。

## 1. 准备依赖

准备 Kubernetes、Helm、psql 和各节点可访问的镜像仓库。PostgreSQL 使用 `cp`、`rt`、`portal`、
`dp`、`ma` 五个 schema，owner 与业务账号分离。Redis 保护活动所有权并加速通知，PostgreSQL
保存 checkpoint、执行事实和 `ma.private_object` 大对象，不使用私有 managed-blobs PVC。

设置 `userFilesEnabled=false` 时关闭公开文件、Shell、Artifact，内部工作数据使用 Session 隔离的
PostgreSQL KV，文本执行无需文件 PVC 或 Sandbox。开放用户文件时，Workspace 和 Artifact 均需
共享 ReadWriteMany 存储。

<span id="prepare-rocketmq"></span>

### 准备 RocketMQ

使用支持 LitePushConsumer 的独立 Cluster Proxy。自建 Broker 5.5.1 需开启 `enableLmq=true`、
`enableMultiDispatch=true`；Proxy 设置 `enableProxyAutoRenew=true`、`renewMaxTimeMillis=10800000`。
原版 5.5.1 在新 LiteTopic 上存在已知初始 offset 投递问题，生产前需对实际 Broker / 云服务验证首次
投递、离线积压和重启恢复。仓库提供的显式补丁 fixture 用于开发验收，不是生产 Broker 发行版。

初始化五个业务父 Topic 与消费组，使用 FIFO、Shared/wildcard 订阅和最小 offset 消费。根与子
Session 共用 Turn 父 Topic，无需按深度创建 Topic。

| 父 Topic | 消费组 | 生产者 → 消费者 |
| --- | --- | --- |
| `ma_turns_v3` | `ma_brain_v3` | Portal → Brain |
| `ma_tool_execute_v1` | `ma_hands_execute_v1` | Brain → Hands |
| `ma_tool_control_v1` | `ma_hands_control_v1` | Brain → Hands |
| `ma_tool_events_v3` | `ma_brain_tool_events_v3` | Hands / Brain → Brain |
| `ma_turn_resumes_v3` | `ma_brain_resumes_v3` | Brain → Brain |

在 RocketMQ 管理主机或容器上，从源码 `agentscope-service` 目录执行：

```bash
ROCKETMQ_HOME=/opt/rocketmq MANAGED_MQ_NAMESRV=namesrv:9876 \
  MANAGED_MQ_CLUSTER=YOUR_CLUSTER sh deploy/managed-mq-init.sh
```

自定义 Topic/消费组时，初始化也需导出匹配的 `BUILDER_MANAGED_MQ_*` 配置。脚本另建自建 Proxy
心跳 Topic。阿里云使用已登录 `aliyun` CLI 执行
`deploy/managed-cloud-mq-init.py --instance INSTANCE --region REGION`，无需访问 NameServer；在
业务 Secret 中配置匹配的地址、TLS 和需要的成对 MQ 凭据。完整文件见
[部署输入](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-service/deploy)。

## 2. 创建 Secret

进入当前源码的 `agentscope-service` 目录，将 `deploy/kubernetes.env.example`、
`deploy/kubernetes-migration.env.example`、`deploy/postgres-grants.env.example` 复制到 Git 之外的
私有文件并替换全部占位符。授权文件使用 owner 的 `PG*` 设置和四个业务原始密码，与业务连接
保持一致。URI 密码需 URL 编码，JDBC 与 psql 使用原始密码。迁移 Secret 不得挂给普通业务副本。

```bash
set -a
. /private/postgres-grants.env
set +a
psql -X -v ON_ERROR_STOP=1 -f deploy/postgres-init.sql
kubectl create namespace agentscope
kubectl -n agentscope create secret generic service-migration --from-env-file=/private/migration.env
kubectl -n agentscope create secret generic service-credentials --from-env-file=/private/service.env
```

已有安装通过 Secret 管理系统或 `kubectl apply` 更新，保留签名、加密密钥和业务密码。Redis / MQ
地址必须能从业务 Pod 解析和访问。

## 3. 准备 values

复制 `deploy/production-values.yaml.example` 到 `/private/production-values.yaml`，设置外部 URL、
imagePullSecrets 与需要的 Ingress/TLS。示例默认开启鉴权，关闭 Local 工具和用户文件，五个角色
各两个副本。开放文件时配置：

```yaml
userFilesEnabled: true
persistence:
  workspaces:
    storageClass: YOUR_RWX_STORAGE_CLASS
    accessModes: [ReadWriteMany]
  artifacts:
    storageClass: YOUR_RWX_STORAGE_CLASS
    accessModes: [ReadWriteMany]
```

逐角色 `existingSecret` 可分离凭据。可选 `managed.mq.existingSecret`、`data.existingSecret` 用于业务
连接覆盖，`schema.dataExistingSecret` 只用于迁移，默认都不引入云依赖。连接预算应包括所有副本
连接池、Brain 原生日志连接和迁移 Job。

## 4. 安装指定版本

填写五个已构建并推送镜像的实际仓库和版本，使用当前源码 Chart 或同一新 Kubernetes 制品中的
Chart 压缩包。不要为此架构选择公共仓库中的旧 BETA1 Chart。

```bash
export IMAGE_REPOSITORY=registry.example.com/agentscope
export SERVICE_VERSION=2.1.0-candidate.1
export CHART=helm/agentscope-service
helm upgrade --install service-schema "$CHART" --namespace agentscope \
  -f /private/production-values.yaml --set imageRepository="$IMAGE_REPOSITORY" \
  --set imageTag="$SERVICE_VERSION" --set schema.only=true --wait --timeout 10m
sh deploy/postgres-roles.sh
helm upgrade --install service "$CHART" --namespace agentscope \
  -f /private/production-values.yaml --set imageRepository="$IMAGE_REPOSITORY" \
  --set imageTag="$SERVICE_VERSION" --wait --timeout 10m
kubectl -n agentscope get pods
kubectl -n agentscope port-forward service/service-agentscope-gateway 18080:8080
```

四个 owner Job 分别迁移 Control（`cp` + `rt`）、Portal（`portal`）、Dataplane（`dp`）和 Managed
（`ma`）。全部成功后执行授权，再启动业务副本。普通组件只校验版本和 checksum，不执行 DDL。
Helm 的幂等迁移 hook 不会自动重授权，新增表的升级也需遵循先迁移、授权，再更新业务的顺序。

## 5. 验证用户路径

检查 Gateway 健康、bootstrap 登录与 Agent 创建，配置 [Environment](/v2/zh/service/environments) 并将 ID 导出为 `ENVIRONMENT_ID`；关闭用户文件时，Session 准入仍要求 Environment 绑定。在 `agentscope-service` 目录执行以下命令，或
使用 Kubernetes 包内的 `smoke.py`，验证原生 Session 与 Events：

```bash
python3 release/smoke.py --base http://127.0.0.1:18080 \
  --env-file /private/service.env --environment-id "$ENVIRONMENT_ID" --state /tmp/service-smoke.json
# 有效模型配置下，用新 state 文件加 --model-turn 验证 Turn。
# 重启组件后，复用对应 state 文件并加 --resume。
```

仅资源检查不代表推理已验收。还需在目标 Environment 跑工具 Turn，检查 SSE 增量、断线重连和
副本重启后未完成工作的恢复。参见[第一个 Managed Agent](/v2/zh/service/create-managed-agent)。

## 升级、卸载与运行模式

不兼容升级前，备份 PostgreSQL（含私有对象）、启用的文件卷与原 Vault/签名密钥，停止受理并
排空 Turn、工具与渠道回复。停用旧 Scheduler/orchestration，再启动合并的 Control Plane。执行新
镜像的迁移任务、重新授权，然后同步替换兼容的组件。

当前 `dp`/`ma` 基线拒绝不兼容的旧版本、checksum 与无版本 Managed 数据，不自动转换旧运行状态
或导入文件对象。使用新的运行数据空间或经过验证的迁移，不能删除迁移记录绕过校验。回退镜像
不会撤销 schema 变更，Helm 卸载保留 PVC。

## 部署边界与生产规划

五个角色均使用 RollingUpdate Deployment，可独立扩容。Brain 的 Harness 容量与 MQ 回调池配置在
`dataplane`，物理工具并发配置在 `hands`。挂起释放活动资源并保留 Session FIFO 顺序；MCP 批次可
从持久记录直接恢复，其他等待使用 Resume。数据库负责跨副本持久协调，无需粘性 Session 路由。

自托管 Service 部署整个平台；`self_hosted` 工具 Worker 提供工具环境，Runtime Host 执行 Hosted
Coding Agent。三者职责分离。Kubernetes-native ControlPlane/ASDP 是另一种部署模式，不是完整
Service 的额外必装组件。参见 [Environment](/v2/zh/service/environments) 和
[Runtime Host](/v2/zh/service/runtime-host)。

## 入口与网络

| 组件 | 端口 | 暴露方式 |
| --- | --- | --- |
| Gateway | 8080 | 公共 API / Console 入口；Compose 默认本机 18080 |
| Control Plane | 8081 | 内部 |
| Brain Dataplane | 8082 | 内部 |
| Session Portal | 8084 | 内部 |
| Hands | 8085 | 内部 |
| PostgreSQL / Redis / MQ | 按依赖配置 | 私有依赖网络 |

## 启用远程访问

HTTPS 入口指向 Gateway，Helm 配置 `publicURL`，Compose 配置 `BUILDER_OAUTH_PUBLIC_URL`，内部
组件保持私网访问。SSE 禁用缓冲与缓存，设置足够长的读取超时。验证增量事件、重连、OAuth /
渠道回调以及实际外部 URL。

## 使用发布版 Docker Compose

使用明确包含当前架构的新 Compose 包和对应 `SHA256SUMS`。校验后解压并进入 `agentscope-service`
目录，按制品清单填写实际镜像版本和仓库：

```bash
./init-env.sh YOUR_VERSION REGISTRY/NAMESPACE
# 在 .env 填写容器可达的 Redis/MQ 地址、MQ 认证/TLS 与模型凭据。
# 保持 BUILDER_LOCAL_DEV=false。
docker compose config --quiet
docker compose pull
docker compose up -d --wait --wait-timeout 600
python3 smoke.py --base http://127.0.0.1:18080 --env-file .env --environment-id "$ENVIRONMENT_ID" --state /tmp/service-smoke.json
```

Compose 启动 PostgreSQL，自动完成四个迁移任务和业务授权后启动服务。Redis/MQ 仍由运维提供，
五组 Topic/消费组需提前创建。只有 Gateway 对外开放。Local 工具需显式开启
`BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`，在 Hands 执行。`docker compose down` 保留卷；旧组件
排空后可用 `--remove-orphans` 清理。参见[运维](/v2/zh/service/operations#compose-operations)。



<span id="self-hosting"></span>
<span id="三个不同的部署对象"></span>
<span id="选择部署路径"></span>
<span id="部署后的交接"></span>
<span id="持续运营"></span>

<span id="production-api-access"></span>

## 认证与权限

生产环境保持 `BUILDER_LOCAL_DEV=false`。发布 Compose 配置默认关闭本地开发模式，完整 Service Helm Chart 也关闭它。首次用 `.env` 或 Secret 中的 `CONTROL_PLANE_BOOTSTRAP_ADMIN`、`CONTROL_PLANE_BOOTSTRAP_PASSWORD` 登录 Console，再在 Profile 中修改密码。

本地教程省略认证请求头。把同样的 API 用于生产时，先完成下面的登录与空间选择：

```bash
export BASE_URL="https://agentscope.example.com"
```

输入平台账号和密码。首次部署使用 `admin`，密码以 `.env` 或 Profile 中修改后的值为准。

```bash
printf 'Username: '
IFS= read -r LOGIN_USER
printf 'Password: '
IFS= read -r -s LOGIN_PASSWORD
printf '\n'

LOGIN_JSON=$(
  jq -n --arg username "$LOGIN_USER" --arg password "$LOGIN_PASSWORD" \
    '{username: $username, password: $password}' \
  | curl -sS --fail-with-body "$BASE_URL/api/auth/login" \
      -H "Content-Type: application/json" \
      --data-binary @-
)
unset LOGIN_PASSWORD
TOKEN=$(jq -er '.token' <<< "$LOGIN_JSON")

curl -sS --fail-with-body "$BASE_URL/api/v1/me/namespaces" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '.items[] | {tenant, name}'
```

从结果中选择一个空间，将它的 `tenant` 和 `name` 分别填入前面的 `TENANT` 和 `NAMESPACE`。平台 token 用于管理资源；应用调用凭据见[应用接入](/v2/zh/service/service-api)。

```bash
export TENANT="YOUR_AUTHORIZED_TENANT"
export NAMESPACE="YOUR_AUTHORIZED_NAMESPACE"
```

管理请求携带以下请求头；请求体和查询参数中的空间应与所选空间一致：

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

账号、空间角色、资源授权与私有工作访问的配置见[生产权限管理](/v2/zh/service/access)。工具确认允许使用显式授权 `approve` 的应用 API key，只有 `interact` 不能确认工具；指定人员审批仍需该用户身份。外部工具、模型和 OAuth 提供方仍有各自的凭据，本地免鉴权模式不会跳过它们。

<span id="production-application-credentials"></span>

## 应用调用凭据

先按[创建 Agent](/v2/zh/service/create-managed-agent)准备实际 `AGENT_ID`；管理操作由 Application 所有者执行。

接入业务后端时，建议创建 Application，为它签发独立的 API key，并明确授予它可以使用的 Agent、Team 或 Workflow。凭据属于 Application，因此同一应用更换 key 后仍能访问自己的 Session；其他应用即使使用同一个 Agent，也不能读取这些记录。API key 应保存在业务后端，由后端检查最终用户的业务权限。


```bash
set -euo pipefail

APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "name": "report-application"
}
JSON
)
APPLICATION_ID=$(jq -er '.application.id' <<< "$APPLICATION_JSON")
```

为应用签发能够调用这个 Agent 的凭据：

```bash
KEY_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "name": "backend",
  "scopes": [
    "invoke",
    "read",
    "interact",
    "cancel",
    "webhooks:write"
  ],
  "targets": [
    {
      "type": "agent",
      "id": "$AGENT_ID"
    }
  ]
}
JSON
)
AGENTSCOPE_API_KEY=$(jq -er '.apiKey' <<< "$KEY_JSON")
CREDENTIAL_ID=$(jq -er '.credential.id' <<< "$KEY_JSON")
```

服务只在创建凭据时返回明文 key。轮换时先签发新 key，更新应用配置并确认调用成功，再撤销旧凭据。`invoke` 允许提交工作，`read` 允许读取记录，`interact` 允许补充输入，`cancel` 允许取消；注册和管理回调还需要 `webhooks:write`。应用如需确认工具，须额外显式授予 `approve`；这些 scope 不会把应用变成指定的人工审批人。

<Accordion title="列出与撤销旧凭据">

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

先使用上面的签发接口创建并验证新凭据。确认后，将 `OLD_CREDENTIAL_ID` 替换为列表中需要撤销的旧凭据 ID；此操作会停用旧 key。

```bash
OLD_CREDENTIAL_ID="OLD_CREDENTIAL_ID_FROM_LIST"
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials/$OLD_CREDENTIAL_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

应用调用 Session API 时，将本地教程中的无凭据请求改为携带 `-H "X-API-Key: $AGENTSCOPE_API_KEY"`。指定人工审批时改用用户 Bearer token。Python 客户端使用 `ServiceClient(BASE_URL, AGENTSCOPE_API_KEY)`；管理客户端使用用户 token。


### 应用额度

先读取当前版本，再调整 Application 的并发和 token 额度：

```bash
APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
APPLICATION_VERSION=$(jq -er '.application.version' <<< "$APPLICATION_JSON")

curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "version": $APPLICATION_VERSION,
  "maxConcurrent": 5,
  "tokenBudget": 1000000
}
JSON
```

<span id="production-cli-install"></span>

## 安装生产 CLI 与 Runtime Host

优先使用与 Service 同一源码构建的 CLI 安装包安装两个命令。若对应发布已有 Go 模块 tag，也可用 Go 1.26+ 安装匹配版本：

```bash
export SERVICE_VERSION=YOUR_CURRENT_RELEASE
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/as@v"$SERVICE_VERSION"
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/agentscope-runtime-host@v"$SERVICE_VERSION"
as connect https://agentscope.example.com
```

按[Runtime Host 指南](/v2/zh/service/runtime-host#使用-go-安装)配置 PATH，替换实际服务地址，按 CLI 提示登录或使用短期 enrollment token。Provider 自身仍需单独安装和认证。
