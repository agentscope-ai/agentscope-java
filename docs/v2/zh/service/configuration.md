# 配置参考

Docker 在 `.env` 中配置；Kubernetes 将敏感项放入已有 Secret，通过 Chart values 配置入口与存储。

| 配置 | 用途 | 注意事项 |
| --- | --- | --- |
| `IMAGE_REPOSITORY` / `SERVICE_VERSION` | Compose 镜像命名空间与版本 | 使用发布说明中的明确版本 |
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
| `BUILDER_SEED_USERS` | Java 旧账号表初始化 | 发布配置固定为 `false` |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | 是否允许 Local Environment | 默认 `false` |
| `BUILDER_OAUTH_PUBLIC_URL` | 用户可访问的公开 origin | 必须与 OAuth 回调配置一致 |
| `DASHSCOPE_API_KEY` | 默认 DashScope 模型凭据 | 只在使用该模型路径时需要 |
| `BUILDER_E2B_API_KEY` | E2B 环境凭据 | 仅对应 Sandbox 路径需要 |

## 文件与组件地址

发布配置统一挂载 `/data/workspaces`。控制面使用 `AISTIO_WORKSPACE_ROOT`，Java 使用 `BUILDER_WORKSPACE_ROOT`。产物目录由 `AISTIO_ARTIFACT_ROOT` 指定。

`BUILDER_CONTROL_URL`、`BUILDER_DATA_URL`、`BUILDER_SCHEDULER_URL` 是组件内部可达地址。不要用浏览器里的 `localhost` 替代它们。

## 版本与迁移

当前 Dataplane/Scheduler 默认使用 Hibernate `update`，Go 在启动时执行迁移。没有完成独立版本化 SQL 迁移前，不应直接把 `BUILDER_JPA_DDL_AUTO` 改成 `validate` 并期待新数据库自动初始化。升级前按[运维指南](operations.md)备份和演练。
