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
 */
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxClient<?> client;
    private final SandboxStateStore stateStore;
    private final String agentId;

    public SandboxManager(SandboxClient<?> client, SandboxStateStore stateStore, String agentId) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    }

    public SandboxAcquireResult acquire(
            SandboxContext sandboxContext, RuntimeContext runtimeContext) throws Exception {
        if (sandboxContext.getExternalSandbox() != null) {
            Sandbox external = sandboxContext.getExternalSandbox();
            log.debug(
                    "[sandbox] Priority 1: using user-managed sandbox: {}",
                    external.getState() != null ? external.getState().getSessionId() : "?");
            return SandboxAcquireResult.userManaged(external);
        }

        if (sandboxContext.getExternalSandboxState() != null) {
            Sandbox sandbox = client.resume(sandboxContext.getExternalSandboxState());
            log.debug(
                    "[sandbox] Priority 2: resuming from explicit state: {}",
                    sandboxContext.getExternalSandboxState().getSessionId());
            return SandboxAcquireResult.selfManaged(sandbox);
        }

        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext.getIsolationScope(), runtimeContext, agentId);
        if (scopeKey.isPresent()) {
            try {
                Optional<String> stateJson = stateStore.load(scopeKey.get());
                if (stateJson.isPresent()) {
                    log.debug(
                            "[sandbox] Priority 3: resuming from persisted state (scope={})",
                            scopeKey.get());
                    SandboxState state = client.deserializeState(stateJson.get());
                    Sandbox sandbox = client.resume(state);
                    return SandboxAcquireResult.selfManaged(sandbox);
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
                        spec, sandboxContext.getSnapshotSpec(), sandboxContext.getClientOptions());
        return SandboxAcquireResult.selfManaged(sandbox);
    }

    public void release(SandboxAcquireResult result) {
        if (result == null) {
            return;
        }
        Sandbox sandbox = result.getSandbox();
        if (sandbox == null) {
            return;
        }

        try {
            sandbox.stop();
        } catch (Exception e) {
            log.warn("[sandbox] Sandbox stop failed: {}", e.getMessage(), e);
        }

        if (result.isSelfManaged()) {
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
