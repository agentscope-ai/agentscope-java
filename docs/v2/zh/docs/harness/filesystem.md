---
title: "文件系统（Filesystem）"
description: "三种部署模式：本机 + shell / 共享存储 / 沙箱；IsolationScope 隔离维度；多用户隔离；技能与工具在各模式下的行为"
---

## 作用

`HarnessAgent` 把 agent 对**工作区**的访问从"一定是本机磁盘"抽象成统一接口。所有文件工具（`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`）和可选的 `execute`（shell）都从这个抽象走。

这样做让你能在三种部署模式之间切换，而**不改 agent 代码**：

- 本机 + shell —— 单进程、本地、信任环境；
- 共享存储 —— 多副本 / 多 pod 共享同一份长期记忆；
- 沙箱 —— 文件与命令都在隔离容器里执行，跨调用恢复同一份工作区。

## 三种声明式模式

在 `HarnessAgent.Builder` 上用 `filesystem(...)` 三选一（不调就是默认模式 3）：

| 模式 | 配置 | 提供 shell？ | 适用场景 |
|------|------|-------------|---------|
| **1 · 共享存储** | `filesystem(new RemoteFilesystemSpec(store))` | ❌ | 多副本要共享 `MEMORY.md` / 对话日志 / 子任务到 KV；**不希望在宿主上跑 shell** |
| **2 · 沙箱** | `filesystem(new DockerFilesystemSpec()...)` 或 K8s / Daytona / E2B / AgentRun | ✅（在沙箱内） | 隔离执行、跨调用恢复同一份工作区、可选快照 + 分布式 |
| **3 · 本机 + shell**（默认） | `filesystem(new LocalFilesystemSpec()...)` 或**不写** | ✅（宿主 `sh -c`） | 单进程 / 本机 / 信任环境 / 简单脚本与测试 |

> `filesystem(...)` 与 `abstractFilesystem(...)` 互斥；后者是给完全自管文件系统的逃生口，正常用法不需要。

---

### 模式 1：共享存储（`RemoteFilesystemSpec`）

适合"多副本，但用户的长期记忆要一致"。把一个 `BaseStore` 实现（Redis / JDBC / 内存）传进去，框架自动按路径前缀把工作区文件路由到这个 KV 存储：

```java
// 最小配置（推荐通过 DistributedStore 一键配置）
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent agent = HarnessAgent.builder()
    .name("store-agent")
    .model(model)
    .workspace(workspace)
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()   // baseStore 由 store 自动注入
        .isolationScope(IsolationScope.USER))
    .build();
```

#### 所有配置项

| 方法 | 说明 | 默认值 |
|------|------|-------|
| `isolationScope(IsolationScope)` | 命名空间隔离维度（详见下文 [IsolationScope](#isolationscope--多用户与多副本怎么分桶)） | `USER` |
| `anonymousUserId(String)` | `userId` 为空时使用的兜底标识 | `"_default"` |
| `addSharedPrefix(String)` | 额外的工作区相对路径前缀也路由到 KV（例如 `"prompts/"` / `"configs/"`） | 无 |
| `workspaceIndex(WorkspaceIndex)` | 加速远端 ls/glob/grep 的 SQLite 索引 | 不加索引，走全量扫描 |

#### 内置路由规则

框架自动把以下路径路由到共享 KV，每个路径段各自独立命名空间，不会互相污染：

| 路径 | KV 命名空间段 |
|------|-------------|
| `AGENTS.md`、`MEMORY.md`、`tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |

其余不在上表的路径落到本地 `LocalFilesystem`（无 shell）。

#### 示例场景：多副本客服 agent

三个 pod 各跑一个 `HarnessAgent`，用同一个 Redis 做 `BaseStore`：

```java
DistributedStore store = RedisDistributedStore.fromJedis(
        new JedisPooled("redis://shared-redis:6379"));

HarnessAgent agent = HarnessAgent.builder()
    .name("customer-service")
    .model(model)
    .workspace(Paths.get("/opt/agent/workspace"))
    .distributedStore(store)                  // stateStore + baseStore 一键配置
    .filesystem(new RemoteFilesystemSpec()
        .isolationScope(IsolationScope.USER)      // 每个用户独立命名空间
        .anonymousUserId("anonymous"))            // 未登录用户的兜底
    .build();
```

- 三个 pod 上本地磁盘的 `AGENTS.md` / `knowledge/` / `skills/` 作为只读模板（git 同步）；
- 运行时产物（`MEMORY.md`、`memory/`、对话日志）自动存到 Redis，任意 pod 都能读到最新状态；
- 用户 alice 的记忆在 KV 键 `agents/customer-service/users/alice/memory/...` 下。

这种模式**不提供 shell**——故意的：要 shell 请用模式 2（沙箱）或 3（本机）。

#### `BaseStore` 可用实现

| 实现 | 说明 |
|------|------|
| `RedisStore` | 基于 Jedis，适合低延迟高并发 | `agentscope-extensions-redis` |
| `JdbcStore` | 基于 JDBC，适合 MySQL / PostgreSQL / H2 | `agentscope-extensions-mysql` |
| `InMemoryStore` | 内存实现，适合测试 | `agentscope-harness` |

---

### 模式 2：沙箱（`SandboxFilesystemSpec` 系列）

适合"代码会执行不可信操作、或要隔离生产环境"。所有文件操作和 shell 命令都发到沙箱里执行，宿主完全不受影响。

#### Docker 沙箱

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION)
        .memorySizeBytes(512 * 1024 * 1024L)   // 512 MB 内存限制
        .cpuCount(2L)
        .network("host")
        .exposedPorts(8080, 3000)
        .environment(Map.of("NODE_ENV", "development"))
        .snapshotSpec(new LocalSnapshotSpec("/data/snapshots")))
    .build();
```

`DockerFilesystemSpec` 所有配置项：

| 方法 | 说明 | 默认值 |
|------|------|-------|
| `image(String)` | Docker 镜像 | 必填 |
| `isolationScope(IsolationScope)` | 隔离维度 | `SESSION` |
| `memorySizeBytes(Long)` | 容器内存限制 | Docker 默认 |
| `cpuCount(Long)` | CPU 限制 | Docker 默认 |
| `network(String)` | Docker network | Docker 默认 |
| `exposedPorts(int...)` | 暴露端口 | 无 |
| `environment(Map)` | 容器环境变量 | 无 |
| `workspaceRoot(String)` | 容器内工作区挂载点 | `/workspace` |
| `additionalRunArgs(String...)` | 额外的 `docker run` 参数 | 无 |
| `snapshotSpec(SandboxSnapshotSpec)` | 快照策略 | `NoopSnapshotSpec`（不快照） |
| `workspaceSpec(WorkspaceSpec)` | 工作区挂载规则 | 默认 |
| `executionGuard(SandboxExecutionGuard)` | 并发执行守卫（用于 AGENT / GLOBAL scope） | 无 |
| `workspaceProjectionEnabled(boolean)` | 是否启用宿主→沙箱的静态资产投影 | `true` |
| `workspaceProjectionRoots(List)` | 投影包含的根路径列表 | `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache` |

#### Kubernetes 沙箱

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("k8s-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new KubernetesFilesystemSpec()
        .image("node:20-slim")
        .namespace("agents")
        .serviceAccount("agent-runner")
        .cpuRequest("500m")
        .memoryRequest("256Mi")
        .nodeSelector(Map.of("pool", "agent"))
        .podLabels(Map.of("app", "agentscope"))
        .isolationScope(IsolationScope.USER))
    .build();
```

#### E2B 沙箱

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("e2b-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new E2bFilesystemSpec()
        .apiKey("${E2B_API_KEY}")
        .templateId("my-template")
        .sandboxTimeoutSeconds(300)
        .isolationScope(IsolationScope.SESSION))
    .build();
```

#### Daytona 沙箱

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("daytona-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new DaytonaFilesystemSpec()
        .apiKey("${DAYTONA_API_KEY}")
        .controlPlaneBaseUrl("https://api.daytona.io")
        .image("python:3.12-slim")
        .cpu(2)
        .memory(4)        // GiB
        .disk(10)         // GiB
        .isolationScope(IsolationScope.USER))
    .build();
```

#### AgentRun 沙箱（阿里云）

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("agentrun-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new AgentRunFilesystemSpec()
        .apiKey("${AGENTRUN_API_KEY}")
        .accountId("your-account-id")
        .region("cn-hangzhou")
        .templateName("python3.12")
        .sandboxIdleTimeoutSeconds(600)
        .isolationScope(IsolationScope.USER))
    .build();
```

#### 所有沙箱后端的公共配置（继承自 `SandboxFilesystemSpec`）

| 方法 | 说明 | 默认值 |
|------|------|-------|
| `isolationScope(IsolationScope)` | 隔离维度 | 后端默认（通常 `SESSION`） |
| `snapshotSpec(SandboxSnapshotSpec)` | 快照策略 | `NoopSnapshotSpec` |
| `executionGuard(SandboxExecutionGuard)` | AGENT/GLOBAL scope 下的并发串行化守卫 | 无 |
| `workspaceProjectionEnabled(boolean)` | 是否从宿主投影静态资产到沙箱 | `true` |
| `workspaceProjectionRoots(List)` | 投影的根路径列表 | `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache` |

#### 快照策略

沙箱可以做快照，使下一次 `call()` 恢复之前的环境状态（安装的依赖、生成的文件等）：

| 实现 | 说明 |
|------|------|
| `NoopSnapshotSpec` | 不快照（默认） |
| `LocalSnapshotSpec(Path)` | 快照存宿主本地磁盘 |
| `RedisSnapshotSpec` | 快照存 Redis |
| `OssSnapshotSpec` | 快照存对象存储（阿里云 OSS） |
| `RemoteSnapshotSpec` | 快照存 `BaseStore` |

#### 示例场景：编程助手（Docker + 本地快照）

```java
HarnessAgent codingAgent = HarnessAgent.builder()
    .name("coder")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .filesystem(new DockerFilesystemSpec()
        .image("node:20-slim")
        .isolationScope(IsolationScope.USER)
        .memorySizeBytes(1024 * 1024 * 1024L)
        .snapshotSpec(new LocalSnapshotSpec("/data/sandbox-snapshots")))
    .distributedStore(store)
    .build();

// alice 第一次调用：沙箱里 npm install，装好后快照保存
RuntimeContext rc = RuntimeContext.builder()
    .userId("alice")
    .sessionId("dev-session-1")
    .build();
agent.call(Msg.user("npm install && npm test"), rc).block();

// alice 第二次调用：恢复快照，node_modules 还在，无需重新安装
agent.call(Msg.user("npm run build"), rc).block();
```

#### 工作区投影（Workspace Projection）

沙箱启动时，框架自动把宿主工作区里的"静态资产"打成 tar，注入（hydrate）到沙箱的 `/workspace`。这些静态资产包括：

- `AGENTS.md`（人格文件）
- `skills/`（技能目录）
- `subagents/`（子 agent 声明）
- `knowledge/`（知识库）
- `.skills-cache/`（技能缓存）

投影按内容 SHA-256 做增量比对，没变的文件跳过 hydrate。可通过 `workspaceProjectionRoots(List)` 自定义包含哪些路径，或用 `workspaceProjectionEnabled(false)` 完全关闭。

---

### 模式 3：本机 + shell（默认）

什么都不写就是这个：工作区落到 `${cwd}/.agentscope/workspace/`，shell 在宿主上跑：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("local-agent")
    .model(model)
    .workspace(workspace)
    // .filesystem(...) 不写 = 本机 + shell
    .build();
```

#### 所有配置项

```java
.filesystem(new LocalFilesystemSpec()
    .executeTimeoutSeconds(120)       // shell 命令超时
    .maxOutputBytes(100_000)          // 单条命令最大输出字节
    .env("MY_VAR", "value")          // 额外环境变量
    .inheritEnv(true)                // 是否继承父进程环境
    .mode(LocalFsMode.ROOTED)        // 路径策略
    .project(Paths.get("/my/project")) // 项目根（shell 的 cwd + overlay 下层）
    .addRoot(Paths.get("/extra/dir"))) // 额外可访问目录
```

| 方法 | 说明 | 默认值 |
|------|------|-------|
| `executeTimeoutSeconds(int)` | 单条 shell 命令超时（秒） | 120 |
| `maxOutputBytes(int)` | 单条命令最大捕获输出字节数 | 100,000 |
| `env(String, String)` | 添加 shell 环境变量 | 无 |
| `inheritEnv(boolean)` | 是否继承父进程环境 | `false` |
| `mode(LocalFsMode)` | 路径解析策略 | `ROOTED` |
| `project(Path)` | 项目根目录（overlay 下层 + shell cwd） | `System.getProperty("user.dir")` |
| `addRoot(Path)` | 额外允许访问的宿主目录 | 无 |
| `additionalRoots(Collection)` | 批量设置额外目录 | 无 |
| `projectWritable(boolean)` | 文件工具写项目文件时直接落到项目目录，而非 workspace | `false` |

#### 路径解析策略（`LocalFsMode`）

| 模式 | 行为 |
|------|------|
| `ROOTED`（默认） | 绝对路径只允许 `workspace` + `project` + `additionalRoots` 范围内；`..` 穿越被拒绝 |
| `SANDBOXED` | 所有路径强制锚定到 workspace 根，绝对路径和 `..` 全部拒绝 |
| `UNRESTRICTED` | 绝对路径原样透传，不做限制。仅用于测试或完全信任的环境 |

#### Overlay 文件系统

本机模式实际产出的是一个 `OverlayFilesystem`：

- **上层**（读写）：`LocalFilesystemWithShell`，根在 `workspace`，提供 shell；
- **下层**（只读）：`LocalFilesystem`，根在 `project`。

读取时先看 workspace，没有再退到 project（copy-on-write 语义）。shell 的 `pwd` 是 project 目录，所以 agent 执行 `ls` 看到的是项目文件。

#### 项目可写模式（`projectWritable`）

默认情况下，所有写入都落到 workspace——这对阅读/分析类场景足够，但如果 agent 的核心任务是**生成代码**（如写一个微服务），你会发现文件全写到了 `.agentscope/workspace/` 而不是项目目录。

开启 `projectWritable(true)` 后，框架会根据路径自动路由写入目标：

| 路径类型 | 写入位置 | 示例 |
|----------|---------|------|
| 工作区元数据 | workspace | `MEMORY.md`、`memory/`、`agents/`、`skills/`、`knowledge/`、`plans/`、`subagents/`、`rules/`、`tools.json` |
| 其他所有文件 | 项目目录 | `src/main/java/App.java`、`pom.xml`、`README.md`、`docker-compose.yml` |

```java
.filesystem(new LocalFilesystemSpec()
    .projectWritable(true)      // 代码文件写到项目目录
    .inheritEnv(true))
```

读取行为不变——仍然是 workspace 优先、project 兜底。

#### 示例场景：本地开发助手

```java
HarnessAgent devHelper = HarnessAgent.builder()
    .name("dev-helper")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .filesystem(new LocalFilesystemSpec()
        .project(Paths.get("/Users/alice/my-project"))
        .addRoot(Paths.get("/Users/alice/.config"))
        .mode(LocalFsMode.ROOTED)
        .inheritEnv(true)
        .executeTimeoutSeconds(300))
    .build();
```

agent 可以读写 `/Users/alice/my-project` 和 `/Users/alice/.config` 下的文件，在 `/Users/alice/my-project` 下执行 shell 命令，但无法访问其他宿主目录。

---

## IsolationScope —— 多用户与多副本怎么分桶

模式 1（共享存储）和模式 2（沙箱）都用同一个 `IsolationScope` 概念，决定**谁和谁共享同一份状态**：

| Scope | 含义 | 命名空间键 | 典型场景 |
|-------|------|-----------|---------|
| `SESSION` | 每个 sessionId 独立 | `agents/<agentId>/sessions/<sessionId>/...` | 多用户 SaaS，每段对话完全隔离 |
| `USER`（默认） | 同一 `userId` 跨会话共享 | `agents/<agentId>/users/<userId>/...` | 同一用户的多个会话共享长期记忆 |
| `AGENT` | 该 agent 的所有用户/会话共享 | `agents/<agentId>/shared/...` | 公共知识库型 agent |
| `GLOBAL` | 全局共享一份 | `global/...` | 谨慎使用 |

### 各 Scope 的降级规则

- `USER` scope 下，如果 `RuntimeContext.userId` 为空，降级为 `SESSION`（按 sessionId 隔离）。
- `SESSION` scope 下，如果 `RuntimeContext.sessionId` 为空，跳过状态查找，创建全新环境。
- `AGENT` scope 的命名空间键由 agent name（build 时固定）决定，不会因缺少上下文字段而降级。

### 沙箱模式下的并发行为

`IsolationScope` 在沙箱模式下是**顺序复用**的共享，不是实时的实例共享。同一 scope key 的并发调用各自启动独立容器；每次调用结束时，最后写入的快照胜出。对 `AGENT` / `GLOBAL` 这种多用户共享 scope，如果需要串行化，使用 `executionGuard(SandboxExecutionGuard)` 做并发守卫。

### 示例：用 Scope 组合实现不同业务需求

**场景 1：每个用户独立的编程沙箱，跨会话保留安装的依赖**

```java
.filesystem(new DockerFilesystemSpec()
    .image("python:3.12")
    .isolationScope(IsolationScope.USER)       // alice 的所有会话共享同一沙箱快照
    .snapshotSpec(new LocalSnapshotSpec("/snapshots")))
```

**场景 2：每个对话独立的一次性沙箱**

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.SESSION))   // 每个 sessionId 独立，互不影响
```

**场景 3：共享知识库的客服 agent（共享存储）**

```java
.distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
    .isolationScope(IsolationScope.AGENT))     // 所有用户和会话共享同一份 memory / skills
```

---

## 多用户隔离怎么实现

`RuntimeContext.userId` 是切多用户的钥匙：

| 模式 | userId 的作用 | 物理表现 |
|------|-------------|---------|
| 本机 | 用户级文件落在 `workspace/<userId>/...`，例如 `workspace/alice/skills/code-reviewer/SKILL.md` 只对 alice 生效 | 路径前缀 |
| 共享存储 | 作为 KV 命名空间前缀 `agents/<agentId>/users/<userId>/...` | KV 键前缀 |
| 沙箱 | 作为沙箱快照 slot key（搭配 `IsolationScope.USER`） | 沙箱实例隔离 |

`userId` 不传的情况下走单租户默认，所有人共享一个根。

### 运行时数据 vs 静态资产

**运行时数据**（对话日志、tasks、memory）跟着 `IsolationScope` / `userId` 走，自动隔离。

**静态资产**（`AGENTS.md`、`tools.json`、`knowledge/`）对所有用户共享，**不**按 userId 自动分区。差异化只能通过「用户覆盖目录」实现：

```
workspace/
├── skills/code-reviewer/SKILL.md     ← 共用版（所有人可见）
└── alice/
    └── skills/code-reviewer/SKILL.md ← 只对 alice 生效，覆盖共用版
```

---

## 技能和工具在各模式下的行为

### 技能（Skills）

`DynamicSkillMiddleware` 在每轮推理前从技能仓库列表合并技能，渲染到 system prompt 里。技能文件的加载走 `AbstractFilesystem` 接口，所以在三种模式下透明工作：

| 模式 | 技能加载方式 |
|------|------------|
| 本机 | 从 `workspace/skills/` 直接读本地磁盘；`<userId>/skills/` 做用户覆盖 |
| 共享存储 | `skills/` 路由到 KV，先查远端再退回本地模板。管理台编辑技能后所有副本下次推理生效 |
| 沙箱 | 宿主 `skills/` 在启动时通过 workspace projection 注入沙箱的 `/workspace/skills/` |

四层优先级不变（低 → 高）：`projectGlobalSkillsDir` → `skillRepository` → `workspace/skills/` → `<userId>/skills/`。

### 文件工具（read_file / write_file / edit_file / ...）

所有文件工具都通过 `AbstractFilesystem` 接口调用，每次操作传入当前 `RuntimeContext`，由文件系统后端决定实际读写位置。agent 代码完全感知不到模式差异。

| 模式 | 读写行为 |
|------|---------|
| 本机 | `OverlayFilesystem`：写落 workspace（上层），读先 workspace 后 project（下层）。开启 `projectWritable(true)` 后，非元数据写入路由到项目目录 |
| 共享存储 | `CompositeFilesystem`：命中路由的路径走 KV overlay（远端上层 + 本地模板下层），其余走本地 |
| 沙箱 | 所有文件操作转发到沙箱容器内 |

### Shell 执行（execute）

| 模式 | Shell 可用？ | 执行位置 |
|------|------------|---------|
| 本机 | ✅ | 宿主 `sh -c`，cwd 为 `project` 目录 |
| 共享存储 | ❌ | 不提供 shell |
| 沙箱 | ✅ | 沙箱容器内 |

### tools.json / MCP 服务器

`tools.json` 在 `build()` 时一次性从工作区读取（走 `WorkspaceManager`，支持两层读），注册 MCP server 和 allow/deny 过滤。**三种模式下行为一致**——都是在 build 时读取配置，不受运行时 filesystem 模式影响。

在共享存储模式下，`tools.json` 也走"远端为上层、本地模板为下层"的 overlay：通过管理台修改 `tools.json` 后，**需要重新 build agent 才能生效**（MCP server 注册是一次性的）。

---

## 工作区里的两层读取

`AGENTS.md`、`MEMORY.md`、`KNOWLEDGE.md` 等关键文件在读取时有"两层兜底"：先看你配的文件系统后端，没有再退回本地磁盘。这对**模式 1（共享存储）下的"模板文件"** 很有用：第一个副本启动时本地有 `AGENTS.md` 模板，立刻可用；后续副本会从共享存储读出最新版本。

写入永远走配置的文件系统后端。

## 完全自管：`abstractFilesystem(...)`

如果三种模式都不合适，可以传一个完全自己实现的文件系统：

```java
HarnessAgent.builder()
    ...
    .abstractFilesystem(myCustomFilesystem)   // 与上面的 filesystem(...) 互斥
    .build();
```

通常不需要——三种模式覆盖了 95% 的场景。

## 相关文档

- [沙箱](./sandbox) — 模式 2 的运行时细节（容器生命周期、快照恢复链路）
- [工作区](./workspace) — 目录布局、加载机制、两层读取的"下层"来源
- [Context](./context) — `AgentState` 与 `AgentStateStore`、`(userId, sessionId)` 寻址
- [技能](./skill) — 四层合成、自学习闭环、`<available_skills>` 块
- [工具](../building-blocks/tool) — `read_file` / `write_file` / `execute` 等参数
- [架构](./architecture) — 文件系统与运行时上下文如何协作
