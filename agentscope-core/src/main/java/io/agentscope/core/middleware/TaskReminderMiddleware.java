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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps the agent's todo list in front of the model so long-running tasks don't drift.
 *
 * <p>Two hooks:
 * <ul>
 *   <li>{@link #onSystemPrompt} appends a one-time, static explanation of how to use the
 *       {@code todo_write} tool (grounding).
 *   <li>{@link #onReasoning} appends a fresh {@code <system-reminder>} rendering the current
 *       {@code AgentState.tasksContext} <i>before every reasoning step</i>, so the model always
 *       sees the latest state regardless of how many tool calls happened since.
 * </ul>
 *
 * <p>The reminder is appended <i>transiently</i> to the reasoning input only; it is never written
 * into {@code AgentState.context}, so it is never
 * persisted, compacted, or recalled. It is additionally tagged with
 * {@link Msg#METADATA_SYNTHETIC} so any consumer that does observe it can skip it.
 *
 * <p>Unlike opencode (which leaves the latest list in the most recent tool output), agentscope
 * re-injects the list every turn: after conversation compaction the tool output may be gone, but
 * {@code tasksContext} survives, so this middleware guarantees the list is always visible.
 */
public class TaskReminderMiddleware implements MiddlewareBase {

    private static final String GROUNDING =
            """

            ## Task List
            You have a `todo_write` tool that maintains a structured task list for this session.
            Use it for multi-step work: capture the plan as todos, keep exactly one task
            `in_progress`, and update the whole list as you make progress. Your current list (if
            any) is shown to you before each step inside a `<system-reminder>` block — treat that
            block as the source of truth for task status.\
            """;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt != null ? currentPrompt : "";
        return Mono.just(base + GROUNDING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        List<Task> tasks = state == null ? List.of() : state.getTasksContext().getTasks();
        if (tasks.isEmpty()) {
            return next.apply(input);
        }
        Msg reminder =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("system")
                        .content(TextBlock.builder().text(render(tasks)).build())
                        .metadata(
                                Map.of(
                                        Msg.METADATA_SYNTHETIC,
                                        true,
                                        Msg.METADATA_REMINDER_KIND,
                                        "todo_state"))
                        .build();
        List<Msg> messages =
                input.messages() != null ? new ArrayList<>(input.messages()) : new ArrayList<>();
        messages.add(reminder);
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    private static String render(List<Task> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append(
                "Your current todo list is shown below. This is the source of truth — do not"
                        + " assume earlier statuses still hold. Keep exactly one task in_progress."
                        + " Update the whole list with todo_write as you progress.\n\n");
        for (Task t : tasks) {
            sb.append(marker(t.getState())).append(' ').append(t.getSubject());
            Object priority = t.getMetadata() == null ? null : t.getMetadata().get("priority");
            if (priority != null) {
                sb.append(" (priority: ").append(priority).append(')');
            }
            sb.append('\n');
        }
        sb.append("</system-reminder>");
        return sb.toString();
    }

    private static String marker(Task.State state) {
        return switch (state) {
            case COMPLETED -> "- [x]";
            case IN_PROGRESS -> "- [~]";
            case PENDING -> "- [ ]";
        };
    }
}
