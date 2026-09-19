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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final CompactionConfig config;

    public CompactionMiddleware(
            WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
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

                    ConversationCompactor compactor =
                            createConversationCompactor(
                                    workspaceManager,
                                    model,
                                    MemoryFlushManager.DEFAULT_FLUSH_PROMPT);
                    final Msg sys = systemMsg;
                    AgentState state = RuntimeContext.resolveAgentState(rc, reActAgent);
                    List<Msg> contextSnapshot = state != null ? state.getContext() : List.of();

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
                            .flatMapMany(
                                    optResult -> {
                                        if (optResult.isEmpty()) {
                                            return next.apply(input);
                                        }
                                        List<Msg> compacted = optResult.get();
                                        // The turn reasons over the compacted messages even when
                                        // the persisted context could not be replaced, so the
                                        // summary that was already paid for is still used.
                                        if (applyToContext(state, contextSnapshot, compacted)) {
                                            log.debug(
                                                    "Compacted to {} messages before reasoning",
                                                    compacted.size());
                                        }
                                        List<Msg> newMessages = new ArrayList<>();
                                        if (sys != null) {
                                            newMessages.add(sys);
                                        }
                                        newMessages.addAll(compacted);
                                        return next.apply(
                                                new ReasoningInput(
                                                        newMessages,
                                                        input.tools(),
                                                        input.options()));
                                    });
                });
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

    /**
     * Applies the compacted messages to the live context, keeping messages appended after the
     * snapshot was taken.
     *
     * <p>A rejected replacement is reported at {@code WARN} with the sizes involved and is not
     * retried: rejection means the context changed while the summary was in flight, so the
     * compacted result no longer describes the current head. The outcome is returned so the caller
     * cannot mistake a rejected replacement for a successful compaction — the persisted context
     * then stays over budget and the next turn pays for another compaction.
     *
     * @return {@code true} when the compacted messages are now in the context
     */
    static boolean applyToContext(AgentState state, List<Msg> snapshot, List<Msg> compacted) {
        if (state == null) {
            log.warn("Cannot apply compacted messages: AgentState is null");
            return false;
        }
        try {
            if (!state.replaceContextPreservingAppends(snapshot, compacted)) {
                log.warn(
                        "Compaction was not applied: the context changed while the summary was"
                                + " being generated (snapshot={} messages, current={} messages,"
                                + " compacted={} messages). The persisted context is left unchanged"
                                + " and stays over budget, so compaction runs again on the next"
                                + " turn.",
                        snapshot == null ? 0 : snapshot.size(),
                        state.getContext().size(),
                        compacted == null ? 0 : compacted.size());
                return false;
            }
            log.debug("Applied compacted messages to state ({} messages)", compacted.size());
            return true;
        } catch (Exception e) {
            log.warn("Failed to apply compacted messages to state: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Creates the conversation compactor with an optional configured flush prompt. */
    public static ConversationCompactor createConversationCompactor(
            WorkspaceManager workspaceManager, Model model, String flushPrompt) {
        MemoryFlushManager flushManager =
                new MemoryFlushManager(workspaceManager, model, flushPrompt);
        return new ConversationCompactor(model, flushManager);
    }
}
