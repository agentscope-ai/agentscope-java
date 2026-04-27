# HarnessAgent Sandbox Mechanism Implementation Plan

## Overview

Based on `sandbox-design.md` goals and the OpenAI Agents Python reference implementation
(`references/openai-agents-python/src/agents/sandbox`), this plan describes a complete Sandbox
subsystem for HarnessAgent.

Core approach:
- Add a `sandbox/` package inside `agentscope-harness`, establishing a full abstraction layer
- Embed into the existing Hook pipeline via a new `SandboxLifecycleHook` (`PreCallEvent` / `PostCallEvent`)
- `SandboxManager` centrally manages session acquisition and lifecycle (SDK-owned / developer-owned modes)
- `SandboxSession` implementations also serve as `AbstractSandboxFilesystem`, unifying the filesystem and session models
- Expose user-facing API on `HarnessAgent.Builder` and `RuntimeContext`

---

## Module Structure

```
agentscope-harness/src/main/java/io/agentscope/harness/agent/
├── sandbox/
│   ├── SandboxSession.java               # Core abstraction: sandbox lifecycle + exec
│   ├── SandboxClient.java                # Interface: create / resume / delete
│   ├── SandboxClientOptions.java         # Polymorphic options base class (type field)
│   ├── SandboxSessionState.java          # Serializable session state
│   ├── SandboxManifest.java              # Workspace descriptor (root + entries + env)
│   ├── SandboxContext.java               # Per-call sandbox config aggregation
│   ├── SandboxManager.java               # Lifecycle management core
│   ├── SandboxSessionAware.java          # Filesystem injection interface
│   ├── snapshot/
│   │   ├── SandboxSnapshot.java          # Snapshot interface
│   │   ├── SandboxSnapshotSpec.java      # Snapshot spec interface (build → SandboxSnapshot)
│   │   ├── LocalSandboxSnapshot.java     # Local tar file implementation
│   │   ├── LocalSnapshotSpec.java
│   │   ├── NoopSandboxSnapshot.java      # No-op implementation
│   │   ├── NoopSnapshotSpec.java
│   │   ├── RemoteSandboxSnapshot.java    # Delegates to remote client (S3, etc.)
│   │   └── RemoteSnapshotSpec.java
│   ├── manifest/
│   │   ├── ManifestEntry.java            # Abstract entry
│   │   ├── FileEntry.java                # Inline file content
│   │   ├── DirEntry.java                 # Empty directory
│   │   ├── LocalFileEntry.java           # Copy file from host
│   │   ├── LocalDirEntry.java            # Copy directory from host
│   │   └── GitRepoEntry.java             # Clone a Git repo
│   └── impl/
│       ├── local/
│       │   ├── UnixLocalSandboxSession.java
│       │   ├── UnixLocalSandboxClient.java
│       │   ├── UnixLocalSandboxClientOptions.java
│       │   └── UnixLocalSandboxSessionState.java
│       └── docker/
│           ├── DockerSandboxSession.java
│           ├── DockerSandboxClient.java
│           ├── DockerSandboxClientOptions.java
│           └── DockerSandboxSessionState.java
└── hook/
    └── SandboxLifecycleHook.java          # New hook
```

---

## Step 1: Core Abstraction Layer

### 1.1 SandboxSession

Corresponds to Python `BaseSandboxSession`. The central abstraction for a sandbox instance.

**Key responsibilities:**
- Manages the full lifecycle of a single sandbox instance: `start()` → running → `stop()` → `shutdown()`
- Provides exec / read / write / ls / mkdir / rm workspace operations
- `start()` internal flow: `ensureBackendStarted()` → `prepareWorkspace()` → `applyManifestOrRestoreSnapshot()` → `ensureRuntimeHelpers()`
- `stop()` only persists a snapshot (`persistSnapshot()`), does not destroy resources
- `shutdown()` destroys backend resources (container / temp dir), default is no-op
- `aclose()` = pre-stop hooks + `stop()` + `shutdown()`

**Interface definition:**

```java
public interface SandboxSession extends AutoCloseable {
    // Lifecycle
    void start() throws Exception;
    void stop() throws Exception;
    void shutdown() throws Exception;
    void aclose() throws Exception;
    boolean isRunning();

    // Workspace operations (delegates to BaseSandboxFilesystem)
    ExecResult exec(String command, Integer timeoutSeconds);
    InputStream read(String path) throws Exception;
    void write(String path, InputStream data) throws Exception;
    List<FileInfo> ls(String path) throws Exception;
    void mkdir(String path) throws Exception;
    void rm(String path, boolean recursive) throws Exception;

    // Workspace serialization
    InputStream persistWorkspace() throws Exception;
    void hydrateWorkspace(InputStream data) throws Exception;

    // State
    SandboxSessionState getState();
}
```

**Fusion with AbstractSandboxFilesystem:**

`SandboxSession` implementations also extend `BaseSandboxFilesystem`, so the existing
shell-based default implementations (ls / read / write / edit / grep / glob) are reused:

```java
// UnixLocalSandboxSession extends BaseSandboxFilesystem implements SandboxSession
// DockerSandboxSession    extends BaseSandboxFilesystem implements SandboxSession
```

---

### 1.2 SandboxClient

Corresponds to Python `BaseSandboxClient`.

```java
public interface SandboxClient<O extends SandboxClientOptions> {
    String getBackendId();

    /**
     * Creates a new SandboxSession.
     * The session is NOT started automatically; the caller (usually SandboxManager) must call start().
     */
    SandboxSession create(SandboxManifest manifest, SandboxSnapshotSpec snapshotSpec, O options)
            throws Exception;

    /**
     * Resumes a session from a previously persisted SandboxSessionState.
     * Re-connects to a still-alive backend instance, or creates a new one and restores via snapshot.
     */
    SandboxSession resume(SandboxSessionState state) throws Exception;

    /** Deletes backend resources for SDK-owned sessions (container, temp dir, etc.). */
    void delete(SandboxSession session) throws Exception;

    /** Serializes session state to a JSON-compatible Map for persistent storage. */
    Map<String, Object> serializeState(SandboxSessionState state);

    /** Deserializes from a JSON payload into the concrete SandboxSessionState subclass. */
    SandboxSessionState deserializeState(Map<String, Object> payload);
}
```

---

### 1.3 SandboxSessionState

Corresponds to Python `SandboxSessionState`. Serializable to JSON for storage in the session file.

```java
public class SandboxSessionState {
    private String type;                        // "unix_local" / "docker" / etc.
    private UUID sessionId;
    private SandboxManifest manifest;
    private Map<String, Object> snapshotState;  // Serialized snapshot info
    private boolean workspaceRootReady;
    private String snapshotFingerprint;
    private String snapshotFingerprintVersion;
    // Subclasses may add more fields (e.g. Docker containerId)
}
```

---

### 1.4 SandboxManifest

Corresponds to Python `Manifest`. Describes the initial workspace state.

```java
public class SandboxManifest {
    private String root = "/workspace";         // Workspace root path
    private Map<String, ManifestEntry> entries; // Relative path → entry
    private Map<String, String> environment;    // Environment variables
    private List<String> users;                 // Sandbox users
}
```

**ManifestEntry sub-types:**

| Java class       | Python equivalent | Purpose                         |
|------------------|-------------------|---------------------------------|
| `FileEntry`      | `File`            | Inline file content             |
| `DirEntry`       | `Dir`             | Create empty directory          |
| `LocalFileEntry` | `LocalFile`       | Copy file from host             |
| `LocalDirEntry`  | `LocalDir`        | Copy directory from host        |
| `GitRepoEntry`   | `GitRepo`         | Clone a Git repository          |

---

### 1.5 SandboxContext

Aggregates per-call sandbox configuration. Passed through `RuntimeContext`, read by `SandboxLifecycleHook`.

```java
public class SandboxContext {
    // Direct injection mode (developer-owned)
    private SandboxSession session;

    // Via client mode (SDK-owned)
    private SandboxClient<?> client;
    private SandboxClientOptions clientOptions;
    private SandboxManifest manifest;           // Overrides builder default manifest
    private SandboxSnapshotSpec snapshotSpec;   // Overrides builder default snapshot spec

    // Resume mode (provide serialized state)
    private SandboxSessionState sessionState;
    // Or auto-loaded from the "_sandbox" key in the session file

    public static Builder builder() { ... }
}
```

---

## Step 2: Snapshot Abstraction Layer

Corresponds to Python `SnapshotBase` / `LocalSnapshot` / `NoopSnapshot` / `RemoteSnapshot`.

### SandboxSnapshot Interface

```java
public interface SandboxSnapshot {
    void persist(InputStream workspaceArchive) throws Exception;
    InputStream restore() throws Exception;
    boolean isRestorable() throws Exception;
    String getId();
}
```

### SandboxSnapshotSpec Interface

```java
public interface SandboxSnapshotSpec {
    SandboxSnapshot build(String snapshotId);
}
```

### Three Implementations

| Class                    | Description                                                                    | Python equivalent  |
|--------------------------|--------------------------------------------------------------------------------|--------------------|
| `LocalSandboxSnapshot`   | Persists to a local `{basePath}/{id}.tar`; atomic write (write .tmp then rename) | `LocalSnapshot`  |
| `NoopSandboxSnapshot`    | No-op; `isRestorable()` returns false                                          | `NoopSnapshot`     |
| `RemoteSandboxSnapshot`  | Delegates to user-provided `RemoteSnapshotClient` interface (upload/download/exists) | `RemoteSnapshot` |

---

## Step 3: Concrete SandboxClient + SandboxSession Implementations

### 3.1 UnixLocal Implementation

Corresponds to Python `unix_local.py`. Suitable for local development with no extra dependencies.

**UnixLocalSandboxClientOptions:**
```java
public class UnixLocalSandboxClientOptions extends SandboxClientOptions {
    private String type = "unix_local";
    private Path workspaceBasePath;      // Defaults to system temp dir if not specified
    private int[] exposedPorts = {};
}
```

**UnixLocalSandboxSession key implementation points:**
- `ensureBackendStarted()`: Creates a temp directory under `workspaceBasePath` as the workspace root
- `exec(command)`: Runs shell commands in the workspace root via `ProcessBuilder`,
  reusing the existing `LocalFilesystemWithShell` exec logic
- `persistWorkspace()`: `exec("tar -cf - -C {root} .")` returns a tar `InputStream`
- `hydrateWorkspace(data)`: Extracts tar stream into the workspace root
- `shutdown()`: Deletes the temp directory if SDK-owned
- State type = `"unix_local"`

**UnixLocalSandboxSessionState extra fields:**
```java
public class UnixLocalSandboxSessionState extends SandboxSessionState {
    private String workspaceRoot;   // Actual absolute workspace path in use
    private boolean workspaceOwned; // Created by SDK (delete on shutdown)?
}
```

---

### 3.2 Docker Implementation

Corresponds to Python `docker.py`. Requires `docker-java` dependency (marked `optional`).

**pom.xml additions (agentscope-harness):**
```xml
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java-core</artifactId>
    <version>3.4.0</version>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java-transport-httpclient5</artifactId>
    <version>3.4.0</version>
    <optional>true</optional>
</dependency>
```

**DockerSandboxClientOptions:**
```java
public class DockerSandboxClientOptions extends SandboxClientOptions {
    private String type = "docker";
    private String image = "ubuntu:22.04";
    private String workspaceRoot = "/workspace";
    private Map<String, String> envVars = Map.of();
    private Long memorySizeBytes;
    private Long cpuCount;
    private int[] exposedPorts = {};
}
```

**DockerSandboxSession key implementation points:**
- `ensureBackendStarted()`: `docker pull` + `docker run -d` to start the container
  (or reconnect via `containerId` from state)
- `exec(command)`: Via docker-java `ExecCreateCmd` + `ExecStartCmd`, collecting stdout/stderr
- `persistWorkspace()`: `docker exec tar -cf - -C {root} .`, using stdout as tar stream
- `hydrateWorkspace(data)`: Write tar stream into the container workspace root via `docker cp`
- `shutdown()`: If SDK-owned: `docker stop` + `docker rm`
- State type = `"docker"`, extra field: `containerId`

**DockerSandboxSessionState extra fields:**
```java
public class DockerSandboxSessionState extends SandboxSessionState {
    private String containerId;
    private String image;
}
```

---

## Step 4: SandboxManager

`SandboxManager` is the central scheduler responsible for resolving the sandbox instance
and completing workspace initialization.

### Resolution Priority (corresponds to Python SandboxRunConfig priority)

```
1. SandboxContext.session is non-null       → developer-owned, use directly, do NOT call start()
2. SandboxContext.sessionState is non-null  → SDK-owned resume, call client.resume(state)
3. "_sandbox" state found in session file   → SDK-owned resume, call client.resume(state)
4. None of the above                        → SDK-owned create, call client.create(manifest, snapshot, options)
```

### Core Methods

```java
public class SandboxManager {

    /**
     * Acquires (or creates) a SandboxSession from the SandboxContext in RuntimeContext.
     * The returned session has already had start() called; the workspace is ready.
     * The caller is responsible for deciding when to call stop/shutdown
     * (handled automatically by SandboxLifecycleHook).
     */
    public SandboxSession acquire(RuntimeContext runtimeContext) throws Exception;

    /**
     * Persists the session workspace (snapshot) and optionally destroys backend resources.
     * SDK-owned sessions trigger client.delete(); developer-owned sessions do not.
     */
    public void release(SandboxSession session, boolean shutdown) throws Exception;

    /**
     * Serializes SandboxSessionState and writes it to the RuntimeContext session store.
     * Key = "_sandbox", for use in subsequent resume calls.
     */
    public void persistState(SandboxSession session, RuntimeContext runtimeContext);

    /**
     * Reads SandboxSessionState from the RuntimeContext session store.
     * Returns null if no stored state is found.
     */
    public SandboxSessionState loadState(RuntimeContext runtimeContext);
}
```

### Workspace Initialization Flow (corresponds to Python `BaseSandboxSession._start_workspace()`)

```
session.start() internally:
  if snapshot.isRestorable():
    if workspacePreserved && fingerprintMatches:
      reapplyEphemeralManifest()   // Only rebuild ephemeral entries
    else:
      clearWorkspace()
      hydrateWorkspace(snapshot.restore())
      applyManifest(onlyEphemeral=true)
  elif workspacePreserved:
    reapplyEphemeralManifest()
  else:
    applyManifest(full)            // Materialize full manifest
```

---

## Step 5: SandboxLifecycleHook

A new Hook that integrates into the HarnessAgent Hook pipeline.

### Trigger Timing

| Event           | Action                                                                                   |
|-----------------|------------------------------------------------------------------------------------------|
| `PreCallEvent`  | Read SandboxContext from RuntimeContext → `SandboxManager.acquire()` → inject filesystem |
| `PostCallEvent` | `SandboxManager.release(session, shutdown=true if SDK-owned)` → `persistState()` → clear ref |
| `ErrorEvent`    | Same as PostCallEvent (best-effort, no re-throw)                                         |

### Implementation Skeleton

```java
public class SandboxLifecycleHook implements Hook, RuntimeContextAwareHook {

    private final SandboxManager sandboxManager;
    private final SandboxClient<?> defaultClient;
    private final SandboxClientOptions defaultClientOptions;
    private final SandboxSnapshotSpec defaultSnapshotSpec;
    private final AbstractFilesystem filesystem;

    private RuntimeContext runtimeContext;
    private SandboxSession activeSession;
    private boolean sdkOwned;

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent) {
            return handlePreCall(event);
        }
        if (event instanceof PostCallEvent || event instanceof ErrorEvent) {
            return handlePostCall(event);
        }
        return Mono.just(event);
    }

    private <T extends HookEvent> Mono<T> handlePreCall(T event) {
        return Mono.fromCallable(() -> {
            SandboxContext ctx = resolveSandboxContext();
            if (ctx == null) return event; // Sandbox not enabled

            activeSession = sandboxManager.acquire(runtimeContext);
            sdkOwned = (ctx.getSession() == null); // Directly injected = developer-owned

            // Inject into filesystem
            if (filesystem instanceof SandboxSessionAware aware) {
                aware.setSandboxSession(activeSession);
            }
            return event;
        });
    }

    private <T extends HookEvent> Mono<T> handlePostCall(T event) {
        return Mono.fromCallable(() -> {
            SandboxSession session = activeSession;
            if (session == null) return event;

            try {
                sandboxManager.release(session, sdkOwned);
                sandboxManager.persistState(session, runtimeContext);
            } catch (Exception e) {
                log.warn("SandboxLifecycleHook: release failed: {}", e.getMessage());
            } finally {
                activeSession = null;
            }
            return event;
        });
    }

    private SandboxContext resolveSandboxContext() {
        // Priority 1: SandboxContext from RuntimeContext
        // Priority 2: Default client configured via builder
        SandboxContext ctx = runtimeContext != null
                ? runtimeContext.getSandboxContext() : null;
        if (ctx == null && defaultClient != null) {
            ctx = SandboxContext.builder()
                    .client(defaultClient)
                    .clientOptions(defaultClientOptions)
                    .snapshotSpec(defaultSnapshotSpec)
                    .build();
        }
        return ctx;
    }
}
```

---

## Step 6: SandboxSessionAware Interface

Provides an injection point so `SandboxLifecycleHook` can push the live session into the filesystem.

```java
/**
 * Marks an AbstractFilesystem implementation that can accept
 * a live SandboxSession at runtime, enabling session injection
 * by SandboxLifecycleHook before each agent call.
 */
public interface SandboxSessionAware {
    void setSandboxSession(SandboxSession session);
    SandboxSession getSandboxSession();
}
```

**SandboxFilesystemAdapter** (optional, for decoupled implementations):

Wraps a `SandboxSession` and implements `AbstractSandboxFilesystem`, delegating all calls
to the current session. Suitable when session implementations do not extend `BaseSandboxFilesystem`.

---

## Step 7: RuntimeContext Extension

Add a `sandboxContext` field to `RuntimeContext`, or pass it via the extra map with a well-known key.

**Recommended: explicit typed field** (type-safe, consistent with existing `session`/`sessionKey` fields):

```java
public class RuntimeContext {
    // Existing fields...
    private final SandboxContext sandboxContext; // New field

    public SandboxContext getSandboxContext() { return sandboxContext; }

    public static class Builder {
        public Builder sandboxContext(SandboxContext ctx) { ... }
    }
}
```

**Alternative: well-known extra key** (backward-compatible):

```java
public SandboxContext getSandboxContext() {
    Object val = getExtra().get("_sandboxContext");
    return val instanceof SandboxContext sc ? sc : null;
}

public RuntimeContext withSandboxContext(SandboxContext ctx) {
    return RuntimeContext.builder()
            .putAllExtra(getExtra())
            .putExtra("_sandboxContext", ctx)
            .build();
}
```

---

## Step 8: HarnessAgent Builder Extension

Add sandbox configuration API to `HarnessAgent.Builder`:

```java
public class Builder {
    // New fields
    private SandboxClient<?> sandboxClient;
    private SandboxClientOptions sandboxClientOptions;
    private SandboxSnapshotSpec sandboxSnapshotSpec;
    private SandboxManifest defaultSandboxManifest;

    /** Configures the sandbox client (e.g. UnixLocalSandboxClient / DockerSandboxClient). */
    public Builder sandboxClient(SandboxClient<?> client) {
        this.sandboxClient = client;
        return this;
    }

    /** Configures sandbox client options (e.g. DockerSandboxClientOptions to specify image). */
    public Builder sandboxClientOptions(SandboxClientOptions options) {
        this.sandboxClientOptions = options;
        return this;
    }

    /** Configures the default snapshot strategy (Local / Noop / Remote). */
    public Builder sandboxSnapshotSpec(SandboxSnapshotSpec spec) {
        this.sandboxSnapshotSpec = spec;
        return this;
    }

    /** Configures the default sandbox workspace manifest (entries, env vars, etc.). */
    public Builder defaultSandboxManifest(SandboxManifest manifest) {
        this.defaultSandboxManifest = manifest;
        return this;
    }

    public HarnessAgent build() {
        // ...existing build logic...

        // If sandboxClient is configured, register SandboxLifecycleHook
        if (sandboxClient != null) {
            SandboxManager sandboxManager = new SandboxManager(sandboxClient, defaultSandboxManifest);
            SandboxLifecycleHook sandboxHook = new SandboxLifecycleHook(
                    sandboxManager,
                    sandboxClient,
                    sandboxClientOptions,
                    sandboxSnapshotSpec,
                    backend               // AbstractFilesystem
            );
            allHooks.add(sandboxHook);
        }
        // ...
    }
}
```

---

## Step 9: Session Sandbox State Persistence

Extend `WorkspaceSession` to support reading/writing the `_sandbox` key,
corresponding to how OpenAI Agents SDK stores sandbox payload in `RunState`.

```java
public class WorkspaceSession implements Session {

    /** Saves sandbox session state to the "_sandbox" field of the session file. */
    public void saveSandboxState(SessionKey key, Map<String, Object> statePayload) {
        // Read current session JSON → merge "_sandbox" field → write back
    }

    /** Reads the "_sandbox" field and returns the serialized Map for SandboxClient.deserializeState(). */
    public Map<String, Object> loadSandboxStatePayload(SessionKey key) {
        // Read session JSON → extract "_sandbox" field
    }
}
```

`SandboxManager.persistState()` call flow:

```
SandboxManager.persistState(session, runtimeContext)
  → state = session.getState()
  → payload = client.serializeState(state)
  → workspaceSession = runtimeContext.getSession()
  → workspaceSession.saveSandboxState(runtimeContext.getSessionKey(), payload)
```

---

## User-Facing Usage Examples

### Unix Local Mode (quick local development)

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .sysPrompt("You are a helpful coding assistant.")
    .workspace(Path.of("/path/to/workspace"))
    .sandboxClient(new UnixLocalSandboxClient())
    .sandboxClientOptions(
        new UnixLocalSandboxClientOptions()
            .workspaceBasePath(Path.of("/tmp/agent-sandboxes"))
    )
    .sandboxSnapshotSpec(
        new LocalSnapshotSpec(Path.of("/tmp/agent-snapshots"))
    )
    .defaultSandboxManifest(
        SandboxManifest.builder()
            .root("/workspace")
            .entry("repo", new LocalDirEntry(Path.of("/my/project")))
            .build()
    )
    .build();

// Sandbox is managed automatically by HarnessAgent
Msg response = agent.call(
    Msg.userMsg("Fix the bug in auth.java"),
    RuntimeContext.builder().sessionId("sess-001").build()
).block();
```

### Docker Mode (container isolation)

```java
DockerClient dockerClient = DockerClientBuilder.getInstance().build();

HarnessAgent agent = HarnessAgent.builder()
    .name("DockerAgent")
    .model(model)
    .sandboxClient(new DockerSandboxClient(dockerClient))
    .sandboxClientOptions(
        new DockerSandboxClientOptions()
            .image("python:3.12-slim")
            .memorySizeBytes(512 * 1024 * 1024L)
    )
    .sandboxSnapshotSpec(new LocalSnapshotSpec(Path.of("/tmp/docker-snapshots")))
    .build();
```

### Developer-Owned Mode (manual lifecycle management)

```java
UnixLocalSandboxClient client = new UnixLocalSandboxClient();
SandboxSession sandbox = client.create(manifest, snapshotSpec, options);
sandbox.start();

try {
    // Reuse the same sandbox across multiple calls
    for (String task : tasks) {
        agent.call(
            Msg.userMsg(task),
            RuntimeContext.builder()
                .sessionId("sess-001")
                .sandboxContext(SandboxContext.builder().session(sandbox).build())
                .build()
        ).block();
    }
} finally {
    sandbox.stop();     // Persist snapshot
    sandbox.shutdown(); // Clean up resources
    client.delete(sandbox);
}
```

### Resume Mode (restore workspace across calls)

```java
// After the first call, SandboxLifecycleHook automatically writes state to the session file.

// On the next call (auto-resume):
// WorkspaceSession reads the "_sandbox" field
// SandboxManager finds sessionState → calls client.resume(state)
// Workspace is restored from snapshot
Msg response2 = agent.call(
    Msg.userMsg("Continue working on the previous task"),
    RuntimeContext.builder().sessionId("sess-001").build() // Same sessionId triggers auto-resume
).block();
```

---

## Implementation Priority

| Phase | Content                                                                           | Priority |
|-------|-----------------------------------------------------------------------------------|----------|
| P0    | Core abstractions (SandboxSession / SandboxClient / SandboxManager / SandboxContext) | Required |
| P0    | Snapshot abstractions (NoopSnapshot + LocalSnapshot)                              | Required |
| P0    | UnixLocalSandboxSession + UnixLocalSandboxClient                                  | Required |
| P0    | SandboxLifecycleHook + SandboxSessionAware                                        | Required |
| P0    | RuntimeContext extension + HarnessAgent Builder API                               | Required |
| P1    | WorkspaceSession `_sandbox` state persistence + Resume flow                       | Important |
| P1    | SandboxManifest materialization (LocalDir / LocalFile / File / Dir entries)       | Important |
| P1    | DockerSandboxSession + DockerSandboxClient                                        | Important |
| P2    | RemoteSandboxSnapshot (S3, etc.)                                                  | Extension |
| P2    | GitRepoEntry manifest entry                                                       | Extension |
| P2    | Workspace fingerprint caching (avoid redundant snapshot restores)                 | Optimization |

---

## Design Decisions

### 1. SandboxSession vs AbstractSandboxFilesystem Fusion Strategy

**Short-term: Option A — Direct inheritance of `BaseSandboxFilesystem`**

Both `UnixLocalSandboxSession` and `DockerSandboxSession` extend `BaseSandboxFilesystem`
and implement `SandboxSession`.

Pros: Reuses the existing shell-based filesystem logic; existing tools (FilesystemTool, ShellExecuteTool)
require no modification.

**Long-term: Option B — Independent `SandboxFilesystemAdapter`** (future refactor direction)

`SandboxSession` is kept independent; a `SandboxFilesystemAdapter` wraps it and injects it,
cleanly decoupling session lifecycle from filesystem operations.

---

### 2. Sandbox Propagation to Subagents

When HarnessAgent spawns subagents, the sandbox mode must be decided:

- **Shared sandbox** (developer-owned propagation): Parent passes its `SandboxSession` via
  `SandboxContext.session` to the subagent. Subagent operates in the same workspace.
  Suitable for collaborative tasks.
- **Isolated sandbox** (independent create): Subagent gets its own sandbox with an isolated workspace.
  Suitable for concurrent independent tasks.

Default strategy: subagents use isolated sandboxes. If `SandboxContext.session` is explicitly
passed, shared mode is used. Configure via `buildGeneralPurposeFactory` / `buildSpecFactory`.

---

### 3. SandboxContext Delivery in RuntimeContext

**Chosen: explicit typed field** (type-safe, consistent with existing `session`/`sessionKey` style)

Add `private final SandboxContext sandboxContext` to `RuntimeContext`,
set via `Builder.sandboxContext(ctx)`.

---

## Reference Material

- OpenAI Agents Python reference: `references/openai-agents-python/src/agents/sandbox/`
- `session/base_sandbox_session.py` — lifecycle core logic
- `session/sandbox_client.py` — client interface and polymorphic options
- `session/sandbox_session_state.py` — serializable state model
- `snapshot.py` — three snapshot implementations
- `manifest.py` — workspace descriptor model
- `sandboxes/unix_local.py` — Unix local implementation reference
- `sandboxes/docker.py` — Docker implementation reference
- `docs/sandbox/guide.md` — full concepts and usage documentation

