---
title: 配置参考
en_link: /v2/en/service/configuration
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
| `CONTROL_PLANE_PRODUCT_DSN` | 控制面产品数据库 | 使用 `cp` schema |
| `CONTROL_PLANE_STORAGE_DSN` | 控制面运行状态数据库 | 设置 `search_path=rt` |
| `BUILDER_DB_URL` / `USER` / `PASSWORD` | Java JDBC 连接 | 完整名称为 `BUILDER_DB_USER`、`BUILDER_DB_PASSWORD`；使用 `dp` |
| `BUILDER_JWT_SECRET` | 用户令牌签名 | 至少 32 字符，相关组件一致 |
| `BUILDER_INTERNAL_TOKEN` | 组件间认证 | 至少 32 字符，不用于用户登录 |
| `BUILDER_VAULT_MASTER_KEY` | 凭据加密 | 各组件一致，随数据备份 |
| `CONTROL_PLANE_BOOTSTRAP_ADMIN` / `PASSWORD` | 空数据库初始管理员 | 密码完整名称为 `CONTROL_PLANE_BOOTSTRAP_PASSWORD`，12–72 字节 |
| `CONTROL_PLANE_SEED_USERS` | Go 演示账号初始化 | 发布配置固定为 `false` |
| `BUILDER_SEED_USERS` | Java 演示账号初始化 | 发布配置固定为 `false` |
| `BUILDER_ALLOW_LOCAL_ENVIRONMENT` | 是否允许 Local Environment | 默认 `false` |
| `BUILDER_OAUTH_PUBLIC_URL` | 用户可访问的公开 origin | 必须与 OAuth 回调配置一致 |
| `DASHSCOPE_API_KEY` | 默认 DashScope 模型凭据 | 只在使用该模型路径时需要 |
| `BUILDER_E2B_API_KEY` | E2B 环境凭据 | 仅对应 Sandbox 路径需要 |

<span id="agent-api-配置" />

## 统一服务调用的配置

Session / Turn 在控制面保存调用、事件、命令和 Webhook 状态，使用持久 Store 后可跨进程恢复。以下属于控制面进程配置，与后面的 Managed Dataplane 配置分别生效：

| 配置 | 默认或来源 | 用途 |
| --- | --- | --- |
| `service-controlplane --service-event-retention` | `720h`，`0` 关闭清理 | 清理超过保留期的终态 Turn 增量事件，累计快照保留；旧 cursor 返回 410 后重新读取 snapshot |
| `CONTROL_PLANE_ENDPOINT_CREDENTIAL_KEY` | 未设置时回退平台 JWT secret | 加密 Webhook 签名密钥；保留历史配置名，多副本和备份恢复时保持一致。应用 API key 只保存哈希 |
| `service-controlplane --enable-asdp` | `true` | 初始化运行通道处理器；当前 HTTP worker 也复用它，HTTP 传输不要求 worker 可访问 gRPC 端口 |

Application 的并发和 token 预算通过 Application API 管理；Session 的超时、单次任务预算和输入输出契约在创建会话时确定。公共 Session Webhook 使用控制面的 HTTPS 目标校验和签名投递，数据面原生 Webhook 的 allowed-hosts 配置不控制这条公共调用路径。

## Managed 原生会话 API 配置

这些是 **Dataplane 的 Spring 配置项**。通过实际加载的 application.yml、启动参数或传入进程的环境配置设置；仅往 Compose 的 `.env` 加一行不会自动传给容器，需要在服务定义中映射并重建 data 容器。

| 配置项 | 默认值 | 用途 |
| --- | --- | --- |
| `builder.agent-api.files.max-bytes` | `16777216` | 单文件上传上限（16 MiB），不改变模型或上下文限制 |
| `builder.agent-api.webhooks.allowed-hosts` | 空 | 允许投递的精确主机名，多个以逗号分隔；仅 HTTPS 443，默认不允许注册目的地址 |
| `builder.agent-api.webhooks.poll-ms` | `2000` | Webhook 投递轮询间隔（毫秒） |
| `builder.agent-api.pricing.models` | `{}` | JSON 字符串：模型名到每百万输入/输出 token 单价的映射 |
| `builder.agent-api.pricing.currency` | `USD` | 估算费用币种；应与 max_cost 预算一致 |
| `builder.agent-api.trace-enabled` | `false` | 管理员诊断；启用后仍检查 session 所有者及 ROLE_ADMIN / ROLE_SESSION_TRACE |
| `builder.agent-api.inbox-poll-ms` | `1000` | 持久任务队列的派发轮询间隔（毫秒） |
| `builder.agent-api.export-poll-ms` | `5000` | 原生日志公共事件补导出轮询间隔（毫秒），不是 SSE token 刷新周期 |

在 Dataplane 实际加载的 YAML 中，例如：

```yaml
builder:
  agent-api:
    files:
      max-bytes: 16777216
    webhooks:
      allowed-hosts: notify.example.com
    pricing:
      currency: USD
      models: '{"your-model":{"input_per_million":1,"output_per_million":2}}'
```

替换为你的通知主机和模型标识。示例价格只用于展示格式，实际价格由部署方维护。未配置价格时费用显示不完整，不能按零费用处理。SSE 消息与工具增量默认持久保存，无需开启 preview。

多副本共享 Data Plane 数据库及支持条件写入的 BaseStore；工作文件的 Filesystem 也可使用分布式后端。日志位置见[存储说明](/v2/zh/service/session-event-log)。代理应关闭 SSE 缓冲、及时转发并配置足够的读取超时；15 秒心跳只保持连接，不表示模型有输出。

## 文件与组件地址

发布配置统一挂载 `/data/workspaces`。控制面使用 `CONTROL_PLANE_WORKSPACE_ROOT`，Java 使用 `BUILDER_WORKSPACE_ROOT`。产物目录由 `CONTROL_PLANE_ARTIFACT_ROOT` 指定。

`BUILDER_CONTROL_URL`、`BUILDER_DATA_URL`、`BUILDER_SCHEDULER_URL` 是组件内部可达地址。不要用浏览器里的 `localhost` 替代它们。

## 版本与迁移

Dataplane/Scheduler 默认使用 Hibernate `update`，Go 在启动时执行迁移。`BUILDER_JPA_DDL_AUTO=validate` 只校验已有表，不初始化新数据库；仅在已自行完成 schema 管理时使用。升级前按[运维指南](/v2/zh/service/operations)备份和演练。

## 修改后如何确认生效

先区分部署配置和 Agent 配置，按下面的范围验证：

| 修改位置 | 操作与验证 |
| --- | --- |
| Compose `.env` | 重新创建受影响容器；仅 `docker compose restart` 不会把新的环境变量应用到已有容器 |
| Helm values / Secret | 按生产安装流程更新，并确认受影响 Pod 使用新配置；环境变量不会在已有进程中自动刷新 |
| Agent Instructions / Definition | 通过 definition API 更新；按需发布 Workspace revision，并创建新 Session 验证 |
| Session defaults | 新建 Session 验证继承值；已有 Session 的显式选择需要单独检查 |
| Memory 文档正文 | 要求 Agent 再次读取；旧回复不会因知识更新自动改写 |

例如修改默认模型凭据后，在安装目录按[部署准备](/v2/zh/service/quickstart)的 Compose 流程重新创建服务，检查健康状态，再通过 API 创建新 Managed 会话并提交简单请求。模型请求成功后，再执行[CRM 方案交付案例](/v2/zh/service/cases/in-product-delivery)的固定输入检查；如果进一步接入 Memory，再验证资源读取，可以分别定位模型配置和资源绑定问题。

验收记录保留修改项名称、应用版本、重建时间和新 Session ID；不记录密钥原文。修改 bootstrap 配置不会覆盖数据库中已存在的管理员密码，处理方式见[账号参考](/v2/zh/service/access)。
