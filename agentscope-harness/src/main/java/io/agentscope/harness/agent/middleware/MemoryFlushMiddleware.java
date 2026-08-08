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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that triggers memory flush and message offload at the end of each agent call.
 *
 * <p>Runs in a genuinely detached, fire-and-forget fashion: the flush {@code Mono} is subscribed
 * independently of the returned {@code Flux} (via {@code doOnComplete}) rather than being
 * concatenated onto it, so callers that wait for the response to complete (e.g.
 * {@code blockLast()}, {@code takeLast(1)}) are not delayed by flush work. Long-term memories are
 * extracted and persisted after every call, even when conversation compaction was not triggered
 * during that call. When {@link CompactionMiddleware} is active, it handles flush/offload for the
 * messages it summarizes; this middleware covers the remaining tail of messages that were kept
 * verbatim.
 *
 * <p>Flush is gated by a {@link MemoryConfig.FlushTrigger}:
 * <ul>
 *   <li>{@link MemoryConfig.FlushMode#ALWAYS} (default) — flush after every call.</li>
 *   <li>{@link MemoryConfig.FlushMode#NEVER} — never flush via this middleware. The CompactionMiddleware
 *       and overflow-recovery paths still run their own flush when they fire.</li>
 *   <li>{@link MemoryConfig.FlushMode#THROTTLED} — flush at most once per
 *       {@link MemoryConfig.FlushTrigger#minGap()}.</li>
 * </ul>
 *
 * <p>Session transcript append is <b>not</b> handled here — see {@link TranscriptMiddleware},
 * which runs independently of memory flush so history stays complete even when flush is
 * disabled.
 *
 * <p>The throttle window is tracked per <em>isolation key</em>, which matches the memory data
 * isolation in use:
 * <ul>
 *   <li>{@link IsolationScope#USER} (default) — one window per {@code userId}.</li>
 *   <li>{@link IsolationScope#SESSION} — one window per {@code sessionId}.</li>
 *   <li>{@link IsolationScope#AGENT} / {@link IsolationScope#GLOBAL} — one shared window for
 *       the whole agent instance (prevents concurrent flush races on shared memory files).</li>
 * </ul>
 */
public class MemoryFlushMiddleware implements HarnessRuntimeMiddleware, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final String flushPrompt;
    private final MemoryConfig.FlushTrigger flushTrigger;
    private final IsolationScope isolationScope;
    private final PeriodicGate periodicGate;

    /** Upper bound on the flush LLM call, preventing a hung model from tying up a worker thread. */
    static final Duration FLUSH_TIMEOUT = Duration.ofMinutes(5);

    /** Upper bound {@link #close()} waits for outstanding fire-and-forget flushes to drain. */
    static final Duration CLOSE_AWAIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Tracks the {@link Disposable} of every fire-and-forget flush subscription that has been
     * scheduled but not yet finished, so {@link #close()} can wait for them instead of leaving
     * them racing against teardown of the workspace resources they read/write.
     */
    private final Set<Disposable> pending = ConcurrentHashMap.newKeySet();

    private volatile boolean closed = false;

    public MemoryFlushMiddleware(WorkspaceManager workspaceManager, Model model) {
        this(
                workspaceManager,
                model,
                MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                MemoryConfig.FlushTrigger.always(),
                IsolationScope.USER,
                new LocalPeriodicGate());
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger) {
        this(
                workspaceManager,
                model,
                flushPrompt,
                flushTrigger,
                IsolationScope.USER,
                new LocalPeriodicGate());
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger,
            IsolationScope isolationScope) {
        this(
                workspaceManager,
                model,
                flushPrompt,
                flushTrigger,
                isolationScope,
                new LocalPeriodicGate());
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger,
            IsolationScope isolationScope,
            PeriodicGate periodicGate) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.flushPrompt =
                flushPrompt != null ? flushPrompt : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
        this.flushTrigger =
                flushTrigger != null ? flushTrigger : MemoryConfig.FlushTrigger.always();
        this.isolationScope = isolationScope != null ? isolationScope : IsolationScope.USER;
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        return next.apply(input).doOnComplete(() -> scheduleFlush(agent, rc));
    }

    /**
     * Fires the fire-and-forget flush {@code Mono} on {@code boundedElastic}, tracking its
     * {@link Disposable} in {@link #pending} until it terminates so {@link #close()} can wait for
     * it. No-ops once {@link #close()} has been called, so a call that races with shutdown
     * doesn't spawn new untracked work.
     *
     * <p>The snapshot capture (including a shallow copy of the RuntimeContext and a copy of the
     * messages) is wrapped in try/catch so that any exception (e.g. from
     * {@code List.copyOf} or {@code resolveAgentState}) does not escape into the {@code
     * doOnComplete} callback and turn an already-completed Flux into an error.
     *
     * <p>The {@code closed} flag and {@code pending} add are guarded by
     * {@code synchronized(pending)}. The {@code pending} set (a ConcurrentHashMap key set) is
     * reused as the mutex object to avoid allocating a separate lock; its own concurrency
     * features are not relied upon for the closed-check/add atomicity.
     */
    private void scheduleFlush(Agent agent, RuntimeContext rc) {
        FlushRequest request;
        try {
            request = captureFlushRequest(agent, rc);
        } catch (Exception e) {
            log.warn("Failed to capture flush request: {}", e.getMessage());
            return;
        }
        if (request == null) {
            return;
        }
        synchronized (pending) {
            if (closed) {
                return;
            }
        }
        Disposable[] holder = new Disposable[1];
        Disposable d =
                Mono.defer(() -> doFlush(request))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(FLUSH_TIMEOUT)
                        .onErrorResume(
                                e -> {
                                    log.warn("Memory flush failed: {}", e.getMessage());
                                    return Mono.empty();
                                })
                        .doFinally(
                                sig -> {
                                    if (holder[0] != null) {
                                        pending.remove(holder[0]);
                                    }
                                })
                        .subscribe();
        holder[0] = d;
        synchronized (pending) {
            if (closed) {
                d.dispose();
                return;
            }
            pending.add(d);
        }
    }

    private FlushRequest captureFlushRequest(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return null;
        }
        List<Msg> messages = state.getContext();
        if (messages.isEmpty()) {
            return null;
        }
        return new FlushRequest(RuntimeContext.builder().from(rc).build(), List.copyOf(messages));
    }

    private Mono<Void> doFlush(FlushRequest request) {
        RuntimeContext rc = request.runtimeContext();
        List<Msg> messages = request.messages();
        MemoryFlushManager flushManager =
                new MemoryFlushManager(workspaceManager, model, flushPrompt);

        boolean shouldFlush = shouldFlushNow(rc);
        Mono<Void> flushMono;
        if (shouldFlush) {
            flushMono =
                    flushManager
                            .flushMemories(rc, messages)
                            .doOnSuccess(v -> log.debug("Memory flush completed"))
                            .onErrorResume(
                                    e -> {
                                        log.warn("Memory flush failed: {}", e.getMessage());
                                        return Mono.empty();
                                    });
        } else {
            log.debug("Memory flush skipped (trigger={})", flushTrigger);
            flushMono = Mono.empty();
        }
        return flushMono;
    }

    /**
     * Waits (bounded by {@link #CLOSE_AWAIT_TIMEOUT}) for outstanding fire-and-forget flushes to
     * finish, then disposes anything still outstanding. Intended to be called from {@code
     * HarnessAgent#close()} so short-lived callers (tests using JUnit {@code @TempDir}, CLI runs,
     * etc.) don't tear down the workspace while a detached flush write is still in flight.
     *
     * <p>The {@code closed} flag and {@code pending} add are guarded by {@code synchronized(pending)}
     * so that a flush scheduled concurrently with close() is either fully tracked (and drained) or
     * disposed immediately, but never lost.
     */
    @Override
    public void close() {
        synchronized (pending) {
            closed = true;
        }
        long deadline = System.nanoTime() + CLOSE_AWAIT_TIMEOUT.toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Disposable d : pending) {
            d.dispose();
        }
        pending.clear();
    }

    private record FlushRequest(RuntimeContext runtimeContext, List<Msg> messages) {}

    /**
     * Returns whether any fire-and-forget flush is currently in flight. Package-private, intended
     * for tests that need to poll for quiescence instead of relying on a fixed sleep.
     */
    boolean hasPendingFlushes() {
        return !pending.isEmpty();
    }

    /**
     * Returns whether this call should trigger a flush, applying the configured trigger policy.
     * For {@link MemoryConfig.FlushMode#THROTTLED}, uses an {@link AtomicReference#compareAndSet}
     * race to ensure at most one caller within {@code minGap} wins the slot.
     *
     * <p>The throttle window is keyed by the isolation dimension that matches the memory data
     * namespace (see {@link #timerKeyFor(RuntimeContext)}).
     *
     * <p>Package-private for unit testing of the trigger gate without standing up a full
     * {@code Agent}.
     */
    boolean shouldFlushNow(RuntimeContext rc) {
        switch (flushTrigger.mode()) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case THROTTLED:
                return periodicGate.tryClaim(compositeTimerKey(rc), flushTrigger.minGap());
            default:
                return true;
        }
    }

    /**
     * Builds a composite key from {@link IsolationScope} name and the per-call identity returned
     * by {@link #timerKeyFor(RuntimeContext)}. The scope prefix ensures that throttle windows
     * from different isolation dimensions are never conflated — e.g. a {@code userId} that
     * happens to equal a {@code sessionId} must not share a slot.
     */
    private String compositeTimerKey(RuntimeContext rc) {
        return isolationScope.name() + ":" + timerKeyFor(rc);
    }

    /**
     * Derives the per-call identity portion of the composite timer key from the configured
     * {@link IsolationScope} and the {@link RuntimeContext}, mirroring the memory data
     * namespace:
     * <ul>
     *   <li>{@link IsolationScope#USER} — {@code userId} (empty string for anonymous)</li>
     *   <li>{@link IsolationScope#SESSION} — {@code sessionId} (empty string when absent)</li>
     *   <li>{@link IsolationScope#AGENT} / {@link IsolationScope#GLOBAL} — constant {@code ""}
     *       so all callers share one throttle slot, serialising flushes on shared memory files</li>
     * </ul>
     */
    String timerKeyFor(RuntimeContext rc) {
        return switch (isolationScope) {
            case USER -> {
                String uid = rc != null ? rc.getUserId() : null;
                yield (uid != null && !uid.isBlank()) ? uid : "";
            }
            case SESSION -> {
                String sid = rc != null ? rc.getSessionId() : null;
                yield (sid != null && !sid.isBlank()) ? sid : "";
            }
            case AGENT, GLOBAL -> "";
        };
    }
}
