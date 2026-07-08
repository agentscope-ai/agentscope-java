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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware that manages the sandbox session lifecycle around each agent call.
 *
 * <h2>Pre-{@code next.apply}</h2>
 * <ol>
 *   <li>Read {@link SandboxContext} from the current {@link RuntimeContext}</li>
 *   <li>Acquire a session via {@link SandboxManager}</li>
 *   <li>Start the session (4-branch workspace init)</li>
 *   <li>Stash the live {@link Sandbox} and {@link SandboxAcquireResult} on the per-call
 *       {@link RuntimeContext} so {@link SandboxBackedFilesystem} can resolve it without sharing
 *       instance state across concurrent calls</li>
 * </ol>
 *
 * <h2>doFinally</h2>
 * <ol>
 *   <li>Read the {@link SandboxAcquireResult} back from the {@link RuntimeContext}</li>
 *   <li>Persist sandbox session state via {@link SandboxManager} and
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore}</li>
 *   <li>Release the session via {@link SandboxManager} (stop + optional shutdown)</li>
 *   <li>Clear the stashed entries from the {@link RuntimeContext}</li>
 * </ol>
 *
 * <p>Per-call state lives on the {@link RuntimeContext} (per-call object) rather than on this
 * middleware instance, which means a single {@code HarnessAgent} can safely serve concurrent calls
 * with different {@link SandboxContext}s without cross-call sandbox bleed-through.
 *
 * <p>Post-call failures (persist, release) are logged but do not propagate — this ensures the
 * agent call result is always returned to the caller even if sandbox cleanup fails.
 */
public class SandboxLifecycleMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);

    private final SandboxManager sandboxManager;

    public SandboxLifecycleMiddleware(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    /**
     * Acquires the sandbox for the current call. Called from
     * {@code ReActAgent.beforeAgentExecution()} to ensure the sandbox is available
     * for both the {@code call()} and {@code streamEvents()} paths.
     *
     * @param ctx the per-call RuntimeContext (must not be null)
     */
    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        try {
            SandboxAcquireResult result = sandboxManager.acquire(sandboxContext, ctx);
            Sandbox sandbox = result.getSandbox();
            try {
                sandbox.start();
                ctx.put(Sandbox.class, sandbox);
                ctx.put(SandboxAcquireResult.class, result);
                log.debug(
                        "[sandbox-mw] Acquired sandbox {}",
                        sandbox.getState() != null ? sandbox.getState().getSessionId() : "?");
            } catch (Exception e) {
                try {
                    sandboxManager.release(result);
                } catch (Exception releaseErr) {
                    log.warn(
                            "[sandbox-mw] Failed to release session after pre-call failure: {}",
                            releaseErr.getMessage(),
                            releaseErr);
                }
                result.getLease().close();
                throw e;
            }
        } catch (Exception e) {
            log.error("[sandbox-mw] Failed to acquire/start sandbox", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Releases the sandbox after the current call. Called from
     * {@code ReActAgent.afterAgentExecution()} to ensure cleanup for both paths.
     *
     * @param ctx the per-call RuntimeContext (same instance used at acquire time)
     */
    public void releaseForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxAcquireResult result = ctx.get(SandboxAcquireResult.class);
        if (result == null) {
            return;
        }
        ctx.put(SandboxAcquireResult.class, null);
        ctx.put(Sandbox.class, null);
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        try {
            sandboxManager.persistState(result, sandboxContext, ctx);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
        try {
            sandboxManager.release(result);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to release sandbox session: {}", e.getMessage(), e);
        }
        result.getLease().close();
    }
}
