---
title: "Going to Production"
description: "From single-node prototype to multi-replica deployment: component selection and configuration for the Agent State Store, Filesystem, Skill, Sandbox, Snapshot, and Observability"
---

> Running a `HarnessAgent` on your laptop is easy. Shipping it to production is another story — replicas must share sessions, users must stay isolated, untrusted code must be sandboxed, and pods must be able to resume mid-conversation after a restart. This page only covers what **changes between single-node and distributed production**: which components must be swapped, what to swap them with, and why the builder throws `IllegalStateException` when you miss something.

In the source, any component documented as "distributed-friendly", "cross-replica", or "shared store" — `RemoteFilesystemSpec`, `RedisSandboxExecutionGuard`, `SandboxSnapshotSpec`, `RedisStore` / `JdbcStore`, and so on — is purpose-built for the scenarios on this page.

## At a glance: single-node defaults vs. distributed production

| Dimension | Single-node default (dev / demo) | Distributed production swap |
|-----------|----------------------------------|-----------------------------|
| `AgentStateStore` (`AgentState` persistence) | `JsonFileAgentStateStore` (local JSON) | `RedisAgentStateStore` / `MysqlAgentStateStore` |
| Filesystem | `LocalFilesystemSpec` (no `filesystem(...)` call) | `RemoteFilesystemSpec(BaseStore)` or `SandboxFilesystemSpec` |
| `BaseStore` (Remote backend) | `InMemoryStore` (tests) | `RedisStore` / `JdbcStore` (MySQL / PG / SQLite / H2) |
| Skill source | `workspace/skills/` | `GitSkillRepository` / `MysqlSkillRepository` / `NacosSkillRepository` |
| Sandbox state | local `JsonFileAgentStateStore` on disk | distributed `AgentStateStore`-backed store |
| Sandbox snapshots | `NoopSnapshotSpec` / `LocalSnapshotSpec` | `OssSnapshotSpec` / `RedisSnapshotSpec` / custom `RemoteSnapshotSpec` |
| Sandbox exec serialization | none needed in-process | `RedisSandboxExecutionGuard` (required for AGENT/GLOBAL scope across replicas) |
| Observability | no tracing by default | `OtelTracingMiddleware` + OpenTelemetry SDK |
| Graceful shutdown | `GracefulShutdownManager` auto-registers a JVM hook | same + `setConfig(...)` to tune the in-flight wait |

**The key validation chain:**
- `filesystem(RemoteFilesystemSpec)` without swapping `stateStore(...)` → `build()` throws `IllegalStateException` telling you to provide a distributed `AgentStateStore`.
- `filesystem(SandboxFilesystemSpec)` with a local `AgentStateStore` → `build()` succeeds but logs a **warning** that sandbox state won't survive JVM restarts and can't be shared across instances. This is intentional for dev/test convenience; in production always supply a distributed store.

## 1. State store backend: put `AgentState` somewhere durable first

`AgentState` (conversation context, compaction summary, permission rules, Plan Mode state, tool state) only survives across processes through an [`AgentStateStore`](../../integration/session/index.md).

| Implementation | Module | When to use |
|----------------|--------|-------------|
| `InMemoryAgentStateStore` | `agentscope-core` | unit tests; everything dies on process exit |
| `JsonFileAgentStateStore` | `agentscope-core` | single-machine dev; one directory per `(userId, sessionId)`. **HarnessAgent default**, rooted at `~/.agentscope/state/<agentId>/`; **single-machine** |
| `RedisAgentStateStore` | `agentscope-extensions-session-redis` | **multi-replica production default**; supports Jedis / Lettuce / Redisson (Standalone / Cluster / Sentinel) |
| `MysqlAgentStateStore` | `agentscope-extensions-session-mysql` | when state must live in a relational store (audit / reporting / joins) |

**Redis with any of the three client adapters** through `RedisAgentStateStore.builder()`:

```java
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.redis.RedisAgentStateStore;
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
.filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER)
        .anonymousUserId("_default"))   // fallback when userId is absent
```

`anonymousUserId` is a production detail — `RuntimeContext.userId` is often null (system tasks, scheduler triggers, admin operations). Don't fall back to the empty string, or every anonymous caller ends up in one shared bucket.

## 3. Remote-mode `BaseStore` backends: KV choice — and why OSS is the wrong fit

`RemoteFilesystemSpec` sits on top of a `BaseStore` interface. Two built-in implementations:

| Implementation | Dependency | Concurrency safety | Use it when |
|----------------|------------|--------------------|-------------|
| `RedisStore` | `agentscope-harness` (ships jedis) | Lua-based CAS `putIfVersion`, `ZRANGEBYLEX` for prefix search | the default; multi-replica sharing |
| `JdbcStore` | `agentscope-harness`; auto-detects MySQL / PostgreSQL / SQLite / H2 dialect | single-statement CAS UPDATE | existing relational infra / need joins |
| `InMemoryStore` | — | — | tests |

```java
// Redis
BaseStore store = new RedisStore(new JedisPooled("redis://prod-redis:6379"));

// MySQL (same agentscope_store table; schema bootstrapped automatically)
BaseStore store = JdbcStore.builder(dataSource)
        .initializeSchema(true)
        .build();

HarnessAgent agent = HarnessAgent.builder()
        .name("multi-tenant-agent")
        .model(model)
        .workspace(workspace)
        .stateStore(RedisAgentStateStore.builder().jedisClient(jedis).build())
        .filesystem(new RemoteFilesystemSpec(store)
                .isolationScope(IsolationScope.USER)
                .workspaceIndex(WorkspaceIndex.open(workspace)))  // speeds up ls/glob
        .build();
```

### What about OSS / NAS / S3?

**Do not implement a `BaseStore` against OSS** — `MEMORY.md` / `memory/YYYY-MM-DD.md` / `agents/<id>/context/<sid>/` get written several times a second; OSS latency and per-request cost will blow up immediately. The correct division of labour:

| Data shape | Backend | Owner |
|------------|---------|-------|
| High-frequency small KV (memory, session snapshots, task records) | Redis / MySQL (`BaseStore`) | `RemoteFilesystemSpec` |
| Large objects (whole sandbox workspace tar, tens of MB) | OSS / S3 | `OssSnapshotSpec` / custom `RemoteSnapshotSpec` |
| Cross-node shared volume (multiple sandbox instances mounting the same dir) | NAS / EFS | `AgentRunFilesystemSpec.nasConfig(...)` (only the AgentRun backend natively supports this) |

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

### Marketplace backends

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

### Five sandbox backends

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

| Spec | Backend | When to use |
|------|---------|-------------|
| `NoopSnapshotSpec` | — | not for production; sandbox cold-starts every time the container is lost |
| `LocalSnapshotSpec(Path)` | local directory `tar` files | single-node debugging |
| `OssSnapshotSpec(endpoint, AK, SK, bucket, prefix)` | Alibaba Cloud OSS | **default for multi-replica production**; large objects belong in object storage |
| `RedisSnapshotSpec(jedis, prefix, ttlSeconds)` | Redis | small workspaces + short TTL (watch Redis memory cost) |
| Custom `RemoteSnapshotClient` → `RemoteSnapshotSpec` | S3 / GCS / MinIO / your own object store | anything not in the built-in list |

```java
SandboxSnapshotSpec ossSnap = new OssSnapshotSpec(
        "oss-cn-hangzhou.aliyuncs.com",
        System.getenv("OSS_AK"),
        System.getenv("OSS_SK"),
        "agentscope-sandbox-snapshots",
        "prod/");                         // key prefix for multi-env isolation

HarnessAgent agent = HarnessAgent.builder()
        .name("coding-agent")
        .model(model)
        .workspace(workspace)
        .stateStore(RedisAgentStateStore.builder().jedisClient(jedis).build())
        .filesystem(new DockerFilesystemSpec()
                .image("python:3.12-slim")
                .isolationScope(IsolationScope.USER)
                .snapshotSpec(ossSnap))
        .build();
```

All sandbox configuration — isolation scope, snapshot spec, execution guard — lives directly on the `SandboxFilesystemSpec`. The distributed `AgentStateStore` (via `.stateStore(...)`) is the only builder-level knob. Providing a distributed store automatically enables cross-replica sandbox state resume.

### Sandbox exec serialization: `RedisSandboxExecutionGuard`

Under `SESSION` / `USER` scope, buckets are already partitioned by session/user and concurrent `exec`s don't collide. Under `AGENT` / `GLOBAL` scope with multiple replicas, N nodes can race to `exec` on the same sandbox slot. Add `RedisSandboxExecutionGuard` (a Redis-based distributed lock) in that case:

```java
.filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.GLOBAL)
        .executionGuard(new RedisSandboxExecutionGuard(jedis, "agentscope:guard:", Duration.ofSeconds(30))))
```

`RedisSandboxExecutionGuard` is the reference implementation of the `SandboxExecutionGuard` interface; you can plug in Zookeeper, etcd, or any other lock mechanism.

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

`AgentRunFilesystemSpec` is the only backend that natively supports **multiple sandbox instances mounting the same directory** (via NAS). When the business case is "one user sees the same workspace across different sessions", AgentRun + NAS is more efficient than re-hydrating snapshots every time:

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
| Sessions / `AgentState` | `RedisAgentStateStore` (Lettuce Cluster or Sentinel) + `(userId, sessionId)` carrying tenant/user/agent dimensions |
| Workspace files | `RemoteFilesystemSpec(RedisStore or JdbcStore)` + `WorkspaceIndex` + `IsolationScope.USER` |
| Large objects / snapshots | `OssSnapshotSpec` (do not write to Redis) |
| Cross-node sandbox sharing | AgentRun + NAS mount, or self-managed K8s + `RedisSandboxExecutionGuard` |
| Skill governance | `MysqlSkillRepository(writeable=false)` or `NacosSkillRepository`; disable agent-side autoPromote |
| Subagent task records | automatic via `WorkspaceTaskRepository` over Remote / Sandbox; no extra config |
| Graceful shutdown | `GracefulShutdownManager` (auto-registers JVM hook); handle SIGTERM; tune in-flight wait via `setConfig(...)` |
| Observability | `OtelTracingMiddleware` + OpenTelemetry SDK + OTLP exporter |
| Rate limiting | custom `MiddlewareBase` (onModelCall); see [Middleware — Rate-limit middleware](../building-blocks/middleware.md#rate-limit-middleware) |

## 7. A complete production builder template

The agent is stateless between calls — a singleton handles concurrent requests. Each `call()` locates state via `RuntimeContext`'s `(userId, sessionId)`, fully isolated.

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.redis.RedisAgentStateStore;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.RedisSandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.OssSnapshotSpec;
import io.agentscope.core.memory.compaction.CompactionConfig;
import io.agentscope.core.memory.compaction.ToolResultEvictionConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import redis.clients.jedis.JedisPooled;

// --- Dependencies (create once at startup) ---
Path workspace = Paths.get("/var/agentscope/workspace");
JedisPooled jedis = new JedisPooled(System.getenv("REDIS_URI"));
RedisAgentStateStore stateStore = RedisAgentStateStore.builder().jedisClient(jedis).build();
OssSnapshotSpec oss = new OssSnapshotSpec(
        "oss-cn-hangzhou.aliyuncs.com",
        System.getenv("OSS_AK"), System.getenv("OSS_SK"),
        "agentscope-sandbox-snapshots", "prod/");
DockerFilesystemSpec sandboxSpec = new DockerFilesystemSpec()
        .image("python:3.12-slim")
        .isolationScope(IsolationScope.USER)
        .snapshotSpec(oss)
        .executionGuard(new RedisSandboxExecutionGuard(
                jedis, "agentscope:guard:", Duration.ofSeconds(30)));

// --- Singleton agent (created once at startup) ---
HarnessAgent agent = HarnessAgent.builder()
        .name("coding-assistant")
        .model("dashscope:qwen-plus")
        .workspace(workspace)
        .stateStore(stateStore)
        .filesystem(sandboxSpec)
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
- [Sandbox](../harness/sandbox.md) — sandbox details, five backends, snapshot mechanics
- [Skill](../harness/skill.md) — four-layer composition, marketplace backends, self-learning loop
- [Middleware](../building-blocks/middleware.md) — custom observability / rate-limit / fallback middleware
