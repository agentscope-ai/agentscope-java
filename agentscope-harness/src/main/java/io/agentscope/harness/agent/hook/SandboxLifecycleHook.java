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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that manages the sandbox session lifecycle around each agent call.
 *
 * <h2>PreCallEvent</h2>
 * <ol>
 *   <li>Read {@link SandboxContext} from the current {@link RuntimeContext}</li>
 *   <li>Acquire a session via {@link SandboxManager}</li>
 *   <li>Start the session (4-branch workspace init)</li>
 *   <li>Inject the live session into the {@link SandboxBackedFilesystem} proxy</li>
 * </ol>
 *
 * <h2>PostCallEvent / ErrorEvent</h2>
 * <ol>
 *   <li>Persist sandbox session state via {@link SandboxManager} and {@link io.agentscope.harness.agent.sandbox.SandboxStateStore}</li>
 *   <li>Release the session via {@link SandboxManager} (stop + optional shutdown)</li>
 *   <li>Clear the session reference from the filesystem proxy</li>
 * </ol>
 *
 * <p>Priority is set to 50 — runs before all other harness hooks (SessionPersistence at 900).
 *
 * <p>Post-call failures (persist, release) are logged but do not propagate — this ensures
 * the agent call result is always returned to the caller even if sandbox cleanup fails.
 */
public class SandboxLifecycleHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleHook.class);

    private final SandboxManager sandboxManager;
    private final SandboxBackedFilesystem filesystemProxy;

    private RuntimeContext runtimeContext;

    /**
     * Holds the acquire result between {@link PreCallEvent} and {@link PostCallEvent}.
     *
     * <p>Not {@link ThreadLocal}: Reactor may resume hook stages on different threads, so a
     * thread-local would drop the handle and skip persist/release. A typical {@link
     * io.agentscope.harness.agent.HarnessAgent} rejects concurrent calls per agent, so a single
     * {@link AtomicReference} is sufficient.
     */
    private final AtomicReference<SandboxAcquireResult> currentAcquireResult =
            new AtomicReference<>();

    /**
     * Creates the hook.
     *
     * @param sandboxManager the manager responsible for session acquire/release
     * @param filesystemProxy the filesystem proxy that receives injected sessions
     */
    public SandboxLifecycleHook(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public void setRuntimeContext(RuntimeContext ctx) {
        this.runtimeContext = ctx;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent e) {
            return (Mono<T>) handlePreCall(e);
        } else if (event instanceof PostCallEvent e) {
            return (Mono<T>) handlePost(e);
        } else if (event instanceof ErrorEvent e) {
            return (Mono<T>) handlePost(e);
        }
        return Mono.just(event);
    }

    @SuppressWarnings("unchecked")
    private <T extends HookEvent> Mono<T> handlePreCall(T event) {
        RuntimeContext ctx = runtimeContext;
        if (ctx == null) {
            return Mono.just(event);
        }

        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return Mono.just(event);
        }

        return Mono.fromCallable(
                        () -> {
                            SandboxAcquireResult result =
                                    sandboxManager.acquire(sandboxContext, ctx);
                            Sandbox sandbox = result.getSandbox();
                            try {
                                sandbox.start();
                                filesystemProxy.setSandbox(sandbox);
                                currentAcquireResult.set(result);
                                log.debug(
                                        "[sandbox-hook] Acquired sandbox {}",
                                        sandbox.getState() != null
                                                ? sandbox.getState().getSessionId()
                                                : "?");
                            } catch (Exception e) {
                                filesystemProxy.setSandbox(null);
                                try {
                                    sandboxManager.release(result);
                                } catch (Exception releaseErr) {
                                    log.warn(
                                            "[sandbox-hook] Failed to release session after"
                                                    + " pre-call failure: {}",
                                            releaseErr.getMessage(),
                                            releaseErr);
                                }
                                throw e;
                            }
                            return event;
                        })
                .onErrorMap(
                        e -> {
                            log.error("[sandbox-hook] Failed to acquire/start sandbox", e);
                            return e;
                        });
    }

    @SuppressWarnings("unchecked")
    private <T extends HookEvent> Mono<T> handlePost(T event) {
        return Mono.fromCallable(
                () -> {
                    SandboxAcquireResult result = currentAcquireResult.getAndSet(null);

                    if (result == null) {
                        log.warn(
                                "[sandbox-hook] PostCall/ErrorEvent: no in-flight acquire result"
                                        + " (persist/release skipped — was PreCall skipped or ref"
                                        + " already consumed?)");
                        return event;
                    }

                    RuntimeContext ctx = runtimeContext;
                    SandboxContext sandboxContext =
                            ctx != null ? ctx.get(SandboxContext.class) : null;

                    // Persist state first (before release destroys workspace)
                    try {
                        sandboxManager.persistState(result, sandboxContext, ctx);
                    } catch (Exception e) {
                        log.warn(
                                "[sandbox-hook] Failed to persist sandbox state: {}",
                                e.getMessage(),
                                e);
                    }

                    // Release the session (stop + optional shutdown)
                    try {
                        sandboxManager.release(result);
                    } catch (Exception e) {
                        log.warn(
                                "[sandbox-hook] Failed to release sandbox session: {}",
                                e.getMessage(),
                                e);
                    }

                    // Clear the session reference from the filesystem proxy
                    filesystemProxy.setSandbox(null);

                    return event;
                });
    }
}
