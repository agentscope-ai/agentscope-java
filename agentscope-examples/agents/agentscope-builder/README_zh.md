# AgentScope Builder

> 🇬🇧 English version: [README.md](README.md)

## 项目概览

Builder 是 [claw](../agentscope-claw/) 的多人版本 —— 底层还是同一套会自我进化的 agent，但被装进了一个可以让整个团队、整个公司共用的平台里。用户从浏览器登录，**不写代码**就能搭出自己的 agent，每个人都有独立的 workspace 让 agent 在里面慢慢成长；做出好东西后，主人可以把它分享给某位同事、某个小组，或者公开给整个组织。

放到实际场景里看：

- 创建一个 agent 是 UI 上的几次点击，而不是一次 `mvn package` —— 挑技能、子智能体、工具和 MCP 服务，保存即得。
- 一个人对某个技能的微调不会渗到别人的 workspace。每一对 `(用户, agent)` 都有自己的独立空间，同一个起点 agent 在不同人手里会长出不一样的模样。
- 共享分级 —— "只能跑"、"可编辑"、"可 fork" 三档，主人可以让别人用，但不必让别人改。
- 运行时本身就是 claw 用的那套自进化 agent；Builder 只是在外面套了一层鉴权、租户和运维控制台。

只是给你自己用，请用 claw。同样的能力要给一个团队、一家公司用，那就是 Builder。

### 一览

| | Builder |
|---|---|
| **适用场景** | 一个团队或一家公司需要共建并运营自进化 agent |
| **用户数** | 多人 —— 每个登录用户都有自己的 workspace |
| **隔离** | 按 `(userId, agentId)` 划分 workspace；Managed Environment 可选 `sandbox`（E2B）或 `self_hosted` Worker |
| **自进化** | ✅ 与 claw 同源，但发生在**每个用户自己**的 workspace 内 |
| **共享** | ✅ 可指定到具体用户、用户组、全局，并按 run / edit / fork 三档分级授权 |
| **分布式** | ✅ 共享 JDBC（+ 可选远端 FS）即可水平扩展；Hands Worker 纯出站、无需共享盘 |
| **执行面** | Environment：`local` / `sandbox`（E2B）/ `remote` / `self_hosted`（出站 Worker） |

### 架构

Builder 的 Managed Agents 走 **HarnessAgent**，Hands 由 **Environment 类型**决定：本机 FS、**E2B** 云沙箱（`type=sandbox`）、远端 KV FS，或事件驱动的 **self_hosted** Worker。Brain 与 Hands 分离 —— Worker 无需入站端口、无需共享盘。

```
┌─────────────────────────────────────────────────────────────────────┐
│  AgentScope Builder（Spring Boot，端口 8080）                       │
│                                                                     │
│   React SPA ──▶  REST API (JWT)                                     │
│                  │                                                  │
│                  ▼                                                  │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  Managed Sessions（Brain）                                   │  │
│   │   Agent × Environment → HarnessAgent + 事件落库 / SSE        │  │
│   └──────────────────────────────────┬───────────────────────────┘  │
│                                      ▼                              │
│   ┌──────────────┬──────────────┬──────────────┬─────────────────┐  │
│   │ local        │ sandbox      │ remote       │ self_hosted     │  │
│   │ （本机 FS）  │ （E2B 云）   │ （BaseStore）│ （出站 Worker）  │  │
│   └──────────────┴──────────────┴──────────────┴─────────────────┘  │
│                                                                     │
│   目录与会话（默认 H2；生产 MySQL / PostgreSQL）                    │
└─────────────────────────────────────────────────────────────────────┘
```

产品面以 Session 绑定的 **Environment 类型**为准（不再是本机 Docker）。E2B：`builder.e2b.*` / `BUILDER_E2B_API_KEY`。详见 **[文件系统模式](#文件系统模式)** 与 [docs/guide/05-environments.md](docs/guide/05-environments.md)。

---

## 快速开始

```bash
# 设置模型 API key
export DASHSCOPE_API_KEY=sk-xxx

# 编译并运行
mvn -pl agentscope-examples/agents/agentscope-builder -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-builder/target/agentscope-builder-*.jar
```

服务在 `http://localhost:8080` 启动。首次启动会在 `~/.agentscope/builder/agentscope.json` 自动生成一份默认 agent 配置。

## Managed Agents 资源模型

Builder 在现有 HarnessAgent 之上对齐了 Claude Managed Agents 的资源面：

| 资源 | API | 说明 |
|---|---|---|
| **Agent**（版本化） | `/api/agents`、`/versions` | 乐观锁更新；archive；配置快照（全局 agent 物化到 `__global__`） |
| **Environment** | `/api/environments` | `local`（host）/ `sandbox`（E2B 云 hands）/ `remote`（分布式 KV，无 shell）/ `self_hosted`（出站 Worker hands）；支持 `/shares`；Worker API：`/work/*` |
| **Session** | `/api/sessions` | Agent×Environment 运行实例 + 事件日志/SSE；IM 渠道也会桥接 |
| **Memory store** | `/api/memory-stores` | 跨 session JPA 文档，以 FS 路由挂载到 `memory-stores/{name}/`（非 host 物化）；与 harness 原生 `MEMORY.md`/`memory/` 长期记忆正交 |
| **Vault** | `/api/vaults` | AES-GCM 加密凭据；构建 session agent 时注入 MCP `${ENV}` |
| **Deployment** | `/api/deployments` | cron / webhook / 手动触发，创建 managed session 并跑一轮 |
| **Multiagent** | `/api/multiagent/run` | 顺序 fan-out；UCA 也可通过 SessionsTool 拉起子代理 |

Chat UI 默认走 managed session。遗留 `/api/agents/{id}/chat/*` 已标记 deprecated。Session turn 按 `(owner, agent, version, environment, mounts)` 构建/缓存 HarnessAgent，使版本与 Environment 真正影响运行时。`user.interrupt` 会调用 `HarnessAgent.interrupt()`。

生产环境请设置 `BUILDER_VAULT_MASTER_KEY`。

不影响试用完整性的生产后续项（多副本 interrupt、Files 挂载、Worker 自定义工具 SPI、legacy chat 下线等）见 [docs/FOLLOW_UP_PRODUCTION.md](docs/FOLLOW_UP_PRODUCTION.md)。

Managed Agents HTTP 面（控制面 / 数据面 / 相对 Claude 的缺口）：[docs/MANAGED_AGENTS_API.md](docs/MANAGED_AGENTS_API.md)。

**产品指南**：[docs/guide/README.md](docs/guide/README.md)。  
**部署运维**：[docs/guide/13-operations.md](docs/guide/13-operations.md)。  
**产品验证清单**（local / E2B / self_hosted / HITL）：[docs/guide/14-validation.md](docs/guide/14-validation.md)。

API 形态改造（Agent body 收拢等）：[docs/API_REFACTOR.md](docs/API_REFACTOR.md)。

数据面契约（事件 / deltas / Worker / 错误模型）：[docs/DATA_PLANE_CONTRACT.md](docs/DATA_PLANE_CONTRACT.md)。

事件类型参考：[docs/events/README.md](docs/events/README.md)。

## 配置

所有配置都使用 `builder.*` 前缀。可写在 `application.yml` 中、传 JVM 系统属性（`-Dbuilder.xxx`），或用环境变量（`BUILDER_XXX`）。

### 模型

```yaml
builder:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY:}
    model-name: qwen-max
    stream: true
```

也可以提供一个自己的 `Model` Spring Bean（OpenAI、Anthropic、Gemini、Ollama 等）。

### 工作目录

```yaml
builder:
  workspace: ${BUILDER_WORKSPACE:}   # 工作目录；默认是 JVM 当前目录
```

agent 配置文件会从 `~/.agentscope/builder/agentscope.json` 读取 —— 这是一个 **per-应用的固定位置**，与 `builder.workspace` 无关，避免不同 harness 应用（builder、dataagent、codingagent、claw）共用 cwd 时互相覆盖。每个 agent 的 workspace 默认放在 `~/.agentscope/builder/workspace`，可以在 `agentscope.json` 里通过每个 agent 自己的 `workspace` 字段覆盖。

### JWT

```yaml
builder:
  jwt:
    secret: ${BUILDER_JWT_SECRET:builder-default-dev-secret-change-in-production-32chars}
```

**生产环境必须替换**为不少于 32 个字符的密钥。

---

## 文件系统模式

**Managed Agents 试用**请选 Environment 类型（`local` / `sandbox` / `self_hosted` / `remote`），见 [14-validation.md](docs/guide/14-validation.md)。  
下方 `builder.workspace-store.fs-spec` 是更早的 workspace 后端开关；Managed `type=sandbox` **固定走 E2B**，不再用本机 Docker。

### 本地模式（默认）

```yaml
builder:
  workspace-store:
    fs-spec: local
    local:
      max-file-size-mb: 10
```

agent 直接在宿主机上以 `LocalFilesystemWithShell` 运行。每个用户的 workspace 通过命名空间隔离（`users/{userId}/agents/{agentId}/`），shell 命令也在宿主机执行。

**适用于：** 单节点部署、本地开发、可信环境。

### Sandbox 模式（Managed Environment `type=sandbox` → E2B）

Managed Agents 中 `environment.type=sandbox` 的 hands 跑在 **E2B 云沙箱**，经 `agentscope-extensions-sandbox-e2b`（`E2bFilesystemSpec`）。Builder **不再**为本路径使用本机 Docker / Daytona。

```yaml
builder:
  e2b:
    api-key: ${BUILDER_E2B_API_KEY:}   # 或 E2B_API_KEY；环境 config.apiKey 可覆盖
    template-id: base
    workspace-root: /home/user
    sandbox-timeout-seconds: 300
    persistence-mode: TAR
```

**前置条件：**
- 有效的 E2B API key
- 需要预装运行时请用自定义 E2B template（Claude 式 `packages` 暂不强制，见 `docs/SANDBOX_GAPS.md`）

**配置参考：**

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `builder.e2b.api-key` | `BUILDER_E2B_API_KEY`（回退 `E2B_API_KEY`） | 空 | E2B API key（`type=sandbox` 必填） |
| `builder.e2b.template-id` | `BUILDER_E2B_TEMPLATE_ID` | `base` | 默认 E2B 模板 |
| `builder.e2b.workspace-root` | `BUILDER_E2B_WORKSPACE_ROOT` | `/home/user` | 沙箱内工作目录 |
| `builder.e2b.sandbox-timeout-seconds` | `BUILDER_E2B_SANDBOX_TIMEOUT_SECONDS` | `300` | 超时秒数 |
| `builder.e2b.api-base-url` | `BUILDER_E2B_API_BASE_URL` | 空 | 可选 API base |
| `builder.e2b.domain` | `BUILDER_E2B_DOMAIN` | 空 | 可选 domain |
| `builder.e2b.persistence-mode` | `BUILDER_E2B_PERSISTENCE_MODE` | `TAR` | `TAR` 或 `NATIVE_SNAPSHOT` |

**隔离粒度**（环境 `config.isolationScope`，默认 `SESSION`）：
- `SESSION` —— 每聊天 session 独立（对齐 Claude）
- `USER` / `AGENT` / `GLOBAL` —— 更粗粒度共享（高级）

创建示例见 [docs/guide/05-environments.md](docs/guide/05-environments.md)。

**适用于：** 需要平台托管隔离执行、对齐 Claude `cloud` 的场景。

### Remote 模式

```yaml
builder:
  workspace-store:
    fs-spec: remote
```

agent 运行时与 workspace 管理都使用分布式 `BaseStore` 后端。agent 文件系统操作走 `RemoteFilesystem` / `CompositeFilesystem`，Web API 的 workspace store 也走 `RemoteFilesystem`。

**前置条件：**
- 必须提供一个 `BaseStore` Spring Bean（Redis、OSS 或自定义实现）
- 必须提供分布式 `Session` Bean（如 `RedisSession`）

**适用于：** 横向扩展部署，workspace 数据需要在多副本间共享。

---

## 持久化

Builder 自带嵌入式 H2，开箱就能本地体验 —— 用户、agent 定义、共享授权、短期 brain 状态（`AgentStateStore` → `builder_agent_state`），以及多副本协调表（`builder_coord_*`：turn 租约、HITL 票、hands 工作队列、cron 触发租约）都会自动持久化；并预置 `admin/admin`、`bob/bob`、`alice/alice` 三个 demo 账号，可直接登录把玩。生产部署时，激活内置 `jdbc` Spring Profile 并覆盖 JDBC URL / 账号密码（`BUILDER_DB_URL`、`BUILDER_DB_USER`、`BUILDER_DB_PASSWORD`）即可切换到 MySQL 或 PostgreSQL —— 两个驱动都已经在 classpath 中。多副本指向同一 DataSource 即可共享目录、会话状态与协调层，无需再为 brain/协调单独配 Redis（如需 Redis 可自行声明 `AgentStateStore` / `CoordinationStore` bean 覆盖）。关闭同进程 hands：`builder.hands.in-process-worker=false`，并独立运行 `io.agentscope.builder.worker.HandsWorkerMain`。

---

## Agent 配置

agent 定义在 `~/.agentscope/builder/agentscope.json`：

```json
{
  "main": "default",
  "agents": {
    "default": {
      "name": "my-agent",
      "sysPrompt": "You are a helpful assistant.",
      "maxIters": 10,
      "model": "anthropic/claude-sonnet-4-6",
      "sandbox": {
        "mode": "all",
        "scope": "user"
      }
    }
  }
}
```

省略 `workspace` 字段时会回落到 per-应用默认（`~/.agentscope/builder/workspace`）。

per-agent 的 sandbox 配置（`sandbox.mode` / `sandbox.scope`）是 agent 定义上的元数据。Managed Session 的执行面由 Session 绑定的 Environment 决定（`type=sandbox` → E2B，见 `builder.e2b.*` / env `config`）。

---

## 环境变量参考

| 变量 | 配置项 | 默认 | 说明 |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | `builder.dashscope.api-key` | （无） | DashScope API key |
| `BUILDER_MODEL_NAME` | `builder.dashscope.model-name` | `qwen-max` | 模型名 |
| `BUILDER_WORKSPACE` | `builder.workspace` | （JVM cwd） | 工作目录 |
| `BUILDER_JWT_SECRET` | `builder.jwt.secret` | （开发默认） | JWT 签名密钥 |
| `BUILDER_WORKSPACE_FS_SPEC` | `builder.workspace-store.fs-spec` | `local` | 文件系统模式 |
| `BUILDER_E2B_API_KEY` | `builder.e2b.api-key` | 空 | `type=sandbox` 的 E2B API key |
| `BUILDER_E2B_TEMPLATE_ID` | `builder.e2b.template-id` | `base` | 默认 E2B 模板 |
| `BUILDER_E2B_SANDBOX_TIMEOUT_SECONDS` | `builder.e2b.sandbox-timeout-seconds` | `300` | E2B 超时 |
| `BUILDER_AGENT_NAME` | `builder.agent.name` | `builder-agent` | 默认 agent 名 |
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | （无） | 设为 `jdbc` 把数据库从 H2 切到 MySQL / PostgreSQL |
| `BUILDER_DB_URL` / `BUILDER_DB_USER` / `BUILDER_DB_PASSWORD` | `spring.datasource.*` | `${user.home}/.agentscope-builder/` 下的 H2 文件 | 生产数据库连接 —— 见 [持久化](#持久化) |
