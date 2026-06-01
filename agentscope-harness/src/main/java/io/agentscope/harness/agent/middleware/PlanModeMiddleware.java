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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Enforces plan mode: while {@code AgentState.planModeContext.planActive} is {@code true}, mutating
 * tool calls are denied so the agent can only read / investigate and draft a plan.
 *
 * <p>Enforcement is deterministic and happens in {@link #onActing}: each tool call is allowed iff
 * it is read-only or one of the plan-control tools ({@code plan_enter} / {@code plan_write} /
 * {@code plan_exit} / {@code todo_write}); everything else gets a synthetic {@code DENIED} tool
 * result (written to context and streamed as events) without being executed. This intentionally
 * does <em>not</em> reuse {@code PermissionMode.EXPLORE}: that mode is snapshotted immutably by the
 * {@code PermissionEngine} at construction and cannot be toggled at runtime, whereas plan mode must
 * switch dynamically.
 *
 * <p>{@link #onSystemPrompt} injects a plan-mode banner so the model knows it is in the read-only
 * design phase.
 */
public class PlanModeMiddleware implements MiddlewareBase {

    private static final Set<String> ALWAYS_ALLOWED =
            Set.of(
                    PlanModeTools.PLAN_ENTER,
                    PlanModeTools.PLAN_WRITE,
                    PlanModeTools.PLAN_EXIT,
                    "todo_write");

    private static final String DENY_MESSAGE =
            "Blocked: you are in PLAN mode (read-only). You may investigate and run read-only"
                    + " tools, record your plan with plan_write, and call plan_exit when ready to"
                    + " execute. Do not modify files or run mutating commands until the plan is"
                    + " approved.";

    private static final String PLAN_BANNER =
            """

            <system-reminder>
            PLAN MODE is active. This is a READ-ONLY design phase: investigate the problem and draft
            a plan, but do NOT modify files, run mutating commands, or otherwise change state. Record
            your plan with the plan_write tool. When the plan is complete, call plan_exit to ask the
            user for approval; only after approval will you return to BUILD mode and be able to make
            changes.
            </system-reminder>\
            """;

    private final PlanModeManager manager;
    private final Predicate<String> readOnlyResolver;

    /**
     * @param manager shared plan-mode state/file coordinator
     * @param readOnlyResolver resolves whether a tool (by name) is read-only; used to decide which
     *     calls are permitted while plan mode is active
     */
    public PlanModeMiddleware(PlanModeManager manager, Predicate<String> readOnlyResolver) {
        this.manager = manager;
        this.readOnlyResolver = readOnlyResolver != null ? readOnlyResolver : name -> false;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        AgentState state = agent != null ? agent.getAgentState() : null;
        if (!manager.isPlanActive(state)) {
            return Mono.just(currentPrompt != null ? currentPrompt : "");
        }
        return Mono.just((currentPrompt != null ? currentPrompt : "") + PLAN_BANNER);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        AgentState state = agent != null ? agent.getAgentState() : null;
        if (!manager.isPlanActive(state) || input.toolCalls() == null) {
            return next.apply(input);
        }

        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isPermitted(call.getName())) {
                allowed.add(call);
            } else {
                denied.add(call);
            }
        }

        if (denied.isEmpty()) {
            return next.apply(input);
        }

        String replyId = state.getReplyId();
        String agentName = agent.getName();

        // Deferred so the context mutation + events run once, at subscription time.
        Flux<AgentEvent> deniedFlux =
                Flux.defer(
                        () -> {
                            List<AgentEvent> events = new ArrayList<>();
                            for (ToolUseBlock call : denied) {
                                ToolResultBlock result =
                                        ToolResultBlock.text(DENY_MESSAGE)
                                                .withIdAndName(call.getId(), call.getName())
                                                .withState(ToolResultState.DENIED);
                                Msg msg =
                                        ToolResultMessageBuilder.buildToolResultMsg(
                                                result, call, agentName);
                                state.contextMutable().add(msg);
                                events.add(
                                        new ToolResultStartEvent(
                                                replyId, call.getId(), call.getName()));
                                events.add(
                                        new ToolResultTextDeltaEvent(
                                                replyId, call.getId(), DENY_MESSAGE));
                                events.add(
                                        new ToolResultEndEvent(
                                                replyId, call.getId(), ToolResultState.DENIED));
                            }
                            return Flux.fromIterable(events);
                        });

        if (allowed.isEmpty()) {
            return deniedFlux;
        }
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    private boolean isPermitted(String toolName) {
        if (toolName == null) {
            return false;
        }
        return ALWAYS_ALLOWED.contains(toolName) || readOnlyResolver.test(toolName);
    }
}
