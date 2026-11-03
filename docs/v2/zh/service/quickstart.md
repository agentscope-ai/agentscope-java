---
title: "部署 AgentScope Service"
description: "通过 Docker Compose 启动 AgentScope Service，登录控制台并运行第一个 Agent。"
en_link: /v2/en/service/quickstart
---

使用 Docker Compose 在本机启动 AgentScope Service，然后在浏览器中创建并使用 Agent。本页使用已发布的 `2.1.0-BETA1` 预发布版本。

准备好 Docker Engine 或 Docker Desktop（含 Compose v2）、Bash、curl、OpenSSL，以及一个 DashScope API Key。如果团队已有 Service，可直接使用管理员提供的地址和账号，从第 3 步开始。

<span id="1-启动"></span>

<span id="准备"></span>
<span id="1-初始化部署并配置模型"></span>

## 1. 下载并配置

下载 Compose 安装包，解压后生成配置文件：

```bash
curl -fLO https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/agentscope-service-2.1.0-BETA1-compose.tar.gz
tar -xzf agentscope-service-2.1.0-BETA1-compose.tar.gz
cd agentscope-service
./init-env.sh 2.1.0-BETA1 sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope
```

编辑生成的 `.env`，填入自己的 DashScope API Key，并为本地体验启用工具执行：

```dotenv
DASHSCOPE_API_KEY=YOUR_DASHSCOPE_API_KEY
BUILDER_ALLOW_LOCAL_ENVIRONMENT=true
```

Local 工具在 Dataplane 容器内执行。其他模型和执行环境的配置见[Agent 配置](/v2/zh/service/managed-agent-configuration)与 [Environment](/v2/zh/service/environments)。

<span id="2-启动并登录"></span>

## 2. 启动服务

```bash
docker compose up -d --wait --wait-timeout 600
```

Compose 会自动拉取镜像并启动数据库和全部 Service 组件。命令成功结束后，打开 [http://localhost:18080](http://localhost:18080)。

若启动失败，运行 `docker compose ps -a` 查看状态，再用 `docker compose logs --tail=100` 查看错误。

<span id="2-登录"></span>
<span id="3-配置执行能力"></span>

## 3. 登录并开始使用

首次部署使用用户名 **`admin`**，密码是 `.env` 中 **`CONTROL_PLANE_BOOTSTRAP_PASSWORD`** 的值。登录后在 **Profile** 中修改密码。

打开 **Design → Agents → New agent**，填写名称（例如 `notes-assistant`）和 **Instructions**，将 **Runtime** 选择为 **AgentScope Managed**，然后点击 **Create & open agent**。**Model** 留空即可使用刚才配置的默认模型。

在 Agent 详情页进入 **Connections → Session API**，填写 **Task message**（例如“把这段会议记录整理成待办：小李周五完成安装说明，下周一评审”），点击 **Create Session and submit**。首次体验可将 **Application API key** 留空，使用当前登录身份。等待 Turn 完成并查看回复，即可确认模型调用成功；完整页面操作见[可视化 Console](/v2/zh/service/console/index)。

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

接下来可以[通过 API 运行第一个托管 Agent](/v2/zh/service/create-managed-agent)，完成文件工具验证与应用接入准备。生产部署、网络和远程访问见[生产安装](/v2/zh/service/kubernetes#self-hosting)，持久化、升级与备份见[运维指南](/v2/zh/service/operations#compose-operations)。
