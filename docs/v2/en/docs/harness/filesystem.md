---
title: "Filesystem"
description: "Three deployment modes: local + shell / shared store / sandbox; multi-user isolation"
---

## Role

`HarnessAgent` abstracts the agent's view of the **workspace** away from "must be local disk" into a uniform interface. All file tools (`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`) and the optional `execute` (shell) go through this abstraction.

The payoff: you can switch between three deployment modes **without changing agent code**:

- Local + shell — single process, local, trusted env;
- Shared store — multiple replicas / pods share the same long-term memory;
- Sandbox — files and commands run in an isolated container; the same workspace state is restored across calls.

## Three declarative modes

Pick one with `filesystem(...)` on `HarnessAgent.Builder` (no call = mode 3 by default):

| Mode | Config | Shell? | When to use |
|------|--------|--------|-------------|
| **1 · Shared store** | `filesystem(new RemoteFilesystemSpec(store))` | ❌ | Multiple replicas share `MEMORY.md` / session logs / subtask records via KV; **no shell on the host** |
| **2 · Sandbox** | `filesystem(new DockerFilesystemSpec()...)`, or K8s / Daytona / E2B / AgentRun | ✅ (inside sandbox) | Isolated execution, cross-call workspace recovery, optional snapshots + distributed |
| **3 · Local + shell** (default) | `filesystem(new LocalFilesystemSpec()...)` or **omit it** | ✅ (host `sh -c`) | Single process / local / trusted env / scripts and tests |

> `filesystem(...)` is mutually exclusive with `abstractFilesystem(...)`; the latter is an escape hatch for fully self-managed filesystems and rarely needed.

### Mode 1: shared store

For "multi-replica, but the user's long-term memory must stay in sync". Pass a KV store (Redis / JDBC / custom) and the framework automatically routes `MEMORY.md`, `memory/`, session logs, subtask records, etc. into it:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("store")
    .model(model)
    .workspace(workspace)
    .filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER))   // namespace per userId
    .build();
```

This mode **does not provide shell** — on purpose: for shell, use mode 2 (sandbox) or 3 (local).

### Mode 2: sandbox

For "code may run untrusted operations" or "isolate from the production host". Every file op and shell command goes to the sandbox; the host is untouched. The sandbox can snapshot, so the next `call()` brings back `node_modules`, `pip install` results, etc.:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION))
    .build();
```

Details in [Sandbox](./sandbox). Backends: Docker / Kubernetes / Daytona / E2B / AgentRun (Aliyun).

### Mode 3: local + shell (default)

What you get with no `filesystem(...)` call: workspace lives at `${cwd}/.agentscope/workspace/`, shell runs on the host:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("local")
    .model(model)
    .workspace(workspace)
    // .filesystem(...) omitted = local + shell
    .build();
```

Adjust timeouts, env vars, etc.:

```java
.filesystem(new LocalFilesystemSpec()
    .executeTimeoutSeconds(120)
    .env("MY_VAR", "value")
    .inheritEnv(false))
```

## IsolationScope — bucketing across users and replicas

Both mode 1 (shared store) and mode 2 (sandbox) use the same `IsolationScope` concept to decide **who shares state with whom**:

| Scope | Meaning | Typical use |
|-------|---------|-------------|
| `SESSION` (default) | Each sessionId is independent | Multi-user SaaS, each conversation runs on its own |
| `USER` | Same `userId` shares across sessions | Same user's multiple sessions share long-term memory (distributed deployments) |
| `AGENT` | All users/sessions of this agent share | Public-knowledge-base type agent |
| `GLOBAL` | One shared slot for everything | Use with care |

Example: to let Alice's conversations across devices share the same long-term memory — choose `USER` and put `userId="alice"` in `RuntimeContext`.

## How multi-user isolation works

`RuntimeContext.userId` is the key to multi-user splitting:

- **Local mode**: user-level files land in `workspace/<userId>/...`, e.g. `workspace/alice/skills/code-reviewer/SKILL.md` is visible only to Alice;
- **Shared store mode**: used as the KV namespace prefix, distributed replicas share naturally;
- **Sandbox mode**: used as the sandbox state slot key (paired with `IsolationScope.USER`).

Without `userId`, single-tenant default applies and everyone shares one root.

## Two-layer reading in the workspace

Key files like `AGENTS.md`, `MEMORY.md`, `KNOWLEDGE.md` have a "two-layer fallback" on reads: look in your configured filesystem backend first, fall back to local disk if not found. This is useful for **"template files" in mode 1 (shared store)**: the first replica's local has the template `AGENTS.md` so it works immediately; later replicas read the up-to-date version from the shared store.

Writes always go through the configured filesystem backend.

## Fully self-managed: `abstractFilesystem(...)`

If none of the three modes fits, pass a fully self-implemented filesystem:

```java
HarnessAgent.builder()
    ...
    .abstractFilesystem(myCustomFilesystem)   // mutually exclusive with filesystem(...)
    .build();
```

Usually not needed — the three modes cover ~95% of use cases.

## Related Pages

- [Sandbox](./sandbox) — details of mode 2
- [Workspace](./workspace) — where the "lower layer" of the two-layer read comes from
- [Architecture](./architecture) — how filesystem and runtime context cooperate
