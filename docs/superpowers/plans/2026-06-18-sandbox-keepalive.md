# Sandbox keepAlive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `keepAlive` support to the sandbox framework so that K8s (and Docker) Pods survive across calls — stop()/snapshot runs normally, shutdown() is skipped — while also fixing the `RemoteSandboxSnapshot` client re-injection bug in `resume()`.

**Architecture:** `keepAlive` is a static config on `SandboxFilesystemSpec`, transparently propagated to `SandboxContext`, and read by `SandboxManager.release()` to conditionally skip `sandbox.shutdown()`. The `RemoteSandboxSnapshot` client null-after-deserialize bug is fixed in `KubernetesSandboxClient.resume()` and `DockerSandboxClient.resume()` by accepting a `SandboxSnapshotSpec` at construction time and calling `snapshotSpec.build(id)` to re-inject the client.

**Tech Stack:** Java 17, JUnit 5, Mockito (already present in harness test scope)

---

## File Map

| File | Change |
|------|--------|
| `agentscope-harness/.../filesystem/spec/SandboxFilesystemSpec.java` | add `keepAlive` field + fluent setter + getter |
| `agentscope-harness/.../sandbox/SandboxContext.java` | add `keepAlive` field + builder entry + getter |
| `agentscope-harness/.../filesystem/spec/SandboxFilesystemSpec.java` | propagate `keepAlive` in `toSandboxContext()` |
| `agentscope-harness/.../sandbox/SandboxManager.java` | change `release()` signature to accept `SandboxContext`, skip shutdown when `keepAlive=true` |
| `agentscope-harness/.../middleware/SandboxLifecycleMiddleware.java` | update both `release()` call sites to pass `sandboxContext` |
| `agentscope-harness/.../sandbox/SandboxManagerIsolationTest.java` | add keepAlive test cases |
| `agentscope-harness/.../sandbox/impl/docker/DockerSandboxClient.java` | add `SandboxSnapshotSpec` field + 3-arg constructor; re-inject in `resume()` |
| `agentscope-harness/.../sandbox/impl/docker/DockerFilesystemSpec.java` | pass `snapshotSpec` to `DockerSandboxClient` in `createClient()` |
| `agentscope-extensions/.../kubernetes/KubernetesSandboxClient.java` | add `SandboxSnapshotSpec` field + 3-arg constructor; re-inject in `resume()` |
| `agentscope-extensions/.../kubernetes/KubernetesFilesystemSpec.java` | pass `snapshotSpec` to `KubernetesSandboxClient` in `createClient()` |
| `agentscope-extensions/.../kubernetes/KubernetesSandboxStateSerdeTest.java` | add snapshot re-injection round-trip test |

---

## Task 1: Add `keepAlive` to `SandboxFilesystemSpec` and `SandboxContext`

**Files:**
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/spec/SandboxFilesystemSpec.java:44-48,110-121`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/SandboxContext.java:29-130`

- [ ] **Step 1: Add `keepAlive` field and fluent setter to `SandboxFilesystemSpec`**

In `SandboxFilesystemSpec.java`, after the existing field `private boolean workspaceProjectionEnabled = true;` (line 47), add:

```java
private boolean keepAlive = false;
```

After the `workspaceProjectionRoots(...)` method (line 108), add:

```java
/**
 * When {@code true}, {@link io.agentscope.harness.agent.sandbox.SandboxManager#release}
 * calls {@link io.agentscope.harness.agent.sandbox.Sandbox#stop()} (persisting the
 * snapshot) but skips {@link io.agentscope.harness.agent.sandbox.Sandbox#shutdown()},
 * leaving the underlying resource (e.g. Pod) alive for reuse in the next call.
 *
 * @param keepAlive true to preserve the sandbox resource across calls
 * @return this spec
 */
public SandboxFilesystemSpec keepAlive(boolean keepAlive) {
    this.keepAlive = keepAlive;
    return this;
}

public boolean isKeepAlive() {
    return keepAlive;
}
```

- [ ] **Step 2: Propagate `keepAlive` in `toSandboxContext()`**

In `SandboxFilesystemSpec.java`, the `toSandboxContext(Path)` method currently builds the context at lines 110-121. Change the builder call to pass `keepAlive`:

```java
public final SandboxContext toSandboxContext(Path hostWorkspaceRoot) {
    SandboxClient<?> client =
            Objects.requireNonNull(createClient(), "sandbox client is required");
    WorkspaceSpec withProjection = buildWorkspaceSpecWithProjection(hostWorkspaceRoot);
    return SandboxContext.builder()
            .client(client)
            .clientOptions(clientOptions())
            .snapshotSpec(snapshotSpecOverride != null ? snapshotSpecOverride : snapshotSpec())
            .workspaceSpec(withProjection)
            .isolationScope(isolationScope)
            .keepAlive(keepAlive)
            .build();
}
```

- [ ] **Step 3: Add `keepAlive` field and builder support to `SandboxContext`**

In `SandboxContext.java`, add `keepAlive` alongside the existing fields (after `isolationScope` at line 35):

```java
private final boolean keepAlive;
```

In the constructor `private SandboxContext(Builder builder)` (line 37), add:

```java
this.keepAlive = builder.keepAlive;
```

Add getter after `getIsolationScope()`:

```java
public boolean isKeepAlive() {
    return keepAlive;
}
```

In `Builder` (line 79), add field and fluent method:

```java
private boolean keepAlive = false;

public Builder keepAlive(boolean keepAlive) {
    this.keepAlive = keepAlive;
    return this;
}
```

- [ ] **Step 4: Verify compilation**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-harness -am compile -q
```

Expected: BUILD SUCCESS, no errors.

- [ ] **Step 5: Commit**

```bash
git add agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/spec/SandboxFilesystemSpec.java
git add agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/SandboxContext.java
git commit -m "feat(sandbox): add keepAlive config to SandboxFilesystemSpec and SandboxContext"
```

---

## Task 2: Update `SandboxManager.release()` to respect `keepAlive`

**Files:**
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/SandboxManager.java:142-170`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/SandboxLifecycleMiddleware.java:116-134`
- Test: `agentscope-harness/src/test/java/io/agentscope/harness/agent/sandbox/SandboxManagerIsolationTest.java`

- [ ] **Step 1: Write failing tests for keepAlive release behavior**

Add these two test methods to `SandboxManagerIsolationTest.java`:

```java
// ---- keepAlive: stop() called, shutdown() skipped ----

@Test
void keepAlive_true_stopsButDoesNotShutdown() throws Exception {
    Sandbox sandbox = mock(Sandbox.class);
    SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox);
    SandboxContext ctx = SandboxContext.builder().keepAlive(true).build();

    manager.release(result, ctx);

    verify(sandbox).stop();
    verify(sandbox, never()).shutdown();
}

@Test
void keepAlive_false_stopsAndShutdown() throws Exception {
    Sandbox sandbox = mock(Sandbox.class);
    SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox);
    SandboxContext ctx = SandboxContext.builder().keepAlive(false).build();

    manager.release(result, ctx);

    verify(sandbox).stop();
    verify(sandbox).shutdown();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-harness -am test -Dtest=SandboxManagerIsolationTest#keepAlive_true_stopsButDoesNotShutdown+keepAlive_false_stopsAndShutdown -q
```

Expected: FAIL — `release(result, ctx)` does not compile yet.

- [ ] **Step 3: Change `SandboxManager.release()` signature and add keepAlive logic**

Replace the existing `release(SandboxAcquireResult result)` method (lines 142-170) with:

```java
public void release(SandboxAcquireResult result, SandboxContext sandboxContext) {
    if (result == null) {
        return;
    }
    Sandbox sandbox = result.getSandbox();
    if (sandbox == null) {
        return;
    }
    if (!result.isSelfManaged()) {
        return;
    }

    try {
        sandbox.stop();
    } catch (Exception e) {
        log.warn("[sandbox] Sandbox stop failed: {}", e.getMessage(), e);
    }

    boolean keepAlive = sandboxContext != null && sandboxContext.isKeepAlive();
    if (!keepAlive) {
        try {
            sandbox.shutdown();
        } catch (Exception e) {
            log.warn("[sandbox] Sandbox shutdown failed: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Update `SandboxLifecycleMiddleware` — two call sites**

In `SandboxLifecycleMiddleware.java`:

**Call site 1** — `acquireForCall()` exception recovery path (around line 94):

```java
// Before (line 94):
sandboxManager.release(result);

// After:
sandboxManager.release(result, sandboxContext);
```

`sandboxContext` is already extracted at line 77: `SandboxContext sandboxContext = ctx.get(SandboxContext.class);`

**Call site 2** — `releaseForCall()` normal path (around line 128):

```java
// Before (line 128):
sandboxManager.release(result);

// After:
sandboxManager.release(result, sandboxContext);
```

`sandboxContext` is already extracted at line 121: `SandboxContext sandboxContext = ctx != null ? ctx.get(SandboxContext.class) : null;`

- [ ] **Step 5: Run the new tests to verify they pass**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-harness -am test -Dtest=SandboxManagerIsolationTest -q
```

Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 6: Commit**

```bash
git add agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/SandboxManager.java
git add agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/SandboxLifecycleMiddleware.java
git add agentscope-harness/src/test/java/io/agentscope/harness/agent/sandbox/SandboxManagerIsolationTest.java
git commit -m "feat(sandbox): keepAlive mode skips shutdown() on release"
```

---

## Task 3: Fix `RemoteSandboxSnapshot` client re-injection in `DockerSandboxClient`

**Files:**
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerSandboxClient.java:40-56,97-107`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerFilesystemSpec.java:100`

- [ ] **Step 1: Add `snapshotSpec` field and 3-arg constructor to `DockerSandboxClient`**

Add field after `private final ObjectMapper objectMapper;` (line 40):

```java
private final SandboxSnapshotSpec snapshotSpec;
```

Replace the existing two constructors (lines 42-56) with three:

```java
public DockerSandboxClient() {
    this(null, null);
}

public DockerSandboxClient(ObjectMapper objectMapper) {
    this(objectMapper, null);
}

/**
 * @param objectMapper optional; when null a default mapper is created
 * @param snapshotSpec used in {@link #resume} to re-inject the snapshot client after
 *     deserialization. When null, the snapshot field is left as-is (backward-compatible).
 */
public DockerSandboxClient(ObjectMapper objectMapper, SandboxSnapshotSpec snapshotSpec) {
    this.objectMapper =
            objectMapper != null
                    ? objectMapper
                    : new ObjectMapper()
                            .findAndRegisterModules()
                            .registerModule(new HarnessSandboxJacksonModule());
    this.snapshotSpec = snapshotSpec;
}
```

- [ ] **Step 2: Re-inject snapshot client in `DockerSandboxClient.resume()`**

Replace `resume()` (lines 97-107):

```java
@Override
public Sandbox resume(SandboxState state) {
    if (!(state instanceof DockerSandboxState dockerState)) {
        throw new IllegalArgumentException(
                "Expected DockerSandboxState but got: " + state.getClass().getName());
    }
    // Re-inject snapshot client lost during JSON serialization
    if (snapshotSpec != null && dockerState.getSnapshot() != null) {
        dockerState.setSnapshot(snapshotSpec.build(dockerState.getSnapshot().getId()));
    }
    log.debug(
            "[sandbox-docker] Resuming sandbox: id={}, containerId={}",
            dockerState.getSessionId(),
            dockerState.getContainerId());
    return new DockerSandbox(dockerState);
}
```

- [ ] **Step 3: Pass `snapshotSpec` in `DockerFilesystemSpec.createClient()`**

In `DockerFilesystemSpec.java`, the `createClient()` method at line 100 currently returns:

```java
return client != null ? client : options.createClient();
```

Replace with:

```java
@Override
protected SandboxClient<?> createClient() {
    if (client != null) {
        return client;
    }
    SandboxSnapshotSpec effective =
            getSnapshotSpecOverride() != null ? getSnapshotSpecOverride() : snapshotSpec();
    return new DockerSandboxClient(null, effective);
}
```

- [ ] **Step 4: Verify compilation and existing tests pass**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-harness -am test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerSandboxClient.java
git add agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerFilesystemSpec.java
git commit -m "fix(sandbox): re-inject RemoteSandboxSnapshot client in DockerSandboxClient.resume()"
```

---

## Task 4: Fix `RemoteSandboxSnapshot` client re-injection in `KubernetesSandboxClient`

**Files:**
- Modify: `agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes/src/main/java/io/agentscope/extensions/sandbox/kubernetes/KubernetesSandboxClient.java:37-64,98-107`
- Modify: `agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes/src/main/java/io/agentscope/extensions/sandbox/kubernetes/KubernetesFilesystemSpec.java:101-103`
- Test: `agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes/src/test/java/io/agentscope/extensions/sandbox/kubernetes/KubernetesSandboxStateSerdeTest.java`

- [ ] **Step 1: Write failing test for snapshot re-injection in `KubernetesSandboxStateSerdeTest`**

Add this test to `KubernetesSandboxStateSerdeTest.java`:

```java
@Test
void resumeReInjectsSnapshotClient() {
    // Build a state that has a RemoteSandboxSnapshot with only id (client=null after deser)
    KubernetesSandboxState state = new KubernetesSandboxState();
    state.setSessionId("test-session");
    state.setNamespace("default");
    state.setContainerName("agent");
    state.setWorkspaceRoot("/workspace");
    state.setImage("ubuntu:24.04");
    state.setWorkspaceRootReady(false);

    // Simulate what happens after deserialization: snapshot has id but client is null
    RemoteSandboxSnapshot snapshotWithNullClient =
            new RemoteSandboxSnapshot(null, "snap-id-123");
    state.setSnapshot(snapshotWithNullClient);

    // Build client with a snapshotSpec that can rebuild the client
    RemoteSnapshotClient mockClient = mock(RemoteSnapshotClient.class);
    SandboxSnapshotSpec snapshotSpec = id -> new RemoteSandboxSnapshot(mockClient, id);
    KubernetesSandboxClient client =
            new KubernetesSandboxClient(
                    new KubernetesSandboxClientOptions(), null, snapshotSpec);

    // resume() should re-inject the snapshot client
    KubernetesSandbox sandbox = (KubernetesSandbox) client.resume(state);

    SandboxSnapshot rebuilt = sandbox.getState().getSnapshot();
    assertNotNull(rebuilt);
    assertEquals("snap-id-123", rebuilt.getId());
    // The rebuilt snapshot should use the mockClient — verify it's callable
    assertInstanceOf(RemoteSandboxSnapshot.class, rebuilt);
}
```

Add required imports:
```java
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes -am test -Dtest=KubernetesSandboxStateSerdeTest#resumeReInjectsSnapshotClient -q
```

Expected: FAIL — 3-arg constructor does not exist yet.

- [ ] **Step 3: Add `snapshotSpec` field and 3-arg constructor to `KubernetesSandboxClient`**

Add field after `private final KubernetesSandboxClientOptions defaultOptions;` (line 38):

```java
private final SandboxSnapshotSpec snapshotSpec;
```

Replace the three existing constructors (lines 40-64) with:

```java
public KubernetesSandboxClient() {
    this(new KubernetesSandboxClientOptions(), null, null);
}

public KubernetesSandboxClient(KubernetesSandboxClientOptions defaultOptions) {
    this(defaultOptions, null, null);
}

public KubernetesSandboxClient(
        KubernetesSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
    this(defaultOptions, objectMapper, null);
}

/**
 * @param defaultOptions template options merged into each {@link #create} call
 * @param objectMapper optional mapper; when null a default mapper is created
 * @param snapshotSpec used in {@link #resume} to re-inject the snapshot client after
 *     deserialization. When null, the snapshot field is left as-is (backward-compatible).
 */
public KubernetesSandboxClient(
        KubernetesSandboxClientOptions defaultOptions,
        ObjectMapper objectMapper,
        SandboxSnapshotSpec snapshotSpec) {
    this.defaultOptions =
            defaultOptions != null ? defaultOptions : new KubernetesSandboxClientOptions();
    this.objectMapper =
            objectMapper != null
                    ? objectMapper
                    : new ObjectMapper()
                            .findAndRegisterModules()
                            .registerModule(new HarnessSandboxJacksonModule())
                            .registerModule(new KubernetesHarnessSandboxJacksonModule());
    this.snapshotSpec = snapshotSpec;
}
```

- [ ] **Step 4: Re-inject snapshot client in `KubernetesSandboxClient.resume()`**

Replace `resume()` (lines 98-107):

```java
@Override
public Sandbox resume(SandboxState state) {
    if (!(state instanceof KubernetesSandboxState k8s)) {
        throw new IllegalArgumentException(
                "Expected KubernetesSandboxState but got: " + state.getClass().getName());
    }
    // Re-inject snapshot client lost during JSON serialization
    if (snapshotSpec != null && k8s.getSnapshot() != null) {
        k8s.setSnapshot(snapshotSpec.build(k8s.getSnapshot().getId()));
    }
    KubernetesSandboxClientOptions merged = merge(null);
    KubernetesClient kc = resolveClient(merged);
    Fabric8KubernetesPodRuntime runtime = new Fabric8KubernetesPodRuntime(kc, merged);
    return new KubernetesSandbox(k8s, runtime);
}
```

- [ ] **Step 5: Pass `snapshotSpec` in `KubernetesFilesystemSpec.createClient()`**

Replace `createClient()` (lines 100-103):

```java
@Override
protected SandboxClient<?> createClient() {
    if (client != null) {
        return client;
    }
    SandboxSnapshotSpec effective =
            getSnapshotSpecOverride() != null ? getSnapshotSpecOverride() : snapshotSpec();
    return new KubernetesSandboxClient(options, null, effective);
}
```

- [ ] **Step 6: Run the new test to verify it passes**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes -am test -Dtest=KubernetesSandboxStateSerdeTest -q
```

Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 7: Commit**

```bash
git add agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes/src/main/java/io/agentscope/extensions/sandbox/kubernetes/KubernetesSandboxClient.java
git add agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes/src/main/java/io/agentscope/extensions/sandbox/kubernetes/KubernetesFilesystemSpec.java
git add agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes/src/test/java/io/agentscope/extensions/sandbox/kubernetes/KubernetesSandboxStateSerdeTest.java
git commit -m "fix(sandbox): re-inject RemoteSandboxSnapshot client in KubernetesSandboxClient.resume()"
```

---

## Task 5: Full test suite green check

- [ ] **Step 1: Run full harness test suite**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-harness -am test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run full kubernetes extension test suite**

```bash
mvn -s "E:\.m2\settings.xml" -pl agentscope-extensions/agentscope-extensions-sandbox/agentscope-extensions-sandbox-kubernetes -am test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit if any fixups were needed, otherwise done**

```bash
git status
# If clean, no commit needed
```
