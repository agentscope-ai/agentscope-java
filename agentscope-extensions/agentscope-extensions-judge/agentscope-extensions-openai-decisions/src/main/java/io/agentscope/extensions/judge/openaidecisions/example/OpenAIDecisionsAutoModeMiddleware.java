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
package io.agentscope.extensions.judge.openaidecisions.example;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Opt-in tool safety pre-check. Refusals deny the affected call; core permissions still apply. */
public final class OpenAIDecisionsAutoModeMiddleware implements MiddlewareBase {
    public static final String DEFAULT_DENY_MESSAGE =
            "Blocked: this tool call did not pass the automatic-execution check. Request review or"
                    + " propose a safer alternative.";
    private final Function<DecisionRequest, Mono<DecisionResult>> call;
    private final Set<String> guardedTools;
    private final double safetyThreshold;
    private final String denyMessage;
    private final boolean enabled;
    private final boolean failOpen;

    private OpenAIDecisionsAutoModeMiddleware(Builder b) {
        call = b.call;
        guardedTools = Set.copyOf(b.guardedTools);
        safetyThreshold = b.safetyThreshold;
        denyMessage = b.denyMessage;
        enabled = b.enabled;
        failOpen = b.failOpen;
        DecisionSupport.requireProbability(safetyThreshold, "safetyThreshold");
        if (guardedTools.isEmpty()
                || guardedTools.stream().anyMatch(String::isBlank)
                || denyMessage == null
                || denyMessage.isBlank()) {
            throw new IllegalArgumentException("guardedTools and denyMessage must not be empty");
        }
    }

    public static Builder builder(OpenAIDecisionsClient client) {
        return builder(Objects.requireNonNull(client, "client")::decide);
    }

    public static Builder builder(Function<DecisionRequest, Mono<DecisionResult>> call) {
        return new Builder(call);
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_ACTING);
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (!enabled || input == null) return next.apply(input);
                    List<ToolUseBlock> tools =
                            input.toolCalls() == null ? List.of() : input.toolCalls();
                    List<ToolUseBlock> guarded =
                            tools.stream()
                                    .filter(
                                            c ->
                                                    guardedTools.contains(c.getName())
                                                            && c.getState()
                                                                    != ToolCallState.ALLOWED)
                                    .toList();
                    if (guarded.isEmpty()) return next.apply(input);
                    AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                    Mono<List<ToolUseBlock>> assessment =
                            state == null
                                    ? Mono.just(failOpen ? guarded : List.of())
                                    : assess(state.getContext(), guarded);
                    return assessment.flatMapMany(
                            allowed -> {
                                List<ToolUseBlock> denied =
                                        guarded.stream().filter(c -> !allowed.contains(c)).toList();
                                if (denied.isEmpty()) return next.apply(input);
                                List<ToolUseBlock> passing =
                                        tools.stream().filter(c -> !denied.contains(c)).toList();
                                Flux<AgentEvent> events = deniedEvents(denied, state, agent);
                                return passing.isEmpty()
                                        ? events
                                        : events.concatWith(
                                                Flux.defer(
                                                        () ->
                                                                next.apply(
                                                                        new ActingInput(passing))));
                            });
                });
    }

    private Mono<List<ToolUseBlock>> assess(List<Msg> messages, List<ToolUseBlock> guarded) {
        return Flux.range(0, (guarded.size() - 1) / DecisionSupport.MAX_QUESTIONS + 1)
                .concatMap(
                        batchIndex -> {
                            int start = batchIndex * DecisionSupport.MAX_QUESTIONS;
                            List<ToolUseBlock> batch =
                                    guarded.subList(
                                            start,
                                            Math.min(
                                                    start + DecisionSupport.MAX_QUESTIONS,
                                                    guarded.size()));
                            return assessBatch(messages, batch);
                        })
                .collectList()
                .map(batches -> batches.stream().flatMap(List::stream).toList());
    }

    private Mono<List<ToolUseBlock>> assessBatch(List<Msg> messages, List<ToolUseBlock> batch) {
        return Mono.defer(() -> DecisionSupport.call(call, riskRequest(messages, batch)))
                .map(
                        result -> {
                            List<ToolUseBlock> allowed = new ArrayList<>();
                            for (int i = 0; i < batch.size(); i++) {
                                if (result.answers().get("tool_" + i)
                                        instanceof DecisionAnswer.Predicate answer) {
                                    DecisionSupport.requireProbability(
                                            answer.probability(), "safety");
                                    if (answer.probability() >= safetyThreshold)
                                        allowed.add(batch.get(i));
                                }
                            }
                            return List.copyOf(allowed);
                        })
                .onErrorResume(error -> Mono.just(failOpen ? batch : List.of()));
    }

    private DecisionRequest riskRequest(List<Msg> messages, List<ToolUseBlock> batch) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (ToolUseBlock tool : batch) {
            details.add(
                    Map.of(
                            "name",
                            tool.getName(),
                            "input",
                            tool.getInput() == null ? Map.of() : tool.getInput()));
        }
        String evidence =
                DecisionSupport.json(
                        Map.of(
                                "messages",
                                DecisionSupport.recentMessages(messages, 8, 8000),
                                "tool_calls",
                                details));
        var request = DecisionRequest.builder().input(evidence);
        for (int i = 0; i < batch.size(); i++) {
            request.question(
                    "tool_" + i,
                    new DecisionQuestion.Predicate(
                            "Is tool_calls["
                                    + i
                                    + "] safe to execute automatically, given the conversation?"
                                    + " Consider destructive operations, data loss, irreversible"
                                    + " changes and security implications."));
        }
        return request.build();
    }

    private Flux<AgentEvent> deniedEvents(
            List<ToolUseBlock> denied, AgentState state, Agent agent) {
        return Flux.deferContextual(
                subscriber -> {
                    String replyId = state == null ? "openai-decisions-guard" : state.getReplyId();
                    String agentName =
                            agent == null || agent.getName() == null ? "agent" : agent.getName();
                    List<AgentEvent> events = new ArrayList<>();
                    for (ToolUseBlock tool : denied) {
                        ToolResultBlock result =
                                ToolResultBlock.text(denyMessage)
                                        .withIdAndName(tool.getId(), tool.getName())
                                        .withState(ToolResultState.DENIED);
                        if (state != null)
                            state.contextMutable()
                                    .add(
                                            ToolResultMessageBuilder.buildToolResultMsg(
                                                    result, tool, agentName));
                        events.add(new ToolResultStartEvent(replyId, tool.getId(), tool.getName()));
                        events.add(
                                new ToolResultTextDeltaEvent(
                                        replyId, tool.getId(), tool.getName(), denyMessage));
                        events.add(
                                new ToolResultEndEvent(
                                        replyId,
                                        tool.getId(),
                                        tool.getName(),
                                        ToolResultState.DENIED));
                    }
                    // ReAct's acting chain drains middleware events; publish the synthetic
                    // lifecycle too.
                    var emitter =
                            AgentEventEmitter.fromForwardingContext(subscriber)
                                    .or(() -> AgentEventEmitter.fromContext(subscriber));
                    return Flux.fromIterable(events)
                            .doOnNext(event -> emitter.ifPresent(e -> e.emit(event)));
                });
    }

    public static final class Builder {
        private final Function<DecisionRequest, Mono<DecisionResult>> call;
        private final Set<String> guardedTools = new LinkedHashSet<>();
        private double safetyThreshold = 0.8;
        private String denyMessage = DEFAULT_DENY_MESSAGE;
        private boolean enabled;
        private boolean failOpen;

        private Builder(Function<DecisionRequest, Mono<DecisionResult>> call) {
            this.call = Objects.requireNonNull(call, "call");
        }

        public Builder guardedTool(String name) {
            guardedTools.add(Objects.requireNonNull(name, "name"));
            return this;
        }

        public Builder guardedTools(Set<String> value) {
            guardedTools.clear();
            guardedTools.addAll(value);
            return this;
        }

        public Builder safetyThreshold(double value) {
            safetyThreshold = value;
            return this;
        }

        public Builder denyMessage(String value) {
            denyMessage = value;
            return this;
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        /** Transport failures deny by default; explicit refusals always deny the affected call. */
        public Builder failOpen(boolean value) {
            failOpen = value;
            return this;
        }

        public OpenAIDecisionsAutoModeMiddleware build() {
            return new OpenAIDecisionsAutoModeMiddleware(this);
        }
    }
}
