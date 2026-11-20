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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.journal.JournalException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.memory.MemoryAdmission;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/**
 * Extracts memories after each eligible call. Native journals supply only unconsumed original
 * messages; legacy execution uses the current context. Standalone calls queue background work,
 * while hosted executions finish extraction inside the recoverable finalization boundary.
 * Per-isolation-key queues serialize local writes and coalesce pending calls of one conversation.
 */
public class MemoryFlushMiddleware implements HarnessRuntimeMiddleware {

    /** Hosted executions finish extraction while their native writer and authorization remain valid. */
    public static final String INLINE_CONTEXT_KEY = "agentscope.memory.inlineFlush";

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final MemoryAdmission admission;
    private final String flushPrompt;
    private final MemoryConfig.FlushTrigger flushTrigger;
    private final IsolationScope isolationScope;
    private final PeriodicGate periodicGate;
    private final Duration flushTimeout;

    // Diagnostic deduplication only; eviction or restart may report a Thread again.
    private static final Map<String, Class<?>> FLUSH_WARNINGS =
            Collections.synchronizedMap(
                    new LinkedHashMap<>() {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
                            return size() > 256;
                        }
                    });

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
        this(
                workspaceManager,
                model,
                flushPrompt,
                flushTrigger,
                isolationScope,
                periodicGate,
                null);
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger,
            IsolationScope isolationScope,
            PeriodicGate periodicGate,
            MemoryAdmission admission) {
        this(
                workspaceManager,
                model,
                flushPrompt,
                flushTrigger,
                isolationScope,
                periodicGate,
                admission,
                MemoryConfig.DEFAULT_FLUSH_TIMEOUT);
    }

    public MemoryFlushMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryConfig.FlushTrigger flushTrigger,
            IsolationScope isolationScope,
            PeriodicGate periodicGate,
            MemoryAdmission admission,
            Duration flushTimeout) {
        if (flushTimeout == null || flushTimeout.isZero() || flushTimeout.isNegative())
            throw new IllegalArgumentException("flushTimeout must be positive");
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.admission = admission;
        this.flushPrompt =
                flushPrompt != null ? flushPrompt : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
        this.flushTrigger =
                flushTrigger != null ? flushTrigger : MemoryConfig.FlushTrigger.always();
        this.isolationScope = isolationScope != null ? isolationScope : IsolationScope.USER;
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
        this.flushTimeout = flushTimeout;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT, ExtensionPoint.ON_AGENT_FINISH);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        if (Boolean.TRUE.equals(rc.get(INLINE_CONTEXT_KEY))) return next.apply(input);
        // Capture the completing subscription's context so the fire-and-forget flush can replay
        // context-scoped instrumentation such as token usage and tracing.
        return next.apply(input)
                .doOnEach(
                        signal -> {
                            if (signal.isOnComplete()) {
                                scheduleFlush(agent, rc, signal.getContextView());
                            }
                        });
    }

    @Override
    public Mono<Void> onAgentFinish(Agent agent, RuntimeContext rc, Msg result) {
        InvocationRecorder recorder = InvocationRecorder.from(rc);
        if (recorder == null) return Mono.empty();
        return Mono.defer(
                        () -> {
                            AgentState state = RuntimeContext.resolveAgentState(rc, agent);
                            if (state != null) recorder.captureMessages(state.getContext());
                            if (result != null) recorder.captureMessages(List.of(result));
                            return recorder.flush();
                        })
                .then(
                        Mono.defer(
                                () ->
                                        Boolean.TRUE.equals(rc.get(INLINE_CONTEXT_KEY))
                                                        && RunOutcome.fromResult(result).status()
                                                                == RunOutcome.Status.COMPLETED
                                                ? doFlush(agent, rc)
                                                : Mono.empty()))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(context -> context.put(AgentBase.RUNTIME_CONTEXT_KEY, rc));
    }

    private void scheduleFlush(Agent agent, RuntimeContext rc, ContextView contextView) {
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
                        agent,
                        blankToEmpty(rc.getUserId()),
                        blankToEmpty(rc.getSessionId()),
                        rc.getThreadId());
        Runnable task = () -> runFlush(key, agent, rc, contextView);
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

    private void runFlush(String key, Agent agent, RuntimeContext rc, ContextView contextView) {
        Mono.defer(() -> doFlush(agent, rc))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(context -> context.putAll(contextView))
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
        boolean nativeJournal = agent instanceof ReActAgent react && react.journalEnabled();
        List<Msg> messages = List.of();
        if (!nativeJournal) {
            // Legacy queued work reads the latest context; native work reads its journal.
            AgentState state = RuntimeContext.resolveAgentState(rc, agent);
            if (state == null || state.getContext().isEmpty()) return Mono.empty();
            messages = new ArrayList<>(state.getContext());
        }
        boolean enabled = shouldFlushNow(rc);
        if (!enabled && !(nativeJournal && Boolean.TRUE.equals(rc.get(INLINE_CONTEXT_KEY)))) {
            log.debug("Memory flush skipped (trigger={})", flushTrigger);
            return Mono.empty();
        }

        Model effective = effectiveFlushModel(agent, rc);
        if (effective == null) {
            // No dedicated model and the agent cannot resolve a per-call one (non-ReActAgent
            // direct construction): nothing to extract memories with.
            return Mono.empty();
        }
        MemoryFlushManager flushManager =
                new MemoryFlushManager(
                        workspaceManager, effective, flushPrompt, admission, flushTimeout);
        Mono<Void> flush =
                nativeJournal
                        ? flushManager.flushJournal(
                                rc,
                                ((ReActAgent) agent).threadKey(rc),
                                ((ReActAgent) agent).threadJournal(rc),
                                enabled)
                        : flushManager.flushMemories(rc, messages);
        String thread =
                nativeJournal
                        ? ((ReActAgent) agent).threadKey(rc).storagePath()
                        : Objects.toString(rc.getSessionId(), "anonymous");
        String warningKey =
                List.of(blankToEmpty(rc.getUserId()), thread, blankToEmpty(rc.getThreadId()))
                        .toString();
        return flush.doOnSuccess(
                        v -> {
                            FLUSH_WARNINGS.remove(warningKey);
                            log.debug("Memory flush completed");
                        })
                .onErrorResume(
                        e -> {
                            if (Boolean.TRUE.equals(rc.get(INLINE_CONTEXT_KEY))
                                    && JournalException.causedBy(e)) return Mono.error(e);
                            if (FLUSH_WARNINGS.put(warningKey, e.getClass()) != e.getClass())
                                log.warn(
                                        "Memory flush failed (thread={}): {}",
                                        thread,
                                        e.getMessage());
                            else
                                log.debug(
                                        "Memory flush still failing (thread={}): {}",
                                        thread,
                                        e.getMessage());
                            return Mono.empty();
                        });
    }

    /**
     * Resolves the model used for memory extraction: a dedicated flush model when configured,
     * else the executing {@link ReActAgent}'s per-call model (its {@link RuntimeContext} model id
     * when set, else its build-time default), so flush follows the model actually serving the
     * conversation. Package-private for unit testing.
     */
    Model effectiveFlushModel(Agent agent, RuntimeContext rc) {
        if (model != null) {
            return model;
        }
        return agent instanceof ReActAgent react ? react.getModel(rc) : null;
    }

    /** Applies the configured per-isolation-key trigger. */
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

    /** Thread-private context; sibling Threads may share memory files but not queued flush work. */
    private record ConversationKey(Agent agent, String userId, String sessionId, String threadId) {}

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
