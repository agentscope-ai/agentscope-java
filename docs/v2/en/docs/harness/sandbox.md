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

Local Docker, isolated per conversation:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("code-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04"))
    .build();

agent.call(msg, RuntimeContext.builder()
    .sessionId("user-1-conv-1")
    .build()).block();
```

Different `sessionId` → different sandbox; same `sessionId` across `call()` → automatically reuses the same sandbox (or restores from snapshot).

## IsolationScope — who shares a sandbox

The most-tuned setting:

| Scope | Sharing | Typical use |
|-------|---------|-------------|
| `SESSION` (default) | Each sessionId independent | Multi-user SaaS, each conversation runs on its own |
| `USER` | Same `userId`'s sessions share | Same user shares the workspace across conversations (including distributed) |
| `AGENT` | All users / sessions of this agent share | Public-tool-type agent |
| `GLOBAL` | One shared slot per store | Use with care |

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.USER))
```

`SESSION` is naturally concurrency-safe (each session has its own slot). `USER` / `AGENT` / `GLOBAL` in multi-replica deployments should pair with a mutex (see "Concurrency control" below).

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

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/")))
```

Host-side workspace files (`AGENTS.md` / `skills/` / `subagents/` / `knowledge/`) are synced into the sandbox at each start, content-hash-gated. So if you edit a script under `skills/`, the next `call()` has the new version inside the sandbox.

## Distributed deployment

When multiple replicas run the same agent and any replica must be able to pick up the same user's conversation, you need:

1. A distributed `Session` (e.g. a Redis-backed implementation)
2. A non-`Noop` snapshot (OSS / Redis / remote store)
3. An appropriate `IsolationScope` (`USER` / `AGENT` / `GLOBAL`)

To declare these together, use `sandboxDistributed(...)`:

```java
HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.USER))
    .sandboxDistributed(SandboxDistributedOptions.oss(redisSession, ossSnapshotSpec))
    .build();
```

With `requireDistributed=true`, build fails fast if the actual Session / snapshot don't qualify — catching misconfig before deployment.

## Concurrency control (multi-replica)

In `USER` / `AGENT` / `GLOBAL` modes across replicas, two replicas serving the same user concurrently both write to the same slot — last writer wins. If that's not OK, add a distributed lock. Redis-backed implementation built in:

```java
SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
    .leaseTtl(Duration.ofMinutes(30))        // a bit larger than worst-case call duration
    .retryInterval(Duration.ofMillis(500))
    .build();

.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.USER)
    .snapshotSpec(redisSnapshotSpec)
    .executionGuard(guard))
```

The lock key is bucketed by scope automatically (`USER` → by userId, `AGENT` → by agent name).

You can also implement the `SandboxExecutionGuard` interface to plug in other lock backends (DB / Zookeeper / etcd).

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

## Choosing a sandbox backend

| Backend | Best for |
|---------|----------|
| **Docker** | Local dev / single machine / trusted shell |
| **Kubernetes** | Self-hosted K8s, node-level bind mounts |
| **Daytona** | Generic managed sandbox HTTP API |
| **E2B** | Generic managed sandbox + native platform snapshots |
| **AgentRun** | Aliyun-managed sandbox (Function Compute FC 3.0); per-instance NAS / OSS auto-mount; mainland-China low latency. Treated as a regular `SandboxFilesystemSpec` — full setup details (templates, RAM permissions, NAS-first config) live in the integration docs |

All backends implement the same interface; agent code, toolkit, and `AGENTS.md` don't change.

## How the workspace maps into the sandbox

Host-side key files under `workspace/` (`AGENTS.md`, `skills/`, `subagents/`, `knowledge/`) are synced into the sandbox at each start, content-hash-gated — unchanged content is skipped.

To bind a host directory into the sandbox (e.g. a code repo), use `BindMountEntry` (only Docker / K8s; managed sandboxes like Daytona / E2B run in the cloud and can't mount your host paths).

File changes inside the sandbox don't sync back to the host — to retrieve sandbox-produced artifacts, have the agent `read_file` them.

## Implementing your own sandbox backend

To integrate a non-Docker isolation environment (self-hosted remote executor, commercial sandbox API, local mock, etc.), no Harness source changes needed — implement a few contract interfaces and pass them to `filesystem(...)`. The `InMemorySandbox` family under `agentscope-harness` tests is the minimal skeleton to copy.

## Related pages

- [Filesystem](./filesystem) — three declarative modes compared
- [Workspace](./workspace) — which files under `workspace/` sync into the sandbox
- [Architecture](./architecture) — where sandbox acquire / release sits in the call() timeline
