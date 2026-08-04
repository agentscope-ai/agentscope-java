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
| **隔离** | 按 `(userId, agentId)` 划分 workspace 命名空间；可选 Docker sandbox 提供 OS 级隔离 |
| **自进化** | ✅ 与 claw 同源，但发生在**每个用户自己**的 workspace 内 |
| **共享** | ✅ 可指定到具体用户、用户组、全局，并按 run / edit / fork 三档分级授权 |
| **分布式** | ✅ 启用远端文件系统 + 分布式 session 后端后即可横向扩展 |
| **文件系统** | `CompositeFilesystem` —— 把 per-用户命名空间存储和可选的 sandbox / 远端后端组合在一起 |

### 架构

Builder 把每个 agent 都跑在一套 **HarnessAgent + `CompositeFilesystem`** 之上。Composite 文件系统把每个 agent 的文件系统切成若干带命名空间的层级，于是同一套 `WorkspaceManager` API 既能服务本地租户、也能服务跑在 Docker sandbox 内的租户，还能对接分布式 `BaseStore` —— 全部通过 `builder.workspace-store.fs-spec` 一个开关切换。

```
┌─────────────────────────────────────────────────────────────────────┐
│  AgentScope Builder（Spring Boot，端口 8080）                       │
│                                                                     │
│   React SPA ──▶  REST API (JWT)                                     │
│                  │                                                  │
│                  ▼                                                  │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  HarnessGateway                                              │  │
│   │   ├─ Agent (alice, agent-A) ──┐                              │  │
│   │   ├─ Agent (alice, agent-B)   │ 每 (user,id) 一个 HarnessAgent│  │
│   │   └─ Agent (bob,   agent-A) ──┘                              │  │
│   └──────────────────────────────────┬───────────────────────────┘  │
│                                      ▼                              │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  CompositeFilesystem  （按 (userId, agentId) 划分命名空间）  │  │
│   │   ┌──────────────┬──────────────┬─────────────────────────┐  │  │
│   │   │  local       │  sandbox     │  remote                 │  │  │
│   │   │  （默认）    │  （Docker）  │  （BaseStore: Redis/OSS）│  │  │
│   │   └──────────────┴──────────────┴─────────────────────────┘  │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│   用户与 agent 记录  （默认 H2；生产可切到 MySQL / PostgreSQL）     │
└─────────────────────────────────────────────────────────────────────┘
```

`local` / `sandbox` / `remote` 三种 `fs-spec` 模式共用**同一个** `CompositeFilesystem` 形状 —— 变的只是底层存储引擎。具体怎么选，见下方 **[文件系统模式](#文件系统模式)**。

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

Builder 通过 `builder.workspace-store.fs-spec` 切换三种显式文件系统模式，控制 per-(用户, agent) workspace 的存储后端。

### Auto 模式（默认）

```yaml
builder:
  workspace-store:
    fs-spec: auto
```

`auto` 保留按部署拓扑自动选择的行为：使用 `InMemoryAgentStateStore` 或 `JsonFileAgentStateStore` 时选择本地模式；提供分布式 `AgentStateStore` 时选择 Remote 模式。需要覆盖自动判断时，可以显式指定其他模式。

### 本地模式

```yaml
builder:
  workspace-store:
    fs-spec: local
    local:
      max-file-size-mb: 10
```

agent 直接在宿主机上以 `LocalFilesystemWithShell` 运行。每个用户的 workspace 通过命名空间隔离（`users/{userId}/agents/{agentId}/`），shell 命令也在宿主机执行。

**适用于：** 单节点部署、本地开发、可信环境。

### Sandbox 模式

```yaml
builder:
  workspace-store:
    fs-spec: sandbox
  sandbox:
    enabled: true
    image: agentscope/python-sandbox:py311-slim
    network: none
    workspace-root: /workspace
    isolation: USER
    projection-roots: AGENTS.md,skills,subagents,knowledge
    cpu-count: 1
    memory-bytes: 1073741824   # 1 GiB
```

agent 跑在 Docker 容器内，提供 per-用户的 OS 级隔离。workspace 文件（skills、subagents、AGENTS.md、knowledge）从宿主机投射进容器；Web API 仍然在宿主侧管理这些被投射的文件。

**前置条件：**
- 应用进程能访问 Docker daemon
- sandbox 镜像已经在本地构建好或可拉取

**配置参考：**

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `builder.sandbox.enabled` | `BUILDER_SANDBOX_ENABLED` | `false` | 是否启用 sandbox |
| `builder.sandbox.image` | `BUILDER_SANDBOX_IMAGE` | `agentscope/python-sandbox:py311-slim` | Docker 镜像 |
| `builder.sandbox.network` | `BUILDER_SANDBOX_NETWORK` | `none` | Docker 网络模式 |
| `builder.sandbox.workspace-root` | `BUILDER_SANDBOX_WORKSPACE_ROOT` | `/workspace` | 容器内挂载路径 |
| `builder.sandbox.isolation` | `BUILDER_SANDBOX_ISOLATION` | `USER` | 隔离粒度：`SESSION` / `USER` / `AGENT` / `GLOBAL` |
| `builder.sandbox.projection-roots` | `BUILDER_SANDBOX_PROJECTION_ROOTS` | `AGENTS.md,skills,subagents,knowledge` | 投射进容器的宿主机文件 |
| `builder.sandbox.cpu-count` | `BUILDER_SANDBOX_CPU_COUNT` | `0`（无限制） | 单容器 CPU 限制 |
| `builder.sandbox.memory-bytes` | `BUILDER_SANDBOX_MEMORY_BYTES` | `0`（无限制） | 单容器内存限制（字节） |

**隔离粒度：**
- `SESSION` —— 每个聊天 session 一个容器
- `USER` —— 每个用户一个容器，多 session 共用
- `AGENT` —— 每个 agent 一个容器，多用户共用
- `GLOBAL` —— 全局共用一个容器

**分布式 sandbox：** sandbox 默认是单节点模式。多副本部署时需要提供分布式的 `Session` Bean（如 Redis 后端），以便容器状态在多副本间共享。

**适用于：** 多租户部署、agent 可能执行不可信代码，或需要 OS 级隔离的场景。

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

Builder 自带嵌入式 H2，开箱就能本地体验 —— 用户、agent 定义、共享授权都会自动持久化；并预置 `admin/admin`、`bob/bob`、`alice/alice` 三个 demo 账号，可直接登录把玩。生产部署时，激活内置 `jdbc` Spring Profile 并覆盖 JDBC URL / 账号密码（`BUILDER_DB_URL`、`BUILDER_DB_USER`、`BUILDER_DB_PASSWORD`）即可切换到 MySQL 或 PostgreSQL —— 两个驱动都已经在 classpath 中。

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

per-agent 的 sandbox 配置（`sandbox.mode` / `sandbox.scope`）当前只是元数据，存在 agent 定义里；运行时的 sandbox 行为目前仍由全局 `builder.sandbox.*` 控制。

---

## 环境变量参考

| 变量 | 配置项 | 默认 | 说明 |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | `builder.dashscope.api-key` | （无） | DashScope API key |
| `BUILDER_MODEL_NAME` | `builder.dashscope.model-name` | `qwen-max` | 模型名 |
| `BUILDER_WORKSPACE` | `builder.workspace` | （JVM cwd） | 工作目录 |
| `BUILDER_JWT_SECRET` | `builder.jwt.secret` | （开发默认） | JWT 签名密钥 |
| `BUILDER_WORKSPACE_FS_SPEC` | `builder.workspace-store.fs-spec` | `auto` | 文件系统模式（`auto` / `local` / `sandbox` / `remote`） |
| `BUILDER_SANDBOX_ENABLED` | `builder.sandbox.enabled` | `false` | 是否启用 sandbox |
| `BUILDER_SANDBOX_IMAGE` | `builder.sandbox.image` | `agentscope/python-sandbox:py311-slim` | Sandbox Docker 镜像 |
| `BUILDER_SANDBOX_ISOLATION` | `builder.sandbox.isolation` | `USER` | Sandbox 隔离粒度 |
| `BUILDER_AGENT_NAME` | `builder.agent.name` | `builder-agent` | 默认 agent 名 |
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | （无） | 设为 `jdbc` 把数据库从 H2 切到 MySQL / PostgreSQL |
| `BUILDER_DB_URL` / `BUILDER_DB_USER` / `BUILDER_DB_PASSWORD` | `spring.datasource.*` | `${user.home}/.agentscope-builder/` 下的 H2 文件 | 生产数据库连接 —— 见 [持久化](#持久化) |
