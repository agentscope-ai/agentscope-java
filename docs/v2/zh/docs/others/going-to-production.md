---
title: "上生产（Going to Production）"
description: "从单机原型到多副本分布式部署：AgentStateStore / Filesystem / Skill / Sandbox / 快照 / 观测的组件选型与配置清单"
---

> 把 `HarnessAgent` 在你笔记本上跑起来很容易，搬到生产环境是另一回事——多副本要共享会话、要隔离用户、要支持不可信代码执行、要在 pod 重启后接着上次跑。本页**只讲单机 → 分布式生产的差异**：哪些组件必须换、换成什么、为什么 builder 会在你漏配时直接抛 `IllegalStateException`。

**最快上手方式**：用 `DistributedStore` 一键配置所有分布式组件：

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);
// 或 MysqlDistributedStore.create(dataSource);
// 或 OssDistributedStore.create(ossClient, bucket, prefix);

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(...)  // 选择你的 workspace 模式
    .build();
```

混合 store（如 MySQL 管状态 + Redis 管 sandbox）也行：

```java
DistributedStore store = DistributedStore.builder()
    .agentStateStore(MysqlDistributedStore.create(ds).agentStateStore())
    .baseStore(MysqlDistributedStore.create(ds).baseStore())
    .sandboxSnapshotSpec(RedisDistributedStore.fromJedis(jedis).sandboxSnapshotSpec())
    .sandboxExecutionGuard(RedisDistributedStore.fromJedis(jedis).sandboxExecutionGuard())
    .build();
```

## 一图速览：单机默认 vs 分布式生产

| 维度 | 单机默认（开发 / demo） | 分布式生产替换 |
|------|----------------------|----------------|
| **一键配置** | 不需要 | **`.distributedStore(RedisDistributedStore.fromJedis(jedis))`** |
| `AgentStateStore` | `JsonFileAgentStateStore`（本地 JSON） | 自动由 `distributedStore` 注入 |
| Filesystem | `LocalFilesystemSpec`（不配 = 此项） | `RemoteFilesystemSpec` 或 `SandboxFilesystemSpec`（`baseStore` 自动由 store 注入） |
| Sandbox 快照 | `NoopSnapshotSpec` / `LocalSnapshotSpec` | 自动由 `distributedStore` 注入 |
| 沙箱执行串行化 | 单进程内即可 | 自动由 `distributedStore` 注入 |
| Skill 来源 | `workspace/skills/` | `GitSkillRepository` / `MysqlSkillRepository` / `NacosSkillRepository` |
| 观测 | 默认无 tracing | `OtelTracingMiddleware` + OpenTelemetry SDK |

### DistributedStore 能力矩阵

| 能力 | Redis (`agentscope-extensions-redis`) | OSS (`agentscope-extensions-oss`) | MySQL (`agentscope-extensions-mysql`) |
|------|:-----:|:---:|:-----:|
| `AgentStateStore` | `RedisAgentStateStore` | `OssAgentStateStore` | `MysqlAgentStateStore` |
| `BaseStore` | `RedisStore` | `OssBaseStore` | `JdbcStore` |
| `SandboxSnapshotSpec` | `RedisSnapshotSpec` | `OssSnapshotSpec` | `JdbcSnapshotSpec` |
| `SandboxExecutionGuard` | `RedisSandboxExecutionGuard` | — (对象存储不适合做锁) | `JdbcSandboxExecutionGuard` |

这些组件分别解决不同的生产问题：

- `AgentStateStore`：保存 Agent 的运行时会话状态，包括对话历史、压缩摘要、权限规则、Plan Mode 状态和 tool state。它决定一个请求落到另一台机器、或进程重启后，Agent 能不能继续同一个 `(userId, sessionId)`。
- `BaseStore`：给 `RemoteFilesystemSpec` 提供共享 KV 文件存储，用来承载 `MEMORY.md`、`memory/`、`skills/`、`sessions/` 等 workspace 路径。多副本部署时，它让不同 pod 看到同一份长期记忆和共享文件。
- `SandboxSnapshotSpec`：保存沙箱 workspace 快照。沙箱容器销毁、pod 重启、下一次请求落到新节点时，它负责把上一次的工作区恢复回来，避免 `pip install`、生成文件、临时项目状态全部丢失。
- `SandboxExecutionGuard`：对同一个 sandbox slot 的命令执行做跨节点串行化。`AGENT` / `GLOBAL` 等共享 scope 下，多个副本可能同时对同一个沙箱执行命令；guard 用 Redis/MySQL 锁避免并发写 workspace、并发启动/停止 sandbox 等竞态。

> OSS 不提供 `SandboxExecutionGuard`——对象存储不适合做分布式锁。需要 sandbox 并发控制的 OSS 用户，用 `DistributedStore.builder()` 混入 Redis 的 guard 即可。

**核心校验链路：**
- `filesystem(RemoteFilesystemSpec)` + 没换 `stateStore(...)` 也没配 `distributedStore(...)` → `build()` 抛 `IllegalStateException`。
- `filesystem(SandboxFilesystemSpec)` + 本地 `AgentStateStore` → `build()` 正常通过但打一条 **warning** 日志，提醒你沙箱状态不能跨 JVM 恢复；生产环境务必配 `distributedStore`。

## 1. 状态存储：先把 `AgentState` 放对地方

> **推荐**：直接用 `distributedStore(...)` 一键配置，不需要手动设置 `stateStore`。下面的详细表格供需要单独控制 `AgentStateStore` 的高级用户参考。

`AgentState`（对话上下文、压缩摘要、权限规则、Plan Mode 状态、tool state）跨进程恢复的唯一通路就是 [`AgentStateStore`](../../integration/session/index.md)。

| 实现 | 模块 | 何时使用 |
|------|------|---------|
| `InMemoryAgentStateStore` | `agentscope-core` | 单元测试；进程退出全部丢 |
| `JsonFileAgentStateStore` | `agentscope-core` | 单机开发；按 `(userId, sessionId)` 在文件系统分目录。**HarnessAgent 默认**，根目录 `~/.agentscope/state/<agentId>/`；**单机** |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | **多副本生产首选**；支持 Jedis / Lettuce / Redisson（Standalone / Cluster / Sentinel） |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | 需要把状态沉淀进关系型库（审计 / 报表 / 联表查询） |

**Redis 三种 client adapter** 都通过 `RedisAgentStateStore.builder()` 切换：

```java
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import redis.clients.jedis.JedisPooled;

// Jedis Standalone
AgentStateStore stateStore = RedisAgentStateStore.builder()
        .jedisClient(new JedisPooled("redis://localhost:6379"))
        .keyPrefix("myapp:session:")
        .build();

// Lettuce Cluster（写多读少更顺）
// .lettuceClusterClient(RedisClusterClient.create(...))

// Redisson（如果你已经在用 Redisson 做其他事）
// .redissonClient(redisson)
```

**按租户隔离。** 单用 `sessionId` 只够单租户。生产应在每次调用的 `RuntimeContext` 上同时设置 `userId` 与 `sessionId`，防止跨用户串读——存储按 `(userId, sessionId)` 二元组寻址每个槽位（`RedisAgentStateStore` 把 `userId` 折进 Redis key，`MysqlAgentStateStore` 折进主键）。其他维度（租户、agent）自行拼进 `sessionId` 字符串：

```java
agent.call(msg, RuntimeContext.builder()
        .userId(tenantId + ":" + userId)
        .sessionId(agentId + ":" + sessionId)
        .build()).block();
```

完整细节见[上下文与 AgentState](../building-blocks/context.md)。

## 2. Filesystem 模式 & IsolationScope：决定"谁和谁共享文件"

三种模式快速回顾（详见 [filesystem](../harness/filesystem.md)）：

| 模式 | 配置 | 提供 shell？ | 适用 |
|------|------|-------------|------|
| **本机 + shell** | `filesystem(new LocalFilesystemSpec()...)` 或不配 | ✅ 宿主 `sh -c` | 单进程 / 信任环境 |
| **共享存储** | `filesystem(new RemoteFilesystemSpec(store))` | ❌（要 shell 请走沙箱） | 多副本 / 多 pod 共享长期记忆 |
| **沙箱** | `filesystem(new DockerFilesystemSpec()...)` 等 5 种 | ✅ 沙箱内执行 | 不可信代码 / 跨调用恢复 / 多用户隔离 |

**`IsolationScope` 是多用户隔离的核心钥匙**。共享存储和沙箱两种模式都用同一套 scope 决定命名空间分桶：

| Scope | 含义 | 典型场景 |
|-------|------|---------|
| `SESSION`（沙箱默认） | 每个 sessionId 独立 slot | 多用户 SaaS，每段对话独立 |
| `USER`（Remote 默认） | 同一 `userId` 跨 session 共享 | 同一用户多设备共享长期记忆 |
| `AGENT` | agent 内所有用户共享 | 公共知识库型 agent |
| `GLOBAL` | 全局一个 slot | 谨慎使用 |

```java
// distributedStore 自动注入 baseStore 到 RemoteFilesystemSpec
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER)
            .anonymousUserId("_default"))   // 未传 userId 时的 fallback
    .build();
```

`anonymousUserId` 是个生产细节——很多场景下 `RuntimeContext.userId` 可能为 null（系统任务、调度器触发、admin 操作），fallback 别用空字符串，否则所有匿名调用会聚到一个共享桶。

## 3. Remote 模式的 BaseStore：KV 选型与"不要把 OSS 当 KV 用"

`RemoteFilesystemSpec` 建在一个 `BaseStore` 接口之上。内置实现两种：

| 实现 | 依赖 | 并发安全 | 适用 |
|------|------|---------|------|
| `RedisStore` | `agentscope-extensions-redis` | Lua 实现 CAS putIfVersion，`ZRANGEBYLEX` 做 prefix search | 主推；多副本共享 |
| `JdbcStore` | `agentscope-extensions-mysql`；MySQL / PostgreSQL / SQLite / H2 dialect 自动判别 | 单语句 CAS UPDATE | 已有关系型基础设施 / 需要联表 |
| `InMemoryStore` | — | — | 测试 |

```java
// 推荐：DistributedStore 一键配置
DistributedStore store = RedisDistributedStore.fromJedis(
        new JedisPooled("redis://prod-redis:6379"));

HarnessAgent agent = HarnessAgent.builder()
        .name("multi-tenant-agent")
        .model(model)
        .workspace(workspace)
        .distributedStore(store)           // 自动注入 stateStore + baseStore
        .filesystem(new RemoteFilesystemSpec() // baseStore 由 store 自动注入
                .isolationScope(IsolationScope.USER)
                .workspaceIndex(WorkspaceIndex.open(workspace)))  // 加速 ls/glob
        .build();

// 或者用 MySQL store：
DistributedStore mysqlStore = MysqlDistributedStore.create(dataSource);
```

### 那 OSS / NAS / S3 怎么放进来？

**不要为了 OSS 写一个 `BaseStore` 实现**——`MEMORY.md` / `memory/YYYY-MM-DD.md` / `agents/<id>/context/<sid>/` 每秒可能写几次，OSS 的延迟与 per-request 成本会立刻失控。正确分工是：

| 数据形态 | Store | 谁来管 |
|---------|------|-------|
| 高频小 KV（记忆、会话快照、任务记录） | Redis / MySQL（`BaseStore`） | `RemoteFilesystemSpec` |
| 大对象（沙箱整个 workspace tar archive，几十 MB） | OSS / S3 | `OssSnapshotSpec` / 自定义 `RemoteSnapshotSpec` |
| 跨节点共享卷（多个沙箱实例挂同一份目录） | NAS / EFS | `AgentRunFilesystemSpec.nasConfig(...)`（仅 AgentRun 原生支持） |

### `RemoteFilesystemSpec` 的路由表

为避免不同子系统的 key 撞车，spec 把工作区路由切成多个命名空间段（每段独立）：

| Workspace 路径 | 命名空间段 |
|---------------|-----------|
| `AGENTS.md` / `MEMORY.md` / `tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |
| 额外目录：`.addSharedPrefix("prompts/")` | 自动派生 |

每段下面再按 `IsolationScope` 切桶（`USER` → `agents/<agentId>/users/<userId>/`）。Redis key 大致长成 `agentscope:store:item:agents\0X\0users\0alice\0memory\0memory/2026-06-02.md`。

### `CompositeFilesystem`：两层读+写穿透

`RemoteFilesystemSpec.toFilesystem(...)` 实际产出的是 `CompositeFilesystem`：底层一个不带 shell 的 `LocalFilesystem`（兜底读本地模板），顶层每条路由是一个 `OverlayFilesystem`（上层 `RemoteFilesystem` + 下层只读 `LocalFilesystem` 模板）。

效果：**写永远落 Remote，读优先 Remote、没有再退回本地模板**。这就是 [Workspace](../harness/workspace.md) 文档里讲的"两层读架构"在 Remote 模式下的具体形态——本地 `<workspace>/AGENTS.md` 是种子（团队 git 同步），Remote 一旦写入就接管。

### `WorkspaceIndex`：可选 SQLite 索引

```java
.filesystem(new RemoteFilesystemSpec(store).workspaceIndex(WorkspaceIndex.open(workspace)))
```

加速 Remote 模式下的 `ls` / `glob` / `exists` / `grep`——不开的话每次都全表扫 KV。WorkspaceIndex 是 best-effort 的 SQLite 文件（落在 `<workspace>/.index/`），失败会自动降级，不影响功能。

## 4. Skill 集中管理：选哪种 SkillRepository

Skill 优先级从低到高合成（详见 [技能](../harness/skill.md)）：

| 层 | 来源 | 用什么 | 适用 |
|---|------|-------|------|
| 1 | 项目全局 | `.projectGlobalSkillsDir(Path)` | 个人开发机器；`~/.agentscope/skills/` |
| 2 | Marketplace | `.skillRepository(...)` | 跨项目共享 |
| 3 | 工作区共用 | `workspace/skills/` | 项目专属；进 git |
| 4 | 用户隔离 | `<userId>/skills/` | 用户级覆盖 |

### Marketplace 存储源选型

| Repository | 模块 | 特点 | 推荐场景 |
|-----------|------|------|---------|
| `GitSkillRepository` | `agentscope-extensions-skill-git-repository` | 团队 git 仓库；HEAD 变化才拉；只读分发 | 早期 / 小团队；改 skill 走 git PR review |
| `MysqlSkillRepository` | `agentscope-extensions-skill-mysql-repository` | DataSource 注入；`writeable(true/false)` 双模式；从 agent 侧可写回 | 平台侧统一治理；多团队多 agent |
| `NacosSkillRepository` | `agentscope-extensions-nacos-skill` | 在线下发 + 配置中心变更订阅；`AutoCloseable` | 阿里系生态；要"改一次全网立即生效" |
| `ClasspathSkillRepository` | `agentscope-core` | 和 JAR 一起发；Spring Boot Fat JAR 兼容 | 产品内置不可改的能力包 |

```java
HarnessAgent agent = HarnessAgent.builder()
        // ...
        .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
        .skillRepository(MysqlSkillRepository.builder(dataSource)
                .databaseName("agentscope")
                .skillsTableName("skills")
                .createIfNotExist(true)
                .writeable(false)                  // 只读分发，生产建议
                .build())
        .build();
```

`skillRepository(...)` 可重复调用；后注册的优先级更高，同名覆盖。

### 生产 checklist

- **优先 `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository`**——平台集中治理，agent 端只读；写回走管理台 + 审核流。
- 不希望 agent 看到 `workspace/skills/`？`.disableDefaultWorkspaceSkills()`。
- 开 `enableSkillManageTool` 让 agent 自己起草新 skill 时，**必须**配 `enableSkillPromotionGate(...)`；生产严禁 `autoPromote=true`。
- `NacosSkillRepository` 是 `AutoCloseable`——Spring `@PreDestroy` 或者 `try-with-resources` 关掉它，否则会泄露订阅。

## 5. 需要 shell：选 Sandbox + 必配 Snapshot

什么场景必走沙箱：

- 模型可能跑不可信代码（Python / shell / `npm install` / 编译）
- 需要跨调用恢复**整个工作目录**状态（`node_modules`、生成文件、`pip install` 后的环境）
- 多用户硬隔离（不能让一个用户的进程看到另一个用户的）

### 五种沙箱实现

| Spec | 模块路径 | 适用 |
|------|---------|------|
| `DockerFilesystemSpec` | `io.agentscope.harness.agent.sandbox.impl.docker` | 单机 / 本地集群；从 image 起容器；最熟悉 |
| `KubernetesFilesystemSpec` | `...impl.kubernetes` | 已经跑 K8s；走 pod / Job |
| `DaytonaFilesystemSpec` | `...impl.daytona` | Daytona 服务（开发环境即服务） |
| `E2bFilesystemSpec` | `...impl.e2b` | E2B 云沙箱；最快上云、不依赖自有基础设施 |
| `AgentRunFilesystemSpec` | `...impl.agentrun` | **阿里云 AgentRun**；原生 NAS / OSS mount、企业级方案 |

```java
.filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION))
```

### Snapshot 是沙箱的"分布式生命线"

> **推荐**：使用 `distributedStore(...)` 后，`SandboxSnapshotSpec` 和 `SandboxExecutionGuard` 都会自动注入到 `SandboxFilesystemSpec`，不需要手动配置。下面的表格供需要单独控制快照实现或使用 `LocalSnapshotSpec` 的场景参考。

沙箱默认是"瞬时"的——下一次 `call()` 可能起在另一个节点的新容器里，之前 `pip install` / 写入的所有产物全丢。`SandboxSnapshotSpec` 把工作区打成 tar 持久化，下次 `call()` 自动 hydrate 回新容器。

| Spec | Store | 模块 | 何时用 |
|------|------|------|--------|
| `NoopSnapshotSpec` | — | `agentscope-harness` | 不要在生产用；容器丢了就走冷启动 |
| `LocalSnapshotSpec(Path)` | 本地目录 `tar` 文件 | `agentscope-harness` | 单机调试 |
| `OssSnapshotSpec` | 阿里云 OSS | `agentscope-extensions-oss` | **大对象首选**；天然适合对象存储 |
| `RedisSnapshotSpec` | Redis | `agentscope-extensions-redis` | 小工作区 + 短 TTL（注意 Redis 内存代价） |
| `JdbcSnapshotSpec` | MySQL / JDBC BLOB | `agentscope-extensions-mysql` | 已有关系型数据库、不想引入额外中间件 |
| 自实现 `RemoteSnapshotClient` → `RemoteSnapshotSpec` | S3 / GCS / MinIO | — | 不在内置列表里 |

```java
DistributedStore redisStore = RedisDistributedStore.fromJedis(jedis);
DistributedStore ossStore = OssDistributedStore.create(
        ossClient,
        "agentscope-sandbox-snapshots",
        "prod/");                         // key 前缀，多环境隔离

DistributedStore store = DistributedStore.builder()
        .agentStateStore(redisStore.agentStateStore())
        .baseStore(redisStore.baseStore())
        .sandboxSnapshotSpec(ossStore.sandboxSnapshotSpec())
        .sandboxExecutionGuard(redisStore.sandboxExecutionGuard())
        .build();

HarnessAgent agent = HarnessAgent.builder()
        .name("coding-agent")
        .model(model)
        .workspace(workspace)
        .distributedStore(store)
        .filesystem(new DockerFilesystemSpec()
                .image("python:3.12-slim")
                .isolationScope(IsolationScope.USER))
        .build();
```

使用 `distributedStore(...)` 后，快照和执行锁都会自动注入，不需要在 `SandboxFilesystemSpec` 上手动配置。如果只是要改 OSS bucket / prefix，优先在创建 `OssDistributedStore` 时配置；只有需要完全自定义 `SandboxSnapshotSpec` 时，才在 `SandboxFilesystemSpec` 上显式覆盖。

### 沙箱执行节点串行化：`SandboxExecutionGuard`

`SESSION` / `USER` scope 下天然按 session/user 分桶，并发不会撞。但 `AGENT` / `GLOBAL` scope 多副本部署时，可能同时有 N 个节点要在同一个 sandbox slot 上 `exec`——会撞。`distributedStore(...)` 会自动注入对应 store 的执行锁：

| 实现 | 模块 | 机制 |
|------|------|------|
| `RedisSandboxExecutionGuard` | `agentscope-extensions-redis` | Redis `SET NX PX` 租约 |
| `JdbcSandboxExecutionGuard` | `agentscope-extensions-mysql` | MySQL `GET_LOCK()` / `RELEASE_LOCK()` |

推荐仍然通过 `DistributedStore` 注入执行锁：

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent.builder()
        .distributedStore(store)
        .filesystem(new DockerFilesystemSpec()
                .image("ubuntu:24.04")
                .isolationScope(IsolationScope.GLOBAL))
        .build();
```

只有需要自定义锁参数（如租约 TTL）时，才在 `SandboxFilesystemSpec` 上显式覆盖：

```java
HarnessAgent.builder()
        .distributedStore(store)
        .filesystem(new DockerFilesystemSpec()
                .image("ubuntu:24.04")
                .isolationScope(IsolationScope.GLOBAL)
                .executionGuard(RedisSandboxExecutionGuard.builder(jedis)
                        .leaseTtl(Duration.ofMinutes(30))
                        .build()))
        .build();
```

也可以实现 `SandboxExecutionGuard` 接口接入 Zookeeper、etcd 等其他锁。

### Workspace projection：把工作区里的种子投到沙箱

`SandboxFilesystemSpec` 默认会把 `AGENTS.md, skills, subagents, knowledge, .skills-cache` 五个 root 打 tar 在沙箱启动时 hydrate 进去（内容 hash 比对、增量重写）。要调整：

```java
.filesystem(new DockerFilesystemSpec()
        .image("...")
        .workspaceProjectionRoots(List.of("AGENTS.md", "skills", "knowledge"))   // 不要 subagents/.skills-cache
        // .workspaceProjectionEnabled(false)   // 完全关掉
)
```

### AgentRun 特有：NAS / OSS mount

`AgentRunFilesystemSpec` 是唯一原生支持**多 sandbox 实例共享同一个目录**的实现（通过 NAS mount）；如果业务是"一个用户在不同 session 里看到同一份 workspace"，用 AgentRun 比每次 hydrate snapshot 更高效：

```java
.filesystem(new AgentRunFilesystemSpec()
        .apiKey(System.getenv("AGENTRUN_API_KEY"))
        .accountId(System.getenv("ALI_ACCOUNT_ID"))
        .region("cn-hangzhou")
        .templateName("python-3.12")
        .nasConfig(new AgentRunNasMountConfig().fileSystemId("...").mountTargetDomain("...").mountDir("/workspace"))
        .addOssMount(new AgentRunOssMountConfig().bucketName("data").mountDir("/mnt/oss")))
```

完整字段见 `AgentRunNasMountConfig` / `AgentRunOssMountConfig` 源码。

## 6. 多副本部署 checklist（综合）

把上面单点替换串成一张表：

| 关注点 | 推荐组合 |
|-------|----------|
| 会话 / `AgentState` | `RedisDistributedStore` 或混合 `DistributedStore` 注入 `AgentStateStore`；`(userId, sessionId)` 承载租户/用户/agent 维度 |
| 工作区文件 | `distributedStore(...)` 注入 `BaseStore` + `RemoteFilesystemSpec` + `WorkspaceIndex` + `IsolationScope.USER` |
| 大对象 / 快照 | 混合 `DistributedStore` 中使用 `OssDistributedStore.sandboxSnapshotSpec()`（不要把大快照写 Redis） |
| 跨节点 sandbox 共享 | AgentRun + NAS mount，或自管 K8s + `distributedStore(...)` 注入的 `SandboxExecutionGuard` |
| Skill 治理 | `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository`；agent 端禁用 autoPromote |
| 子 agent 任务记录 | 自动用 `WorkspaceTaskRepository`，落 Remote / Sandbox；不需要额外配 |
| 暴露的子 agent（用户直接和子 agent 对话） | 注册表由 `distributedStore` 自动接好——`subagentId` 在任意副本/重启后都能解析并恢复子 agent；把某个 `subagentId` 的消息路由回同一节点（粘性）即可让恢复只作为故障切换兜底。多 agent 的 `GatewayBootstrap` 传 `.distributedStore(...)` |
| 优雅停机 | `GracefulShutdownManager`（默认注册 JVM hook）；接好 SIGTERM；视需要 `setConfig(...)` 调 inflight 等待时间 |
| 可观测 | `OtelTracingMiddleware` + OpenTelemetry SDK + OTLP exporter |
| 限流 | 自写 `MiddlewareBase`（onModelCall）；参考 [Middleware — 限速 middleware](../building-blocks/middleware.md#限速-middleware) |

## 7. 一个完整的生产 builder 模板

Agent 在调用之间是无状态的——单例即可服务并发请求。每次 `call()` 通过 `RuntimeContext` 的 `(userId, sessionId)` 定位状态，互不干扰。

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.core.memory.compaction.CompactionConfig;
import io.agentscope.core.memory.compaction.ToolResultEvictionConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import redis.clients.jedis.JedisPooled;

// --- 依赖（应用启动时创建一次） ---
Path workspace = Paths.get("/var/agentscope/workspace");
JedisPooled jedis = new JedisPooled(System.getenv("REDIS_URI"));
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

// --- 单例 agent（应用启动时创建一次） ---
HarnessAgent agent = HarnessAgent.builder()
        .name("coding-assistant")
        .model("dashscope:qwen-plus")
        .workspace(workspace)
        .distributedStore(store)  // 自动注入 stateStore + snapshotSpec + executionGuard
        .filesystem(new DockerFilesystemSpec()
                .image("python:3.12-slim")
                .isolationScope(IsolationScope.USER))
        .compaction(CompactionConfig.builder()
                .triggerMessages(50)
                .keepMessages(20)
                .build())
        .toolResultEviction(ToolResultEvictionConfig.defaults())
        .skillRepository(io.agentscope.core.skill.repository.mysql.MysqlSkillRepository
                .builder(skillsDataSource())
                .createIfNotExist(false)
                .writeable(false)
                .build())
        .middlewares(List.of(new OtelTracingMiddleware()))
        .build();
```

调用时传入 `RuntimeContext` 标识用户和会话。不同 session 在同一个 agent 实例上自动并行：

```java
// 在 HTTP handler 中
agent.call(msg, RuntimeContext.builder()
        .userId(httpRequest.tenantUserId())
        .sessionId(httpRequest.sessionId())
        .build()).block();
```

## 8. 常见坑位

- **忘记传 `RuntimeContext`**——不传 `sessionId` 时所有请求共享 `defaultSessionId` 的状态，造成串台。在多用户场景下，**每次 `call()` 都应通过 `RuntimeContext.builder().userId(...).sessionId(...).build()` 传入**，确保各会话状态隔离。参见 [Agent — 多用户并发](../building-blocks/agent.md#多用户--多会话并发)。
- **`java.nio.Files` 写工作区**——在沙箱 / Remote 模式下落到错的位置。永远走 `agent.getWorkspaceManager()`。**例外**：builder 装配时的种子文件（`initWorkspaceIfAbsent` 之类）那时还没有运行时上下文，用 `java.nio.Files` 是 OK 的。
- **`tools.json` 的 `allow` 会过滤内置工具**——用白名单时务必把 `read_file` / `memory_search` / `agent_spawn` 这些保留下来，否则整套内置工具一起被砍。
- **`IsolationScope` 改了，旧数据不会自动迁移**——上线前定下来，别上线后改。改了等同于"换了一个命名空间"。
- **本地 `AgentStateStore` 单机限制**：K8s 多副本部署里如果把分布式文件系统和本地 `JsonFileAgentStateStore` 搭配，第一次 build 就抛 `IllegalStateException`，**这是设计如此**——告诉你别把 agent 状态留在某个 pod 的本地磁盘上。
- **`NacosSkillRepository` 不关闭**——会泄露订阅，集群规模大了 Nacos 会喊。Spring 注入用 `@PreDestroy` 或 `destroyMethod="close"`。
- **OSS / NAS 走完 IAM 再上线**——`OssSnapshotSpec` 的 AK/SK 是平台凭证；用 RAM Role + STS 临时凭证更稳。
- **本地 `AgentStateStore` + 沙箱模式仅用于开发**——构建时的 warning 日志是故意的，生产环境别忽略。

## 相关文档

- [Quickstart](../quickstart.md) —— 端到端跑通第一个 `HarnessAgent`
- [Harness 架构](../harness/architecture.md) —— 各能力如何协作
- [上下文与 AgentState](../building-blocks/context.md) —— `AgentState` / `AgentStateStore` / 跨节点恢复
- [上下文压缩](../harness/compaction.md) —— 对话摘要、工具结果卸载、溢出恢复
- [Workspace](../harness/workspace.md) —— 目录布局、两层读、`tools.json`
- [Filesystem](../harness/filesystem.md) —— 三种部署模式、`IsolationScope`
- [Sandbox](../harness/sandbox.md) —— 沙箱细节、五种实现、快照机制
- [技能](../harness/skill.md) —— 四层合成、市场存储源、自学习闭环
- [Middleware](../building-blocks/middleware.md) —— 自定义观测 / 限流 / fallback 中间件
