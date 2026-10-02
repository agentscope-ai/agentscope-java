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
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that triggers memory flush and message offload at the end of each agent call.
 *
 * <p>Runs in {@link #onAgent}'s {@code doOnComplete} so long-term memories are extracted and
 * persisted after every call, even when conversation compaction was not triggered during that
 * call. The flush is <em>fire-and-forget</em>: the agent stream completes immediately while the
 * extraction runs on a background scheduler. When {@link CompactionMiddleware} is active, it
 * handles flush/offload for the messages it summarizes; this middleware covers the remaining
 * tail of messages that were kept verbatim.
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
 * <p>Concurrent flushes for the same isolation key are serialised: at most one flush runs per
 * key at a time, and pending flushes of the same conversation coalesce into a single queued
 * task, because a queued task reads the conversation state only when it executes and the
 * newest task therefore subsumes the ones it replaces. Flushes of distinct conversations
 * under the same key are queued separately.
 *
 * <p>The class is thread-safe: instances hold only configuration, and the per-key queues live
 * in a static map and are synchronised internally.
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
public class MemoryFlushMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final String flushPrompt;
    private final MemoryConfig.FlushTrigger flushTrigger;
    private final IsolationScope isolationScope;
    private final PeriodicGate periodicGate;

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

    private void scheduleFlush(Agent agent, RuntimeContext rc) {
        // The in-flight slot is acquired at dispatch rather than inside the task: a queued
        // flush must already be counted, or a quiescence check could observe an empty
        // in-flight set while work is still pending.
        MemoryBackgroundTasks.begin();
        String key = compositeTimerKey(rc);
        // Coalescing key of the task: the conversation whose state the task reads when it
        // executes. The agent reference distinguishes agent instances serving the same user
        // and session ids, whose flushes must not displace each other.
        ConversationKey conversationKey =
                new ConversationKey(
                        agent, blankToEmpty(rc.getUserId()), blankToEmpty(rc.getSessionId()));
        Runnable task = () -> runFlush(key, agent, rc);
        Runnable[] starter = new Runnable[1];
        Runnable[] displaced = new Runnable[1];
        FLUSH_QUEUES.compute(
                key,
                (k, queue) -> {
                    FlushQueue q = queue == null ? new FlushQueue() : queue;
                    if (q.running) {
                        // A queued task reads the conversation state only when it executes, and
                        // calls of one conversation are serialised on the agent's
                        // (userId, sessionId) serialization key, so the newest task's state
                        // includes everything the replaced tasks would have read.
                        displaced[0] = q.pending.put(conversationKey, task);
                    } else {
                        q.running = true;
                        starter[0] = task;
                    }
                    return q;
                });
        if (displaced[0] != null) {
            // The replaced task will never execute; release the in-flight slot it acquired at
            // dispatch. Done outside compute to avoid running under the map's bin lock.
            MemoryBackgroundTasks.end();
        }
        if (starter[0] != null) {
            starter[0].run();
        }
    }

    private void runFlush(String key, Agent agent, RuntimeContext rc) {
        Mono.defer(() -> doFlush(agent, rc))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(
                        signal -> {
                            MemoryBackgroundTasks.end();
                            drainFlushQueue(key);
                        })
                .subscribe(null, e -> log.warn("Memory flush failed: {}", e.getMessage()));
    }

    private void drainFlushQueue(String key) {
        Runnable[] next = new Runnable[1];
        FLUSH_QUEUES.compute(
                key,
                (k, queue) -> {
                    if (queue == null) {
                        return null;
                    }
                    Iterator<Runnable> it = queue.pending.values().iterator();
                    if (it.hasNext()) {
                        next[0] = it.next();
                        it.remove();
                    }
                    if (next[0] == null) {
                        return null; // idle: evict so the map holds only active keys
                    }
                    return queue; // the dequeued task continues as the running flush
                });
        if (next[0] != null) {
            next[0].run();
        }
    }

    /**
     * Per-isolation-key pending flush tasks, keeping at most one flush in flight per memory
     * namespace. Fields are only mutated inside {@link ConcurrentHashMap#compute} (which holds
     * the per-key bin lock), so they need no additional synchronisation; entries are removed
     * as soon as a key goes idle.
     */
    private static final class FlushQueue {
        final LinkedHashMap<ConversationKey, Runnable> pending = new LinkedHashMap<>();
        boolean running;
    }

    private static final ConcurrentHashMap<String, FlushQueue> FLUSH_QUEUES =
            new ConcurrentHashMap<>();

    private Mono<Void> doFlush(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return Mono.empty();
        }
        // Read at execution time rather than scheduling time: a task that waited in the queue
        // sees everything appended in the meantime, so coalescing queued tasks never loses
        // messages.
        List<Msg> messages = new ArrayList<>(state.getContext());
        if (messages.isEmpty()) {
            return Mono.empty();
        }
        if (!shouldFlushNow(rc)) {
            log.debug("Memory flush skipped (trigger={})", flushTrigger);
            return Mono.empty();
        }

        MemoryFlushManager flushManager =
                new MemoryFlushManager(workspaceManager, model, flushPrompt);
        return flushManager
                .flushMemories(rc, messages)
                .doOnSuccess(v -> log.debug("Memory flush completed"))
                .onErrorResume(
                        e -> {
                            log.warn("Memory flush failed: {}", e.getMessage());
                            return Mono.empty();
                        });
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
     * by {@link #timerKeyFor(RuntimeContext)}. The operation prefix keeps flush independent
     * of maintenance when both use the same {@link PeriodicGate}. The scope prefix keeps
     * different isolation dimensions from sharing a slot.
     */
    private String compositeTimerKey(RuntimeContext rc) {
        return "memory-flush:" + isolationScope.name() + ":" + timerKeyFor(rc);
    }

    /** The conversation a queued flush reads when it executes: agent instance, user, session. */
    private record ConversationKey(Agent agent, String userId, String sessionId) {}

    /**
     * Normalises {@code null} and blank identifiers to {@code ""} so absent ids share one slot.
     * Package-private: shared with {@link MemoryMaintenanceMiddleware#timerKeyFor}.
     */
    static String blankToEmpty(String s) {
        return (s != null && !s.isBlank()) ? s : "";
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
            case USER -> blankToEmpty(rc != null ? rc.getUserId() : null);
            case SESSION -> blankToEmpty(rc != null ? rc.getSessionId() : null);
            case AGENT, GLOBAL -> "";
        };
    }
}
