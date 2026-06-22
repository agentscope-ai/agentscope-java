# Sandbox

[Filesystem](../filesystem.md) explains where an agent's "files and commands" come from. When these operations must be **isolated from the host process**, executed in a **replaceable execution environment** (local Unix, Docker, etc.), and able to **restore the same workspace state** across multiple `call`s, use the **sandbox mode** described here (`filesystem(SandboxFilesystemSpec)`).

## 1. What Sandbox Solves

- **Execution boundary**: the model operates on files and commands through the same `AbstractFilesystem` / `ShellExecuteTool` interfaces, but the **actual IO and processes** happen in an isolated environment managed by the sandbox client — suitable for scenarios where user input cannot be fully trusted, or where decoupling from the production host is required.
- **Recoverable work unit**: unlike "single HTTP requests", multi-turn `call`s should be able to continue in the same logical workspace. `SandboxManager` **persists sandbox-side state** after each `call` ends (via `SandboxStateStore`), and looks it up by `IsolationScope` + `sessionId`/`userId` keys on the next `acquire`.
- **Relationship to the Harness workspace**: the host still has a `WorkspaceManager` root directory; what the sandbox sees is defined by `WorkspaceSpec` and **workspace projection** mechanisms (syncing/mounting certain host paths into the sandbox at startup).

## 2. Assembly in Harness

When sandbox mode is enabled, `HarnessAgent.Builder` will:

1. Call **`SandboxFilesystemSpec#toSandboxContext(hostWorkspaceRoot)`** to get a **`SandboxContext`** (containing `SandboxClient`, isolation scope, snapshot spec, `WorkspaceSpec`, etc.), while packing host-side paths to project into the sandbox (`AGENTS.md`, `skills/`, `subagents/`, `knowledge/`) into a `WorkspaceProjectionEntry` (see [§7 Workspace Projection](#7-workspace-projection-and-skills-sync)).
2. Use **`SandboxBackedFilesystem`** as the agent's `AbstractFilesystem` implementation (transparent to upper layers).
3. Construct **`SandboxManager(client, stateStore, agentId)`**; when no explicit **`SandboxFilesystemSpec#sandboxStateStore`** is configured, defaults to **`SessionSandboxStateStore(effectiveSession, agentId)`**, tying sandbox metadata to the current `Session`.
4. Register **`SandboxLifecycleHook(sandboxManager, filesystemProxy)`** (priority `50`): on every `PreCall` it **acquires → `start()`** (including the 4-branch workspace initialization, see [§6 Snapshot and 4-Branch Recovery](#6-snapshot-and-4-branch-recovery)); on **`PostCall` / `Error`** it **`stop()` (persist snapshot) → persist state → release** and clears the active session on the proxy.

Only when the backend implements **`AbstractSandboxFilesystem`** does `HarnessAgent` register **`ShellExecuteTool`**; in sandbox mode, files and shell commands go through the sandbox — the host is unaffected.

For steps to implement a custom non-Docker isolation backend (`SandboxClient`, `SandboxState`, `SandboxFilesystemSpec`, etc.) and a self-check checklist, see **[§5 Extending Custom Sandbox Execution Environments](#5-extending-custom-sandbox-execution-environments)**.

## 3. Isolation Dimensions (`IsolationScope`)

`IsolationScope` controls **the persistence key for sandbox state** (sandbox mode) and **the namespace prefix for shared storage** (store mode, see [Filesystem mode 1](../filesystem.md)). Both modes share the same enum with consistent semantics.

| Scope | Persistence key source | Behavior when missing | Typical use case |
|-------|----------------------|----------------------|-----------------|
| `SESSION` (default) | `sessionKey.toIdentifier()` | Skip state lookup, create new sandbox | Each session has its own sandbox/memory; conversation isolation |
| `USER` | `RuntimeContext.userId` | Warn and fall back to new | Same user shares workspace or memory across sessions (including distributed) |
| `AGENT` | Agent name (fixed at build time) | — | All users and sessions of a single agent share the same workspace |
| `GLOBAL` | Fixed value `__global__` | — | All agents/users/sessions globally share within one store |

### 3.1 SESSION — Conversation Isolation (Default)

Each conversation has its own independent sandbox with no interference. Suitable for multi-user SaaS where each session's temporary work files and installed dependencies are isolated.

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("code-agent")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .snapshotSpec(new OssSnapshotSpec(...)))
    // isolationScope defaults to SESSION; this line can be omitted
    .filesystem(dockerSpec.isolationScope(IsolationScope.SESSION))
    .build();

// Different sessionId on each call → independent sandbox
agent.call(msgs, RuntimeContext.builder().sessionId("user1-session1").build()).block();
agent.call(msgs, RuntimeContext.builder().sessionId("user1-session2").build()).block();
```

### 3.2 USER — User-level Sharing (Recommended for Distributed Memory)

**Most common distributed scenario**: multiple Pods/processes serving multiple sessions for the same user in parallel, but the user's long-term memory (`MEMORY.md`, `memory/`) must stay consistent across all replicas.

**Sandbox mode + USER**: different sessions (different Pods) write the latest snapshot reference to the same state slot (key = `userId`) after each conversation ends. The next time any replica handles the same user, it restores the same workspace from that snapshot. Note this is **sequential reuse**, not concurrent sharing: concurrent requests each get independent containers, but both update the same state slot on `stop()`, with last-write winning. For `AGENT` / `GLOBAL` scopes requiring strong mutual exclusion, see [§9 Concurrency Control](#9-concurrency-control-sandboxexecutionguard).

**Remote mode + USER** (equivalent without sandbox): `RemoteFilesystemSpec` uses `userId` as the KV namespace prefix, so all reads/writes routed to `MEMORY.md`, `memory/`, etc. land under the same store key — enabling memory sharing across distributed replicas without snapshots.

```java
// Sandbox + USER isolation: same user shares snapshots across Pods
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .snapshotSpec(new OssSnapshotSpec(...))
        .isolationScope(IsolationScope.USER))
    .sandboxDistributed(SandboxDistributedOptions.oss(redisSession, ossSnapshotSpec))
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .userId("alice")       // same userId → same state slot → recoverable workspace
    .sessionId("session-xyz")
    .build();
agent.call(msgs, ctx).block();
```

```java
// Remote mode + USER isolation: lightweight distributed memory sharing (no sandbox)
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER))
    .build();
// All replicas with the same userId share MEMORY.md / memory/ directory
```

### 3.3 AGENT — Agent-level Sharing

All users and sessions of the same agent (by name) share the workspace snapshot or storage namespace. Suitable for "public knowledge base" agents: a single global workspace with write order determined by call sequence; suitable for tool-type, read-only, or admin scenarios.

### 3.4 GLOBAL — Global Sharing

The widest sharing scope within a store/workspace instance. Use with caution.

## 4. Custom Sandbox Instances and Lifecycle Management

By default, `SandboxManager` fully manages sandbox create / start / stop / shutdown (**self-managed**). When you need to **reuse an existing container**, **share a sandbox across multiple agents**, or **manage the container lifecycle yourself**, you can hand lifecycle control back to the caller in two ways.

### 4.1 Passing an Existing `Sandbox` Instance (User-Managed, Highest Priority)

On each `call`, bring in an **already-started** `Sandbox` object via `SandboxContext` in `RuntimeContext`:

```java
// Pre-create and start sandbox (container lifecycle managed by caller)
Sandbox mySandbox = dockerClient.create(workspaceSpec, snapshotSpec, options);
mySandbox.start();

// Inject this instance on each call
SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)         // same client used at agent build time
    .externalSandbox(mySandbox)   // ← explicitly tell Manager: this is user-managed
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("my-session")
    .put(SandboxContext.class, callCtx)      // overrides the defaultSandboxContext from build time
    .build();

agent.call(msgs, ctx).block();
// SandboxLifecycleHook will call mySandbox.stop() (persist snapshot)
// but will NOT call mySandbox.shutdown() — container keeps running
```

**Behavior rules** (`SandboxManager.acquire`'s 4-level priority):

| Priority | Condition | Behavior |
|----------|-----------|----------|
| 1 (highest) | `SandboxContext.externalSandbox != null` | Use directly, mark user-managed; `PostCall` only calls `stop()`, not `shutdown()` |
| 2 | `SandboxContext.externalSandboxState != null` | Restore from specified state, self-managed |
| 3 | Persisted state exists in `SandboxStateStore` | Restore by `IsolationScope` key, self-managed |
| 4 (default) | None of the above | Create new sandbox, self-managed |

### 4.2 Passing Serialized State (Precise Snapshot Recovery)

If you already have a `SandboxState` JSON string saved from a previous `call`, you can bypass `SandboxStateStore` automatic lookup and directly specify the state to restore:

```java
// Load previously serialized state (e.g., from a database or request parameters)
String savedStateJson = db.load("checkpoint-2026-04-28");
SandboxState savedState = dockerClient.deserializeState(savedStateJson);

SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)
    .externalSandboxState(savedState)   // ← specify state; SDK handles resume + lifecycle
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .put(SandboxContext.class, callCtx)
    .build();

agent.call(msgs, ctx).block();
```

### 4.3 Sharing a Sandbox Across Multiple Agents

```java
// After main agent finishes a call, pass the sandbox to another agent
Sandbox sharedSandbox = ...;  // already start()ed

agent1.call(msgs1, RuntimeContext.builder()
    .put(SandboxContext.class, SandboxContext.builder().externalSandbox(sharedSandbox).client(client).build())
    .build()).block();

agent2.call(msgs2, RuntimeContext.builder()
    .put(SandboxContext.class, SandboxContext.builder().externalSandbox(sharedSandbox).client(client).build())
    .build()).block();

// Manually shutdown after all agents are done
sharedSandbox.shutdown();
```

## 5. Extending Custom Sandbox Execution Environments

When your application needs an **isolation backend other than Docker** (custom remote executor, commercial sandbox API, local mock, etc.), **no changes to the Harness source are required**: implement the contract types below and plug them in via **`HarnessAgent.Builder#filesystem(SandboxFilesystemSpec)`**. Overall assembly still follows [§2](#2-assembly-in-harness): `SandboxContext` → `SandboxBackedFilesystem` → `SandboxManager` → `SandboxLifecycleHook`.

### 5.1 Extension Points Overview

| Extension Point | Responsibility | Contract with Framework |
|-----------------|---------------|------------------------|
| **`SandboxClient` + `SandboxClientOptions`** | **create / resume** in isolated environment, **serialize/deserialize** sandbox state | `create` receives `WorkspaceSpec`, `SandboxSnapshotSpec` (snapshot and "where to run" are **orthogonal**, see [§6](#6-snapshot-and-4-branch-recovery)), and your options; `resume` / `deserializeState` must return a `Sandbox` that can cooperate with `SandboxManager`. |
| **`SandboxState` subclass** | Backend-specific fields to persist across `call`s (resource id, image, internal path, etc.) | Base class only has **`@JsonTypeInfo(property = "type")`**, **does not use `@JsonSubTypes` in the parent to hard-code subclasses**. Official Docker registers in **`HarnessSandboxJacksonModule`** as `docker` → `DockerSandboxState`; your subclass must **`registerSubtypes(NamedType)`** (or equivalent `SimpleModule`) on any **`ObjectMapper`** participating in deserialization. |
| **`Sandbox` implementation** | `start` / `stop` / `shutdown`, `exec`, workspace/snapshot cooperation | Typically extends **`AbstractBaseSandbox`** to reuse 4-branch recovery and snapshot hooks; you connect to the real process or API. |
| **`SandboxFilesystemSpec` subclass** | **Connect** client, options, default `WorkspaceSpec` / `SandboxSnapshotSpec` **to Harness** | Implement **`createClient()`**, **`clientOptions()`**, **`snapshotSpec()`**, **`workspaceSpec()`**; can chain **`isolationScope`**, **`sandboxStateStore`**, **`executionGuard`**, projections, etc. (feature parity with **`DockerFilesystemSpec`**). |

### 5.2 Implementing `SandboxClient` and Options

1. Define **`MySandboxClientOptions extends SandboxClientOptions`**: **`getType()`** returns a stable string (e.g. `acme`), consistent with the **`type`** in persistence/config. If you need to **deserialize options from YAML/JSON**, add Jackson registration for `SandboxClientOptions` polymorphism (see framework's **`DockerSandboxClientOptions`** and the base class's **`@JsonTypeInfo`**); skip this if configuring from Java code only.
2. Implement **`SandboxClient<MySandboxClientOptions>`**: in **`create`** construct a `Sandbox` that has **not yet been `start()`ed**; **`serializeState` / `deserializeState`** must be consistent with the `SandboxState` subclass fields; **`delete`** can be a no-op if there are no additional resources.
3. If **`deserializeState`** uses **`objectMapper.readValue(json, SandboxState.class)`**, that **`ObjectMapper` must** register **`HarnessSandboxJacksonModule`** (which includes built-in `NamedType` for `docker` etc.) and your **`NamedType(MySandboxState.class, "acme")`**. **No-arg `new DockerSandboxClient()`** automatically registers **`HarnessSandboxJacksonModule`**; **`new DockerSandboxClient(customMapper)`** requires manually **`registerModule(new HarnessSandboxJacksonModule())`** and **`registerSubtypes`**, otherwise reading back persisted state will fail.

### 5.3 `SandboxState` and Jackson

- **Design intent**: the subtype table is provided at runtime via Module / `registerSubtypes`, making it easy for the same application or downstream jars to **extend state** without modifying **`SandboxState.java`**.
- **This repo**: in **`io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule`**, add **`registerSubtypes(new NamedType(XxxSandboxState.class, "xxx"))`** for new official stores.
- **Application-private subclasses**: call **`mapper.registerSubtypes(new NamedType(MySandboxState.class, "acme"))`** on any **`ObjectMapper`** that holds sandbox JSON, and ensure **`SandboxManager` / `SandboxStateStore`** paths use **the same** mapper configuration as the **`SandboxClient`**.

### 5.4 Implementing `SandboxFilesystemSpec`

- **`createClient()`**: return your **`SandboxClient`** (or create it via **`options.createClient()`**, same pattern as **`DockerFilesystemSpec`**).
- **`clientOptions()`**: return the mutable configuration object.
- **`snapshotSpec()`** / **`workspaceSpec()`**: can provide default **`NoopSnapshotSpec`** and **`new WorkspaceSpec()`**; callers can still override with **`SandboxFilesystemSpec#snapshotSpec(...)`** before building the agent.

**Reference implementation**: **`InMemorySandboxFilesystemSpec`** in the repo (`agentscope-harness` test support, `harness-example-sandbox` example project) simulates a sandbox with a temp directory, requires no Docker, and is a good minimal skeleton to copy and modify.

### 5.5 Enabling

Same as Docker, pass your spec to **`HarnessAgent.builder()`**:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model(model)
    .filesystem(new MySandboxFilesystemSpec()
        .isolationScope(IsolationScope.SESSION))
    .build();
```

When distributed **`Session`** or OSS/Redis snapshots are needed, continue configuring **`sandboxDistributed(...)`** as needed (see [§10](#10-distributed-operation-and-sandboxdistributed)); **snapshot spec and isolated execution backend are chosen independently** on **`SandboxFilesystemSpec`**.

### 5.6 Self-Check Checklist

- [ ] JSON written by **`Sandbox#getState()`** can be losslessly read back by **`SandboxClient#deserializeState`**.
- [ ] **`SandboxClientOptions#getType()`** and the **`type`** in **`SandboxState` JSON** do not conflict globally.
- [ ] All **`ObjectMapper`**s that do **`readValue(..., SandboxState.class)`** have the corresponding **`NamedType`** registered (including **`HarnessSandboxJacksonModule`** if Docker-compatible data must be read).
- [ ] If using **`AbstractSandboxFilesystem`**, **`ShellExecuteTool`** and file APIs are routed to your **`Sandbox#exec`** and sandbox-internal path conventions.

### 5.7 Optional Sandbox Backends: Kubernetes / Daytona / E2B (`agentscope-harness` subpackages)

These three remote sandbox implementations are in **`agentscope-harness`**'s **`io.agentscope.harness.agent.sandbox.impl.*`** subpackages (alongside **`io.agentscope.harness.agent.sandbox.impl.docker`**), available within the same artifact; dependencies like **fabric8** and **protobuf-java** are declared in **`agentscope-harness`**'s `pom.xml`.

| Java Package | Description |
|--------------|-------------|
| **`io.agentscope.harness.agent.sandbox.impl.kubernetes`** | Uses Pod as remote host: fabric8 Exec + container-side `tar`; **`KubernetesFilesystemSpec`** / **`KubernetesSandboxClient`** / **`KubernetesHarnessSandboxJacksonModule`** (`NamedType`: `kubernetes`). Supports top-level **`BindMountEntry`** (node **HostPath** + `volumeMount`), see [§5.8](#58-workspace-bind-mount-bindmountentry). |
| **`io.agentscope.harness.agent.sandbox.impl.daytona`** | Daytona HTTP API: **`DaytonaFilesystemSpec`**, **`DaytonaHarnessSandboxJacksonModule`** (`daytona`), timeouts with limited retries. Does **not** apply host bind mounts; **WARN** at startup if spec contains `bind_mount`. |
| **`io.agentscope.harness.agent.sandbox.impl.e2b`** | E2B: `https://api.e2b.app` lifecycle + envd **`process.Process/Start`** (Connect+protobuf); **`E2bFilesystemSpec`**, **`E2bHarnessSandboxJacksonModule`** (`e2b`); **`E2bPersistenceMode#TAR`** and **`NATIVE_SNAPSHOT`** (platform snapshot API + **`E2bSnapshotRefs`** magic prefix). Does **not** apply host bind mounts; **WARN** at startup if spec contains `bind_mount`; TAR snapshots still add **`tar --exclude`** for bind paths (consistent with Docker/K8s). |

**Jackson assembly**: register at least **`HarnessSandboxJacksonModule`** on any `ObjectMapper` holding sandbox state JSON, then register each backend's `SimpleModule` as needed (e.g., **`new KubernetesHarnessSandboxJacksonModule()`**), consistent with [§5.3](#53-sandboxstate-and-jackson). **Do not** add **`@JsonSubTypes`** on core **`SandboxClientOptions`** for optional stores — this would cause harness to reverse-depend on optional modules creating Maven cycles; optional stores are wired on the application side via their own **`FilesystemSpec`** + **`SandboxClientOptions` subclass**.

**Dependency coordinates**: depend only on **`agentscope-harness`** (or harness coordinates managed by **`agentscope`** / BOM); **`agentscope-all`** includes these implementation classes alongside harness.

### 5.8 Workspace Bind Mount (`BindMountEntry`)

Place **`io.agentscope.harness.agent.sandbox.layout.BindMountEntry`** (Jackson polymorphic name **`bind_mount`**) in **`WorkspaceSpec#getEntries()`**: the **key (map key)** is the **relative mount point** under workspace root (POSIX style, e.g. `data` → `{root}/data`), **`hostPath`** is the absolute path on the host (Docker machine / K8s node), **`readOnly`** controls whether read-only.

| Backend | Behavior |
|---------|----------|
| **Docker** | Adds **`-v host:container:rw|ro`** to `docker run`; `WorkspaceSpecApplier` does not copy the path contents into the container. |
| **Kubernetes** | Adds a **HostPath `Volume`** and **`volumeMount`** for each top-level bind (path must exist or be creatable on the scheduled node; evaluate security risks in production). |
| **Daytona / E2B** | Cannot mount your host directory to a remote cloud sandbox; **WARN** at startup if spec contains bind mounts; entries **do not take effect**. |

**Snapshots and `tar`**: when persisting workspace, the framework appends arguments like **`tar --exclude=./`** plus the entry relative path to avoid including external directories under mount points in the archive (aligned with the Python reference implementation). If you want a path **not in snapshots** but still written by the applier as initial content, use an **`ephemeral`** regular file/directory entry rather than a bind mount.

**Security**: when `hostPath` comes from configuration or upstream input, restrict it to trusted directories; bind mounts are equivalent to letting container-side processes directly access the host path.

## 6. Snapshot and 4-Branch Recovery

`Sandbox.start()` decides how to initialize the workspace via **4 branches**, ensuring correct recovery under all combinations of "whether the container is still available" and "whether a snapshot is available":

```
Branch A: workspaceRootReady=true  &  directory still exists in container   → only re-apply ephemeral entries (fastest, warm start)
Branch B: workspaceRootReady=true  &  directory lost in container            → restore from snapshot + re-apply ephemeral entries
Branch C: workspaceRootReady=false &  snapshot available                     → restore from snapshot + re-apply all entries
Branch D: workspaceRootReady=false &  no snapshot available                  → full initialization from WorkspaceSpec (cold start)
```

When `Sandbox.stop()` executes, if `SandboxSnapshotSpec` has persistence enabled, the workspace is tarred and stored in the snapshot backend (OSS, Redis, local file, etc.), and `workspaceRootReady` is set to true. This tar is the **archive** used by Branch B/C for the next recovery.

**`WorkspaceEntry.ephemeral` flag**: each entry in `WorkspaceSpec` can be marked as ephemeral (re-written on every start) or non-ephemeral (saved with snapshot, only written on cold start). For host-side files that may be updated at any time like `skills/` and `AGENTS.md`, use `WorkspaceProjectionEntry` (next section) rather than the ephemeral flag.

**Available snapshot spec types**:

| Spec | Storage Location |
|------|----------------|
| `NoopSnapshotSpec` (default) | No persistence; cold start from WorkspaceSpec after container rebuild |
| `LocalSnapshotSpec` | Host local file (suitable for single-machine long-running) |
| `OssSnapshotSpec` | OSS / S3-compatible storage (suitable for multi-replica) |
| `RedisSnapshotSpec` | Redis (suitable for low-latency, small workspaces) |

## 7. Workspace Projection and Skills Sync

**Workspace projection** (`WorkspaceProjectionEntry`) is the mechanism by which Harness syncs specific directories/files from the host workspace into the sandbox **at every sandbox startup** — the foundation for Skills and other capabilities running inside the sandbox.

### 7.1 Projection Scope

When `SandboxFilesystemSpec` builds `SandboxContext`, it defaults to packing the following host paths into the projection:

```
AGENTS.md       ← agent identity and instructions
skills/         ← directory for all Skills in SkillBox (includes SKILL.md and script files)
subagents/      ← subagent spec files
knowledge/      ← domain knowledge files
```

You can customize which root paths to project via `SandboxFilesystemSpec#workspaceProjectionRoots(List<String>)`, or completely disable projection via `workspaceProjectionEnabled(false)`.

### 7.2 How Projection Works

`WorkspaceProjectionApplier` runs at the end of `Sandbox.start()`:

1. Iterates all `WorkspaceProjectionEntry`s, collects the host-side file set, sorts by path, and computes a **SHA-256 content hash**.
2. Packs these files into a tar, and decompresses them to the corresponding paths inside the sandbox workspace via `Sandbox.hydrateWorkspace(archive)`.
3. Stores the current hash in `SandboxState.workspaceProjectionHash`; on the next startup, if the hash is unchanged, **skips the projection** (avoiding redundant transfers).

This means: when `skills/` content on the host is updated, the hash changes on the next sandbox start, and new files are automatically synced in; changes to skill files inside the sandbox are not synced back to the host.

### 7.3 How Skills Execute Inside the Sandbox

Harness's `SkillBox` mechanism injects the instructions from `workspace/skills/<skill-name>/SKILL.md` into the agent's system prompt; after the model understands "this skill is needed", it executes scripts or commands in the skill directory via `ShellExecuteTool`. In sandbox mode, all of this happens inside the sandbox:

```
host workspace/skills/pytest/
│── SKILL.md          # description: how to run pytest
└── run_tests.sh      # actual script

         ▼ projection (at every startup)

sandbox /workspace/skills/pytest/
│── SKILL.md
└── run_tests.sh

agent thinks and calls shell_execute:
  "bash /workspace/skills/pytest/run_tests.sh tests/"
          ↓
   ExecResult(exitCode=0, stdout="5 passed")
```

**Benefit**: scripts run in the isolated container; pip install, apt-get, rm -rf and other operations only affect the sandbox workspace — the host is unaffected. After the sandbox is snapshotted, installed dependencies are archived along with the workspace, so the next restore can use them directly (Branch A/B/C) without reinstalling.

### 7.4 State Persistence of Shell Commands and Scripts

`ShellExecuteTool` calls `AbstractSandboxFilesystem.execute(cmd, timeout)` → `Sandbox.exec(cmd, timeout)`, executing commands in the sandbox. All filesystem changes from commands (new files, installed packages, written logs, etc.) are retained inside the sandbox's overlay/container. When `stop()` runs, these states are persisted with the tar snapshot and restored on the next `start()`.

Therefore, **state is fully preserved across `call`s**:

```
call 1: shell_execute("pip install pandas")   → pandas installed in sandbox
call 2: shell_execute("python analyze.py")    → directly available, no reinstall
call 3: shell_execute("cat results.csv")      → reads file produced in call 2
```

## 8. State: `SandboxStateStore` and `Session`

- **`SandboxStateStore`**: abstracts persistence of "sandbox metadata (sessionId + snapshot reference) bound to an isolation key". Easy to replace with a custom implementation; configured on **`SandboxFilesystemSpec#sandboxStateStore`** (defaults when not set).
- **Default `SessionSandboxStateStore`**: depends on the `Session` selected at build time (the **session abstraction** shared with `SessionPersistenceHook` etc.; if you use a distributed `Session` like Redis, sandbox metadata becomes visible across processes).
- **`WorkspaceSession`** remains responsible for **per-session configuration under the workspace layout**; **do not** conflate `WorkspaceSession`'s JSON with "sandbox state JSON" — the sandbox resume data source of truth is **`SandboxStateStore`**.

## 9. Concurrency Control: `SandboxExecutionGuard`

### 9.1 Concurrency Safety Boundaries

Different `IsolationScope`s have different concurrency safety guarantees:

| Scope | Concurrency Safety | Notes |
|-------|--------------------|-------|
| `SESSION` | ✅ Naturally isolated | Each session has its own state slot; requests from different sessions do not interfere |
| `USER` | ⚠️ Needs extra guarantee in multi-replica | Multiple sessions of the same user are reused sequentially; `checkRunning=true` is sufficient for single-instance; recommend `SandboxExecutionGuard` for multi-replica deployment to protect the shared `userId` state slot |
| `AGENT` | ⚠️ Needs extra guarantee | All users/sessions share the same state slot; concurrent writes in multi-replica may cause snapshots and state to overwrite each other |
| `GLOBAL` | ⚠️ Needs extra guarantee | Same as `AGENT`, with wider scope |

**For `SESSION` scope, single-instance `checkRunning=true` (default) is sufficient; `USER`, `AGENT`, and `GLOBAL` in multi-replica deployments should all explicitly configure `SandboxExecutionGuard`** to serialize access to shared slots.

### 9.2 `SandboxExecutionGuard` Interface

```java
@FunctionalInterface
public interface SandboxExecutionGuard {

    // Block until execution rights for the key's slot are obtained; return the held lease
    SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException;

    // Default: no-op; behavior identical to not being configured
    static SandboxExecutionGuard noop() { ... }
}
```

**Lifecycle**: the guard intervenes before `acquire`; `lease.close()` is automatically called by Harness after `release()` completes, covering the entire call window:

```
tryEnter(key)          ← may block here until the previous call completes
  └─ acquire / resume sandbox
  └─ sandbox.start()
  └─ [agent call executing]
  └─ persistState()
  └─ sandbox.stop() + shutdown()
lease.close()          ← release execution rights; next waiter can enter
```

Priority 1 (`externalSandbox`) and Priority 2 (`externalSandboxState`) bypass the guard — user-managed sandboxes handle concurrency control on the caller side.

### 9.3 Built-in Redis Implementation

`RedisSandboxExecutionGuard` uses Redis `SET NX PX` leases for distributed mutual exclusion, **sharing the same `UnifiedJedis` instance as `RedisSnapshotSpec`** without introducing extra dependencies. It encodes `IsolationScope` into the lock key, so `USER`, `AGENT`, and `GLOBAL` each land on their own independent distributed lock:

```java
UnifiedJedis jedis = new JedisPooled("redis-host", 6379);

// Share the same jedis instance with RedisSnapshotSpec
SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
    .leaseTtl(Duration.ofMinutes(30))      // must be greater than worst-case call duration
    .retryInterval(Duration.ofMillis(500)) // polling interval
    .keyPrefix("myapp:sandbox:lock:")      // optional; use for multi-environment isolation
    .build();

HarnessAgent.builder()
    .name("shared-agent")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.AGENT)
        .snapshotSpec(new RedisSnapshotSpec(jedis, null, null))
        .executionGuard(guard))            // ← serialize concurrent access at AGENT dimension
    .sandboxDistributed(SandboxDistributedOptions.builder()
        .session(redisSession)
        .requireDistributed(true)
        .build())
    .build();
```

Redis key format: `<keyPrefix><scope_lower>:<value>`, for example:

- `myapp:sandbox:lock:user:alice`
- `myapp:sandbox:lock:agent:shared-agent`
- `myapp:sandbox:lock:global:__global__`

If you change `isolationScope` to `USER`, the same guard should be reused; the lock key is then bucketed by `userId`, protecting the shared sandbox state slot for the same user across multiple replicas.

**TTL note**: TTL is a safety valve, not a correctness guarantee. If a call exceeds the TTL, Redis automatically releases the lock so the next waiter can enter — this prevents permanent deadlock from process crashes, but cannot guarantee the state safety of the timed-out call itself. Set `leaseTtl` to a reasonable upper bound of actual call duration (including LLM latency and retries).

### 9.4 Custom Implementation Reference

`SandboxExecutionGuard` is a `@FunctionalInterface`; any lock backend can be connected:

```java
// Example: JVM in-memory Semaphore (single-process multi-thread scenario)
Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

SandboxExecutionGuard jvmGuard = key -> {
    Semaphore sem = semaphores.computeIfAbsent(key.toString(), k -> new Semaphore(1));
    sem.acquire();           // block until available, respects InterruptedException
    return sem::release;     // SandboxLease: release semaphore
};
```

Implementation contract:

1. `tryEnter` blocks until execution rights are obtained or the thread is interrupted (throws `InterruptedException`)
2. `lease.close()` must be idempotent and must not throw exceptions (failures can only be internally logged)
3. Implementation must be thread-safe

## 10. Distributed Operation and `sandboxDistributed`

When multi-replica or stateless workers need to share **the sandbox recovery capability of the same logical session**, you need:

- A **distributed `Session`** (e.g., `RedisSession`), not just the default `WorkspaceSession` file store; and
- A non-no-op **`SandboxSnapshotSpec`** (archives the workspace for re-fetching), which passes the "must be distributed" validation.

`HarnessAgent.Builder#sandboxDistributed(SandboxDistributedOptions)` can uniformly configure:

- Override **`snapshotSpec`** (if provided); **`IsolationScope` is only configured on `SandboxFilesystemSpec`**, not repeated here;
- **Explicitly specify** the `Session` used for the sandbox (if different from the main `session`) in the options;
- Use `SandboxDistributedOptions#oss` / `#redis` etc. helper constructors for common combinations (see class JavaDoc).

If `requireDistributed` is true but the current `effectiveSession` is still `WorkspaceSession` or the snapshot is no-op, the build **fails fast**.

## 11. Choosing Between the Three Filesystem Modes

Sandbox is one of three **declarative** configuration options. For a full comparison see [Filesystem](../filesystem.md#three-declarative-modes); here are the key decision points:

| You primarily need | Recommended mode |
|-------------------|-----------------|
| Multi-instance sharing of `MEMORY.md`, session logs, etc. to KV, **without** running shell on host | `RemoteFilesystemSpec` (see [Filesystem — Mode 1](../filesystem.md)) |
| Single-process/local, trusted shell, **without** a separate sandbox | `LocalFilesystemSpec` or default local + shell (see [Filesystem — Mode 3](../filesystem.md)) |
| **Isolated execution**, commands and files in sandbox, **long-session recovery**, optional **snapshots + cluster** | **`SandboxFilesystemSpec` (this page) + optional `sandboxDistributed`** |

## 12. Subagents

When `SubagentsHook` is enabled, if the parent agent is built in sandbox mode, **subagents' filesystem reuses** the same `SandboxBackedFilesystem` session-binding strategy (per current implementation, facilitating environment sharing within the same orchestration tree). Subagents remain independent `ReActAgent` instances; the isolation boundary is consistent with the parent agent's sandbox spec.

## 13. Related Pages

- [Filesystem](../filesystem.md) — class hierarchy, three modes, `abstractFilesystem` escape hatch
- [Tool](../tool.md) — `FilesystemTool`, `ShellExecuteTool` parameters
- [Session](../session.md) — `Session` and `WorkspaceSession`
- [Architecture](../architecture.md) — hook collaboration and lifecycle
