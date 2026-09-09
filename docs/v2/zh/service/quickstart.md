# Docker 快速上手

目标：启动完整 Service，登录控制台，然后进入第一个 Agent 会话。

## 开始前

准备 Docker Engine 或 Docker Desktop、Compose v2、OpenSSL。发布镜像部署不需要 Maven、Go 或 Node.js。运行模型任务还需要模型凭据。

```bash
docker info
docker compose version
```

从所选版本的 Release 下载 `agentscope-service-VERSION-compose.tar.gz` 与 `SHA256SUMS`，按发布说明校验压缩包，再解压。下面的 `VERSION` 和 `REGISTRY/NAMESPACE` 要替换为该版本公布的值。

## 1. 初始化并启动

```bash
tar -xzf agentscope-service-VERSION-compose.tar.gz
cd agentscope-service
./init-env.sh VERSION REGISTRY/NAMESPACE
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

初始化脚本在权限为 `600` 的 `.env` 中生成数据库密码、JWT 密钥、内部令牌、Vault 密钥和管理员密码。重复执行保留已有配置。请在本地查看文件，不要把它提交到仓库。

## 2. 登录并确认状态

```bash
docker compose ps
curl -fsS http://localhost:18080/actuator/health
```

打开 `http://localhost:18080`，用 `admin` 和 `.env` 中的 `AISTIO_BOOTSTRAP_PASSWORD` 登录，在 Profile 修改密码。正式部署包不创建 `alice`、`bob` 等演示账号。初始化密码只用于空数据库，重启不会重置已有账号。

## 3. 配置执行环境

用于可信本地体验时，可以在 `.env` 中设置 `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`，并填写模型凭据，例如 `DASHSCOPE_API_KEY`，然后重新执行启动命令。Local 工具运行在 Dataplane 容器内。

其他部署保持 Local 关闭，在控制台配置 Sandbox 或 Self-hosted Environment。接着完成[第一个 Session](first-session.md)。

## 停止与继续

```bash
docker compose down
docker compose up -d --wait --wait-timeout 600
```

数据库、Workspace 和 Artifact 卷会保留。`down -v` 会删除数据卷，不能用作日常停止命令。远程访问和升级见 [Docker 部署](docker.md) 与[运维](operations.md)。
