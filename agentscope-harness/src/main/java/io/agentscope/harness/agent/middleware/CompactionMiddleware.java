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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that performs conversation compaction before each LLM reasoning call.
 *
 * <p>Fires on {@link #onReasoning}. When the compaction threshold is exceeded:
 * <ol>
 *   <li>Long-term memories are flushed from the prefix via {@link MemoryFlushManager}.</li>
 *   <li>The full conversation is offloaded to the session JSONL.</li>
 *   <li>The prefix is distilled into a structured summary via one LLM call.</li>
 *   <li>The agent's working {@link AgentState#contextMutable() context} is replaced with
 *       {@code [summaryMsg] + preservedTail}.</li>
 *   <li>The downstream {@link ReasoningInput} is rebuilt with
 *       {@code [systemMsg] + [summaryMsg] + preservedTail}.</li>
 * </ol>
 *
 * <p>When {@link CompactionConfig#getTriggerTokens()} is 0 (dynamic mode, the default), the
 * effective trigger threshold is computed as {@code model.getContextWindowSize() - reserved}.
 * If the model does not report its context window, falls back to
 * {@link CompactionConfig#FALLBACK_TRIGGER_TOKENS}.
 */
public class CompactionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(CompactionMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final CompactionConfig config;
    private final Model emergencyModel;
    private final String emergencyFlushPrompt;

    public CompactionMiddleware(
            WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this(workspaceManager, model, config, null, null);
    }

    /** Harness wiring; emergency recovery additionally requires its private call-context opt-in. */
    public CompactionMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            CompactionConfig config,
            Model emergencyModel,
            String emergencyFlushPrompt) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
        this.emergencyModel = emergencyModel;
        this.emergencyFlushPrompt = emergencyFlushPrompt;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return next.apply(input);
        }
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();

        return Flux.deferContextual(
                cv -> {
                    // Capture once while the reasoning stage owns the admitted CallExecution.
                    AgentState liveState = RuntimeContext.resolveAgentState(rc, reActAgent);
                    AtomicBoolean attempt = cv.getOrDefault(this, null);
                    InterruptControl control = cv.getOrDefault(InterruptControl.class, null);
                    AtomicBoolean partial =
                            cv.getOrDefault(ReasoningContext.class, new AtomicBoolean());
                    List<Msg> messages = input.messages();
                    Msg systemMsg = null;
                    List<Msg> conversation;
                    if (messages != null
                            && !messages.isEmpty()
                            && messages.get(0).getRole() == MsgRole.SYSTEM) {
                        systemMsg = messages.get(0);
                        conversation = new ArrayList<>(messages.subList(1, messages.size()));
                    } else {
                        conversation = messages != null ? new ArrayList<>(messages) : List.of();
                    }

                    String agentId = agent.getName();
                    String sessionId =
                            rc != null && rc.getSessionId() != null ? rc.getSessionId() : "default";

                    CompactionConfig effectiveConfig = resolveEffectiveConfig();

                    MemoryFlushManager flushManager =
                            new MemoryFlushManager(workspaceManager, model);
                    ConversationCompactor compactor =
                            new ConversationCompactor(model, flushManager);
                    final Msg sys = systemMsg;

                    // Only compaction may degrade; downstream reasoning errors must propagate.
                    return compactor
                            .compactIfNeeded(rc, conversation, effectiveConfig, agentId, sessionId)
                            .onErrorResume(
                                    error -> {
                                        if (ExceptionUtils.containsInterruptedException(error)) {
                                            return Mono.error(error);
                                        }
                                        log.warn(
                                                "Compaction failed, continuing without compaction:"
                                                        + " {}",
                                                error.getMessage());
                                        return Mono.just(Optional.empty());
                                    })
                            .map(
                                    optResult -> {
                                        if (optResult.isEmpty()) {
                                            return input;
                                        }
                                        List<Msg> compacted = optResult.get();
                                        applyToContext(liveState, compacted);
                                        log.debug(
                                                "Compacted to {} messages before reasoning",
                                                compacted.size());
                                        return rebuildInput(input, sys, compacted);
                                    })
                            .flatMapMany(
                                    prepared ->
                                            reasonWithRecovery(
                                                    rc, liveState, prepared, sys, next, attempt,
                                                    control, partial, agentId, sessionId));
                });
    }

    private Flux<AgentEvent> reasonWithRecovery(
            RuntimeContext rc,
            AgentState state,
            ReasoningInput input,
            Msg systemMsg,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            AtomicBoolean attempt,
            InterruptControl control,
            AtomicBoolean partial,
            String agentId,
            String sessionId) {
        return Flux.defer(() -> next.apply(input))
                .doOnNext(
                        event -> {
                            // A model-call start is harmless; any later event may expose partial
                            // text, thinking, a tool call, or a downstream stop request. Never hide
                            // that output by converting a failed round to a successful retry.
                            if (!(event instanceof ModelCallStartEvent)) {
                                partial.set(true);
                            }
                        })
                .onErrorResume(
                        error -> {
                            if (attempt == null
                                    || emergencyModel == null
                                    || control == null
                                    || state == null
                                    || rc.getAgentState() != state
                                    || partial.get()
                                    || ExceptionUtils.containsInterruptedException(error)
                                    || !isContextOverflowError(error)
                                    || !attempt.compareAndSet(false, true)) {
                                return Flux.error(error);
                            }
                            return emergencyCompact(rc, state, control, agentId, sessionId)
                                    .flatMapMany(
                                            result -> {
                                                if (control.isInterrupted()) {
                                                    return Flux.error(
                                                            new InterruptedException(
                                                                    "Agent execution interrupted"));
                                                }
                                                if (result.isEmpty()) {
                                                    return Flux.error(error);
                                                }
                                                // No reload or separate save: the existing call-end
                                                // and error paths persist this exact scoped state.
                                                state.contextMutable().clear();
                                                state.contextMutable().addAll(result.get());
                                                // Retry only the downstream model reasoning stage,
                                                // not normal compaction or input admission again.
                                                return Flux.defer(
                                                        () ->
                                                                next.apply(
                                                                        rebuildInput(
                                                                                input,
                                                                                systemMsg,
                                                                                result.get())));
                                            });
                        });
    }

    private Mono<Optional<List<Msg>>> emergencyCompact(
            RuntimeContext rc,
            AgentState state,
            InterruptControl control,
            String agentId,
            String sessionId) {
        // This branch's interrupt is cooperative. Observe the same scoped flag while waiting for
        // summary/flush publishers as well as immediately before replacing the working history.
        Mono<Void> interrupted =
                Flux.interval(Duration.ofMillis(25))
                        .concatMap(ignored -> checkInterrupted(control))
                        .then();
        return checkInterrupted(control)
                .then(
                        Mono.defer(
                                () -> {
                                    log.warn(
                                            "Context overflow detected, compacting in reasoning"
                                                    + " scope");
                                    List<Msg> conversation = List.copyOf(state.contextMutable());
                                    MemoryFlushManager fm =
                                            new MemoryFlushManager(
                                                    workspaceManager,
                                                    emergencyModel,
                                                    emergencyFlushPrompt);
                                    return new ConversationCompactor(emergencyModel, fm)
                                            .compactIfNeeded(
                                                    rc,
                                                    conversation,
                                                    CompactionConfig.builder()
                                                            .triggerMessages(1)
                                                            .build(),
                                                    agentId,
                                                    sessionId);
                                }))
                .subscribeOn(Schedulers.boundedElastic())
                // firstWithSignal cancels the losing summary publisher even when the interrupt
                // publisher fails; takeUntilOther does not cancel its source on an other-error.
                .or(interrupted.then(Mono.empty()));
    }

    private static Mono<Void> checkInterrupted(InterruptControl control) {
        return Mono.defer(
                () ->
                        control.isInterrupted()
                                ? Mono.error(
                                        new InterruptedException("Agent execution interrupted"))
                                : Mono.empty());
    }

    private static ReasoningInput rebuildInput(
            ReasoningInput input, Msg systemMsg, List<Msg> conversation) {
        List<Msg> messages = new ArrayList<>();
        if (systemMsg != null) {
            messages.add(systemMsg);
        }
        messages.addAll(conversation);
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    private static boolean isContextOverflowError(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("context_length_exceeded")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("token limit")
                || lower.contains("too many tokens")
                || lower.contains("exceeds the model's maximum")
                || lower.contains("reduce the length");
    }

    /**
     * Resolves dynamic defaults in the config using the model's context window.
     */
    private CompactionConfig resolveEffectiveConfig() {
        int configTrigger = config.getTriggerTokens();
        int configKeep = config.getKeepTokens();

        boolean needsDynamic = (configTrigger == 0) || (configKeep == -1);
        if (!needsDynamic) {
            return config;
        }

        int contextWindow = model.getContextWindowSize();

        int effectiveTrigger;
        if (configTrigger == 0) {
            if (contextWindow > 0) {
                effectiveTrigger = contextWindow - config.getReserved();
                if (effectiveTrigger <= 0) {
                    // reserved exceeds the model's context window; a negative or zero trigger
                    // would fire compaction on every call. Clamp to half the context window so
                    // compaction still activates at a sensible point without thrashing.
                    effectiveTrigger = Math.max(1, contextWindow / 2);
                    log.warn(
                            "Dynamic compaction trigger clamped: contextWindow={} <= reserved={}"
                                    + "; using proportional trigger={}. Consider reducing"
                                    + " reserved() for this model.",
                            contextWindow,
                            config.getReserved(),
                            effectiveTrigger);
                } else {
                    log.debug(
                            "Dynamic compaction trigger: contextWindow={} - reserved={} = {}",
                            contextWindow,
                            config.getReserved(),
                            effectiveTrigger);
                }
            } else {
                effectiveTrigger = CompactionConfig.FALLBACK_TRIGGER_TOKENS;
                log.debug(
                        "Model does not report context window, using fallback trigger: {}",
                        effectiveTrigger);
            }
        } else {
            effectiveTrigger = configTrigger;
        }

        int effectiveKeep;
        if (configKeep == -1) {
            if (contextWindow > 0) {
                int usable = contextWindow - config.getReserved();
                effectiveKeep =
                        Math.min(
                                config.getKeepTokensMax(),
                                Math.max(
                                        config.getKeepTokensMin(),
                                        (int) (usable * config.getKeepTokensRatio())));
                log.debug("Dynamic keep tokens: {}", effectiveKeep);
            } else {
                effectiveKeep = 0;
            }
        } else {
            effectiveKeep = configKeep;
        }

        return config.withEffective(effectiveTrigger, effectiveKeep);
    }

    private static void applyToContext(AgentState state, List<Msg> compacted) {
        if (state == null) {
            log.warn("Cannot apply compacted messages: AgentState is null");
            return;
        }
        try {
            List<Msg> ctx = state.contextMutable();
            ctx.clear();
            ctx.addAll(compacted);
            log.debug("Applied compacted messages to state ({} messages)", compacted.size());
        } catch (Exception e) {
            log.warn("Failed to apply compacted messages to state: {}", e.getMessage());
        }
    }
}
