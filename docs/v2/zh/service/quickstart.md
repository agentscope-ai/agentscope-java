---
title: "本地安装与快速启动"
---

[English](/v2/en/service/quickstart)

使用正式发布的 Compose 安装包启动完整 Service，无需从源码构建。它包含 Gateway、Control、Dataplane、Scheduler 和 PostgreSQL，适合本机体验或单机安装。

## 准备

安装 Docker Engine 或 Docker Desktop、Compose v2 和 OpenSSL，确认 `docker info` 与 `docker compose version` 正常。模型任务需要你自己的模型凭据。先保证磁盘能保存数据库与工作文件；实际 CPU/内存需求取决于并发和工具负载。

从 [Release 下载页](https://github.com/agentscope-ai/agentscope-java/releases)选择版本，下载 `agentscope-service-VERSION-compose.tar.gz` 和 `SHA256SUMS`。比较压缩包的 SHA-256 与清单中的对应条目：Linux 使用 `sha256sum`，macOS 使用 `shasum -a 256`。下面的 VERSION 与 REGISTRY/NAMESPACE 使用该 Release 公布的准确值，镜像仓库路径不包含 `https://`。

## 1. 启动

```bash
tar -xzf agentscope-service-VERSION-compose.tar.gz
cd agentscope-service
./init-env.sh VERSION REGISTRY/NAMESPACE
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

初始化生成权限为 `600` 的 `.env`，保存数据库密码、JWT 密钥、内部令牌、Vault 密钥和初始管理员密码。重复运行脚本保留原文件，不更新版本或重置密码。

## 2. 登录

```bash
docker compose ps
curl -fsS http://localhost:18080/actuator/health
```

确认整栈健康后打开 `http://localhost:18080`，用 `admin` 与 `.env` 中的 `AISTIO_BOOTSTRAP_PASSWORD` 登录，在 Profile 修改密码。初始管理员只在空用户库中创建，重启不重置已有账号。

## 3. 配置执行能力

可信本地体验可以编辑 `.env`：

```dotenv
BUILDER_ALLOW_LOCAL_ENVIRONMENT=true
DASHSCOPE_API_KEY=YOUR_MODEL_CREDENTIAL
```

填写实际模型凭据，重新执行 `docker compose up -d --wait --wait-timeout 600`。Local 工具运行在 Dataplane 容器内，主机文件并不自动挂载。随后按[第一次对话与交付](/v2/zh/service/first-session)创建 Managed Agent。

已有 Coding Agent 时，也可走 [Hosted Agent](/v2/zh/service/hosted-agent) 路径。需要隔离工具时保持 Local 关闭，配置相应 Environment。

## 停止、继续与排错

`docker compose down` 停止服务，数据卷仍保留；重新运行启动命令即可继续。不要在日常停止时加 `-v`，它会删除数据卷。

启动失败先运行 `docker compose ps -a` 和 `docker compose logs --tail=100`，检查镜像拉取、数据库和组件错误。端口冲突可修改 `.env` 的 `GATEWAY_PORT`，并同步修改 `BUILDER_OAUTH_PUBLIC_URL` 后重建容器。

下一步：[Docker 远程访问与存储](/v2/zh/service/docker) · [生产 Helm 安装](/v2/zh/service/kubernetes)。
