---
title: "Going to Production"
description: "From single-node prototype to multi-replica deployment: component selection and configuration for the Agent State Store, Filesystem, Skill, Sandbox, Snapshot, and Observability"
---

> Running a `HarnessAgent` on your laptop is easy. Shipping it to production is another story — replicas must share sessions, users must stay isolated, untrusted code must be sandboxed, and pods must be able to resume mid-conversation after a restart. This page only covers what **changes between single-node and distributed production**: which components must be swapped, what to swap them with, and why the builder throws `IllegalStateException` when you miss something.

**Fastest path to production**: use `DistributedStore` to configure all distributed components at once:

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);
// or MysqlDistributedStore.create(dataSource);
// or OssDistributedStore.create(ossClient, bucket, prefix);

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(...)  // choose your workspace mode
    .build();
```

Mixed stores (e.g. MySQL for state + Redis for sandbox locks) are also supported:

```java
DistributedStore store = DistributedStore.builder()
    .agentStateStore(MysqlDistributedStore.create(ds).agentStateStore())
    .baseStore(MysqlDistributedStore.create(ds).baseStore())
    .sandboxSnapshotSpec(RedisDistributedStore.fromJedis(jedis).sandboxSnapshotSpec())
    .sandboxExecutionGuard(RedisDistributedStore.fromJedis(jedis).sandboxExecutionGuard())
    .build();
```

## At a glance: single-node defaults vs. distributed production

| Dimension | Single-node default (dev / demo) | Distributed production swap |
|-----------|----------------------------------|-----------------------------|
| **One-line config** | not needed | **`.distributedStore(RedisDistributedStore.fromJedis(jedis))`** |
| `AgentStateStore` | `JsonFileAgentStateStore` (local JSON) | auto-wired by `distributedStore` |
| Filesystem | `LocalFilesystemSpec` (no call) | `RemoteFilesystemSpec` or `SandboxFilesystemSpec` (`baseStore` auto-injected from store) |
| Sandbox snapshots | `NoopSnapshotSpec` / `LocalSnapshotSpec` | auto-wired by `distributedStore` |
| Sandbox exec serialization | none needed in-process | auto-wired by `distributedStore` |
| Skill source | `workspace/skills/` | `GitSkillRepository` / `MysqlSkillRepository` / `NacosSkillRepository` |
| Observability | no tracing by default | `OtelTracingMiddleware` + OpenTelemetry SDK |

### DistributedStore capability matrix

| Capability | Redis (`agentscope-extensions-redis`) | OSS (`agentscope-extensions-oss`) | MySQL (`agentscope-extensions-mysql`) |
|------------|:-----:|:---:|:-----:|
| `AgentStateStore` | `RedisAgentStateStore` | `OssAgentStateStore` | `MysqlAgentStateStore` |
| `BaseStore` | `RedisStore` | `OssBaseStore` | `JdbcStore` |
| `SandboxSnapshotSpec` | `RedisSnapshotSpec` | `OssSnapshotSpec` | `JdbcSnapshotSpec` |
| `SandboxExecutionGuard` | `RedisSandboxExecutionGuard` | — (object storage can't do locks) | `JdbcSandboxExecutionGuard` |

Each component solves a different production problem:

- `AgentStateStore`: persists the agent's runtime session state, including conversation history, compaction summaries, permission rules, Plan Mode state, and tool state. This is what lets another replica, or a restarted process, continue the same `(userId, sessionId)`.
- `BaseStore`: provides shared KV-backed workspace storage for `RemoteFilesystemSpec`, carrying paths such as `MEMORY.md`, `memory/`, `skills/`, and `sessions/`. In multi-replica deployments, it lets different pods see the same long-term memory and shared files.
- `SandboxSnapshotSpec`: persists sandbox workspace snapshots. When a sandbox container is destroyed, a pod restarts, or the next request lands on a new node, it restores the previous workspace instead of losing `pip install` output, generated files, or temporary project state.
- `SandboxExecutionGuard`: serializes command execution for the same sandbox slot across nodes. With shared scopes such as `AGENT` or `GLOBAL`, multiple replicas may try to execute against the same sandbox at once; the guard uses Redis/MySQL locking to avoid concurrent workspace writes and sandbox start/stop races.

> OSS does not provide a `SandboxExecutionGuard` — object storage is unsuitable for distributed locking. OSS users who need sandbox concurrency control can mix in a Redis guard via `DistributedStore.builder()`.

**The key validation chain:**
- `filesystem(RemoteFilesystemSpec)` without `stateStore(...)` or `distributedStore(...)` → `build()` throws `IllegalStateException`.
- `filesystem(SandboxFilesystemSpec)` with a local `AgentStateStore` → `build()` logs a **warning**; in production always supply a `distributedStore`.

## 1. State store: put `AgentState` somewhere durable first

> **Recommended**: use `distributedStore(...)` for one-line setup. The detailed table below is for advanced users who need individual control over `AgentStateStore`.

`AgentState` (conversation context, compaction summary, permission rules, Plan Mode state, tool state) only survives across processes through an [`AgentStateStore`](../../integration/session/index.md).

| Implementation | Module | When to use |
|----------------|--------|-------------|
| `InMemoryAgentStateStore` | `agentscope-core` | unit tests; everything dies on process exit |
| `JsonFileAgentStateStore` | `agentscope-core` | single-machine dev; one directory per `(userId, sessionId)`. **HarnessAgent default**, rooted at `~/.agentscope/state/<agentId>/`; **single-machine** |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | **multi-replica production default**; supports Jedis / Lettuce / Redisson (Standalone / Cluster / Sentinel) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | when state must live in a relational store (audit / reporting / joins) |

**Redis with any of the three client adapters** through `RedisAgentStateStore.builder()`:

```java
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import redis.clients.jedis.JedisPooled;

// Jedis Standalone
AgentStateStore stateStore = RedisAgentStateStore.builder()
        .jedisClient(new JedisPooled("redis://localhost:6379"))
        .keyPrefix("myapp:session:")
        .build();

// Lettuce Cluster (better for write-heavy)
// .lettuceClusterClient(RedisClusterClient.create(...))

// Redisson (if you already use Redisson elsewhere)
// .redissonClient(redisson)
```

**Per-tenant isolation.** A bare `sessionId` only covers single-tenant. In production, set both `userId` and `sessionId` on each call's `RuntimeContext` so multi-tenant calls can't cross-read — the store addresses each slot by the `(userId, sessionId)` pair (`RedisAgentStateStore` folds `userId` into the Redis key; `MysqlAgentStateStore` folds it into the primary key). Compose any other dimensions (tenant, agent) into the `sessionId` string yourself:

```java
agent.call(msg, RuntimeContext.builder()
        .userId(tenantId + ":" + userId)
        .sessionId(agentId + ":" + sessionId)
        .build()).block();
```

Full mechanics in [Context & AgentState](../building-blocks/context.md).

## 2. Filesystem mode & `IsolationScope`: deciding "who shares files with whom"

Three modes recap (details in [Filesystem](../harness/filesystem.md)):

| Mode | Config | Shell? | Use it when |
|------|--------|--------|-------------|
| **Local + shell** | `filesystem(new LocalFilesystemSpec()...)` or omit | ✅ host `sh -c` | single process / trusted environment |
| **Shared store** | `filesystem(new RemoteFilesystemSpec(store))` | ❌ (use sandbox if you need shell) | multi-replica / multi-pod sharing long-term memory |
| **Sandbox** | `filesystem(new DockerFilesystemSpec()...)` and four siblings | ✅ inside the sandbox | untrusted code / cross-call recovery / hard user isolation |

**`IsolationScope` is the multi-user isolation key.** Both shared-store and sandbox modes use the same scope to decide how namespaces are bucketed:

| Scope | Meaning | Typical use |
|-------|---------|-------------|
| `SESSION` (sandbox default) | one slot per sessionId | multi-user SaaS, each conversation independent |
| `USER` (Remote default) | same `userId` shares across sessions | one user on multiple devices sharing long-term memory |
| `AGENT` | all users/sessions of the agent share | public-knowledge-base agents |
| `GLOBAL` | one shared slot for everything | use with care |

```java
// distributedStore auto-injects baseStore into RemoteFilesystemSpec
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER)
            .anonymousUserId("_default"))   // fallback when userId is absent
    .build();
```

`anonymousUserId` is a production detail — `RuntimeContext.userId` is often null (system tasks, scheduler triggers, admin operations). Don't fall back to the empty string, or every anonymous caller ends up in one shared bucket.

## 3. Remote-mode `BaseStore` stores: KV choice — and why OSS is the wrong fit

`RemoteFilesystemSpec` sits on top of a `BaseStore` interface. Two built-in implementations:

| Implementation | Dependency | Concurrency safety | Use it when |
|----------------|------------|--------------------|-------------|
| `RedisStore` | `agentscope-extensions-redis` | Lua-based CAS `putIfVersion`, `ZRANGEBYLEX` for prefix search | the default; multi-replica sharing |
| `JdbcStore` | `agentscope-extensions-mysql`; auto-detects MySQL / PostgreSQL / SQLite / H2 dialect | single-statement CAS UPDATE | existing relational infra / need joins |
| `InMemoryStore` | — | — | tests |

```java
// Recommended: one-line configuration via DistributedStore
DistributedStore store = RedisDistributedStore.fromJedis(
        new JedisPooled("redis://prod-redis:6379"));

HarnessAgent agent = HarnessAgent.builder()
        .name("multi-tenant-agent")
        .model(model)
        .workspace(workspace)
        .distributedStore(store)           // auto-wires stateStore + baseStore
        .filesystem(new RemoteFilesystemSpec() // baseStore injected from store
                .isolationScope(IsolationScope.USER)
                .workspaceIndex(WorkspaceIndex.open(workspace)))  // speeds up ls/glob
        .build();

// Or with MySQL:
DistributedStore mysqlStore = MysqlDistributedStore.create(dataSource);
```

### What about OSS / NAS / S3?

**Do not implement a `BaseStore` against OSS** — `MEMORY.md` / `memory/YYYY-MM-DD.md` / `agents/<id>/context/<sid>/` get written several times a second; OSS latency and per-request cost will blow up immediately. The correct division of labour:

| Data shape | Store | Owner |
|------------|---------|-------|
| High-frequency small KV (memory, session snapshots, task records) | Redis / MySQL (`BaseStore`) | `RemoteFilesystemSpec` |
| Large objects (whole sandbox workspace tar, tens of MB) | OSS / S3 | `OssSnapshotSpec` / custom `RemoteSnapshotSpec` |
| Cross-node shared volume (multiple sandbox instances mounting the same dir) | NAS / EFS | `AgentRunFilesystemSpec.nasConfig(...)` (only AgentRun natively supports this) |

### `RemoteFilesystemSpec` routing table

To prevent key collisions across subsystems, the spec slices the workspace into independent namespace segments:

| Workspace path | Namespace segment |
|----------------|-------------------|
| `AGENTS.md` / `MEMORY.md` / `tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |
| Extra: `.addSharedPrefix("prompts/")` | derived automatically |

Each segment is then bucketed by `IsolationScope` (`USER` → `agents/<agentId>/users/<userId>/`). A Redis key ends up looking like `agentscope:store:item:agents\0X\0users\0alice\0memory\0memory/2026-06-02.md`.

### `CompositeFilesystem`: two-layer reads + write-through

`RemoteFilesystemSpec.toFilesystem(...)` actually produces a `CompositeFilesystem`: a base `LocalFilesystem` without shell (fallback for local templates) plus one `OverlayFilesystem` per route (upper = `RemoteFilesystem`, lower = read-only `LocalFilesystem` template).

Effect: **writes always go to Remote; reads check Remote first, fall back to the local template**. That is the "two-layer read architecture" described in [Workspace](../harness/workspace.md) instantiated for Remote mode — the local `<workspace>/AGENTS.md` is a seed (synced via team git), and Remote takes over as soon as it has been written to.

### `WorkspaceIndex`: optional SQLite index

```java
.filesystem(new RemoteFilesystemSpec(store).workspaceIndex(WorkspaceIndex.open(workspace)))
```

Speeds up `ls` / `glob` / `exists` / `grep` under Remote mode — without it every call scans the full KV. `WorkspaceIndex` is a best-effort SQLite file (under `<workspace>/.index/`), failures degrade silently without affecting correctness.

## 4. Skill marketplaces: which `SkillRepository` to pick

Skills compose from low to high priority (details in [Skill](../harness/skill.md)):

| Layer | Source | Configured by | Use it for |
|-------|--------|---------------|------------|
| 1 | Project global | `.projectGlobalSkillsDir(Path)` | personal dev box; `~/.agentscope/skills/` |
| 2 | Marketplace | `.skillRepository(...)` | cross-project sharing |
| 3 | Workspace shared | `workspace/skills/` | project-specific; checked into git |
| 4 | Per-user | `<userId>/skills/` | user-level override |

### Marketplace stores

| Repository | Module | Notes | Best for |
|-----------|--------|-------|----------|
| `GitSkillRepository` | `agentscope-extensions-skill-git-repository` | team git repo; pulls only when HEAD changes; read-only distribution | early stage / small teams; review skill changes via PR |
| `MysqlSkillRepository` | `agentscope-extensions-skill-mysql-repository` | `DataSource`-driven; `writeable(true/false)` toggle; agent can write back | platform-side central governance; multi-team multi-agent |
| `NacosSkillRepository` | `agentscope-extensions-nacos-skill` | online distribution + config-center change subscription; `AutoCloseable` | Aliyun ecosystem; "change once, take effect fleet-wide" |
| `ClasspathSkillRepository` | `agentscope-core` | shipped with the JAR; Spring Boot fat-JAR compatible | hard-bound capabilities baked into the product |

```java
HarnessAgent agent = HarnessAgent.builder()
        // ...
        .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
        .skillRepository(MysqlSkillRepository.builder(dataSource)
                .databaseName("agentscope")
                .skillsTableName("skills")
                .createIfNotExist(true)
                .writeable(false)                  // read-only distribution; recommended for production
                .build())
        .build();
```

`skillRepository(...)` is additive; later registrations win on name collisions.

### Production checklist

- **Prefer `MysqlSkillRepository(writeable=false)` or `NacosSkillRepository`** — platform-side central governance, agents read-only; write-backs go through an admin console + review flow.
- Don't want the agent to see `workspace/skills/`? `.disableDefaultWorkspaceSkills()`.
- When `enableSkillManageTool` lets the agent draft new skills, **always** pair it with `enableSkillPromotionGate(...)`; never `autoPromote=true` in production.
- `NacosSkillRepository` is `AutoCloseable` — close it from Spring `@PreDestroy` or a `try-with-resources`, otherwise subscriptions leak.

## 5. When you need shell: pick a Sandbox + mandatory Snapshot

When you must use a sandbox:

- the model might run untrusted code (Python / shell / `npm install` / compilation)
- you need to recover the **entire working directory** across calls (`node_modules`, generated files, post-`pip install` environment)
- you need hard user isolation (no peeking into another user's processes)

### Five sandbox stores

| Spec | Module | Use it for |
|------|--------|------------|
| `DockerFilesystemSpec` | `io.agentscope.harness.agent.sandbox.impl.docker` | single-machine / local cluster; container from an image; most familiar |
| `KubernetesFilesystemSpec` | `...impl.kubernetes` | already running K8s; pods / Jobs |
| `DaytonaFilesystemSpec` | `...impl.daytona` | Daytona (dev-env-as-a-service) |
| `E2bFilesystemSpec` | `...impl.e2b` | E2B cloud sandboxes; fastest to ship, no self-managed infra |
| `AgentRunFilesystemSpec` | `...impl.agentrun` | **Aliyun AgentRun**; native NAS / OSS mounts; enterprise-grade |

```java
.filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION))
```

### Snapshots are the sandbox's distributed lifeline

Sandboxes are ephemeral by default — the next `call()` may land on a different node in a fresh container, losing every `pip install` and generated file. `SandboxSnapshotSpec` archives the workspace as tar so the next `call()` hydrates it back into a new container.

| Spec | Store | Module | When to use |
|------|-------|--------|-------------|
| `NoopSnapshotSpec` | — | `agentscope-harness` | not for production; sandbox cold-starts every time the container is lost |
| `LocalSnapshotSpec(Path)` | local directory `tar` files | `agentscope-harness` | single-node debugging |
| `OssSnapshotSpec` | Alibaba Cloud OSS | `agentscope-extensions-oss` | **large objects first choice**; natural fit for object storage |
| `RedisSnapshotSpec` | Redis | `agentscope-extensions-redis` | small workspaces + short TTL (watch Redis memory cost) |
| `JdbcSnapshotSpec` | MySQL / JDBC BLOB | `agentscope-extensions-mysql` | existing relational DB, no extra middleware |
| Custom `RemoteSnapshotClient` → `RemoteSnapshotSpec` | S3 / GCS / MinIO | — | anything not in the built-in list |

```java
DistributedStore redisStore = RedisDistributedStore.fromJedis(jedis);
DistributedStore ossStore = OssDistributedStore.create(
        ossClient,
        "agentscope-sandbox-snapshots",
        "prod/");                         // key prefix for environment isolation

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

With `distributedStore(...)`, the snapshot spec and execution guard are auto-injected — no manual configuration needed. To customize the OSS bucket or prefix, prefer configuring `OssDistributedStore` when you create it; only set `SandboxSnapshotSpec` explicitly on `SandboxFilesystemSpec` when you need a fully custom snapshot implementation.

### Sandbox exec serialization: `SandboxExecutionGuard`

Under `SESSION` / `USER` scope, buckets are already partitioned by session/user and concurrent `exec`s don't collide. Under `AGENT` / `GLOBAL` scope with multiple replicas, N nodes can race to `exec` on the same sandbox slot. `distributedStore(...)` auto-injects the appropriate execution guard:

| Implementation | Module | Mechanism |
|---------------|--------|-----------|
| `RedisSandboxExecutionGuard` | `agentscope-extensions-redis` | Redis `SET NX PX` lease |
| `JdbcSandboxExecutionGuard` | `agentscope-extensions-mysql` | MySQL `GET_LOCK()` / `RELEASE_LOCK()` |

The recommended path is still to inject the guard through `DistributedStore`:

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent.builder()
        .distributedStore(store)
        .filesystem(new DockerFilesystemSpec()
                .image("ubuntu:24.04")
                .isolationScope(IsolationScope.GLOBAL))
        .build();
```

Only override the guard explicitly when you need custom lock parameters, such as a lease TTL:

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

You can also implement `SandboxExecutionGuard` yourself to plug in Zookeeper, etcd, or any other lock mechanism.

### Workspace projection: pushing seed files into the sandbox

`SandboxFilesystemSpec` projects `AGENTS.md, skills, subagents, knowledge, .skills-cache` (five roots) into the sandbox at start time by hydrating a content-hashed tar archive (incremental rewrites). Tweak it:

```java
.filesystem(new DockerFilesystemSpec()
        .image("...")
        .workspaceProjectionRoots(List.of("AGENTS.md", "skills", "knowledge"))   // drop subagents/.skills-cache
        // .workspaceProjectionEnabled(false)   // fully disable
)
```

### AgentRun-specific: NAS / OSS mounts

`AgentRunFilesystemSpec` is the only sandbox filesystem that natively supports **multiple sandbox instances mounting the same directory** (via NAS). When the business case is "one user sees the same workspace across different sessions", AgentRun + NAS is more efficient than re-hydrating snapshots every time:

```java
.filesystem(new AgentRunFilesystemSpec()
        .apiKey(System.getenv("AGENTRUN_API_KEY"))
        .accountId(System.getenv("ALI_ACCOUNT_ID"))
        .region("cn-hangzhou")
        .templateName("python-3.12")
        .nasConfig(new AgentRunNasMountConfig().fileSystemId("...").mountTargetDomain("...").mountDir("/workspace"))
        .addOssMount(new AgentRunOssMountConfig().bucketName("data").mountDir("/mnt/oss")))
```

Full fields in the `AgentRunNasMountConfig` / `AgentRunOssMountConfig` source.

## 6. Multi-replica deployment checklist (combined)

Pulling the single-component picks above into one table:

| Concern | Recommended combo |
|---------|-------------------|
| Sessions / `AgentState` | `RedisDistributedStore` or a mixed `DistributedStore` injecting `AgentStateStore`; `(userId, sessionId)` carries tenant/user/agent dimensions |
| Workspace files | `BaseStore` injected by `distributedStore(...)` + `RemoteFilesystemSpec` + `WorkspaceIndex` + `IsolationScope.USER` |
| Large objects / snapshots | Use `OssDistributedStore.sandboxSnapshotSpec()` in a mixed `DistributedStore` (do not write large snapshots to Redis) |
| Cross-node sandbox sharing | AgentRun + NAS mount, or self-managed K8s + `SandboxExecutionGuard` injected by `distributedStore(...)` |
| Skill governance | `MysqlSkillRepository(writeable=false)` or `NacosSkillRepository`; disable agent-side autoPromote |
| Subagent task records | automatic via `WorkspaceTaskRepository` over Remote / Sandbox; no extra config |
| Exposed subagents (user talks to a subagent directly) | registry auto-wired by `distributedStore` — the `subagentId` resolves and the subagent recovers on any replica / after restart; route a `subagentId`'s messages back to the same node (sticky) so recovery is only the failover path. For `GatewayBootstrap`, pass `.distributedStore(...)` |
| Graceful shutdown | `GracefulShutdownManager` (auto-registers JVM hook); handle SIGTERM; tune in-flight wait via `setConfig(...)` |
| Observability | `OtelTracingMiddleware` + OpenTelemetry SDK + OTLP exporter |
| Rate limiting | custom `MiddlewareBase` (onModelCall); see [Middleware — Rate-limit middleware](../building-blocks/middleware.md#rate-limit-middleware) |

## 7. A complete production builder template

The agent is stateless between calls — a singleton handles concurrent requests. Each `call()` locates state via `RuntimeContext`'s `(userId, sessionId)`, fully isolated.

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

// --- Dependencies (create once at startup) ---
Path workspace = Paths.get("/var/agentscope/workspace");
JedisPooled jedis = new JedisPooled(System.getenv("REDIS_URI"));
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

// --- Singleton agent (created once at startup) ---
HarnessAgent agent = HarnessAgent.builder()
        .name("coding-assistant")
        .model("dashscope:qwen-plus")
        .workspace(workspace)
        .distributedStore(store)  // auto-wires stateStore + snapshotSpec + executionGuard
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

At call time, pass `RuntimeContext` to identify the user/session. Different sessions run concurrently on the same agent instance:

```java
// In your HTTP handler
agent.call(msg, RuntimeContext.builder()
        .userId(httpRequest.tenantUserId())
        .sessionId(httpRequest.sessionId())
        .build()).block();
```

## 8. Common pitfalls

- **Forgetting to pass `RuntimeContext`** — without a `sessionId`, all requests share the `defaultSessionId` state, causing cross-talk. In multi-user scenarios, **always pass `RuntimeContext.builder().userId(...).sessionId(...).build()` to every `call()`** to ensure state isolation. See [Agent — Multi-user Concurrency](../building-blocks/agent.md#multi-user--multi-session-concurrency).
- **`java.nio.Files` for workspace writes** — under sandbox / Remote mode this lands in the wrong place. Always go through `agent.getWorkspaceManager()`. **Exception**: builder-time seed files (`initWorkspaceIfAbsent`-style code) — no runtime context yet, `java.nio.Files` is correct because you're seeding the local template.
- **`tools.json`'s `allow` filters built-in tools too** — when whitelisting, keep `read_file` / `memory_search` / `agent_spawn` and friends in the list, or every built-in gets stripped.
- **`IsolationScope` changes do not migrate existing data** — pin it before launch. Changing it post-launch is equivalent to switching to a new namespace.
- **Local `AgentStateStore` single-machine constraint**: a K8s multi-replica build that pairs a distributed filesystem with a local `JsonFileAgentStateStore` throws `IllegalStateException` on the very first `build()`. **This is intentional** — you can't park agent state on one pod's local disk.
- **`NacosSkillRepository` not closed** — subscriptions leak; at fleet scale Nacos complains. Use Spring `@PreDestroy` or `destroyMethod="close"`.
- **OSS / NAS without IAM** — `OssSnapshotSpec` takes platform AK/SK; RAM Role + STS temporary credentials is more robust.
- **Local `AgentStateStore` with sandbox mode is dev-only** — the build-time warning is intentional; don't ignore it in production.

## Related pages

- [Quickstart](../quickstart.md) — end-to-end first `HarnessAgent`
- [Harness Architecture](../harness/architecture.md) — how capabilities cooperate
- [Context & AgentState](../building-blocks/context.md) — `AgentState` / `AgentStateStore` / cross-node recovery
- [Compaction](../harness/compaction.md) — conversation summarization, tool-result eviction, overflow recovery
- [Workspace](../harness/workspace.md) — directory layout, two-layer reads, `tools.json`
- [Filesystem](../harness/filesystem.md) — three deployment modes, `IsolationScope`
- [Sandbox](../harness/sandbox.md) — sandbox details, five implementations, snapshot mechanics
- [Skill](../harness/skill.md) — four-layer composition, marketplace stores, self-learning loop
- [Middleware](../building-blocks/middleware.md) — custom observability / rate-limit / fallback middleware
