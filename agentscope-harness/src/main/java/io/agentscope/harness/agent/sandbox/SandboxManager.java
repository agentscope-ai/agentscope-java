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
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of {@link Sandbox} instances for the current call.
 *
 * <p>Acquire priority: {@link SandboxContext#getExternalSandbox()} &gt; {@link
 * SandboxContext#getExternalSandboxState()} &gt; persisted {@link SandboxState} &gt; {@link
 * SandboxClient#create}. When a persisted state exists but the resume fails, the fresh-create
 * fallback keeps the persisted snapshot id so the workspace restores from the previous archive
 * instead of silently resetting.
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
        this.agentId = requireConsistentAgentId(stateStore, agentId);
        this.executionGuard =
                executionGuard != null ? executionGuard : SandboxExecutionGuard.noop();
    }

    /**
     * The execution guard and the persisted slot must be scoped by the <b>same</b> agent id:
     * {@link SandboxManager} uses it for the guard key (see
     * {@link SandboxIsolationKey#forExecutionGuard}), while {@link SessionSandboxStateStore}
     * uses it to compose the slot ({@code sandbox/user/{agentId}/{userId}},
     * {@code sandbox/agent/{agentId}}). If the two ever diverge, equal slots no longer imply
     * equal locks and the guard silently stops serialising the right executions — so fail at
     * build time rather than at first acquire.
     *
     * <p>Also rejects a null/blank agentId here: a blank id would make the guard-key derivation
     * throw on the first call — including on the default {@code noop()} guard path, where a
     * configuration that previously worked would suddenly fail. Failing in the constructor
     * instead moves that to build time.
     *
     * @param stateStore the store whose agent id must match
     * @param agentId the agent id supplied for guard-key derivation
     * @return the validated agent id
     */
    private static String requireConsistentAgentId(
            SessionSandboxStateStore stateStore, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException(
                    "agentId must not be null or blank: guard-key derivation for the USER scope"
                            + " requires it, and failing at first acquire is harder to diagnose"
                            + " than failing here");
        }
        String storeAgentId = stateStore.getAgentId();
        // getAgentId() returns a final field the store's constructor requires to be non-null,
        // so the equality check below holds unconditionally.
        if (!agentId.equals(storeAgentId)) {
            throw new IllegalArgumentException(
                    "agentId '"
                            + agentId
                            + "' does not match the state store's agent id '"
                            + storeAgentId
                            + "' — guard keys and persisted slots would be scoped by different"
                            + " agents, and equal slots would no longer imply equal locks");
        }
        return agentId;
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
            // The persisted slot key and the guard key differ for USER scope: the slot is
            // sandbox/user/{agentId}/{userId}, so the guard must discriminate by agent too.
            // Otherwise a subagent spawned inside its parent's call window would request the
            // key its parent holds for the whole call and deadlock.
            SandboxIsolationKey guardKey = scopeKey.get().forExecutionGuard(agentId);
            log.debug(
                    "[sandbox] Acquiring execution guard for scope {} (guard key {})",
                    scopeKey.get(),
                    guardKey);
            lease = executionGuard.tryEnter(guardKey);
        }

        try {
            String persistedSnapshotId = null;
            if (scopeKey.isPresent()) {
                try {
                    Optional<String> stateJson = stateStore.load(scopeKey.get());
                    if (stateJson.isPresent()) {
                        log.debug(
                                "[sandbox] Priority 3: resuming from persisted state (scope={})",
                                scopeKey.get());
                        SandboxState state =
                                client.deserializeState(
                                        stateJson.get(), sandboxContext.getSnapshotSpec());
                        // Overwrite stale WorkspaceSpec with current application config
                        if (sandboxContext.getWorkspaceSpec() != null) {
                            state.setWorkspaceSpec(sandboxContext.getWorkspaceSpec().copy());
                        }
                        if (state.getSnapshot() != null) {
                            persistedSnapshotId = state.getSnapshot().getId();
                        }
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
            carryOverPersistedSnapshotId(
                    sandbox, persistedSnapshotId, sandboxContext.getSnapshotSpec());
            return SandboxAcquireResult.selfManaged(sandbox, lease);

        } catch (Exception e) {
            // Guard must be released if acquire fails — the caller won't see the result
            lease.close();
            throw e;
        }
    }

    /**
     * Keeps workspace continuity when a failed resume falls through to a fresh create: points
     * the new sandbox at the snapshot id persisted for this scope, so {@link Sandbox#start()}
     * restores the workspace from the previous archive instead of silently resetting it, and
     * {@link Sandbox#stop()} overwrites that same archive instead of orphaning it. The snapshot
     * is rebuilt from the current spec, so it carries a bound client regardless of how the
     * persisted state deserialized.
     */
    private static void carryOverPersistedSnapshotId(
            Sandbox sandbox, String persistedSnapshotId, SandboxSnapshotSpec snapshotSpec) {
        SandboxState state = sandbox.getState();
        if (persistedSnapshotId == null
                || persistedSnapshotId.isBlank()
                || snapshotSpec == null
                || state == null
                || state.getSnapshot() == null
                || persistedSnapshotId.equals(state.getSnapshot().getId())) {
            return;
        }
        log.info(
                "[sandbox] Resume fell back to fresh create; carrying over snapshot id {} (fresh"
                        + " id was {})",
                persistedSnapshotId,
                state.getSnapshot().getId());
        state.setSnapshot(snapshotSpec.build(persistedSnapshotId));
    }

    public void release(SandboxAcquireResult result) {
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

        try {
            sandbox.shutdown();
        } catch (Exception e) {
            log.warn("[sandbox] Sandbox shutdown failed: {}", e.getMessage(), e);
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
