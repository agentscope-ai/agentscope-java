# 部署运维 · 把 Managed Agents 跑起来

[← Limitations](12-limitations.md) · [回目录](README.md) · [下一页：产品验证 →](14-validation.md)

---

本章讲 **如何把四层拆分的 AgentScope Service（gateway / control / data / scheduler）部署并运维起来**。  
不要与 [Deployments](10-deployments.md)（产品资源：cron / webhook 触发会话）混淆。

更细的配置表见仓库 [README_zh.md](../../README_zh.md) / [README.md](../../README.md)；多副本生产债见 [FOLLOW_UP_PRODUCTION.md](../WIP/FOLLOW_UP_PRODUCTION.md)。部署完成后用 [产品验证清单](14-validation.md) 做实操验收。

## 部署形态速览

```mermaid
flowchart TB
  subgraph local [本地开发_scripts_dev-up.sh]
    GW1[gateway_8080] --> CP1[control_8081]
    GW1 --> DP1[data_8082]
    SCH1[scheduler_8083] --> CP1
    SCH1 --> DP1
    CP1 --> H2[(H2_TCP_9092)]
    DP1 --> H2
    SCH1 --> H2
  end
  subgraph prod [生产_docker_compose_或等效编排]
    GW2[gateway] --> CP2[control_xN]
    GW2 --> DP2[data_xN]
    SCH2[scheduler] --> CP2
    SCH2 --> DP2
    CP2 --> DB[(MySQL_or_PostgreSQL)]
    DP2 --> DB
    SCH2 --> DB
  end
```

| 形态 | 数据库 | 进程 | 适用 |
|---|---|---|---|
| 本地试跑 | H2 TCP（脚本自带） | `scripts/dev-up.sh` 起四平面 | 开发 / Demo |
| 单机生产 | MySQL / PostgreSQL | `docker compose up --build` | 小流量上线 |
| 多副本 | **同一** JDBC DataSource | control / data 各 N 副本；gateway 前置 LB | 水平扩展 |

四条边界，记牢就不会配错：

- **gateway**：唯一对外端口；纯路径转发，无业务逻辑、无 DB。剥离客户端伪造的内部鉴权头；`/api/internal/**` 在公网边缘直接 404。
- **control**：静态定义与版本 + session 创建/归档/列表 + 控制台 SPA；**不**跑 turn；webhook / manual deployment fire 暂留于此。
- **data**：`user.message` → turn 执行；HarnessAgent、事件日志、SSE、work 队列服务端。
- **scheduler**：IM 渠道运行时（配置拉自 control）、出站投递、**cron deployment 到期调度**、self-hosted hands worker；**不**做推理循环。

## 1. 本地最快路径

```bash
export DASHSCOPE_API_KEY=sk-xxx

# 按需编译 + 起全部四个平面（共享 H2 TCP 库）
agentscope-service/scripts/dev-up.sh
```

- 控制台（经网关）：`http://localhost:8080`
- 默认账号：`admin` / `admin`（另有 demo：`bob`/`bob`、`alice`/`alice`）
- 停止：`scripts/dev-down.sh`；运行状态（pid / 日志 / H2 数据文件）在 `agentscope-service/.dev-stack/`
- 前端热更：`cd agentscope-service/frontend && npm run dev`（vite 把 `/api` 代理到 :8080 网关）

Docker 替代路径：

```bash
mvn -pl agentscope-service -am install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build
```

## 2. 生产必改清单

上线前至少设置（**四个平面要保持一致**）：

| 项 | 环境变量 / 配置 | 说明 |
|---|---|---|
| JWT 签名 | `BUILDER_JWT_SECRET`（≥32 字符） | 各平面用同一密钥互相验签；勿用开发默认值 |
| 面间密钥 | `BUILDER_INTERNAL_TOKEN` | 面间内部调用（X-Builder-Internal-Token）；control / data / scheduler 必须一致；**非 `dev`/`test` profile 下禁止为空、长度 &lt;32 或使用 compose/dev 默认值** |
| Vault 主密钥 | `BUILDER_VAULT_MASTER_KEY` | 凭证 AES-GCM；未设会用开发默认并打 WARN |
| 模型密钥 | `DASHSCOPE_API_KEY` 或自备 `Model` Bean | 无模型无法跑 turn（control / data 都需要） |
| 外部库 | `BUILDER_DB_URL` / `USER` / `PASSWORD` / `DRIVER` | 所有平面指向**同一**库，见下一节 |
| 改密 | 登录后立刻改 `admin` 密码 | 种子账号仅用于冷启动 |

可选但推荐：

| 项 | 变量 | 说明 |
|---|---|---|
| 实例 ID | `BUILDER_INSTANCE_ID` | data 多副本区分 turn 租约持有者；不设则自动生成 |
| Turn 租约 TTL | `BUILDER_TURN_LEASE_TTL_SECONDS`（默认 90） | 过期后其他副本可收口 |
| HITL 超时 | `BUILDER_TOOL_CONFIRMATION_TIMEOUT_MS` | 确认票过期 |
| 工作目录 | `BUILDER_WORKSPACE` | Agent 工作区根；与 DB 路径分离 |
| 渠道回包超时 | `BUILDER_CHANNEL_REPLY_TIMEOUT_MS`（scheduler，默认 120s） | IM 入站等待 turn 结束的上限 |

属性前缀统一为 `builder.*` / `BUILDER_*`（旧 `claw.*` 仅兼容迁移）。

## 3. 数据库：H2 → MySQL / PostgreSQL

本地 H2 适合开发。生产直接覆盖 `BUILDER_DB_*`（无需激活额外 profile）：

```bash
export BUILDER_DB_URL='jdbc:mysql://db:3306/agentscope_builder?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC'
export BUILDER_DB_USER=agentscope
export BUILDER_DB_PASSWORD='***'
export BUILDER_DB_DRIVER=com.mysql.cj.jdbc.Driver
# PostgreSQL 示例：改 URL + BUILDER_DB_DRIVER=org.postgresql.Driver
```

要点：

- MySQL / PostgreSQL 驱动已在各平面 classpath；Hibernate 按 URL 选方言。
- **schema 种子由 control 面负责**；其余平面 `spring.sql.init.mode=never`，只管连同一个库。
- `BUILDER_JPA_DDL_AUTO` 默认 `update`；严肃生产建议改 `validate` 并自管 Flyway/Liquibase。
- **所有平面必须指向同一 DataSource**：共享 Agent 目录、Session 事件、`builder_agent_state`、以及 `builder_coord_*`（turn 租约 / HITL / work 队列 / cron fire）。

## 4. 单机生产最小启动示例

推荐直接用仓库根下的 compose（Postgres + aistiod + 三平面 Java，见 §1）。手工起进程的等价物：

```bash
export DASHSCOPE_API_KEY=sk-xxx
export BUILDER_JWT_SECRET='replace-with-long-random-secret-32+'
export BUILDER_VAULT_MASTER_KEY='replace-with-vault-master-key-32+'
export BUILDER_INTERNAL_TOKEN='replace-with-plane-to-plane-token'
export BUILDER_DB_URL='jdbc:postgresql://127.0.0.1:5432/builder?currentSchema=dp'
export BUILDER_DB_USER=builder
export BUILDER_DB_PASSWORD='***'

# 控制面（Go，单进程单端口：/api/*、/api/v1/*、控制台 SPA）
(cd aistio && go build -o bin/aistiod ./cmd/aistiod)
AISTIO_ENABLE_KUBERNETES=false \
AISTIO_PRODUCT_DSN='postgres://builder:builder@127.0.0.1:5432/builder?sslmode=disable' \
AISTIO_STATIC_DIR=aistio/ui \
BUILDER_DATA_URL=http://127.0.0.1:8082 \
  ./aistio/bin/aistiod --http-bind-address=:8081 \
    --storage-driver=postgres \
    --storage-dsn='postgres://builder:builder@127.0.0.1:5432/builder?sslmode=disable&search_path=rt'

java -jar service-dataplane/target/service-dataplane-*.jar           # :8082
BUILDER_CONTROL_URL=http://127.0.0.1:8081 \
BUILDER_DATA_URL=http://127.0.0.1:8082 \
  java -jar service-scheduler/target/service-scheduler-*.jar  # :8083
BUILDER_CONTROL_URL=http://127.0.0.1:8081 \
BUILDER_DATA_URL=http://127.0.0.1:8082 \
  java -jar service-gateway/target/service-gateway-*.jar      # :8080（唯一对外）
```

对外只暴露 gateway 的 8080；aistiod / data / scheduler 端口应在内网。SSE（`/api/sessions/{id}/events/stream`）经网关透传，反向代理需关缓冲。

## 5. Hands：self_hosted Worker 在调度层

仅 **`type=self_hosted`** 需要 Worker。`sandbox`（E2B）与 `local` **不走** work 队列。

**调度层是唯一 Hands 执行面**（含 local 开发）——控制面/数据面没有内嵌 Worker。独立 Worker 入口随 scheduler jar 发布：

```bash
java -cp service-scheduler/target/service-scheduler-*.jar \
  io.agentscope.builder.worker.HandsWorkerMain \
  --base-url http://gateway-or-data:8080 \
  --environment-id env_xxx \
  --environment-key ebk_xxx \
  --hands-root /var/lib/agentscope/hands \
  --worker-id worker-1
```

运维注意：

- `--base-url` 可指 gateway（`/work/**`、`/tool-results` 会被路由到 data）或直连 data；Worker **仅出站**，无入站端口、无共享盘要求。
- Worker 本地执行外化工具后 `POST …/tool-results` 续跑 turn（落在 data 面）。
- Environment key 只在 create / rotate 时明文出现一次，按密钥保管。
- 协议见 [Hands / Worker](08-hands-worker.md)；验收见 [产品验证清单](14-validation.md)。

## 5.1 E2B sandbox（`type=sandbox`）

```bash
export BUILDER_E2B_API_KEY=ek_xxx
# 可选：BUILDER_E2B_TEMPLATE_ID、BUILDER_E2B_SANDBOX_TIMEOUT_SECONDS …
```

创建 Environment `type=sandbox` 后 Session 的 shell/FS 在 E2B 云端执行（由 data 面驱动）。**不需要** Docker daemon，也**不要**再配已废弃的 `builder.sandbox.*`。详见 [05-environments.md](05-environments.md)、[SANDBOX_GAPS.md](../WIP/SANDBOX_GAPS.md)。

## 6. Environment 选型（运维视角）

创建 Session 时选的 Environment `type` 决定执行面成本与隔离：

| type | 运维含义 |
|---|---|
| `local` | 宿主机 FS；开发默认 |
| `sandbox` | **E2B 云沙箱**；需 `BUILDER_E2B_API_KEY`（或 env `config.apiKey`）；多副本共享 AgentStateStore 以便 resume |
| `remote` | 依赖分布式 `BaseStore`；无 shell |
| `self_hosted` | 依赖 scheduler 层 Worker 队列 |

Managed Environment `type=sandbox` **不**使用本机 Docker，也不读已废弃的 `builder.sandbox.*` Docker 键。

全局 `builder.workspace-store` 控制 Composite 工作区后端，与单个 Environment 资源的 `type` 层级不同——部署时按租户隔离需求分别配置。详见 README 与 [05-environments.md](05-environments.md)。

## 7. 多副本

1. control / data 各起 N 个副本，**相同** `BUILDER_JWT_SECRET`、`BUILDER_VAULT_MASTER_KEY`、`BUILDER_INTERNAL_TOKEN`、`BUILDER_DB_*`。
2. data 副本建议设置不同的 `BUILDER_INSTANCE_ID`（turn 租约持有者标识）。
3. 流量入口：客户端 → LB → gateway 副本；gateway 再把 turn 路径转给 data、其余转给 control。
4. `event_deltas` SSE 仅 turn-owner best-effort，权威以落库事件为准；跨副本 `user.interrupt` 可能 409（见 Limitations / FOLLOW_UP）。
5. scheduler 单副本即可起步（渠道长连接不宜多开）；可选：用自研 Redis 实现覆盖 `CoordinationStore` / `AgentStateStore` bean（默认 JDBC 已够同库多副本）。

## 8. 日常运维检查项

| 检查 | 做法 |
|---|---|
| 服务存活 | 各平面 `/actuator/health`；网关路由表 `/actuator/gateway/routes` |
| 登录链路 | `POST /api/auth/login`（经网关 → control） |
| Hands 队列 | `GET /api/environments/{id}/work/stats`（JWT）；`GET /api/hands/status`（均落在 data） |
| Session Hands 指标 | `GET /api/sessions/{id}/hands-stats` |
| 渠道运行时 | scheduler 日志 `Scheduler channel runtime started: N channel(s) active` |
| 卡住的 turn | 查 `builder_coord_*` 租约是否过期；看 `session.status_*` / `session.error` |
| 轮换 Env key | `POST /api/environments/{id}/keys/rotate`，同步更新 Worker |
| 备份 | 备份 JDBC 库 + 需要的 workspace / hands 目录 |

## 9. 配置项速查（运维常用）

| 变量 | 默认倾向 | 用途 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 空 | 模型 |
| `BUILDER_MODEL_NAME` | `qwen-max` | 默认模型名 |
| `BUILDER_JWT_SECRET` | 开发占位 | JWT（各平面一致） |
| `BUILDER_INTERNAL_TOKEN` | 空 | 面间内部调用密钥 |
| `BUILDER_VAULT_MASTER_KEY` | 空 | Vault |
| `BUILDER_WORKSPACE` | JVM cwd | 工作区 |
| `BUILDER_DB_URL` / `USER` / `PASSWORD` / `DRIVER` | H2 文件 | 共享 JDBC |
| `BUILDER_JPA_DDL_AUTO` | `update` | 生产改 `validate` |
| `BUILDER_INSTANCE_ID` | 自动 | data 副本标识 |
| `BUILDER_CONTROL_URL` / `BUILDER_DATA_URL` / `BUILDER_SCHEDULER_URL` | localhost:8081/8082/8083 | gateway / scheduler 的对端地址 |
| `BUILDER_GATEWAY_PORT` / `CONTROL_PORT` / `DATA_PORT` / `SCHEDULER_PORT` | 8080/8081/8082/8083 | 各平面端口 |
| `BUILDER_CHANNEL_REPLY_TIMEOUT_MS` | `120000` | scheduler 等 turn 回包 |
| `BUILDER_E2B_API_KEY` | 空 | Managed `type=sandbox` 的 E2B key |
| `BUILDER_E2B_TEMPLATE_ID` | `base` | 默认 E2B 模板 |
| `BUILDER_TURN_LEASE_TTL_SECONDS` | `90` | turn 租约 |

完整表以 README「环境变量参考」为准。

## 10. 推荐阅读顺序

1. 本章（部署形态与必改项）  
2. [Quickstart](03-quickstart.md) 验证产品链路  
3. [Environments](05-environments.md) + [Hands / Worker](08-hands-worker.md)  
4. [FOLLOW_UP_PRODUCTION.md](../WIP/FOLLOW_UP_PRODUCTION.md) 多副本 / 跨机 Hands  
5. [Limitations](12-limitations.md) 能力边界
