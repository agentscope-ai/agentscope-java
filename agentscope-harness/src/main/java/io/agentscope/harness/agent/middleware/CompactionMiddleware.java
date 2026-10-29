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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.context.ToolResultBudgetGuard;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
 *   <li>The native session log retains the complete conversation before compaction.</li>
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

    /**
     * Reads serialized histories as exact decimal trees: restored copies keep their values
     * across Integer/Double/BigDecimal coercion, while edits to decimal metadata beyond
     * double precision still compare unequal. Numbers compare by exact value only, so
     * scale-only and notation-only changes ("1.10" vs "1.1", "1e2" vs "100") count as
     * unchanged.
     */
    private static final ObjectReader HISTORY_VALUES =
            new ObjectMapper().reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final CompactionConfig config;

    public CompactionMiddleware(
            WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
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

    /** Retry provider context overflow inside the same execution, before its recorder is closed. */
    public HarnessRuntimeMiddleware overflowRecovery() {
        return new HarnessRuntimeMiddleware() {
            @Override
            public Flux<AgentEvent> onReasoning(
                    Agent agent,
                    RuntimeContext ctx,
                    ReasoningInput input,
                    Function<ReasoningInput, Flux<AgentEvent>> next) {
                if (!(agent instanceof ReActAgent react)) return next.apply(input);
                AgentState initialState = RuntimeContext.resolveAgentState(ctx, agent);
                List<Msg> initialHistory =
                        initialState == null ? List.of() : List.copyOf(initialState.getContext());
                return next.apply(input)
                        .onErrorResume(
                                error ->
                                        Flux.defer(
                                                        () ->
                                                                recoverOverflow(
                                                                        react,
                                                                        ctx,
                                                                        input,
                                                                        initialHistory,
                                                                        next,
                                                                        error))
                                                .subscribeOn(Schedulers.boundedElastic()));
            }
        };
    }

    private Flux<AgentEvent> recoverOverflow(
            ReActAgent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            List<Msg> initialHistory,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            Throwable error) {
        if (ExceptionUtils.containsInterruptedException(error)
                || SessionLogException.causedBy(error)
                || !isContextOverflowError(error)) return Flux.error(error);
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (state == null || state.getContext().isEmpty()) return Flux.error(error);
        List<Msg> before = List.copyOf(state.getContext());
        // The final preparer may have compacted state before the provider rejected the request.
        var retryMessages = new ArrayList<Msg>();
        input.messages().stream()
                .filter(
                        message ->
                                message.getRole() == MsgRole.SYSTEM
                                        || (message.isRequestProjection(initialHistory)
                                                && message.isRequestProjection(before)))
                .forEach(retryMessages::add);
        retryMessages.addAll(before);
        var retryInput =
                new ModelCallInput(retryMessages, input.tools(), input.options(), agent.getModel());
        // Unknown windows and optimistic estimates require a deterministic reduction before a
        // summary model can safely inspect the evidence. Retry only once inside this boundary.
        var estimator = ContextTokenEstimator.approximate();
        long retryBudget = Math.max(1, estimator.estimate(retryInput).tokens() / 2);
        var bounded =
                ToolResultBudgetGuard.prepare(
                        agent,
                        ctx,
                        retryInput,
                        before,
                        workspaceManager == null || workspaceManager.getFilesystem() == null
                                ? null
                                : new ToolResultEvictionMiddleware(
                                        workspaceManager.getFilesystem(),
                                        ToolResultEvictionConfig.defaults()),
                        request -> estimator.estimate(request).tokens() <= retryBudget);
        if (!bounded.history().equals(before) && state.getContext().equals(before)) {
            applyToContext(state, bounded.history());
            return next.apply(
                    new ReasoningInput(bounded.input().messages(), input.tools(), input.options()));
        }
        int currentTurnMessages = 1;
        for (int i = before.size() - 1; i >= 0; i--) {
            if (before.get(i).getRole() == MsgRole.USER) {
                currentTurnMessages = before.size() - i;
                break;
            }
        }
        var forced =
                new CompactionMiddleware(
                        workspaceManager,
                        model,
                        CompactionConfig.builder()
                                .triggerMessages(1)
                                .keepMessages(currentTurnMessages)
                                .keepTokens(0)
                                .flushBeforeCompact(config.isFlushBeforeCompact())
                                .build());
        return forced.prepareCandidate(
                        agent, ctx, retryInput, CompactionConfig.FALLBACK_TRIGGER_TOKENS, before)
                .flatMapMany(
                        candidate -> {
                            if (candidate.history().equals(before)
                                    || !state.getContext().equals(before)) return Flux.error(error);
                            applyToContext(state, candidate.history());
                            return next.apply(
                                    new ReasoningInput(
                                            candidate.input().messages(),
                                            candidate.input().tools(),
                                            candidate.input().options()));
                        });
    }

    public static boolean isContextOverflowError(Throwable error) {
        String message = error.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("context_length_exceeded")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("token limit")
                || lower.contains("too many tokens")
                || lower.contains("exceeds the model's maximum")
                || lower.contains("reduce the length");
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
                        if (msg.getRole() == MsgRole.SYSTEM || msg.isRequestProjection(canonical)) {
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
                    // Copies may retain message IDs. Only an unchanged view can become durable;
                    // matching IDs alone must not persist hook-edited content or metadata.
                    boolean canCommit = sameSerializedHistory(canonical, conversation);
                    return compactor
                            .compactIfNeeded(
                                    rc,
                                    conversation,
                                    effective,
                                    agent.getName(),
                                    rc.getSessionId() == null ? "default" : rc.getSessionId())
                            .onErrorResume(
                                    error -> {
                                        if (ExceptionUtils.containsInterruptedException(error)
                                                || SessionLogException.causedBy(error))
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

    private static boolean sameSerializedHistory(List<Msg> canonical, List<Msg> conversation) {
        if (canonical.equals(conversation)) return true;
        if (canonical.size() != conversation.size()) return false;
        var codec = JsonUtils.getJsonCodec();
        try {
            // Normalize restored number types without rounding distinct decimal metadata values.
            return HISTORY_VALUES
                    .readTree(codec.toJson(canonical))
                    .equals(HISTORY_VALUES.readTree(codec.toJson(conversation)));
        } catch (JsonException | JsonProcessingException error) {
            // A history that cannot be serialized or parsed cannot be proven unchanged; skip
            // the writeback instead of failing the reasoning call.
            return false;
        }
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
