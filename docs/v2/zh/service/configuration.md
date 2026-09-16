---
title: 配置参考
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Docker 在 `.env` 中配置；Kubernetes 将敏感项放入已有 Secret，通过 Chart values 配置入口与存储。

| 配置 | 用途 | 注意事项 |
| --- | --- | --- |
| `IMAGE_REPOSITORY` / `SERVICE_VERSION` | Compose 镜像命名空间与版本 | 使用发布说明中的明确版本 |
| `BIND_ADDRESS` / `GATEWAY_PORT` | Compose 对外监听 | 默认 `127.0.0.1` / `18080`，改变后同步公开 URL |
| `POSTGRES_DB` | Compose 数据库名称 | 默认 `agentscope`，恢复后可切换到新数据库 |
| `POSTGRES_PASSWORD` | Compose 内置数据库密码 | 初始化后保持稳定，使用 URL-safe 值 |
| `AISTIO_PRODUCT_DSN` | 控制面产品数据库 | 使用 `cp` schema |
| `AISTIO_STORAGE_DSN` | 控制面运行状态数据库 | 设置 `search_path=rt` |
| `BUILDER_DB_URL` / `USER` / `PASSWORD` | Java JDBC 连接 | 完整名称为 `BUILDER_DB_USER`、`BUILDER_DB_PASSWORD`；使用 `dp` |
| `BUILDER_JWT_SECRET` | 用户令牌签名 | 至少 32 字符，相关组件一致 |
| `BUILDER_INTERNAL_TOKEN` | 组件间认证 | 至少 32 字符，不用于用户登录 |
| `BUILDER_VAULT_MASTER_KEY` | 凭据加密 | 各组件一致，随数据备份 |
| `AISTIO_BOOTSTRAP_ADMIN` / `PASSWORD` | 空数据库初始管理员 | 密码完整名称为 `AISTIO_BOOTSTRAP_PASSWORD`，12–72 字节 |
| `AISTIO_SEED_USERS` | Go 演示账号初始化 | 发布配置固定为 `false` |
| `BUILDER_SEED_USERS` | Java 演示账号初始化 | 发布配置固定为 `false` |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | 是否允许 Local Environment | 默认 `false` |
| `BUILDER_OAUTH_PUBLIC_URL` | 用户可访问的公开 origin | 必须与 OAuth 回调配置一致 |
| `DASHSCOPE_API_KEY` | 默认 DashScope 模型凭据 | 只在使用该模型路径时需要 |
| `BUILDER_E2B_API_KEY` | E2B 环境凭据 | 仅对应 Sandbox 路径需要 |

## 文件与组件地址

发布配置统一挂载 `/data/workspaces`。控制面使用 `AISTIO_WORKSPACE_ROOT`，Java 使用 `BUILDER_WORKSPACE_ROOT`。产物目录由 `AISTIO_ARTIFACT_ROOT` 指定。

`BUILDER_CONTROL_URL`、`BUILDER_DATA_URL`、`BUILDER_SCHEDULER_URL` 是组件内部可达地址。不要用浏览器里的 `localhost` 替代它们。

## 版本与迁移

Dataplane/Scheduler 默认使用 Hibernate `update`，Go 在启动时执行迁移。`BUILDER_JPA_DDL_AUTO=validate` 只校验已有表，不初始化新数据库；仅在已自行完成 schema 管理时使用。升级前按[运维指南](/v2/zh/service/operations)备份和演练。

## 修改后如何确认生效

先区分部署配置和 Agent 配置，按下面的范围验证：

| 修改位置 | 操作与验证 |
| --- | --- |
| Compose `.env` | 重新创建受影响容器；仅 `docker compose restart` 不会把新的环境变量应用到已有容器 |
| Helm values / Secret | 按生产安装流程更新，并确认受影响 Pod 使用新配置；环境变量不会在已有进程中自动刷新 |
| Agent Instructions / Definition | 按编辑页面保存、发布并绑定目标 revision，然后新建工作验证实际使用的定义 |
| Session defaults | 新建 Session 验证继承值；已有 Session 的显式选择需要单独检查 |
| Memory 文档正文 | 要求 Agent 再次读取；旧回复不会因知识更新自动改写 |

例如修改默认模型凭据后，在安装目录按[本地安装](/v2/zh/service/quickstart)的 Compose 流程重新创建服务，检查健康状态，再新建 Managed Chat 提交一句简单请求。模型请求成功后，再执行[售前方案团队](/v2/zh/service/cases/presales-team)的知识读取检查，可以分别定位模型配置和资源绑定问题。

验收记录保留修改项名称、应用版本、重建时间和新 Session ID；不记录密钥原文。修改 bootstrap 配置不会覆盖数据库中已存在的管理员密码，处理方式见[账号参考](/v2/zh/service/access)。
