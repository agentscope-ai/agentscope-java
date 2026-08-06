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
import java.util.List;
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
 * call. When {@link CompactionMiddleware} is active, it handles flush/offload for the messages
 * it summarizes; this middleware covers the remaining tail of messages that were kept verbatim.
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
        return next.apply(input)
                .concatWith(
                        Mono.defer(() -> doFlush(agent, rc))
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorResume(
                                        e -> {
                                            log.warn("Memory flush failed: {}", e.getMessage());
                                            return Mono.empty();
                                        })
                                .then(Mono.<AgentEvent>empty()));
    }

    private Mono<Void> doFlush(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return Mono.empty();
        }
        List<Msg> messages = state.getContext();
        if (messages.isEmpty()) {
            return Mono.empty();
        }

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

        // Message offload is owned by TranscriptMiddleware (independent of memory flush).
        return flushMono;
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
