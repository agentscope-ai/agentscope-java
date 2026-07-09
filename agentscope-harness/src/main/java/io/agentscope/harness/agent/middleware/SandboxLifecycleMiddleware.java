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
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Middleware that manages the sandbox session lifecycle around each agent call.
 *
 * <h2>Pre-{@code next.apply}</h2>
 * <ol>
 *   <li>Read {@link SandboxContext} from the current {@link RuntimeContext}</li>
 *   <li>Acquire a session via {@link SandboxManager}</li>
 *   <li>Start the session (4-branch workspace init)</li>
 *   <li>Inject the live session into the {@link SandboxBackedFilesystem} proxy</li>
 * </ol>
 *
 * <h2>doFinally</h2>
 * <ol>
 *   <li>Persist sandbox session state via {@link SandboxManager} and
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore}</li>
 *   <li>Release the session via {@link SandboxManager} (stop + optional shutdown)</li>
 *   <li>Clear the session reference from the filesystem proxy</li>
 * </ol>
 *
 * <p>Post-call failures (persist, release) are logged but do not propagate — this ensures
 * the agent call result is always returned to the caller even if sandbox cleanup fails.
 */
public class SandboxLifecycleMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);

    private final SandboxManager sandboxManager;
    private final SandboxBackedFilesystem filesystemProxy;
    private final AtomicReference<SandboxAcquireResult> currentAcquireResult =
            new AtomicReference<>();
    private volatile Consumer<RuntimeContext> beforeStartCallback;
    private final ConcurrentHashMap<String, SandboxAcquireResult> acquireResults =
            new ConcurrentHashMap<>();
    // Per-session async serialization gate — ensures same-session acquire/release pairs
    // do not overlap even when HarnessAgent.wrappedCall is subscribed concurrently.
    private final ConcurrentHashMap<String, Mono<Void>> sandboxGates = new ConcurrentHashMap<>();

    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
    }

    /**
     * Registers a callback that runs after the sandbox session is acquired but before
     * {@link io.agentscope.harness.agent.sandbox.Sandbox#start()} applies workspace projection.
     * This allows callers to materialise resources on the host workspace (e.g.
     * {@code .skills-cache/}) so that projection picks them up in the same call.
     *
     * @param callback receives the per-call {@link RuntimeContext}; may be {@code null} to clear
     */
    public void setBeforeStartCallback(Consumer<RuntimeContext> callback) {
        this.beforeStartCallback = callback;
    }


    // ==================== Serialized call wrappers ====================

    /**
     * Wraps a {@code Mono<Msg>} call with serialized sandbox acquire/release.
     *
     * <p>Same-session calls are queued so that the second subscription waits (non-blocking)
     * until the first completes and releases its sandbox, preventing concurrent overwrites
     * of the filesystem binding map.
     */
    public Mono<Msg> serializedCall(RuntimeContext ctx, Supplier<Mono<Msg>> inner) {
        SandboxContext sandboxContext = ctx != null ? ctx.get(SandboxContext.class) : null;
        String sessionKey = ctx != null ? bindingKey(ctx) : null;
        if (sessionKey == null || sandboxContext == null) {
            return inner.get();
        }
        return Mono.defer(
                () -> {
                    Sinks.Empty<Void> release = Sinks.empty();
                    Mono<Void> releaseMono = release.asMono();
                    @SuppressWarnings("unchecked")
                    Mono<Void>[] prev = new Mono[1];
                    sandboxGates.compute(
                            sessionKey,
                            (k, tail) -> {
                                prev[0] = tail == null ? Mono.empty() : tail;
                                return releaseMono;
                            });
                    return prev[0].onErrorComplete()
                            .then(
                                    Mono.using(
                                            () -> {
                                                doAcquire(ctx, sandboxContext, sessionKey);
                                                return ctx;
                                            },
                                            c -> inner.get(),
                                            this::doRelease))
                            .doFinally(
                                    sig -> {
                                        release.tryEmitEmpty();
                                        sandboxGates.remove(sessionKey, releaseMono);
                                    });
                });
    }

    /**
     * Wraps a {@code Flux<T>} stream with serialized sandbox acquire/release.
     *
     * @see #serializedCall(RuntimeContext, Supplier)
     */
    public <T> Flux<T> serializedFlux(RuntimeContext ctx, Supplier<Flux<T>> inner) {
        SandboxContext sandboxContext = ctx != null ? ctx.get(SandboxContext.class) : null;
        String sessionKey = ctx != null ? bindingKey(ctx) : null;
        if (sessionKey == null || sandboxContext == null) {
            return inner.get();
        }
        return Flux.defer(
                () -> {
                    Sinks.Empty<Void> release = Sinks.empty();
                    Mono<Void> releaseMono = release.asMono();
                    @SuppressWarnings("unchecked")
                    Mono<Void>[] prev = new Mono[1];
                    sandboxGates.compute(
                            sessionKey,
                            (k, tail) -> {
                                prev[0] = tail == null ? Mono.empty() : tail;
                                return releaseMono;
                            });
                    return prev[0].onErrorComplete()
                            .thenMany(
                                    Flux.using(
                                            () -> {
                                                doAcquire(ctx, sandboxContext, sessionKey);
                                                return ctx;
                                            },
                                            c -> inner.get(),
                                            this::doRelease))
                            .doFinally(
                                    sig -> {
                                        release.tryEmitEmpty();
                                        sandboxGates.remove(sessionKey, releaseMono);
                                    });
                });
    }

    // ==================== Direct acquire/release (for tests) ====================


    /**
     * Acquires the sandbox for the current call.
     *
     * <p><b>Warning:</b> this method does NOT serialize concurrent same-session calls.
     * Production code should use {@link #serializedCall} or {@link #serializedFlux} instead.
     */
    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        String sessionKey = bindingKey(ctx);
        if (sessionKey == null) {
            return;
        }
        doAcquire(ctx, sandboxContext, sessionKey);
    }

    /**
     * Releases the sandbox after the current call.
     *
     * <p><b>Warning:</b> this method does NOT serialize concurrent same-session calls.
     * Production code should use {@link #serializedCall} or {@link #serializedFlux} instead.
     */
    public void releaseForCall(RuntimeContext ctx) {
        doRelease(ctx);
    }

    // ==================== Internal ====================

    private void doAcquire(RuntimeContext ctx, SandboxContext sandboxContext, String sessionKey) {
        try {
            Consumer<RuntimeContext> cb = beforeStartCallback;
            if (cb != null) {
                try {
                    cb.accept(ctx);
                } catch (Exception e) {
                    log.warn(
                            "[sandbox-mw] beforeStartCallback failed; proceeding with sandbox"
                                    + " start: {}",
                            e.getMessage(),
                            e);
                }
            }
            SandboxAcquireResult result = sandboxManager.acquire(sandboxContext, ctx);
            Sandbox sandbox = result.getSandbox();
            try {
                sandbox.start();
                filesystemProxy.bindSandbox(sessionKey, sandbox);
                acquireResults.put(sessionKey, result);
                log.debug(
                        "[sandbox-mw] Acquired sandbox {} for session {}",
                        sandbox.getState() != null ? sandbox.getState().getSessionId() : "?",
                        sessionKey);
            } catch (Exception e) {
                filesystemProxy.unbindSandbox(sessionKey);
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

    private void doRelease(RuntimeContext ctx) {
        String sessionKey = ctx != null ? bindingKey(ctx) : null;
        SandboxAcquireResult result = sessionKey != null ? acquireResults.remove(sessionKey) : null;
        if (result == null) {
            return;
        }
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
        filesystemProxy.unbindSandbox(sessionKey);
    }

    static String bindingKey(RuntimeContext ctx) {
        String uid = ctx.getUserId();
        String sid = ctx.getSessionId();
        if (sid == null || sid.isBlank()) {
            return null;
        }
        return (uid == null || uid.isBlank() ? "__anon__" : uid) + "/" + sid;
    }
}
