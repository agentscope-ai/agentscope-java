/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of {@link Sandbox} instances for the current call.
 *
 * <p>Acquire priority: {@link SandboxContext#getExternalSandbox()} &gt; {@link
 * SandboxContext#getExternalSandboxState()} &gt; persisted {@link SandboxState} &gt; {@link
 * SandboxClient#create}.
 *
 * <p>When a {@link SandboxExecutionGuard} is configured, the manager acquires an execution
 * {@link SandboxLease} before sandbox resume/create for isolation keys that are present. The
 * lease is carried by the {@link SandboxAcquireResult} and closed by the caller
 * ({@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}) after {@link #release},
 * ensuring the full call window is covered.
 *
 * <p>Priority 1 (external sandbox) and Priority 2 (external sandbox state) bypass the guard,
 * since the caller is managing that sandbox externally.
 */
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxClient<?> client;
    private final SessionSandboxStateStore stateStore;
    private final String agentId;
    private final SandboxExecutionGuard executionGuard;

    public SandboxManager(
            SandboxClient<?> client, SessionSandboxStateStore stateStore, String agentId) {
        this(client, stateStore, agentId, SandboxExecutionGuard.noop());
    }

    public SandboxManager(
            SandboxClient<?> client,
            SessionSandboxStateStore stateStore,
            String agentId,
            SandboxExecutionGuard executionGuard) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        this.executionGuard =
                executionGuard != null ? executionGuard : SandboxExecutionGuard.noop();
    }

    public SandboxAcquireResult acquire(
            SandboxContext sandboxContext, RuntimeContext runtimeContext) throws Exception {
        // Priority 1: user-supplied sandbox — guard does not apply
        if (sandboxContext.getExternalSandbox() != null) {
            Sandbox external = sandboxContext.getExternalSandbox();
            log.debug(
                    "[sandbox] Priority 1: using user-managed sandbox: {}",
                    external.getState() != null ? external.getState().getSessionId() : "?");
            return SandboxAcquireResult.userManaged(external);
        }

        // Priority 2: user-supplied state — guard does not apply
        if (sandboxContext.getExternalSandboxState() != null) {
            Sandbox sandbox = client.resume(sandboxContext.getExternalSandboxState());
            log.debug(
                    "[sandbox] Priority 2: resuming from explicit state: {}",
                    sandboxContext.getExternalSandboxState().getSessionId());
            return SandboxAcquireResult.selfManaged(sandbox);
        }

        // Priority 3 / 4: harness-managed — apply guard when a scope key is present
        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext.getIsolationScope(), runtimeContext, agentId);

        SandboxLease lease = SandboxLease.noop();
        if (scopeKey.isPresent()) {
            log.debug("[sandbox] Acquiring execution guard for scope {}", scopeKey.get());
            lease = executionGuard.tryEnter(scopeKey.get());
        }

        try {
            if (scopeKey.isPresent()) {
                try {
                    Optional<String> stateJson = stateStore.load(scopeKey.get());
                    if (stateJson.isPresent()) {
                        log.debug(
                                "[sandbox] Priority 3: resuming from persisted state (scope={})",
                                scopeKey.get());
                        SandboxState state = client.deserializeState(stateJson.get());
                        Sandbox sandbox = client.resume(state);
                        return SandboxAcquireResult.selfManaged(sandbox, lease);
                    }
                } catch (Exception e) {
                    log.warn(
                            "[sandbox] Failed to load persisted state for scope {}, falling through"
                                    + " to fresh create: {}",
                            scopeKey.get(),
                            e.getMessage(),
                            e);
                }
            }

            log.debug("[sandbox] Priority 4: creating new sandbox");
            WorkspaceSpec spec =
                    sandboxContext.getWorkspaceSpec() != null
                            ? sandboxContext.getWorkspaceSpec().copy()
                            : new WorkspaceSpec();

            @SuppressWarnings("unchecked")
            SandboxClient<SandboxClientOptions> typedClient =
                    (SandboxClient<SandboxClientOptions>) client;
            Sandbox sandbox =
                    typedClient.create(
                            spec,
                            sandboxContext.getSnapshotSpec(),
                            sandboxContext.getClientOptions());
            return SandboxAcquireResult.selfManaged(sandbox, lease);

        } catch (Exception e) {
            // Guard must be released if acquire fails — the caller won't see the result
            lease.close();
            throw e;
        }
    }

    /**
     * Releases the sandbox with default behavior (no keepAlive).
     *
     * @deprecated Use {@link #release(SandboxAcquireResult, SandboxContext)} to control keepAlive.
     */
    @Deprecated
    public void release(SandboxAcquireResult result) {
        release(result, null);
    }

    /**
     * Releases the sandbox, optionally keeping it alive for reuse across calls.
     *
     * <p>When {@link SandboxContext#isKeepAlive()} is {@code true}, only {@link Sandbox#stop()}
     * (snapshot) is called — the underlying resource (container/Pod) is preserved so subsequent
     * calls can resume without cold-start latency. Otherwise both stop and shutdown are performed.
     *
     * @param result the acquire result holding the sandbox reference
     * @param sandboxContext context controlling release behavior; may be null (defaults to full
     *     shutdown)
     */
    public void release(SandboxAcquireResult result, SandboxContext sandboxContext) {
        if (result == null) {
            return;
        }
        Sandbox sandbox = result.getSandbox();
        if (sandbox == null) {
            return;
        }

        // User-managed sandboxes (Priority 1) are owned by the caller — the harness must not
        // stop/snapshot or shutdown them, since the caller relies on the sandbox staying alive
        // across multiple acquire/release cycles (e.g. a registry that reuses one container per
        // user across the browser path and successive agent turns).
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

    public void persistState(
            SandboxAcquireResult result,
            SandboxContext sandboxContext,
            RuntimeContext runtimeContext) {
        if (result == null || result.getSandbox() == null) {
            return;
        }
        // User-managed sandboxes carry their own persistence story (the registry owns the
        // lifecycle). Writing through the harness state store would double-track state and
        // could conflict with the caller's snapshot policy.
        if (!result.isSelfManaged()) {
            return;
        }
        SandboxState state = result.getSandbox().getState();
        if (state == null) {
            return;
        }

        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext != null ? sandboxContext.getIsolationScope() : null,
                        runtimeContext,
                        agentId);
        if (scopeKey.isEmpty()) {
            log.debug("[sandbox] No scope key available, skipping state persistence");
            return;
        }

        try {
            String json = client.serializeState(state);
            stateStore.save(scopeKey.get(), json);
            log.debug(
                    "[sandbox] Persisted sandbox state for scope {}: sessionId={}",
                    scopeKey.get(),
                    state.getSessionId());
        } catch (Exception e) {
            log.warn("[sandbox] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
    }

    /**
     * Archives the sandbox state for the given isolation key: loads persisted state, resumes the
     * sandbox, stops it (persisting the snapshot), shuts it down (destroying resources), deletes
     * the state from the store, and returns the final serialized state for external persistence.
     *
     * <p>Intended for out-of-band graceful teardown (e.g. session pause/timeout), where there is
     * no active {@link RuntimeContext} and no running {@code HarnessAgent} instance. Callers
     * should persist the returned JSON to their own data store (MySQL, audit log, etc.).
     *
     * @param key the isolation key identifying the state slot to archive
     * @return the final serialized sandbox state, or empty if no state was found
     */
    public Optional<String> archive(SandboxIsolationKey key) {
        try {
            Optional<String> stateJson = stateStore.load(key);
            if (stateJson.isEmpty()) {
                return Optional.empty();
            }
            SandboxState state = client.deserializeState(stateJson.get());
            Sandbox sandbox = client.resume(state);
            try {
                sandbox.stop();
            } catch (Exception e) {
                log.warn("[sandbox] Failed to stop sandbox during archive: {}", e.getMessage(), e);
            }
            try {
                sandbox.shutdown();
            } catch (Exception e) {
                log.warn(
                        "[sandbox] Failed to shutdown sandbox during archive: {}",
                        e.getMessage(),
                        e);
            }
            String finalJson = client.serializeState(sandbox.getState());
            stateStore.delete(key);
            return Optional.of(finalJson);
        } catch (Exception e) {
            log.warn("[sandbox] Failed to archive sandbox for key {}: {}", key, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Convenience method for archiving a user-scoped sandbox state.
     *
     * @param userId the user identifier
     * @return the final serialized sandbox state, or empty if no state was found
     * @see #archive(SandboxIsolationKey)
     */
    public Optional<String> archiveForUser(String userId) {
        return archive(new SandboxIsolationKey(IsolationScope.USER, userId));
    }

    /**
     * Convenience method for archiving a session-scoped sandbox state.
     *
     * @param sessionId the session identifier
     * @return the final serialized sandbox state, or empty if no state was found
     * @see #archive(SandboxIsolationKey)
     */
    public Optional<String> archiveForSession(String sessionId) {
        return archive(new SandboxIsolationKey(IsolationScope.SESSION, sessionId));
    }

    public void clearState(SandboxContext sandboxContext, RuntimeContext runtimeContext) {
        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext != null ? sandboxContext.getIsolationScope() : null,
                        runtimeContext,
                        agentId);
        if (scopeKey.isEmpty()) {
            return;
        }

        try {
            stateStore.delete(scopeKey.get());
        } catch (Exception e) {
            log.warn("[sandbox] Failed to clear sandbox state: {}", e.getMessage(), e);
        }
    }
}
