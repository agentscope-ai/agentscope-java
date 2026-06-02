# Filesystem

## Purpose

`AbstractFilesystem` abstracts agent access to the **workspace** from "must be local disk" into a unified interface: `ls / read / write / edit / grep / glob / upload / download`. When **executing commands in an isolated environment** is required, a backend additionally implements `AbstractSandboxFilesystem`, which is when `HarnessAgent` registers `ShellExecuteTool`.

In Harness, **the filesystem serves three distinct but often confused roles**:

1. **Tool surface**: `FilesystemTool` (and optional `ShellExecuteTool`) recognize a single `AbstractFilesystem` instance; all paths and executions flow through this outlet, making it easy to swap implementations.
2. **Physical landing for workspace reads/writes**: `WorkspaceManager` reads "filesystem first, fall back to local if not found"; writes and uploads always go through filesystem. Therefore **where long-term memory, daily logs, and session logs ultimately land** depends on which **mode** you choose.
3. **Multi-tenant and isolation**: `NamespaceFactory` assembles a path prefix from `RuntimeContext.userId` and other sources on every operation, making the same codebase transparently switch storage shards across **user / session / global** boundaries; `RemoteFilesystemSpec` and `SandboxFilesystemSpec` also connect **IsolationScope** to "shared KV" or "sandbox state key", aligned with the [Sandbox](./sandbox/index.md) isolation story.

## Three Declarative Modes

`HarnessAgent.Builder` accepts **at most one** of the **`filesystem(...)` family** (mutually exclusive with **`abstractFilesystem(...)`**; the latter is an escape hatch for self-managed implementations, see next section):

| Mode | Config Method | Typical Artifact | Shell | Best For |
|------|--------------|-----------------|-------|----------|
| **1 — Composite + Shared Storage** | `filesystem(RemoteFilesystemSpec)` | `CompositeFilesystem`: **shell-free** `LocalFilesystem` at workspace root + `RemoteFilesystem` routed by prefix | No | Multi-replica sharing of `MEMORY.md`, `memory/`, session logs, etc.; **no host shell** |
| **2 — Sandbox** | `filesystem(SandboxFilesystemSpec)` | `SandboxBackedFilesystem` + lifecycle by [Sandbox](./sandbox/index.md) | Yes (inside sandbox) | Isolated execution, recoverable sandbox sessions, optional snapshots and distributed sessions |
| **3 — Local + Shell** | `filesystem(LocalFilesystemSpec)` or **no explicit filesystem call** | `LocalFilesystemWithShell` | Yes (host `sh -c`) | Single-process/local, trusted environment, simple scripts and tests |

**When no `filesystem(...)` is called**, it is equivalent to **explicit `filesystem(new LocalFilesystemSpec())`** — mode 3, with root directory at `workspace` and host shell available.

### Mode 1: Composite + Storage (`RemoteFilesystemSpec`)

- **Structure**: `RemoteFilesystemSpec#toFilesystem` assembles a `CompositeFilesystem`:
  - **Default/unmatched prefixes** → plain `LocalFilesystem` (**no** `ShellExecuteTool`)
  - **Configured prefixes** (e.g., defaults include `MEMORY.md`, `memory/`, `agents/<agentId>/sessions/` + extensible via `addSharedPrefix`) → `RemoteFilesystem` (on `BaseStore`, namespace controlled by `IsolationScope`: SESSION / USER / AGENT / GLOBAL)
- **Why not `LocalFilesystemWithShell` by default**: mode 1's design goal is **cross-node consistent long memory and logs** while **avoiding opening a shell on the host**; use modes 2 or 3 when a shell is needed.

### Mode 2: Sandbox (`SandboxFilesystemSpec`)

- See [Sandbox](./sandbox/index.md). Key point: still exposes `AbstractFilesystem` + optional `ShellExecuteTool` (via `AbstractSandboxFilesystem`) to upper layers, but actual IO/processes happen on the `SandboxClient` side in an isolated environment; `SandboxLifecycleHook` acquires/persists/releases around each `call`.

### Mode 3: Local + Shell (`LocalFilesystemSpec` or default)

- **Behavior**: `LocalFilesystemWithShell` uses the workspace as root, commands run as host `sh -c` (configurable timeout, environment variables, `virtualMode`, etc.) — **fundamentally different from mode 1's "shell-free local root"**.

## Class Hierarchy and `ShellExecuteTool` Registration

```{mermaid}
classDiagram
    class AbstractFilesystem {
        <<interface>>
        ls/read/write/edit
        grep/glob
        uploadFiles/downloadFiles
    }
    class AbstractSandboxFilesystem {
        <<interface>>
        +id() String
        +execute(cmd, timeout)
    }
    AbstractSandboxFilesystem --|> AbstractFilesystem

    class LocalFilesystem
    class LocalFilesystemWithShell
    class BaseSandboxFilesystem
    class RemoteFilesystem
    class CompositeFilesystem
    class SandboxBackedFilesystem

    LocalFilesystem ..|> AbstractFilesystem
    RemoteFilesystem ..|> AbstractFilesystem
    LocalFilesystemWithShell --|> LocalFilesystem
    LocalFilesystemWithShell ..|> AbstractSandboxFilesystem
    BaseSandboxFilesystem ..|> AbstractSandboxFilesystem
    CompositeFilesystem ..|> AbstractFilesystem
    SandboxBackedFilesystem ..|> AbstractSandboxFilesystem
```

- **`CompositeFilesystem` only implements `AbstractFilesystem`**, not `AbstractSandboxFilesystem`, so it does **not** register `ShellExecuteTool`. If you need composite routing plus a shell, provide a shell-capable default backend via `abstractFilesystem` or use sandbox/local mode.
- **`read(filePath, offset, limit)`**: `limit <= 0` means "use the implementation-defined default line count" (may differ between local and sandbox).

## Implementation Quick Reference

| Implementation | Description |
|----------------|-------------|
| `LocalFilesystem` | Local files only, no execution; `virtualMode` anchors `rootDir` to prevent traversal |
| `LocalFilesystemWithShell` | Local + host shell; **core of mode 3** |
| `BaseSandboxFilesystem` | Base class for connecting to remote Unix; most methods implemented via `execute` + shell commands |
| `RemoteFilesystem` | KV storage based on `BaseStore`; no shell; used with `IsolationScope` |
| `CompositeFilesystem` | Multi-backend via longest-prefix matching; **no shell** capability |
| `SandboxBackedFilesystem` | Sandbox proxy implementing `AbstractSandboxFilesystem`; works with `SandboxManager` |

## `BaseSandboxFilesystem` Default Implementation Strategy

Subclasses primarily implement `execute / uploadFiles / downloadFiles / id`; the base class typically implements `ls/read/grep/glob/edit/write` as remote shell and Python3 snippets, enabling quick deployment in standard Unix environments.

## `NamespaceFactory` and Multi-Tenancy

```java
@FunctionalInterface
public interface NamespaceFactory { List<String> getNamespace(); }
```

Called on every file operation, returns the current request's path segments (e.g., `["users", "alice"]`). When building `HarnessAgent`, you can use an `AtomicReference` linked to `RuntimeContext.userId`, so the same `AbstractFilesystem` instance routes to different subtrees for different users.

## `WorkspaceIndex` and `grep` Semantics (Mode 1)

`RemoteFilesystem` optionally attaches a local SQLite `WorkspaceIndex` (auto-built when using `RemoteFilesystemSpec`) to accelerate `ls / glob / exists / grep` by avoiding full-store scans. The index is **best-effort** and may not reflect writes made by sibling replicas.

- `ls / glob / exists` consult the index first and fall back to a store scan when no match is found, so cross-replica visibility is preserved.
- `grep` follows the same pattern: index-driven candidate enumeration first, store-scan fallback when the index yields zero matches. This ensures `grep` on node B still surfaces files written via node A even if B's index has not yet been refreshed.
- Callers needing authoritative enumeration (rather than fast read paths) should refresh the index via `WorkspaceIndex.rebuildFromDisk(...)` or rely on the store-scan fallback path.

## Configuration Examples

**Recommended: choose one of the three modes first, then only touch `abstractFilesystem` when needed:**

```java
// Mode 3: explicit local + shell (equivalent to "no filesystem" default; useful for adjusting timeouts, etc.)
HarnessAgent agent = HarnessAgent.builder()
    .name("local")
    .model(model)
    .workspace(workspace)
    .filesystem(new LocalFilesystemSpec().executeTimeoutSeconds(120))
    .build();
```

```java
// Mode 1: share long-term memory to Store (no host shell)
HarnessAgent agent = HarnessAgent.builder()
    .name("store")
    .model(model)
    .workspace(workspace)
    .filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER))
    .build();
```

```java
// Mode 2: sandbox (specific spec varies by implementation, e.g. Docker)
HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox")
    .model(model)
    .workspace(workspace)
    .filesystem(dockerFilesystemSpec)  // extends SandboxFilesystemSpec
    .build();
```

**Escape hatch (mutually exclusive with the `filesystem(...Spec)` calls above):**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("custom")
    .model(model)
    .workspace(workspace)
    .abstractFilesystem(myCustomTree)  // fully self-managed AbstractFilesystem tree
    .build();
```

**Manual composition (advanced)**: inside `abstractFilesystem` or a custom factory, you can still use `CompositeFilesystem` + `LocalFilesystemWithShell`, etc., but you are responsible for security boundaries and whether `ShellExecuteTool` should be exposed.

## Related Pages

- [Sandbox](./sandbox/index.md) — sandbox mode principles, `SandboxStateStore`, distributed options
- [Tool](./tool.md) — `FilesystemTool` / `ShellExecuteTool` parameters
- [Workspace](./workspace.md) — `WorkspaceManager` and two-layer reads
- [Architecture](./architecture.md) — collaboration with hooks and `RuntimeContext`
