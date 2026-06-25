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
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

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

                    MemoryFlushManager flushManager =
                            new MemoryFlushManager(workspaceManager, model);
                    ConversationCompactor compactor =
                            new ConversationCompactor(model, flushManager);
                    final Msg sys = systemMsg;

                    return compactor
                            .compactIfNeeded(rc, conversation, effectiveConfig, agentId, sessionId)
                            .flatMapMany(
                                    optResult -> {
                                        if (optResult.isEmpty()) {
                                            return next.apply(input);
                                        }
                                        List<Msg> compacted = optResult.get();
                                        applyToContext(
                                                RuntimeContext.resolveAgentState(rc, reActAgent),
                                                compacted);
                                        log.debug(
                                                "Compacted to {} messages before reasoning",
                                                compacted.size());
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
                                    })
                            .onErrorResume(
                                    e -> {
                                        log.warn(
                                                "Compaction failed, continuing without compaction:"
                                                        + " {}",
                                                e.getMessage());
                                        return next.apply(input);
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
