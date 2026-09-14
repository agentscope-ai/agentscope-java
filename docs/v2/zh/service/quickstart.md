---
title: "本地安装"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

本页面向本地开发者，使用 Docker Compose 启动完整 AgentScope Service，无需从源码构建。正式安装包包含 Gateway、Control、Dataplane、Scheduler 和 PostgreSQL，适合本机体验、功能开发与联调。生产环境的 Kubernetes/Helm 部署见[生产安装](/v2/zh/service/kubernetes)。

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

填写实际模型凭据，重新执行 `docker compose up -d --wait --wait-timeout 600`。Local 工具运行在 Dataplane 容器内，主机文件并不自动挂载。随后按[快速开始](/v2/zh/service/first-session)创建 Managed Agent。

已有 Coding Agent 时，也可走 [Hosted Agent](/v2/zh/service/hosted-agent) 路径。需要隔离工具时保持 Local 关闭，配置相应 Environment。

## 入口与网络

| 组件 | 容器端口 | 暴露方式 |
| --- | --- | --- |
| Gateway | 8080 | 默认宿主 `127.0.0.1:18080` |
| Control | 8081 | 内部网络 |
| Dataplane | 8082 | 内部网络 |
| Scheduler | 8083 | 内部网络 |
| PostgreSQL | 5432 | 内部网络 |

同机反向代理可以转发到 `127.0.0.1:18080`。代理运行在另一个容器时，localhost 指代理容器自身，需要配置共享网络或可达的宿主地址。只向用户暴露 Gateway，内部组件和数据库保留在私网。

## 启用远程访问

需要从其他设备访问本地服务，或联调 OAuth/Channel 公网回调时，再配置这一部分。准备域名和 TLS 证书，将 HTTPS 代理指向 Gateway。在 `.env` 设置 `BUILDER_OAUTH_PUBLIC_URL=https://agentscope.example.com`，如需监听其他地址再设置 `BIND_ADDRESS` 和 `GATEWAY_PORT`，之后重建容器。

代理必须支持 SSE：及时转发事件、不缓存事件流，并设置足够长的读取超时。验证登录、长回复、刷新重连及 OAuth/Channel 回调，不能只验证首页可打开。

## 数据持久化

三个命名卷分别保存 PostgreSQL、共享 Workspace 和 Artifact。使用 `docker volume ls` 找到 Compose 项目卷，按存储策略备份。`.env` 中的 Vault master key 必须和加密数据一起保存。

需要接入宿主目录时显式配置挂载，并保证容器用户 `65532:65532` 有必要访问权限。不要把本地路径写入 Agent 指令后就假定容器能够读取。业务资料的访问应与所选 Environment 保持一致。

## 更新配置和版本

编辑 `.env` 后：

```bash
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

版本升级还需先拉取新镜像。`init-env.sh` 不覆盖已有配置，因此修改版本应编辑 `SERVICE_VERSION`。密钥变更需要协调组件；Vault master key 不是可随意替换的普通密码。

完整 Compose 运行 standalone HTTP。需要 ASDP 的 External SDK 请使用[对应接入部署](/v2/zh/service/external-agent)。生产环境安装见[生产安装](/v2/zh/service/kubernetes)。升级前完成[备份恢复演练](/v2/zh/service/operations)。

## 停止、继续与排错

`docker compose down` 停止服务，数据卷仍保留；重新运行启动命令即可继续。不要在日常停止时加 `-v`，它会删除数据卷。

启动失败先运行 `docker compose ps -a` 和 `docker compose logs --tail=100`，检查镜像拉取、数据库和组件错误。端口冲突可修改 `.env` 的 `GATEWAY_PORT`，并同步修改 `BUILDER_OAUTH_PUBLIC_URL` 后重建容器。

下一步：[快速开始](/v2/zh/service/first-session) · [生产安装](/v2/zh/service/kubernetes)。
