---
title: "Sandbox"
description: "Isolated execution + cross-call recovery + multi-replica deployment"
---

> For the three filesystem-mode comparison see [Filesystem](./filesystem). This page focuses on sandbox mode usage.

## What sandbox solves

Confines the agent's **file operations and command execution** to an isolated environment; the host stays untouched. Plus three extra wins:

1. **Execution boundary** — untrusted input, suspicious scripts, `rm -rf`-shaped commands all stay inside the sandbox.
2. **Cross-call recovery** — not just conversation state: `pip install`, `npm install`, generated temp files (the executable environment itself) are snapshotted, so the next `call()` resumes in the same sandbox without reinstalling.
3. **Multi-replica friendly** — when multiple replicas serve the same logical user, sandbox state can share a single slot so any node can resume the same workspace.

## A minimal example

Local Docker, isolated per user:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("code-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04"))
    .build();

agent.call(msg, RuntimeContext.builder()
    .userId("alice")
    .sessionId("conv-1")
    .build()).block();
```

Same `userId` across `call()` → automatically reuses the same sandbox (or restores from snapshot). Different `userId` → separate sandbox. When `userId` is absent, falls back to `sessionId` as the isolation key.

## IsolationScope — who shares a sandbox

All sandbox configuration lives on the `SandboxFilesystemSpec` (e.g. `DockerFilesystemSpec`). The key parameter is `isolationScope`:

| Scope | Sharing | Typical use |
|-------|---------|-------------|
| `USER` (default) | Same `userId`'s sessions share; falls back to `SESSION` when userId is absent | Multi-user SaaS — each user keeps one workspace across conversations |
| `SESSION` | Each sessionId independent | Strict per-conversation isolation |
| `AGENT` | All users / sessions of this agent share | Public-tool-type agent, shared knowledge base |
| `GLOBAL` | One shared slot per store | Use with care |

```java
// Explicit SESSION scope (overrides default USER)
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.SESSION))
```

`SESSION` is naturally concurrency-safe (each session has its own slot). `USER` / `AGENT` / `GLOBAL` in multi-replica deployments should pair with a mutex (see "Concurrency control" below).

**USER-scope fallback:** when `IsolationScope.USER` is active (either explicitly or by default) but `RuntimeContext.userId` is absent, the framework automatically falls back to `SESSION` scope using `sessionId`. This means you don't need to guard against missing userId — the sandbox degrades gracefully.

## Cross-call recovery = snapshots

The sandbox snapshots its workspace at each `call()` end and restores at the next start:

- Container still alive + workspace still there → just continue (fastest)
- Container gone → reboot from snapshot, restore workspace
- No snapshot → full init from `WorkspaceSpec` (cold start)

Where snapshots land is decided by `snapshotSpec`:

| Option | When |
|--------|------|
| `NoopSnapshotSpec` (default) | No persistence; cold start when the container is gone |
| `LocalSnapshotSpec` | Host local file (single-machine long-running) |
| `OssSnapshotSpec` | OSS / S3-compatible (multi-replica) |
| `RedisSnapshotSpec` | Redis (low latency, small workspaces) |
| `JdbcSnapshotSpec` | MySQL / JDBC BLOB (existing relational DB) |

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/")))
```

Host-side workspace files (`AGENTS.md` / `skills/` / `subagents/` / `knowledge/`) are synced into the sandbox at each start, content-hash-gated. So if you edit a script under `skills/`, the next `call()` has the new version inside the sandbox.

## Distributed deployment

When multiple replicas run the same agent and any replica must be able to pick up the same user's conversation, you need:

1. A distributed `AgentStateStore` (e.g. Redis-backed) — passed via `.stateStore(...)` on the builder
2. A non-`Noop` snapshot (OSS / Redis / remote store) — configured directly on the filesystem spec via `.snapshotSpec(...)`
3. An appropriate `IsolationScope` (default `USER` is usually correct)

Everything is configured in one place:

```java
HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .workspace(workspace)
    .stateStore(redisStateStore)                    // distributed state
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .snapshotSpec(ossSnapshotSpec)              // cross-replica snapshot
        .isolationScope(IsolationScope.USER))       // default, can omit
    .build();
```

The framework stores sandbox metadata (container ID, snapshot pointers, workspace-ready flag) in the same `AgentStateStore` that holds agent runtime state. Providing a distributed store automatically enables cross-replica sandbox resume — no extra configuration needed.

If you're using a local `AgentStateStore` (the default `JsonFileAgentStateStore`) with sandbox mode, the framework logs a warning at build time reminding you that sandbox state won't survive JVM restarts and can't be shared across instances.

## Concurrency control (multi-replica)

In `USER` / `AGENT` / `GLOBAL` modes across replicas, two replicas serving the same user concurrently both write to the same slot — last writer wins. If that's not OK, you need a distributed lock.

**Recommended**: use `distributedStore(...)` — snapshot and execution guard are auto-injected:

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent.builder()
    .distributedStore(store)    // auto-wires stateStore + snapshotSpec + executionGuard
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.USER))
    .build();
```

To customize lock parameters, set the guard explicitly on the `SandboxFilesystemSpec`:

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.USER)
    .executionGuard(RedisSandboxExecutionGuard.builder(jedis)
        .leaseTtl(Duration.ofMinutes(30)).build()))
```

Built-in implementations: `RedisSandboxExecutionGuard` (Redis `SET NX PX`), `JdbcSandboxExecutionGuard` (MySQL `GET_LOCK()`). You can also implement `SandboxExecutionGuard` to plug in Zookeeper, etcd, or other lock stores.

## Self-managed sandbox instances (advanced)

By default the framework owns the whole sandbox lifecycle. Three "I'll manage it myself" scenarios:

**1. I already have a running container; I want the agent to use it**

```java
Sandbox mySandbox = dockerClient.create(workspaceSpec, snapshotSpec, options);
mySandbox.start();

SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)
    .externalSandbox(mySandbox)       // framework only stops() at end of call, doesn't shutdown()
    .build();

agent.call(msgs, RuntimeContext.builder()
    .sessionId("my-session")
    .sandboxContext(callCtx)
    .build()).block();

// shut it down yourself when done
mySandbox.shutdown();
```

**2. I have a specific snapshot string; restore to that moment**

```java
SandboxState savedState = dockerClient.deserializeState(savedStateJson);
SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)
    .externalSandboxState(savedState)  // framework restores from this state but owns the lifecycle
    .build();
```

**3. Multiple agents share one sandbox**

Pass the same `externalSandbox` to each agent's `call()`, then `shutdown()` it yourself when done.

## Choosing a sandbox store

| Store | Best for |
|---------|----------|
| **Docker** | Local dev / single machine / trusted shell |
| **Kubernetes** | Self-hosted K8s, node-level bind mounts |
| **Daytona** | Generic managed sandbox HTTP API |
| **E2B** | Generic managed sandbox + native platform snapshots |
| **AgentRun** | Aliyun-managed sandbox (Function Compute FC 3.0); per-instance NAS / OSS auto-mount; mainland-China low latency. Treated as a regular `SandboxFilesystemSpec` — full setup details (templates, RAM permissions, NAS-first config) live in the integration docs |

All stores implement the same interface; agent code, toolkit, and `AGENTS.md` don't change.

## How the workspace maps into the sandbox

Host-side key files under `workspace/` (`AGENTS.md`, `skills/`, `subagents/`, `knowledge/`) are synced into the sandbox at each start, content-hash-gated — unchanged content is skipped.

To bind a host directory into the sandbox (e.g. a code repo), use `BindMountEntry` (only Docker / K8s; managed sandboxes like Daytona / E2B run in the cloud and can't mount your host paths).

File changes inside the sandbox don't sync back to the host — to retrieve sandbox-produced artifacts, have the agent `read_file` them.

## Implementing your own sandbox store

To integrate a non-Docker isolation environment (self-hosted remote executor, commercial sandbox API, local mock, etc.), no Harness source changes needed — implement a few contract interfaces and pass them to `filesystem(...)`. The `InMemorySandbox` family under `agentscope-harness` tests is the minimal skeleton to copy.

## Related pages

- [Filesystem](./filesystem) — three declarative modes compared
- [Workspace](./workspace) — which files under `workspace/` sync into the sandbox
- [Architecture](./architecture) — where sandbox acquire / release sits in the call() timeline
