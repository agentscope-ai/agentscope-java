---
title: "部署 AgentScope Service"
description: "通过 Docker Compose 启动 AgentScope Service，免登录打开控制台并运行第一个 Agent。"
en_link: /v2/en/service/quickstart
---

使用 Docker Compose 在本机启动 Service，然后直接打开 Console 或调用 API。本地开发模式自动使用开发身份和默认空间，无需登录、用户 token、应用 API key 或授权配置。

准备 Git、OpenSSL、Docker Engine 或 Docker Desktop（含 Compose v2）、DashScope API Key，以及容器可达的 Redis 和 RocketMQ Cluster Proxy。启动前先初始化[五个业务 Lite Topic 与消费组](/v2/zh/service/kubernetes#prepare-rocketmq)。首次启动会在容器内构建服务，所需的 Java、Go 和 Node.js 构建工具由 Docker 提供。

<Note>
免鉴权模式需要包含此功能的源码构建，已发布的 `2.1.0-BETA1` 镜像不支持该模式。已发布镜像和 Helm 的安装与身份配置见[生产部署](/v2/zh/service/kubernetes)。
</Note>

## 1. 准备代码与模型

```bash
git clone https://github.com/agentscope-ai/agentscope-java.git
cd agentscope-java/agentscope-service
export DASHSCOPE_API_KEY="YOUR_DASHSCOPE_API_KEY"
```

已有源码时，直接进入 `agentscope-service` 目录并设置模型凭据。

## 2. 启动服务

```bash
./deploy/init-env.sh 2.1.0-local agentscope-local
# 在 deploy/.env 填写容器可达的 Redis/MQ 地址，以及需要的 MQ 认证和 TLS 设置。
export BUILDER_LOCAL_DEV=true
export BUILDER_ALLOW_LOCAL_ENVIRONMENT=true
docker compose --env-file deploy/.env config --quiet
docker compose --env-file deploy/.env up -d --build --wait --wait-timeout 600
```

上述显式开发配置开启开发身份与 Local 工具，Gateway 端口仅绑定本机。Local 工具在 Hands 容器中执行。Compose 启动 PostgreSQL，Redis 与 RocketMQ 由运维提供；四个迁移任务初始化 `cp/rt`、`portal`、`dp`、`ma`，业务授权完成后才启动服务。checkpoint 和大对象存入 PostgreSQL。模型及外部工具仍使用各自提供方的凭据。

首次构建可能需要几分钟。启动失败时，用 `docker compose --env-file deploy/.env ps -a` 查看状态，再用 `docker compose --env-file deploy/.env logs --tail=100` 查看错误。

## 3. 直接开始使用

打开 [http://localhost:18080](http://localhost:18080)，Console 自动进入开发身份。

打开 **Design → Agents → New agent**，填写名称和 **Instructions**，选择 **AgentScope Managed**，点击 **Create & open agent**。**Model** 留空即可使用默认模型。

继续按[第一个 Managed Agent](/v2/zh/service/create-managed-agent)配置 Environment、创建原生 Session，通过 `/events` 提交 `user.message` 并检查 Turn。该教程使用当前 `/v1/sessions` 契约，其他后端的兼容 API 是另一条路径。

也可以直接检查 API，无需携带认证请求头：

```bash
curl -sS --fail-with-body http://localhost:18080/api/v1/agents
```

接下来[通过 API 运行第一个托管 Agent](/v2/zh/service/create-managed-agent)。停止服务使用 `docker compose --env-file deploy/.env down`，数据卷会保留。生产部署、认证、权限与应用凭据见[生产指南](/v2/zh/service/kubernetes#production-api-access)，备份与升级见[运维指南](/v2/zh/service/operations)。

<span id="1-启动"></span>
<span id="准备"></span>
<span id="1-初始化部署并配置模型"></span>
<span id="2-启动并登录"></span>
<span id="2-登录"></span>
<span id="3-配置执行能力"></span>
<span id="self-hosting"></span>
<span id="三个不同的部署对象"></span>
<span id="选择部署路径"></span>
<span id="部署后的交接"></span>
<span id="持续运营"></span>
<span id="3-准备-api-身份与空间"></span>
<span id="4-准备工具执行环境"></span>
<span id="5-检查是否准备好"></span>
<span id="部署边界与生产规划"></span>
<span id="入口与网络"></span>
<span id="启用远程访问"></span>
<span id="数据持久化"></span>
<span id="更新配置和版本"></span>
<span id="停止继续与排错"></span>
<span id="1-下载并配置"></span>
<span id="3-登录并开始使用"></span>
