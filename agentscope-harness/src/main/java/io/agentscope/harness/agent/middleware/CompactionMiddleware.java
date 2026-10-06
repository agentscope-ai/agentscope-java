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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor.CompactionPlan;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor.CompactionResult;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    /** Custom event emitted immediately before a planned context compaction starts. */
    public static final String CONTEXT_COMPACTION_STARTED = "context_compaction_started";

    /** Custom event emitted when a planned context compaction reaches a terminal outcome. */
    public static final String CONTEXT_COMPACTION_FINISHED = "context_compaction_finished";

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final CompactionConfig config;
    private final String flushPrompt;

    public CompactionMiddleware(
            WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this(workspaceManager, model, config, null);
    }

    public CompactionMiddleware(
            WorkspaceManager workspaceManager,
            Model model,
            CompactionConfig config,
            String flushPrompt) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
        this.flushPrompt = flushPrompt;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
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

        return Flux.defer(
                () -> {
                    ReasoningMessages reasoningMessages = splitMessages(input);
                    String agentId = agent.getName();
                    String sessionId = rc.getSessionId() != null ? rc.getSessionId() : "default";

                    CompactionConfig effectiveConfig = resolveEffectiveConfig();
                    MemoryFlushManager flushManager =
                            new MemoryFlushManager(workspaceManager, model, flushPrompt);
                    ConversationCompactor compactor =
                            new ConversationCompactor(model, flushManager);
                    Optional<CompactionPlan> plan =
                            compactor.planIfNeeded(
                                    reasoningMessages.conversation(), effectiveConfig);
                    if (plan.isEmpty()) {
                        return reasonWithOverflowRecovery(
                                reActAgent,
                                rc,
                                input,
                                next,
                                compactor,
                                effectiveConfig,
                                agentId,
                                sessionId);
                    }

                    return compactAndContinue(
                            CompactionMode.NORMAL,
                            reActAgent,
                            rc,
                            input,
                            reasoningMessages,
                            next,
                            compactor,
                            effectiveConfig,
                            plan.get(),
                            agentId,
                            sessionId);
                });
    }

    private Flux<AgentEvent> reasonWithOverflowRecovery(
            ReActAgent agent,
            RuntimeContext rc,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            ConversationCompactor compactor,
            CompactionConfig effectiveConfig,
            String agentId,
            String sessionId) {
        return Flux.defer(() -> next.apply(input))
                .onErrorResume(
                        error -> {
                            if (!isContextOverflowError(error)) {
                                return Flux.error(error);
                            }
                            ReasoningMessages messages = splitMessages(input);
                            CompactionConfig emergencyConfig = emergencyConfig(effectiveConfig);
                            Optional<CompactionPlan> emergencyPlan =
                                    compactor.planIfNeeded(
                                            messages.conversation(), emergencyConfig);
                            if (emergencyPlan.isEmpty()) {
                                return Flux.error(error);
                            }
                            log.warn(
                                    "Context overflow detected, starting emergency compaction"
                                            + " before retrying reasoning");
                            return compactAndContinue(
                                    CompactionMode.EMERGENCY,
                                    agent,
                                    rc,
                                    input,
                                    messages,
                                    next,
                                    compactor,
                                    emergencyConfig,
                                    emergencyPlan.get(),
                                    agentId,
                                    sessionId);
                        });
    }

    private Flux<AgentEvent> compactAndContinue(
            CompactionMode mode,
            ReActAgent agent,
            RuntimeContext rc,
            ReasoningInput originalInput,
            ReasoningMessages originalMessages,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            ConversationCompactor compactor,
            CompactionConfig effectiveConfig,
            CompactionPlan plan,
            String agentId,
            String sessionId) {
        CustomEvent started = compactionStartedEvent(mode, plan);
        Mono<CompactionAttempt> attempt =
                Mono.defer(() -> compactor.compact(rc, plan, effectiveConfig, agentId, sessionId))
                        .map(CompactionAttempt::success)
                        .onErrorResume(error -> Mono.just(CompactionAttempt.failure(error)));

        Flux<AgentEvent> compactAndContinue =
                attempt.flatMapMany(
                        result -> {
                            if (result.error() != null) {
                                return handleCompactionFailure(
                                        mode,
                                        agent,
                                        rc,
                                        originalInput,
                                        originalMessages,
                                        next,
                                        compactor,
                                        effectiveConfig,
                                        plan,
                                        agentId,
                                        sessionId,
                                        result.error());
                            }

                            CompactionResult compactionResult = result.result();
                            List<Msg> compacted = compactionResult.messages();
                            boolean applied =
                                    applyToContext(
                                            RuntimeContext.resolveAgentState(rc, agent), compacted);
                            log.debug(
                                    "Compacted to {} messages before reasoning", compacted.size());
                            ReasoningInput compactedInput =
                                    rebuildInput(
                                            originalInput,
                                            originalMessages.systemMessage(),
                                            compacted);
                            CustomEvent finished =
                                    compactionFinishedEvent(
                                            mode,
                                            plan,
                                            compacted,
                                            !compactionResult.degraded() && applied
                                                    ? "success"
                                                    : "degraded");
                            Flux<AgentEvent> downstream =
                                    mode == CompactionMode.NORMAL
                                            ? reasonWithOverflowRecovery(
                                                    agent,
                                                    rc,
                                                    compactedInput,
                                                    next,
                                                    compactor,
                                                    effectiveConfig,
                                                    agentId,
                                                    sessionId)
                                            : Flux.defer(() -> next.apply(compactedInput));
                            return Flux.concat(Flux.just(finished), downstream);
                        });
        return Flux.concat(Flux.just(started), compactAndContinue);
    }

    private Flux<AgentEvent> handleCompactionFailure(
            CompactionMode mode,
            ReActAgent agent,
            RuntimeContext rc,
            ReasoningInput originalInput,
            ReasoningMessages originalMessages,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            ConversationCompactor compactor,
            CompactionConfig effectiveConfig,
            CompactionPlan plan,
            String agentId,
            String sessionId,
            Throwable error) {
        boolean interrupted = ExceptionUtils.containsInterruptedException(error);
        CustomEvent finished =
                compactionFinishedEvent(
                        mode,
                        plan,
                        originalMessages.conversation(),
                        interrupted ? "interrupted" : "failed");
        if (interrupted || mode == CompactionMode.EMERGENCY) {
            return Flux.concat(Flux.just(finished), Flux.error(error));
        }

        log.warn("Compaction failed, continuing without compaction: {}", error.getMessage());
        return Flux.concat(
                Flux.just(finished),
                reasonWithOverflowRecovery(
                        agent,
                        rc,
                        originalInput,
                        next,
                        compactor,
                        effectiveConfig,
                        agentId,
                        sessionId));
    }

    private static ReasoningMessages splitMessages(ReasoningInput input) {
        List<Msg> messages = input.messages();
        if (messages != null
                && !messages.isEmpty()
                && messages.get(0).getRole() == MsgRole.SYSTEM) {
            return new ReasoningMessages(
                    messages.get(0), new ArrayList<>(messages.subList(1, messages.size())));
        }
        return new ReasoningMessages(
                null, messages != null ? new ArrayList<>(messages) : List.of());
    }

    private static ReasoningInput rebuildInput(
            ReasoningInput originalInput, Msg systemMessage, List<Msg> conversation) {
        List<Msg> messages = new ArrayList<>();
        if (systemMessage != null) {
            messages.add(systemMessage);
        }
        messages.addAll(conversation);
        return new ReasoningInput(messages, originalInput.tools(), originalInput.options());
    }

    private static CompactionConfig emergencyConfig(CompactionConfig source) {
        return CompactionConfig.builder()
                .triggerMessages(1)
                .triggerTokens(Integer.MAX_VALUE)
                .keepMessages(1)
                .keepTokens(0)
                .summaryPrompt(source.getSummaryPrompt())
                .flushBeforeCompact(source.isFlushBeforeCompact())
                .offloadBeforeCompact(source.isOffloadBeforeCompact())
                .truncateArgs(source.getTruncateArgsConfig())
                .prune(source.getPruneConfig())
                .build();
    }

    private static boolean isContextOverflowError(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && seen.add(current)) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("context_length_exceeded")
                        || lower.contains("context length")
                        || lower.contains("maximum context")
                        || lower.contains("token limit")
                        || lower.contains("too many tokens")
                        || lower.contains("exceeds the model's maximum")
                        || lower.contains("reduce the length")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
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

    private static CustomEvent compactionStartedEvent(CompactionMode mode, CompactionPlan plan) {
        return new CustomEvent(
                CONTEXT_COMPACTION_STARTED,
                Map.of(
                        "mode", mode.value,
                        "beforeMsgCount", plan.beforeMsgCount(),
                        "beforeTokenCount", plan.beforeTokenCount()));
    }

    private static CustomEvent compactionFinishedEvent(
            CompactionMode mode, CompactionPlan plan, List<Msg> afterMessages, String status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", mode.value);
        value.put("status", status);
        value.put("beforeMsgCount", plan.beforeMsgCount());
        value.put("beforeTokenCount", plan.beforeTokenCount());
        value.put("afterMsgCount", afterMessages.size());
        value.put("afterTokenCount", TokenCounterUtil.calculateToken(afterMessages));
        return new CustomEvent(CONTEXT_COMPACTION_FINISHED, value);
    }

    private static boolean applyToContext(AgentState state, List<Msg> compacted) {
        if (state == null) {
            log.warn("Cannot apply compacted messages: AgentState is null");
            return false;
        }
        try {
            List<Msg> ctx = state.contextMutable();
            ctx.clear();
            ctx.addAll(compacted);
            log.debug("Applied compacted messages to state ({} messages)", compacted.size());
            return true;
        } catch (Exception e) {
            log.warn("Failed to apply compacted messages to state: {}", e.getMessage());
            return false;
        }
    }

    private enum CompactionMode {
        NORMAL("normal"),
        EMERGENCY("emergency");

        private final String value;

        CompactionMode(String value) {
            this.value = value;
        }
    }

    private record ReasoningMessages(Msg systemMessage, List<Msg> conversation) {}

    private record CompactionAttempt(CompactionResult result, Throwable error) {

        private static CompactionAttempt success(CompactionResult result) {
            return new CompactionAttempt(result, null);
        }

        private static CompactionAttempt failure(Throwable error) {
            return new CompactionAttempt(null, error);
        }
    }
}
