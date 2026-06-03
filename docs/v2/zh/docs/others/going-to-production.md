---
title: "上生产（Going to Production）"
description: "从单机原型到多副本分布式部署：Session / Filesystem / Skill / Sandbox / 快照 / 观测的组件选型与配置清单"
---

> 把 `HarnessAgent` 在你笔记本上跑起来很容易，搬到生产环境是另一回事——多副本要共享会话、要隔离用户、要支持不可信代码执行、要在 pod 重启后接着上次跑。本页**只讲单机 → 分布式生产的差异**：哪些组件必须换、换成什么、为什么 builder 会在你漏配时直接抛 `IllegalStateException`。

源码层面，凡是文档里出现"distributed-friendly"、"cross-replica"、"shared store"字样的组件——`RemoteFilesystemSpec`、`SandboxDistributedOptions`、`RedisSandboxExecutionGuard`、`SandboxSnapshotSpec`、`RedisStore` / `JdbcStore` 等——都是专门为这一页所述场景设计的。

## 一图速览：单机默认 vs 分布式生产

| 维度 | 单机默认（开发 / demo） | 分布式生产替换 |
|------|----------------------|----------------|
| `Session`（`AgentState` 持久化） | `WorkspaceSession`（本地 JSON） | `RedisSession` / `MysqlSession`（或 `RedissonSession`） |
| Filesystem | `LocalFilesystemSpec`（不配 = 此项） | `RemoteFilesystemSpec(BaseStore)` 或 `SandboxFilesystemSpec` |
| `BaseStore`（Remote 后端） | `InMemoryStore`（测试） | `RedisStore` / `JdbcStore`（MySQL / PG / SQLite / H2） |
| Skill 来源 | `workspace/skills/` | `GitSkillRepository` / `MysqlSkillRepository` / `NacosSkillRepository` |
| Sandbox 状态 | `WorkspaceSession` 写本地 | 分布式 Session 写后端 KV |
| Sandbox 快照 | `NoopSnapshotSpec` / `LocalSnapshotSpec` | `OssSnapshotSpec` / `RedisSnapshotSpec` / 自定义 `RemoteSnapshotSpec` |
| 沙箱执行串行化 | 单进程内即可 | `RedisSandboxExecutionGuard`（AGENT/GLOBAL scope 多副本必备） |
| 观测 | 默认无 tracing | `OtelTracingMiddleware` + OpenTelemetry SDK |
| 优雅停机 | `GracefulShutdownManager` 自动注册 JVM hook | 同上 + `setConfig(...)` 调节 inflight 等待 |

**核心校验链路：**
- `filesystem(RemoteFilesystemSpec)` + 没换 `session(...)` → `build()` 抛 `IllegalStateException`，告诉你换 `RedisSession`。
- `filesystem(SandboxFilesystemSpec)` + 没换 `session(...)` → 同上。
- `filesystem(SandboxFilesystemSpec)` + `NoopSnapshotSpec` → 抛 `IllegalStateException`，要求你显式配快照。
- 单节点测试想绕过：`.sandboxDistributed(SandboxDistributedOptions.builder().requireDistributed(false).build())`。

这套校验来自 `HarnessAgentBuilderSupport#validateDistributedSandboxConfig`——刻意 fail-fast，避免"测试环境跑得好、上线后状态丢失"。

## 1. Session 后端：先把 `AgentState` 放对地方

`AgentState`（对话上下文、压缩摘要、权限规则、Plan Mode 状态、tool state）跨进程恢复的唯一通路就是 `Session`。

| 实现 | 模块 | 何时使用 |
|------|------|---------|
| `InMemorySession` | `agentscope-core` | 单元测试；进程退出全部丢 |
| `JsonSession` | `agentscope-core` | 单机开发；按 `SessionKey` 在文件系统分目录 |
| `WorkspaceSession` | `agentscope-harness` | **HarnessAgent 默认**；落到 `<workspace>/agents/<agentId>/context/<sessionId>/`；**单机单租户** |
| `RedisSession` | `agentscope-extensions-session-redis` | **多副本生产首选**；支持 Jedis / Lettuce / Redisson（Standalone / Cluster / Sentinel） |
| `MysqlSession` | `agentscope-extensions-session-mysql` | 需要把会话沉淀进关系型库（审计 / 报表 / 联表查询） |

**Redis 三种 client adapter** 都通过 `RedisSession.builder()` 切换：

```java
import io.agentscope.core.session.redis.RedisSession;
import redis.clients.jedis.JedisPooled;

// Jedis Standalone
Session session = RedisSession.builder()
        .jedisClient(new JedisPooled("redis://localhost:6379"))
        .keyPrefix("myapp:session:")
        .build();

// Lettuce Cluster（写多读少更顺）
// .lettuceClusterClient(RedisClusterClient.create(...))

// Redisson（如果你已经在用 Redisson 做其他事）
// .redissonClient(redisson)
```

**`SessionKey` 的设计要点。** `SimpleSessionKey.of(sessionId)` 只够单租户。生产应自定义实现，把租户 / 用户 / agent id 编进 key 防止跨用户串读——`RedisSession` 把它作为 Redis key 的一部分，`MysqlSession` 用作主键：

```java
class TenantSessionKey implements SessionKey {
    private final String tenantId, userId, agentId, sessionId;
    @Override public String toIdentifier() {
        return tenantId + ":" + userId + ":" + agentId + ":" + sessionId;
    }
}
```

完整细节见 [Harness — Context](../harness/context.md)。

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
.filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER)
        .anonymousUserId("_default"))   // 未传 userId 时的 fallback
```

`anonymousUserId` 是个生产细节——很多场景下 `RuntimeContext.userId` 可能为 null（系统任务、调度器触发、admin 操作），fallback 别用空字符串，否则所有匿名调用会聚到一个共享桶。

## 3. Remote 模式的 BaseStore 后端：KV 选型与"不要把 OSS 当 KV 用"

`RemoteFilesystemSpec` 建在一个 `BaseStore` 接口之上。内置实现两种：

| 实现 | 依赖 | 并发安全 | 适用 |
|------|------|---------|------|
| `RedisStore` | `agentscope-harness`（自带 jedis） | Lua 实现 CAS putIfVersion，`ZRANGEBYLEX` 做 prefix search | 主推；多副本共享 |
| `JdbcStore` | `agentscope-harness`；MySQL / PostgreSQL / SQLite / H2 dialect 自动判别 | 单语句 CAS UPDATE | 已有关系型基础设施 / 需要联表 |
| `InMemoryStore` | — | — | 测试 |

```java
// Redis
BaseStore store = new RedisStore(new JedisPooled("redis://prod-redis:6379"));

// MySQL（同一张 agentscope_store 表，schema 自动建）
BaseStore store = JdbcStore.builder(dataSource)
        .initializeSchema(true)
        .build();

HarnessAgent agent = HarnessAgent.builder()
        .name("multi-tenant-agent")
        .model(model)
        .workspace(workspace)
        .session(RedisSession.builder().jedisClient(jedis).build())
        .filesystem(new RemoteFilesystemSpec(store)
                .isolationScope(IsolationScope.USER)
                .workspaceIndex(WorkspaceIndex.open(workspace)))  // 加速 ls/glob
        .build();
```

### 那 OSS / NAS / S3 怎么放进来？

**不要为了 OSS 写一个 `BaseStore` 实现**——`MEMORY.md` / `memory/YYYY-MM-DD.md` / `agents/<id>/context/<sid>/` 每秒可能写几次，OSS 的延迟与 per-request 成本会立刻失控。正确分工是：

| 数据形态 | 后端 | 谁来管 |
|---------|------|-------|
| 高频小 KV（记忆、会话快照、任务记录） | Redis / MySQL（`BaseStore`） | `RemoteFilesystemSpec` |
| 大对象（沙箱整个 workspace tar archive，几十 MB） | OSS / S3 | `OssSnapshotSpec` / 自定义 `RemoteSnapshotSpec` |
| 跨节点共享卷（多个沙箱实例挂同一份目录） | NAS / EFS | `AgentRunFilesystemSpec.nasConfig(...)`（仅 AgentRun 后端原生支持） |

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

### Marketplace 后端选型

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

### 五种沙箱后端

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

沙箱默认是"瞬时"的——下一次 `call()` 可能起在另一个节点的新容器里，之前 `pip install` / 写入的所有产物全丢。`SandboxSnapshotSpec` 把工作区打成 tar 持久化，下次 `call()` 自动 hydrate 回新容器。

| Spec | 后端 | 何时用 |
|------|------|--------|
| `NoopSnapshotSpec` | — | 不要在生产用；builder 会拦你（除非显式 `requireDistributed(false)`） |
| `LocalSnapshotSpec(Path)` | 本地目录 `tar` 文件 | 单机调试 |
| `OssSnapshotSpec(endpoint, AK, SK, bucket, prefix)` | 阿里云 OSS | **多副本生产首选**；大对象天然适合对象存储 |
| `RedisSnapshotSpec(jedis, prefix, ttlSeconds)` | Redis | 小工作区 + 短 TTL（注意 Redis 内存代价） |
| 自实现 `RemoteSnapshotClient` → `RemoteSnapshotSpec` | S3 / GCS / MinIO / 自有对象存储 | 不在内置后端列表里 |

```java
SandboxSnapshotSpec ossSnap = new OssSnapshotSpec(
        "oss-cn-hangzhou.aliyuncs.com",
        System.getenv("OSS_AK"),
        System.getenv("OSS_SK"),
        "agentscope-sandbox-snapshots",
        "prod/");                         // key 前缀，多环境隔离

HarnessAgent agent = HarnessAgent.builder()
        .name("coding-agent")
        .model(model)
        .workspace(workspace)
        .session(RedisSession.builder().jedisClient(jedis).build())
        .filesystem(new DockerFilesystemSpec()
                .image("python:3.12-slim")
                .isolationScope(IsolationScope.USER))
        .sandboxDistributed(SandboxDistributedOptions.oss(redisSession, ossSnap))
        .build();
```

`SandboxDistributedOptions.oss(session, ossSpec)` / `.redis(session, redisSpec)` 是常用快捷工厂。注意：snapshot 自带 `snapshotId`（默认就是 sessionId），所以同一 user 跨多设备访问只要 sessionId 一致就能拉到同一份 archive。

### 沙箱执行节点串行化：`RedisSandboxExecutionGuard`

`SESSION` / `USER` scope 下天然按 session/user 分桶，并发不会撞。但 `AGENT` / `GLOBAL` scope 多副本部署时，可能同时有 N 个节点要在同一个 sandbox slot 上 `exec`——会撞。这时上 `RedisSandboxExecutionGuard`（基于 Redis 的分布式锁）：

```java
.filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.GLOBAL)
        .executionGuard(new RedisSandboxExecutionGuard(jedis, "agentscope:guard:", Duration.ofSeconds(30))))
```

`RedisSandboxExecutionGuard` 是 `SandboxExecutionGuard` 接口的参考实现；你也可以接 Zookeeper、etcd 等其他锁实现。

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

`AgentRunFilesystemSpec` 是唯一原生支持**多 sandbox 实例共享同一个目录**的后端（通过 NAS mount）；如果业务是"一个用户在不同 session 里看到同一份 workspace"，用 AgentRun 比每次 hydrate snapshot 更高效：

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
| 会话 / `AgentState` | `RedisSession`（Lettuce Cluster 或 Sentinel）+ 自定义多维 `SessionKey` |
| 工作区文件 | `RemoteFilesystemSpec(RedisStore or JdbcStore)` + `WorkspaceIndex` + `IsolationScope.USER` |
| 大对象 / 快照 | `OssSnapshotSpec`（不要写 Redis） |
| 跨节点 sandbox 共享 | AgentRun + NAS mount，或自管 K8s + RedisSandboxExecutionGuard |
| Skill 治理 | `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository`；agent 端禁用 autoPromote |
| 子 agent 任务记录 | 自动用 `WorkspaceTaskRepository`，落 Remote / Sandbox；不需要额外配 |
| 优雅停机 | `GracefulShutdownManager`（默认注册 JVM hook）；接好 SIGTERM；视需要 `setConfig(...)` 调 inflight 等待时间 |
| 可观测 | `OtelTracingMiddleware` + OpenTelemetry SDK + OTLP exporter |
| 限流 | 自写 `MiddlewareBase`（onModelCall）；参考 [Middleware — 限速 middleware](../building-blocks/middleware.md#限速-middleware) |

## 7. 一个完整的生产 builder 模板

把上面所有要点拼到一起：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.redis.RedisSession;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.RedisSandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.OssSnapshotSpec;
import io.agentscope.harness.agent.store.RedisStore;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.core.memory.compaction.CompactionConfig;
import io.agentscope.core.memory.compaction.ToolResultEvictionConfig;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import redis.clients.jedis.JedisPooled;

public class ProductionAgentFactory {

    public HarnessAgent build() {
        var workspace = Paths.get("/var/agentscope/workspace");
        var jedis = new JedisPooled(System.getenv("REDIS_URI"));

        // 1. 分布式 Session
        var session = RedisSession.builder().jedisClient(jedis).build();

        // 2. 选 sandbox + OSS snapshot（要 shell）
        var sandboxSpec = new DockerFilesystemSpec()
                .image("python:3.12-slim")
                .isolationScope(IsolationScope.USER)
                .executionGuard(new RedisSandboxExecutionGuard(
                        jedis, "agentscope:guard:", Duration.ofSeconds(30)));
        var oss = new OssSnapshotSpec(
                "oss-cn-hangzhou.aliyuncs.com",
                System.getenv("OSS_AK"), System.getenv("OSS_SK"),
                "agentscope-sandbox-snapshots", "prod/");

        return HarnessAgent.builder()
                .name("coding-assistant")
                .model("dashscope:qwen-plus")
                .workspace(workspace)

                // session + filesystem 必须一起换
                .session(session)
                .filesystem(sandboxSpec)
                .sandboxDistributed(SandboxDistributedOptions.oss(session, oss))

                // 记忆压缩 + 大结果卸载（生产长会话必备）
                .compaction(CompactionConfig.builder()
                        .triggerMessages(50)
                        .keepMessages(20)
                        .build())
                .toolResultEviction(ToolResultEvictionConfig.defaults())

                // 集中治理的 skill 仓库（只读分发）
                .skillRepository(io.agentscope.core.skill.repository.mysql.MysqlSkillRepository
                        .builder(skillsDataSource())
                        .createIfNotExist(false)
                        .writeable(false)
                        .build())

                // tracing
                .middlewares(List.of(new OtelTracingMiddleware()))
                .build();
    }
}
```

调用时填好 `RuntimeContext` 让所有"按用户/会话分桶"的子系统拿到 key：

```java
agent.call(msg, RuntimeContext.builder()
        .userId(httpRequest.tenantUserId())
        .sessionId(httpRequest.sessionId())
        .build()).block();
```

## 8. 常见坑位

- **`java.nio.Files` 写工作区**——在沙箱 / Remote 模式下落到错的位置。永远走 `agent.getWorkspaceManager()`。**例外**：builder 装配时的种子文件（`initWorkspaceIfAbsent` 之类）那时还没有运行时上下文，用 `java.nio.Files` 是 OK 的。
- **`tools.json` 的 `allow` 会过滤内置工具**——用白名单时务必把 `read_file` / `memory_search` / `agent_spawn` 这些保留下来，否则整套内置工具一起被砍。
- **`IsolationScope` 改了，旧数据不会自动迁移**——上线前定下来，别上线后改。改了等同于"换了一个命名空间"。
- **`WorkspaceSession` 单机限制**：K8s 多副本部署里第一次 build 就抛 `IllegalStateException`，**这是设计如此**——告诉你别把会话状态留在某个 pod 的本地磁盘上。
- **`NacosSkillRepository` 不关闭**——会泄露订阅，集群规模大了 Nacos 会喊。Spring 注入用 `@PreDestroy` 或 `destroyMethod="close"`。
- **OSS / NAS 走完 IAM 再上线**——`OssSnapshotSpec` 的 AK/SK 是平台凭证；用 RAM Role + STS 临时凭证更稳。
- **`SandboxDistributedOptions.requireDistributed(false)` 是个调试开关**——上线前确认它没漏在生产配置里。

## 相关文档

- [Quickstart](../quickstart.md) —— 端到端跑通第一个 `HarnessAgent`
- [Harness 架构](../harness/architecture.md) —— 各能力如何协作
- [Context](../harness/context.md) —— `AgentState` / `Session` / 跨节点恢复
- [Workspace](../harness/workspace.md) —— 目录布局、两层读、`tools.json`
- [Filesystem](../harness/filesystem.md) —— 三种部署模式、`IsolationScope`
- [Sandbox](../harness/sandbox.md) —— 沙箱细节、五种后端、快照机制
- [技能](../harness/skill.md) —— 四层合成、市场后端、自学习闭环
- [Middleware](../building-blocks/middleware.md) —— 自定义观测 / 限流 / fallback 中间件
