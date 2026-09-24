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
package io.agentscope.core.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Explains todo usage and projects state for standalone ReActAgent. A final request preparer
 * that owns task projection suppresses this early pass. Synthetic reminders never enter history.
 */
public class TaskReminderMiddleware implements MiddlewareBase {
    private final boolean todo;
    private final boolean requirements;

    public TaskReminderMiddleware() {
        this(true, false);
    }

    public TaskReminderMiddleware(boolean todo, boolean requirements) {
        this.todo = todo;
        this.requirements = requirements;
    }

    public boolean todoEnabled() {
        return todo;
    }

    public boolean requirementsEnabled() {
        return requirements;
    }

    private static final String GROUNDING =
            """

            ## Task List
            You have a `todo_write` tool that maintains a structured task list for this session.
            Use it for multi-step work: capture the plan as todos, keep exactly one task
            `in_progress`, and update the whole list as you make progress. Your current list (if
            any) is available as the latest complete tool receipt or a `<TASK_STATE>` projection.
            Use the latest state, not older statuses. These are agent-maintained progress records,
            not independent evidence of successful verification.
            Never infer task acceptance from all todos being completed.\
            """;

    private static final String REQUIREMENTS_GROUNDING =
            """

            ## Candidate requirements
            Use task_requirement_propose to capture candidate constraints or acceptance criteria.
            A user-message or PLAN.md reference does not itself confirm a proposal. Only explicit
            caller/user decisions can confirm it. CONFIRMED means authorized, not verified or satisfied.
            Never infer task acceptance from all todos being completed.\
            """;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt != null ? currentPrompt : "";
        return Mono.just(
                base + (todo ? GROUNDING : "") + (requirements ? REQUIREMENTS_GROUNDING : ""));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (agent instanceof ReActAgent react
                && react.getModelRequestPreparer() != null
                && react.getModelRequestPreparer().handlesTaskProjection()) {
            return next.apply(input);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (state == null) {
            return next.apply(input);
        }
        List<Msg> messages =
                TaskContextProjection.project(
                        input.messages() == null ? List.of() : input.messages(),
                        state.getTasksContext(),
                        requirements);
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }
}
