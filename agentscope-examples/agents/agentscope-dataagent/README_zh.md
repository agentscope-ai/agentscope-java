# agentscope-dataagent

> 🇬🇧 English version: [README.md](README.md)

## 项目概览

dataagent 让公司里**每位数据分析师都有一个属于自己的数据 agent** —— 它会摸熟你们组用的数据源、习惯的报表风格、踩过的坑，越用越顺手。

设计上有三件事最能说明它的不同：

- **多人并行进化、互不干扰。** 每个用户都有自己的私有 workspace。他教 agent 的技能、调出来的子智能体、积累下来的记忆，都是他自己的 —— 不会溢到别人那边。每个人拿到的初始 agent 是一样的，但它在不同人手里会长成不一样的模样。
- **能力市场，不是大杂烩。** 当某个人确实磨出了一份值得共享的东西 —— 一项 SQL 技能、一个子智能体、一段 memory 备忘 —— 可以把它提名出来；管理员审完通过后，这份内容会进到一个共享库里，下一次别人的 agent 启动就会自动看到。知识自下而上流动，但中间有道闸。
- **Sandbox 怎么管由你定。** agent 跑的每段脚本都在隔离的 sandbox 里。和 codingagent 不一样 —— codingagent 是运行时自己按 session 拉起、销毁容器的；dataagent 则把 sandbox 生命周期交给**你**：按你们安全和运维团队的口味去调容器规格、回收节奏，要带哪些数据库驱动和 notebook 工具链，全由你定。

dataagent 从设计起就考虑了分布式部署 —— 打开共享状态开关，任意副本都能服务任意用户，不需要 sticky session。

### 一览

| | dataagent |
|---|---|
| **适用场景** | 一组数据分析师，每人都需要一个会进化的 SQL / 图表 / 报表 agent，并希望把内部经验沉淀到共享库 |
| **用户数** | 多人 —— 每人一个私有 workspace，外加可选的侧边通道 |
| **隔离** | 按 `(用户, agent)` 划分的私有 workspace；脚本跑在隔离 sandbox |
| **自进化** | ✅ per-用户进化；**外加**经审批的贡献会进入共享库 |
| **Sandbox 生命周期** | **由应用方掌握** —— 在 agent 运行时之外完成创建、规格、回收 |
| **通道** | 内置 Web UI · 钉钉 · 通用 HTTP Webhook |
| **分布式** | ✅ 一等公民 —— 打开共享状态后，任意副本都能服务任意用户 |
| **文件系统** | 脚本执行用 `SandboxFilesystem`；私有 / 共享技能融合用 `OverlayFilesystem` |

### 架构

dataagent 跑在 **HarnessAgent + `SandboxFilesystem`** 之上，sandbox 生命周期由应用方持有（你决定 sandbox 何时、何处、以什么形态存在 —— 而不是运行时）。sandbox 之上挂着一个 `OverlayFilesystem`，把 per-用户的 `RemoteFilesystem`（上层、可写）和全局 `shared/` 目录（下层、只读，由 marketplace 审批流维护）融合起来。

```
┌───────────────────────────────────────────────────────────────────────┐
│  agentscope-dataagent（端口 8080，Spring Boot WebFlux）               │
│                                                                       │
│   React SPA ──▶ REST API (JWT)                                        │
│                  │                                                    │
│                  ▼                                                    │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  HarnessGateway                                                 │ │
│   │   ├ data-agent              （内置骨架，GLOBAL）                │ │
│   │   └ uda-{userId}-{agentId}  （每用户 fork、每租户独占）         │ │
│   └────────────────────────┬────────────────────────────────────────┘ │
│                            ▼                                          │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  per-(userId, agentId) 文件系统栈                               │ │
│   │   ┌──────────────────────────────────────────────────────────┐  │ │
│   │   │ OverlayFilesystem  （skills/、subagents/）               │  │ │
│   │   │   ┌── 上层：per-用户 RemoteFilesystem（可写） ──┐        │  │ │
│   │   │   └── 下层：shared/{skills,subagents}（只读）   │        │  │ │
│   │   └──────────────────────────────────────────────────────────┘  │ │
│   │   memory/、MEMORY.md、sessions/、tasks/   ← per-用户 RemoteFS  │ │
│   │   knowledge/、AGENTS.md                   ← 共享（只读）       │ │
│   │                                                                 │ │
│   │   SandboxFilesystem  （脚本执行）                               │ │
│   │     ▲ 生命周期由应用方管理（而不是运行时）                      │ │
│   └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│   能力市场:  用户贡献 ─▶ 管理员审批 ─▶ shared/ 持续生长               │
│   通道:      chatui · dingtalk · 通用 webhook                         │
│   存储:      默认嵌入式 H2（生产可切到 MySQL / PostgreSQL）           │
└───────────────────────────────────────────────────────────────────────┘
```

设置 `dataagent.session.redis.enabled=true` 后，per-用户的 `RemoteFilesystem`、`Session`、`ToolEventBus` 全部走 Redis —— 任意副本都能服务任意用户。详见 [`docs/cluster-deploy.md`](docs/cluster-deploy.md)。

---

## 默认包含

- **内置 `data-agent`** —— 全局骨架，自带 SQL 分析、图表渲染技能，外加 `data-explorer` / `report-writer` 子智能体。
- **per-用户数据 agent** —— 任一登录用户都可以 fork 内置或新建。每个用户 agent 拥有独立的 `CompositeFilesystem`，按 `(userId, agentId)` 切分；`skills/` 和 `subagents/` 是 `OverlayFilesystem`，下层是磁盘上共享的内容。
- **通道（v1）** —— `chatui`（默认开启、主用）、`dingtalk`（可选，原样从 agentscope-builder 移植）以及全新的**通用 Webhook** 通道（HMAC 签名入站，回调 / 长轮询出站）。
- **能力市场** —— 用户从自己 workspace 中提名 skill / 子智能体 / memory 片段作为 *contributions*。管理员在 Approvals 页审批，通过后内容落到 `shared/`，下次构建时所有租户都能看到。
- **DataAgent 工具集插槽** —— `list_data_sources`、`describe_table`、`run_sql_preview`、`render_chart` 默认注册在每个 data agent 上。v1 只提供接口骨架 + `InMemoryDataSourceRegistry`，方便管理员在 `agentscope.json` 里种子数据源；具体的 JDBC 连接器超出本模块范围，可以通过 `DataSourceRegistry` / `ChartRenderer` Spring Bean 注入。

---

## per-用户隔离

每个 `HarnessAgent` 都跑在 `WorkspaceManagerFactory.forAgent(ownerId, agentId)` 构建出来的 `CompositeFilesystem` 上：

| 路径 | 挂载方式 |
|---|---|
| `memory/`、`MEMORY.md`、`sessions/`、`tasks/` | `RemoteFilesystem`，按 `(ownerId, agentId)` 命名空间隔离 |
| `skills/`、`subagents/` | `OverlayFilesystem` —— 上层是 per-用户 `RemoteFilesystem`，下层是共享目录 `shared/{skills,subagents}/` |
| `knowledge/`、`AGENTS.md` | 磁盘上的共享根目录，对租户只读挂载（变更只能走能力市场贡献流程） |

当 `dataagent.session.redis.enabled=true` 时，同一份 `RemoteFilesystem` 由 Redis 后端支撑（跨副本重启可恢复）；否则是本机文件存储。详见 [`docs/cluster-deploy.md`](docs/cluster-deploy.md)。

---

## 能力市场流程

1. 用户（或 agent 自己通过 `contribute_to_workspace` 工具）从自己的 workspace 提交一个文件作为贡献：
   ```http
   POST /api/me/contributions
   { "targetType": "skill",
     "targetPath": "cohort-builder/SKILL.md",
     "rationale": "...",
     "payload": "<file contents>" }
   ```
2. 贡献以 `PENDING` 状态持久化，呈现在管理员的 `/admin/approvals` 页。
3. 管理员通过 → payload 落到 `~/.agentscope/dataagent/workspace/shared/skills/cohort-builder/SKILL.md`。
4. 每个 per-`(userId, agentId)` 的 overlay 在下次构建时就能看到，无需重启即对所有租户可见。

能力市场的颗粒度故意比 per-agent 的 ACL 共享更细 —— 单个贡献的单位是一项 skill / 一个子智能体 / 一段 memory，而不是整个 agent。

---

## 快速开始

### 1. 配置模型

```bash
export DASHSCOPE_API_KEY=sk-...
```

或者提供你自己的 `Model` Spring Bean 切到其他 provider。

### 2.（可选）启用 Redis 做分布式部署

```yaml
dataagent:
  session:
    redis:
      enabled: true
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD:}
      database: 0
      key-prefix: "dataagent:session:"
```

启用后：

- `RemoteFilesystem` 写入走 Redis —— 被路由到副本 R2 的租户能看到 R1 写到 `memory/` / `sessions/` 等的内容。
- `ToolEventBus` 切成 Redis Pub/Sub —— R2 上的 SSE 消费方能收到 R1 在同一 session 触发的 tool-call 事件。
- 启动前置检查：如果 `dataagent.workspace` 指向临时路径（`/tmp/`、`/var/tmp/` 等），会拒绝启动。

3 副本部署详见 [`docs/cluster-deploy.md`](docs/cluster-deploy.md)。

### 3.（可选）启用 Webhook 侧通道

在 `~/.agentscope/dataagent/agentscope.json`：

```json
{
  "channels": {
    "ops-webhook": {
      "type": "webhook",
      "defaultAgentId": "data-agent",
      "dmScope": "MAIN",
      "properties": {
        "sharedSecret": "${WEBHOOK_SECRET}",
        "allowedIps": ["10.0.0.0/8"]
      }
    }
  }
}
```

外部系统这样调用：

```http
POST /api/webhook/ops-webhook/inbound
X-DataAgent-Sig: <body 的 HMAC-SHA256，hex>
{ "externalUserId": "alice@corp",
  "externalSessionId": "ticket-1234",
  "message": "how many users signed up yesterday?",
  "replyMode": "callback",
  "callbackUrl": "https://ops.internal/webhook/dataagent" }
```

回复要么 POST 回 `callbackUrl`（同样的 HMAC），要么在 `replyMode=poll` 时停在 `GET /api/webhook/ops-webhook/outbound/{inboundId}` 等长轮询取走。

### 4. 运行

```bash
java -jar target/agentscope-dataagent-*-exec.jar
```

打开 **http://localhost:8080** 登录。H2 demo 种子会创建两个 demo 账号：`bob` / `bob` 与 `alice` / `alice`。第一个 `ROLE_ADMIN` 用户也会种入 —— 看启动 banner。

---

## 配置参考（`application.yml`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `dataagent.jwt.secret` | 开发占位 | JWT 签名密钥（>= 32 字符）。**非 `dev` Profile 下若仍是默认值会拒绝启动**。 |
| `dataagent.workspace` | `$CWD`（仅开发态） | agent 运行时状态的工作目录（与配置无关 —— 配置在 `~/.agentscope/dataagent/agentscope.json`）。**非 `dev` Profile 下必填**，留空就启动失败。 |
| `dataagent.workspace-store.local.max-file-size-mb` | `10` | `RemoteFilesystem` 本地后端的单文件上限。 |
| `dataagent.dashscope.api-key` | _空_ | DashScope API key（无 `Model` Bean 时回落到这里）。 |
| `dataagent.dashscope.model-name` | `qwen-max` | DashScope 模型 id。 |
| `dataagent.agent.name` | `data-agent` | 自动生成 `~/.agentscope/dataagent/agentscope.json` 时的 agent 名 |
| `dataagent.agent.sys-prompt` | _（内置）_ | 自动生成 `agentscope.json` 时的系统提示 |
| `dataagent.channels.chatui.enabled` | `true` | 主 Web 通道，默认开启 |
| `dataagent.session.redis.enabled` | `false` | 是否启用 Redis 分布式 agent 状态 |
| `dataagent.session.redis.host/port/password/database` | `localhost:6379/0` | Redis 连接 |
| `dataagent.session.redis.key-prefix` | `dataagent:session:` | Redis key 前缀 |
| `dataagent.marketplace.enabled` | `true` | 关掉则隐藏贡献 + 审批 API |
| `dataagent.marketplace.max-contribution-bytes` | `1048576` | `POST /api/me/contributions` 接受的最大 payload |
| `server.port` | `8080` | HTTP 端口 |

用户、agent、贡献记录默认持久化到嵌入式 H2，开箱即用，便于本地快速体验。生产部署时，激活 `jdbc` Spring Profile 并设置 `DATAAGENT_DB_URL` / `DATAAGENT_DB_USER` / `DATAAGENT_DB_PASSWORD` 即可切换到 MySQL 或 PostgreSQL。

---

## API 端点

### 公开

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/auth/login` | 登录，返回 JWT |
| `POST` | `/api/webhook/{channelId}/inbound` | 通用 webhook 入站（必须带 HMAC） |
| `GET` | `/api/webhook/{channelId}/outbound/{inboundId}` | `poll` 模式下长轮询取回复 |

### 用户级（任何登录用户）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/auth/me` | 当前用户信息 |
| `GET` | `/api/me/agent-info` | 内置 `data-agent` 元数据 |
| `POST` | `/api/chat/stream` | SSE 流式对话（请求体 `{ message }`） |
| `POST` | `/api/chat/send` | 同步对话（请求体 `{ message }`） |
| `GET` | `/api/sessions` | 列出自己的 session |
| `GET` | `/api/sessions/{key}/history` | session 消息历史 |
| `GET` | `/api/sessions/{key}/turns` | 按轮次的 transcript |
| `GET` | `/api/sessions/{key}/tree` | 子 agent fan-out 树 |
| `POST` | `/api/sessions/{key}/reset` | 重置 session |
| `GET` `POST` | `/api/me/agents` | 列出 / 新建 per-用户数据 agent |
| `GET` `POST` | `/api/me/agents/{id}/skills` | 列出 / 写入 workspace 中的 skill |
| `GET` `POST` | `/api/me/agents/{id}/tools` | 列出 / 注册自定义工具 |
| `POST` | `/api/me/agents/{sourceId}/clone` | fork 一个内置或自己的 agent |
| `GET` `POST` | `/api/user/bindings` | 列出 / 新增通道偏好 |
| `PUT` `DELETE` | `/api/user/bindings/{index}` | 更新 / 删除偏好 |
| `GET` `POST` | `/api/me/contributions` | 列出自己的贡献 / 提交新贡献 |

### 管理员级（`ROLE_ADMIN`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` `POST` | `/api/admin/users` | 列出 / 新建用户 |
| `GET` | `/api/admin/runtime/overview` | 平台概览 |
| `GET` | `/api/admin/runtime/instances` | 已注册的 agent 实例 |
| `GET` | `/api/admin/runtime/sessions` | 全部 session |
| `GET` | `/api/admin/runtime/channels` | 通道状态 |
| `GET` | `/api/admin/contributions?status=PENDING\|APPROVED\|REJECTED` | 列出贡献 |
| `POST` | `/api/admin/contributions/{id}/approve` | 通过 → 写入 `shared/` |
| `POST` | `/api/admin/contributions/{id}/reject` | 驳回，附评审备注 |
| `GET` | `/api/admin/config/agentscope` | 原始 `agentscope.json` |
| `GET` | `/api/admin/channels/{channelId}/bindings` | 某通道的路由绑定 |
| `GET` | `/api/admin/usage/...` | 用量汇总（per-用户 / per-agent / 按小时 / 按天） |

---

## 构建

```bash
mvn -pl agentscope-examples/agents/agentscope-dataagent -am package -DskipTests
```

输出：

- `target/agentscope-dataagent-<version>-exec.jar` —— 可执行 fat JAR
- `target/agentscope-dataagent-<version>.jar` —— thin 库 JAR

格式检查（CI 会卡）：

```bash
mvn -pl agentscope-examples/agents/agentscope-dataagent spotless:check
```
