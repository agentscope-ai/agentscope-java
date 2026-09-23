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
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.Question;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Uses Jev to decide, before execution, whether a guarded tool call is safe enough to run
 * automatically.
 *
 * <p>For every tool call whose name appears in {@code guardedTools}, this middleware asks Jev a
 * yes/no risk question built from the conversation history and the tool input. Calls whose
 * P(safe) is at least {@code safetyThreshold} pass through; the rest get a synthetic
 * {@code DENIED} tool result written to the conversation state and are never dispatched to the
 * toolkit. Tool calls that were already explicitly allowed by the user (state {@code ALLOWED})
 * skip the check entirely, so this guardrail cannot override a human confirmation.
 *
 * <p>The check runs in {@link #onActing}, after any outer middleware that filters or rewrites
 * tool calls and immediately before the core permission/execution pipeline. This makes it
 * complementary to the deterministic {@code PermissionEngine}: a call that passes the Jev
 * assessment is still subject to permission deny rules inside the core acting pipeline, so this
 * middleware adds a model-driven pre-check for the specific tools it guards without replacing
 * the rule-based engine.
 */
public final class JevAutoModeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(JevAutoModeMiddleware.class);

    public static final String DEFAULT_DENY_MESSAGE =
            "Blocked: this tool call was assessed as too risky to run automatically. If the"
                    + " operation is genuinely necessary, ask the user for confirmation or"
                    + " propose a safer alternative.";

    private static final String QUESTION_ID_PREFIX = "tool_";

    private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
    private final Set<String> guardedTools;
    private final double safetyThreshold;
    private final String denyMessage;
    private final boolean failOpen;

    private JevAutoModeMiddleware(Builder builder) {
        this.jevCall = builder.jevCall;
        this.guardedTools = Set.copyOf(builder.guardedTools);
        this.safetyThreshold = builder.safetyThreshold;
        this.denyMessage = builder.denyMessage;
        this.failOpen = builder.failOpen;
        validate();
    }

    public static Builder builder(JevClient client) {
        Objects.requireNonNull(client, "client");
        return new Builder(client::systemOne);
    }

    static Builder builder(Function<SystemOneRequest, Mono<SystemOneResult>> jevCall) {
        return new Builder(jevCall);
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
        List<ToolUseBlock> toolCalls = input == null ? List.of() : input.toolCalls();
        Partition partition = partition(toolCalls);
        if (partition.guarded().isEmpty()) {
            return next.apply(input);
        }

        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (state == null) {
            log.warn(
                    "JevAutoModeMiddleware cannot resolve AgentState; passing guarded tool calls"
                            + " through without risk assessment");
            return next.apply(input);
        }

        return assess(state.getContext(), partition.guarded())
                .flatMapMany(
                        risk -> {
                            if (risk.denied().isEmpty()) {
                                return next.apply(input);
                            }
                            List<ToolUseBlock> allowed = new ArrayList<>(partition.unguarded());
                            allowed.addAll(risk.allowed());
                            writeDeniedResults(risk.denied(), state, agent);
                            if (allowed.isEmpty()) {
                                return Flux.empty();
                            }
                            return next.apply(new ActingInput(allowed));
                        });
    }

    private Partition partition(List<ToolUseBlock> toolCalls) {
        List<ToolUseBlock> guarded = new ArrayList<>();
        List<ToolUseBlock> unguarded = new ArrayList<>();
        if (toolCalls != null) {
            for (ToolUseBlock call : toolCalls) {
                if (call == null) {
                    continue;
                }
                boolean needsCheck =
                        guardedTools.contains(call.getName())
                                && call.getState() != ToolCallState.ALLOWED;
                if (needsCheck) {
                    guarded.add(call);
                } else {
                    unguarded.add(call);
                }
            }
        }
        return new Partition(guarded, unguarded);
    }

    private Mono<RiskDecision> assess(List<Msg> messages, List<ToolUseBlock> guarded) {
        SystemOneRequest request = riskRequest(messages, guarded);
        return jevCall.apply(request)
                .map(result -> parseDecision(guarded, result))
                .onErrorResume(
                        error -> {
                            if (!failOpen) {
                                return Mono.error(error);
                            }
                            log.warn(
                                    "Jev risk assessment failed; failing open for {} guarded tool"
                                            + " call(s)",
                                    guarded.size(),
                                    error);
                            return Mono.just(new RiskDecision(List.copyOf(guarded), List.of()));
                        });
    }

    private SystemOneRequest riskRequest(List<Msg> messages, List<ToolUseBlock> guarded) {
        Map<String, Object> toolCallDetails = new LinkedHashMap<>();
        Map<String, Question> questions = new LinkedHashMap<>();
        for (int i = 0; i < guarded.size(); i++) {
            String questionId = QUESTION_ID_PREFIX + i;
            ToolUseBlock call = guarded.get(i);
            toolCallDetails.put(
                    questionId,
                    Map.of(
                            "name",
                            call.getName(),
                            "input",
                            call.getInput() == null ? Map.of() : call.getInput()));
            questions.put(
                    questionId,
                    new NoulQuestion(
                            "Is the tool call described by state.tool_calls."
                                    + questionId
                                    + " safe to execute, given the conversation in"
                                    + " state.messages? Consider destructive operations, data"
                                    + " loss, irreversible changes, and security implications.",
                            new NoulQuestion.NoulCriteria(
                                    "Yes, the operation is safe to execute automatically.",
                                    "No, the operation is too risky to execute automatically.")));
        }
        Map<String, Object> stateMap = new LinkedHashMap<>();
        stateMap.put("messages", List.copyOf(messages));
        stateMap.put("tool_calls", Collections.unmodifiableMap(toolCallDetails));
        return SystemOneRequest.builder()
                .state(Collections.unmodifiableMap(stateMap))
                .questions(questions)
                .build();
    }

    private RiskDecision parseDecision(List<ToolUseBlock> guarded, SystemOneResult result) {
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (int i = 0; i < guarded.size(); i++) {
            Answer answer = result.answers().get(QUESTION_ID_PREFIX + i);
            if (answer instanceof NoulAnswer noul
                    && noul.noul() != null
                    && noul.noul() < safetyThreshold) {
                denied.add(guarded.get(i));
            } else {
                allowed.add(guarded.get(i));
            }
        }
        return new RiskDecision(allowed, denied);
    }

    private void writeDeniedResults(List<ToolUseBlock> denied, AgentState state, Agent agent) {
        String agentName = agent != null && agent.getName() != null ? agent.getName() : "agent";
        for (ToolUseBlock call : denied) {
            ToolResultBlock result =
                    ToolResultBlock.text(denyMessage)
                            .withIdAndName(call.getId(), call.getName())
                            .withState(ToolResultState.DENIED);
            Msg msg = ToolResultMessageBuilder.buildToolResultMsg(result, call, agentName);
            state.contextMutable().add(msg);
        }
    }

    private void validate() {
        if (jevCall == null) {
            throw new IllegalArgumentException("jevCall must not be null");
        }
        if (guardedTools.isEmpty()) {
            throw new IllegalArgumentException("guardedTools must not be empty");
        }
        for (String name : guardedTools) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("guarded tool names must not be blank");
            }
        }
        if (safetyThreshold < 0 || safetyThreshold > 1) {
            throw new IllegalArgumentException("safetyThreshold must be between 0 and 1");
        }
        if (denyMessage == null || denyMessage.isBlank()) {
            throw new IllegalArgumentException("denyMessage must not be blank");
        }
    }

    private record Partition(List<ToolUseBlock> guarded, List<ToolUseBlock> unguarded) {}

    private record RiskDecision(List<ToolUseBlock> allowed, List<ToolUseBlock> denied) {}

    public static final class Builder {
        private final Function<SystemOneRequest, Mono<SystemOneResult>> jevCall;
        private final Set<String> guardedTools = new LinkedHashSet<>();
        private double safetyThreshold = 0.5;
        private String denyMessage = DEFAULT_DENY_MESSAGE;
        private boolean failOpen = true;

        private Builder(Function<SystemOneRequest, Mono<SystemOneResult>> jevCall) {
            this.jevCall = jevCall;
        }

        public Builder guardedTool(String toolName) {
            Objects.requireNonNull(toolName, "toolName");
            guardedTools.add(toolName);
            return this;
        }

        public Builder guardedTools(Set<String> toolNames) {
            guardedTools.clear();
            if (toolNames != null) {
                guardedTools.addAll(toolNames);
            }
            return this;
        }

        /**
         * Minimum P(safe) required for a guarded tool call to pass. A call is denied when its
         * calibrated safety probability is below this value.
         *
         * @param safetyThreshold threshold in [0, 1]; defaults to {@code 0.5}
         */
        public Builder safetyThreshold(double safetyThreshold) {
            this.safetyThreshold = safetyThreshold;
            return this;
        }

        public Builder denyMessage(String denyMessage) {
            this.denyMessage = denyMessage;
            return this;
        }

        public Builder failOpen(boolean failOpen) {
            this.failOpen = failOpen;
            return this;
        }

        public JevAutoModeMiddleware build() {
            return new JevAutoModeMiddleware(this);
        }
    }
}
