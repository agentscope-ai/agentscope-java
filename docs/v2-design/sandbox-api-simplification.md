# Sandbox API Simplification

## Goals

1. **Single-layer configuration** — all sandbox config lives on `SandboxFilesystemSpec`; remove `SandboxDistributedOptions`
2. **Remove `SandboxStateStore` interface** — always use `SessionSandboxStateStore` (backed by `AgentStateStore`)
3. **Default `IsolationScope` → USER** with SESSION fallback

## Before / After

### Before (3 layers)
```java
HarnessAgent.builder()
    .stateStore(redisStateStore)                          // layer 1
    .filesystem(new DockerFilesystemSpec()
        .isolationScope(IsolationScope.USER)               // layer 2
        .executionGuard(guard))
    .sandboxDistributed(SandboxDistributedOptions.builder() // layer 3
        .stateStore(redisStateStore)
        .snapshotSpec(ossSnapshot)
        .requireDistributed(true)
        .build())
    .build();
```

### After (1 layer)
```java
HarnessAgent.builder()
    .stateStore(redisStateStore)
    .filesystem(new DockerFilesystemSpec()
        .image("python:3.12")
        .snapshotSpec(ossSnapshot)
        .executionGuard(guard))
    // IsolationScope defaults to USER — no need to set
    // SandboxStateStore auto-created from AgentStateStore — no need to configure
    .build();
```

## Detailed Changes

### Phase A: Delete `SandboxDistributedOptions`

| Action | File | Detail |
|--------|------|--------|
| DELETE | `SandboxDistributedOptions.java` | Entire class |
| MODIFY | `HarnessAgent.Builder` | Remove `sandboxDistributedOptions` field, `sandboxDistributed()` method |
| MODIFY | `HarnessAgent.Builder.build()` | Remove all `sandboxDistributedOptions` branches in sandbox integration block; `effectiveSession` simply = `stateStoreOverride` |
| MODIFY | `HarnessAgentBuilderSupport.validateDistributedSandboxConfig()` | Delete method entirely |
| MODIFY | `HarnessAgent.build()` validation | Remove fail-fast for local AgentStateStore + sandbox mode; replace with `log.warn` |
| MODIFY | `HarnessAgent.build()` validation | Remove fail-fast for noop snapshot; replace with `log.warn` |
| MODIFY | `BuilderSandboxConfig.java` (claw) | Delete `sandboxDistributedOptions` bean; `snapshotSpec` configured on `DockerFilesystemSpec` directly |
| MODIFY | `CodingBootstrap.java` (codingagent) | Remove `.sandboxDistributed(...)` call |
| MODIFY | `HarnessAgentDistributedSandboxTest.java` | Rewrite tests for new API |
| MODIFY | `SandboxFilesystemIsolationScopeExampleTest.java` | Remove `.sandboxDistributed(...)` calls |

### Phase B: Delete `SandboxStateStore` interface

| Action | File | Detail |
|--------|------|--------|
| DELETE | `SandboxStateStore.java` | Interface |
| DELETE | `WorkspaceSandboxStateStore.java` | Filesystem-backed implementation |
| DELETE | `WorkspaceSandboxStateStoreTest.java` | Test |
| MODIFY | `SandboxFilesystemSpec` | Remove `sandboxStateStore` field, `sandboxStateStore()` setter, `getSandboxStateStore()` getter |
| MODIFY | `SandboxManager` | Change constructor: `SandboxStateStore` → `SessionSandboxStateStore` |
| MODIFY | `SessionSandboxStateStore` | Remove `implements SandboxStateStore`, keep as standalone class with same `load/save/delete` methods |
| MODIFY | `HarnessAgent.Builder.build()` | Remove conditional: always `new SessionSandboxStateStore(effectiveSession, resolvedAgentId)` |
| MODIFY | `SandboxManagerIsolationTest` | Update mock from `SandboxStateStore` to `SessionSandboxStateStore` |

### Phase C: IsolationScope default → USER with SESSION fallback

| Action | File | Detail |
|--------|------|--------|
| MODIFY | `SandboxIsolationKey.resolve()` | Change `scope == null` from `SESSION` to `USER` |
| MODIFY | `SandboxIsolationKey.resolve()` USER branch | When userId is absent: **fall back to SESSION** (try sessionId) instead of returning empty |
| MODIFY | `IsolationScope` javadoc | Document that USER is the default; SESSION is the fallback |
| MODIFY | `SandboxIsolationKeyTest` | Update expected defaults |
| MODIFY | `SandboxManagerIsolationTest` | Update expected scope resolution |

#### `SandboxIsolationKey.resolve()` new logic (pseudocode):

```java
IsolationScope effective = scope != null ? scope : IsolationScope.USER;
return switch (effective) {
    case USER -> {
        if (hasUserId(ctx))     yield key(USER, userId);
        if (hasSessionId(ctx))  yield key(SESSION, sessionId); // fallback
        log.warn("USER scope: neither userId nor sessionId present");
        yield Optional.empty();
    }
    case SESSION -> { /* unchanged */ }
    case AGENT   -> { /* unchanged */ }
    case GLOBAL  -> { /* unchanged */ }
};
```

## Validation Changes

### Current (fail-fast, strict)
- Local AgentStateStore + sandbox mode → **throws**
- Noop snapshot + sandbox mode → **throws**

### New (warn, permissive)
- Local AgentStateStore + sandbox mode → **log.warn** ("sandbox state will not survive JVM restart")
- Noop snapshot + sandbox mode → **log.warn** ("workspace will be re-initialized each call")

Single-node dev works out of the box; distributed prod gets the right warnings in logs.

## Files Summary

### Delete (5 files)
1. `sandbox/SandboxDistributedOptions.java`
2. `sandbox/SandboxStateStore.java`
3. `sandbox/WorkspaceSandboxStateStore.java`
4. test: `WorkspaceSandboxStateStoreTest.java`
5. `HarnessAgentBuilderSupport.validateDistributedSandboxConfig()` (method, not file)

### Modify (main, ~10 files)
1. `HarnessAgent.java` (Builder: field, method, build() logic)
2. `HarnessAgentBuilderSupport.java` (remove validation method)
3. `SandboxFilesystemSpec.java` (remove sandboxStateStore)
4. `SandboxManager.java` (constructor type)
5. `SessionSandboxStateStore.java` (remove implements)
6. `SandboxIsolationKey.java` (default scope + fallback)
7. `IsolationScope.java` (javadoc)
8. `BuilderSandboxConfig.java` (claw example)
9. `CodingBootstrap.java` (codingagent example)

### Modify (test, ~4 files)
1. `HarnessAgentDistributedSandboxTest.java`
2. `SandboxFilesystemIsolationScopeExampleTest.java`
3. `SandboxManagerIsolationTest.java`
4. `SandboxIsolationKeyTest.java`
