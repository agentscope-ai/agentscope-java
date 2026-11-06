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

package io.agentscope.extensions.judge.jev.example;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Selects applicable tools independently, then caps the selected set. Disabled by default.
 *
 * <p>When enabled, sends a bounded recent conversation window by default. Configure
 * {@link ContextStrategy#FULL_CONVERSATION} to restore the complete conversation state.
 */
public final class JevToolSelectionMiddleware implements MiddlewareBase {
    /** Strategy for building the conversation state sent to Jev. */
    public enum ContextStrategy {
        /** Recent nonblank messages, bounded by message count and total text characters. */
        RECENT_WINDOW,
        /** Only the latest user text, as {@code userRequest}. */
        LATEST_USER_MESSAGE,
        /** The complete original message list. */
        FULL_CONVERSATION
    }

    private final ContextStrategy contextStrategy;
    private final int maxContextMessages;
    private final int maxContextChars;
    private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
    private final Set<String> alwaysIncludeTools;
    private final int maxTools;
    private final double confidenceThreshold;
    private final double rejectionThreshold;
    private final JevExecution execution;

    private JevToolSelectionMiddleware(Builder b) {
        jevCall = Objects.requireNonNull(b.jevCall);
        alwaysIncludeTools = Set.copyOf(b.alwaysIncludeTools);
        maxTools = b.maxTools;
        confidenceThreshold = b.confidenceThreshold;
        rejectionThreshold = b.rejectionThreshold;
        contextStrategy = Objects.requireNonNull(b.contextStrategy, "contextStrategy");
        maxContextMessages = b.maxContextMessages;
        maxContextChars = b.maxContextChars;
        if (maxContextMessages <= 0 || maxContextChars <= 0)
            throw new IllegalArgumentException(
                    "context message and character limits must be positive");
        execution = new JevExecution("tool-selection", b.options);
        if (maxTools <= 0
                || !Double.isFinite(confidenceThreshold)
                || !Double.isFinite(rejectionThreshold)
                || rejectionThreshold < 0
                || confidenceThreshold > 1
                || rejectionThreshold >= confidenceThreshold)
            throw new IllegalArgumentException(
                    "require maxTools > 0 and 0 <= rejection < selection <= 1");
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    public static Builder builder(JevClient client) {
        return new Builder(client::systemOne);
    }

    public static Builder builder(Function<SystemOneRequest, Mono<SystemOneResult>> call) {
        return new Builder(call);
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    List<ToolSchema> tools = input.tools() == null ? List.of() : input.tools();
                    List<ToolSchema> optional =
                            tools.stream()
                                    .filter(t -> !alwaysIncludeTools.contains(t.getName()))
                                    .toList();
                    if (optional.isEmpty()
                            || JevSelectionSupport.latestUserText(input.messages()).isBlank())
                        return next.apply(input);
                    return execution
                            .execute(ctx, () -> selectTools(stateFor(input.messages()), optional))
                            .flatMapMany(
                                    d ->
                                            execution.mode() != JevExecution.Mode.ENFORCE
                                                            || d.status()
                                                                    != JevExecution.Status.DECIDED
                                                    ? next.apply(input)
                                                    : next.apply(
                                                            new ReasoningInput(
                                                                    input.messages(),
                                                                    tools.stream()
                                                                            .filter(
                                                                                    t ->
                                                                                            alwaysIncludeTools
                                                                                                            .contains(
                                                                                                                    t
                                                                                                                            .getName())
                                                                                                    || d.value()
                                                                                                            .contains(
                                                                                                                    t
                                                                                                                            .getName()))
                                                                            .toList(),
                                                                    input.options())));
                });
    }

    private Map<String, Object> stateFor(List<Msg> messages) {
        return switch (contextStrategy) {
            case RECENT_WINDOW ->
                    JevSelectionSupport.recentWindowState(
                            messages, maxContextMessages, maxContextChars);
            case LATEST_USER_MESSAGE ->
                    JevSelectionSupport.userRequestState(
                            JevSelectionSupport.latestUserText(messages));
            case FULL_CONVERSATION -> JevSelectionSupport.messagesState(messages);
        };
    }

    private Mono<JevExecution.Decision<Set<String>>> selectTools(
            Map<String, Object> state, List<ToolSchema> tools) {
        // Independent relevance questions avoid losing multiple useful tools in a Choice chunk.
        return Flux.fromIterable(JevSelectionSupport.partition(tools, 64))
                .concatMap(
                        batch -> {
                            var request = SystemOneRequest.builder().state(state);
                            for (int i = 0; i < batch.size(); i++)
                                request.question(
                                        "tool_" + i,
                                        new NoulQuestion(
                                                Map.of(
                                                        "instruction",
                                                        "Is this tool applicable to the current"
                                                                + " request? Judge independently of"
                                                                + " other tools.",
                                                        "tool",
                                                        batch.get(i)),
                                                null));
                            return Mono.defer(() -> jevCall.apply(request.build()))
                                    .switchIfEmpty(
                                            Mono.error(new IllegalStateException("empty response")))
                                    .map(
                                            r -> {
                                                if (r.answers() == null
                                                        || r.answers().size() != batch.size())
                                                    throw new IllegalArgumentException(
                                                            "invalid answers");
                                                Map<String, Double> scores = new LinkedHashMap<>();
                                                for (int i = 0; i < batch.size(); i++) {
                                                    if (!(r.answers().get("tool_" + i)
                                                                    instanceof NoulAnswer a)
                                                            || a.noul() == null
                                                            || !Double.isFinite(a.noul())
                                                            || a.noul() < 0
                                                            || a.noul() > 1)
                                                        throw new IllegalArgumentException(
                                                                "invalid relevance");
                                                    scores.put(batch.get(i).getName(), a.noul());
                                                }
                                                return scores;
                                            });
                        })
                .collectList()
                .map(
                        batches -> {
                            Map<String, Double> scores = new LinkedHashMap<>();
                            batches.forEach(scores::putAll);
                            if (scores.values().stream()
                                    .anyMatch(
                                            p -> p > rejectionThreshold && p < confidenceThreshold))
                                return JevExecution.Decision.<Set<String>>uncertain(
                                        "UNCERTAIN_RELEVANCE");
                            Set<String> selected =
                                    scores.entrySet().stream()
                                            .filter(e -> e.getValue() >= confidenceThreshold)
                                            .sorted(
                                                    Map.Entry.<String, Double>comparingByValue()
                                                            .reversed())
                                            .limit(maxTools)
                                            .map(Map.Entry::getKey)
                                            .collect(
                                                    java.util.stream.Collectors.toCollection(
                                                            LinkedHashSet::new));
                            return new JevExecution.Decision<>(
                                    JevExecution.Status.DECIDED,
                                    selected,
                                    selected.isEmpty() ? "NONE_APPLICABLE" : "SELECTED",
                                    Map.of("tools", String.join(",", selected)));
                        });
    }

    public static final class Builder {
        private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
        private final Set<String> alwaysIncludeTools =
                new LinkedHashSet<>(
                        Set.of("load_skill_through_path", "reset_tools", "generate_response"));
        private ContextStrategy contextStrategy = ContextStrategy.RECENT_WINDOW;
        private int maxContextMessages = 8;
        private int maxContextChars = 8000;
        private int maxTools = 3;
        private double confidenceThreshold = 0.8;
        private double rejectionThreshold = 0.2;
        private JevExecution.Options options = JevExecution.Options.disabled();

        private Builder(Function<SystemOneRequest, Mono<SystemOneResult>> call) {
            jevCall = call;
        }

        public Builder execution(JevExecution.Options options) {
            this.options = options;
            return this;
        }

        public Builder alwaysIncludeTools(Set<String> tools) {
            alwaysIncludeTools.clear();
            if (tools != null) alwaysIncludeTools.addAll(tools);
            return this;
        }

        public Builder maxTools(int value) {
            maxTools = value;
            return this;
        }

        public Builder confidenceThreshold(double value) {
            confidenceThreshold = value;
            return this;
        }

        public Builder rejectionThreshold(double value) {
            rejectionThreshold = value;
            return this;
        }

        /** Sets the state strategy; the default is a bounded recent window. */
        public Builder contextStrategy(ContextStrategy value) {
            contextStrategy = value;
            return this;
        }

        /** Sets the positive recent-window message limit (default 8). */
        public Builder maxContextMessages(int value) {
            maxContextMessages = value;
            return this;
        }

        /** Sets the positive recent-window text character budget (default 8000). */
        public Builder maxContextChars(int value) {
            maxContextChars = value;
            return this;
        }

        public JevToolSelectionMiddleware build() {
            return new JevToolSelectionMiddleware(this);
        }
    }
}
