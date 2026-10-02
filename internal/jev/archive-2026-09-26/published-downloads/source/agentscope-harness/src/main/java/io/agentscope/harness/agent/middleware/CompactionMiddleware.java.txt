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
import io.agentscope.core.middleware.ModelCallInput;
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
        Model executionModel = agent instanceof ReActAgent react ? react.getModel() : model;
        if (executionModel == null) executionModel = model;
        int window = executionModel.getContextWindowSize();
        int budget =
                window > 0
                        ? Math.max(1, window - config.getReserved())
                        : CompactionConfig.FALLBACK_TRIGGER_TOKENS;
        return prepare(
                        agent,
                        ctx,
                        new ModelCallInput(
                                input.messages(), input.tools(), input.options(), executionModel),
                        budget)
                .flatMapMany(
                        prepared ->
                                next.apply(
                                        new ReasoningInput(
                                                prepared.messages(),
                                                prepared.tools(),
                                                prepared.options())));
    }

    /** Compact at the final model boundary, using the actual request's conversation allowance. */
    public Mono<ModelCallInput> prepare(
            Agent agent, RuntimeContext ctx, ModelCallInput input, int conversationBudget) {
        return Mono.defer(
                () -> {
                    AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                    List<Msg> original =
                            state == null ? List.of() : List.copyOf(state.contextMutable());
                    return prepareCandidate(agent, ctx, input, conversationBudget, original)
                            .map(
                                    candidate -> {
                                        if (state != null
                                                && state.contextMutable().equals(original)) {
                                            applyToContext(state, candidate.history());
                                        }
                                        return candidate.input();
                                    });
                });
    }

    /** A model view and candidate durable history. Creating it never replaces active history. */
    public record Candidate(ModelCallInput input, List<Msg> history) {
        public Candidate {
            history = List.copyOf(history);
        }
    }

    public Mono<Candidate> prepareCandidate(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            int conversationBudget,
            List<Msg> canonical) {
        return Mono.defer(
                () -> {
                    RuntimeContext rc = ctx == null ? RuntimeContext.empty() : ctx;
                    List<Msg> prefix = new ArrayList<>();
                    List<Msg> conversation = new ArrayList<>();
                    for (Msg msg : input.messages()) {
                        if (msg.getRole() == MsgRole.SYSTEM || isSynthetic(msg)) {
                            prefix.add(msg);
                        } else {
                            conversation.add(msg);
                        }
                    }
                    int trigger =
                            config.getTriggerTokens() > 0
                                    ? Math.min(config.getTriggerTokens(), conversationBudget)
                                    : conversationBudget;
                    int keep =
                            config.getKeepTokens() >= 0
                                    ? config.getKeepTokens()
                                    : Math.min(
                                            config.getKeepTokensMax(),
                                            Math.max(
                                                    config.getKeepTokensMin(),
                                                    (int)
                                                            (conversationBudget
                                                                    * config
                                                                            .getKeepTokensRatio())));
                    CompactionConfig effective =
                            config.withEffective(
                                    Math.max(1, trigger),
                                    Math.min(keep, Math.max(0, conversationBudget / 2)));
                    ConversationCompactor compactor =
                            new ConversationCompactor(
                                    model, new MemoryFlushManager(workspaceManager, model));
                    // Commit only if the view is exactly the canonical history. Hook-added context
                    // must never leak into durable history, and concurrent changes must not be
                    // erased.
                    boolean canCommit = canonical.equals(conversation);
                    return compactor
                            .compactIfNeeded(
                                    rc,
                                    conversation,
                                    effective,
                                    agent.getName(),
                                    rc.getSessionId() == null ? "default" : rc.getSessionId())
                            .onErrorResume(
                                    error -> {
                                        if (ExceptionUtils.containsInterruptedException(error))
                                            return Mono.error(error);
                                        log.warn(
                                                "Compaction failed; final request budget validation"
                                                        + " still applies",
                                                error);
                                        return Mono.just(Optional.empty());
                                    })
                            .map(
                                    result -> {
                                        if (result.isEmpty())
                                            return new Candidate(input, canonical);
                                        List<Msg> compacted = result.get();
                                        List<Msg> messages = new ArrayList<>(prefix);
                                        messages.addAll(compacted);
                                        return new Candidate(
                                                new ModelCallInput(
                                                        List.copyOf(messages),
                                                        input.tools(),
                                                        input.options(),
                                                        input.model()),
                                                canCommit ? compacted : canonical);
                                    });
                });
    }

    private static boolean isSynthetic(Msg msg) {
        return msg.getMetadata() != null
                && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC));
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
