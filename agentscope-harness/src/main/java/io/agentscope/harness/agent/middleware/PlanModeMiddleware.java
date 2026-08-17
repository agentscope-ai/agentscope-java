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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
public class PlanModeMiddleware implements HarnessRuntimeMiddleware {

    private static final Set<String> ALWAYS_ALLOWED =
            Set.of(
                    PlanModeTools.PLAN_ENTER,
                    PlanModeTools.PLAN_WRITE,
                    PlanModeTools.PLAN_EXIT,
                    "todo_write",
                    "agent_spawn",
                    "agent_send",
                    "agent_list",
                    "task_output",
                    "task_list");

    private static final String DENY_MESSAGE =
            "Blocked: you are in PLAN mode (read-only). You may investigate and run read-only"
                    + " tools, record your plan with plan_write, and call plan_exit when ready to"
                    + " execute. Do not modify files or run mutating commands until the plan is"
                    + " approved.";

    private static final String PLAN_BANNER_TEMPLATE =
            """

            <system-reminder>
            PLAN MODE is active (read-only). Plan file: %s
            Investigate the problem and draft a plan, but do NOT modify files, run mutating commands,
            or otherwise change state. Record your plan with the plan_write tool. When the plan is
            complete, call plan_exit to ask the user for approval; only after approval will you return
            to BUILD mode and be able to make changes.
            ACT, do not just narrate: when you decide to record or finish the plan, call plan_write
            (or plan_exit) in the SAME step — never say you "will write the plan" without actually
            calling the tool, and never claim a plan exists unless you have called plan_write.
            If you cannot produce a concrete plan because you lack information (for example the system
            you were asked to work on is not present in this workspace), STOP and ask the user one
            specific clarifying question instead of inventing a plan or assuming details.
            </system-reminder>\
            """;

    private static final String BUILD_MODE_PLAN_HINT =
            "\n\n<system-reminder>You have switched from PLAN to BUILD mode; the read-only"
                    + " restriction is lifted. An approved plan exists at %s — read it for the"
                    + " details, then EXECUTE it step by step until the task is complete. Do NOT"
                    + " stop after merely producing the plan. If a todo list is available, capture"
                    + " the plan's steps with the todo_write tool and keep exactly one task"
                    + " in_progress as you work through them.</system-reminder>";

    private static final String PLAN_EXTRA_TOOLS_HINT =
            "\n\n<system-reminder>The following tool(s) are additionally available during PLAN"
                    + " mode for read-only investigation: %s. Use them ONLY to read/inspect (e.g."
                    + " cat, ls, grep, git log/diff/show/status). Do NOT run mutating commands"
                    + " (file writes, installs, git commit, rm, mv, network side effects, etc.)"
                    + " until the plan is approved.</system-reminder>";

    private final PlanModeManager manager;
    private final Predicate<String> readOnlyResolver;
    private final Set<String> additionalAllowed;

    /**
     * @param manager shared plan-mode state/file coordinator
     * @param readOnlyResolver resolves whether a tool (by name) is read-only; used to decide which
     *     calls are permitted while plan mode is active
     */
    public PlanModeMiddleware(PlanModeManager manager, Predicate<String> readOnlyResolver) {
        this(manager, readOnlyResolver, Set.of());
    }

    /**
     * @param manager shared plan-mode state/file coordinator
     * @param readOnlyResolver resolves whether a tool (by name) is read-only; used to decide which
     *     calls are permitted while plan mode is active
     * @param additionalAllowed extra tool names that are permitted while plan mode is active even
     *     when not read-only (opt-in escape hatch — e.g. {@code execute} for shell-based
     *     investigation). The model is instructed via the plan banner to use them read-only only.
     */
    public PlanModeMiddleware(
            PlanModeManager manager,
            Predicate<String> readOnlyResolver,
            Set<String> additionalAllowed) {
        this.manager = manager;
        this.readOnlyResolver = readOnlyResolver != null ? readOnlyResolver : name -> false;
        this.additionalAllowed =
                additionalAllowed == null || additionalAllowed.isEmpty()
                        ? Set.of()
                        : new LinkedHashSet<>(additionalAllowed);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String base = currentPrompt != null ? currentPrompt : "";

        if (manager.isPlanActive(state)) {
            String path = manager.planFilePath(state);
            String banner = base + PLAN_BANNER_TEMPLATE.formatted(path);
            if (!additionalAllowed.isEmpty()) {
                String tools = additionalAllowed.stream().collect(Collectors.joining(", "));
                banner += PLAN_EXTRA_TOOLS_HINT.formatted(tools);
            }
            return Mono.just(banner);
        }

        // BUILD mode: if a plan file was previously written, surface its path so the model
        // can re-read it after compaction without needing to remember the original tool call.
        String planFile = state != null ? state.getPlanModeContext().getCurrentPlanFile() : null;
        if (planFile != null && !planFile.isBlank()) {
            return Mono.just(base + BUILD_MODE_PLAN_HINT.formatted(planFile));
        }
        return Mono.just(base);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
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
                                                replyId,
                                                call.getId(),
                                                call.getName(),
                                                DENY_MESSAGE));
                                events.add(
                                        new ToolResultEndEvent(
                                                replyId,
                                                call.getId(),
                                                call.getName(),
                                                ToolResultState.DENIED));
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
        return ALWAYS_ALLOWED.contains(toolName)
                || additionalAllowed.contains(toolName)
                || readOnlyResolver.test(toolName);
    }
}
