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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.state.TaskRequirement;
import io.agentscope.core.tool.builtin.TodoTools;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure, transient projection. Never appends reminders to durable conversation history. */
public final class TaskContextProjection {
    private TaskContextProjection() {}

    private static boolean tracked(ToolResultBlock result) {
        return "todo_write".equals(result.getName())
                && "tasksContext".equals(result.getMetadata().get("context.state_key"));
    }

    private static boolean current(
            ToolResultBlock result, TaskContextState state, String rendered) {
        return tracked(result)
                && result.getMetadata().get("context.state_revision") instanceof Number revision
                && revision.longValue() == state.getRevision()
                && "full".equals(result.getMetadata().get("context.representation"))
                && result.getOutput().size() == 1
                && result.getOutput().get(0) instanceof TextBlock text
                && rendered.equals(text.getText());
    }

    public static List<Msg> project(List<Msg> input, TaskContextState state) {
        return project(input, state, true);
    }

    /** Requirement/evidence projections require an explicitly enabled capability. */
    public static List<Msg> project(List<Msg> input, TaskContextState state, boolean assurance) {
        return project(input, state, assurance, assurance);
    }

    /** Selects requirement and verification projections independently. */
    public static List<Msg> project(
            List<Msg> input,
            TaskContextState state,
            boolean requirements,
            boolean verificationResults) {
        state = state.snapshot();
        List<Msg> messages = new ArrayList<>();
        String rendered = TodoTools.render(state.getTasks());
        int lastFull = -1;
        int position = 0;
        for (Msg message : input) {
            for (var block : message.getContent()) {
                if (block instanceof ToolResultBlock result && current(result, state, rendered)) {
                    lastFull = position;
                }
                position++;
            }
        }
        boolean visible = lastFull >= 0;
        position = 0;
        for (Msg message : input) {
            if (message.getMetadata() != null
                    && Boolean.TRUE.equals(message.getMetadata().get(Msg.METADATA_SYNTHETIC))
                    && "todo_state".equals(message.getMetadata().get(Msg.METADATA_REMINDER_KIND))) {
                position += message.getContent().size();
                continue;
            }
            List<ContentBlock> blocks = new ArrayList<>();
            boolean changed = false;
            for (var block : message.getContent()) {
                if (block instanceof ToolResultBlock result
                        && tracked(result)
                        && "full".equals(result.getMetadata().get("context.representation"))
                        && position != lastFull) {
                    var metadata = new HashMap<>(result.getMetadata());
                    metadata.put("context.representation", "receipt");
                    blocks.add(
                            new ToolResultBlock(
                                    result.getId(),
                                    result.getName(),
                                    List.of(
                                            TextBlock.builder()
                                                    .text(
                                                            "Todo update recorded at revision "
                                                                    + result.getMetadata()
                                                                            .get(
                                                                                    "context.state_revision")
                                                                    + "; use the current task"
                                                                    + " state, not this historical"
                                                                    + " status.")
                                                    .build()),
                                    metadata,
                                    result.getState(),
                                    result.getExecutionDetails()));
                    changed = true;
                } else blocks.add(block);
                position++;
            }
            messages.add(changed ? message.withContent(blocks) : message);
        }
        if ((!visible && (!state.getTasks().isEmpty() || state.getRevision() > 0))
                || (requirements && !state.getRequirements().isEmpty())
                || ((requirements || verificationResults) && state.getScope() != null)
                || (verificationResults && !state.getVerifications().isEmpty())) {
            messages.add(
                    Msg.builder()
                            .id("harness:todo_state")
                            .role(MsgRole.USER)
                            .name("system")
                            .textContent(
                                    "<TASK_STATE revision=\""
                                            + state.getRevision()
                                            + "\">\n"
                                            + ((requirements || verificationResults)
                                                    ? renderScope(state.getScope())
                                                    : "")
                                            + (visible
                                                    ? "Todo progress: use the current full tool"
                                                            + " receipt.\n"
                                                    : "Current todo status (agent-maintained, not"
                                                            + " independent verification):\n"
                                                            + escape(rendered))
                                            + (requirements
                                                    ? renderRequirements(state.getRequirements())
                                                    : "")
                                            + (verificationResults
                                                    ? renderVerifications(state)
                                                    : "")
                                            + "\n</TASK_STATE>")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_SYNTHETIC,
                                            true,
                                            Msg.METADATA_REMINDER_KIND,
                                            "todo_state"))
                            .build());
        }
        return List.copyOf(messages);
    }

    private static String renderRequirements(List<TaskRequirement> requirements) {
        if (requirements.isEmpty()) return "";
        StringBuilder text =
                new StringBuilder(
                        "\n"
                                + "<REQUIREMENTS>\n"
                                + "CANDIDATE = unconfirmed proposal; CONFIRMED = authorized"
                                + " requirement, not verified satisfaction; REJECTED = inactive."
                                + " Proposed source references are unverified.\n");
        for (var requirement : requirements) {
            text.append(escape(requirement.id()))
                    .append(" | ")
                    .append(requirement.kind())
                    .append(" | ")
                    .append(requirement.status())
                    .append(" | ")
                    .append(escape(requirement.text()))
                    .append("\nProposed source: ")
                    .append(escape(requirement.proposedSourceRef()));
            if (requirement.decision() != null) {
                text.append("\nDecision: ")
                        .append(requirement.decision().authority())
                        .append(" | ")
                        .append(escape(requirement.decision().sourceRef()));
            }
            text.append('\n');
        }
        return text.append("</REQUIREMENTS>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String renderVerifications(TaskContextState state) {
        if (state.getVerifications().isEmpty()) return "";
        StringBuilder text =
                new StringBuilder(
                        "\n"
                            + "<VERIFICATION>\n"
                            + "These are bounded checks, not an overall task completion verdict."
                            + " STALE evidence must not be used for current acceptance.\n");
        for (var report : state.getVerifications()) {
            boolean current = report.binding().equals(state.getEvidenceBinding());
            text.append(escape(report.requirementId()))
                    .append(" | ")
                    .append(current ? report.outcome().name() : "STALE")
                    .append(" | verifier=")
                    .append(escape(report.verifierId()))
                    .append(" | evidence=verification_")
                    .append(escape(report.id()))
                    .append(" | action=")
                    .append(escape(report.actionId()))
                    .append("\nChecked version: ")
                    .append(escape(report.binding().subjectVersion()))
                    .append("\nReason: ")
                    .append(escape(report.reason()))
                    .append('\n');
        }
        return text.append("</VERIFICATION>").toString();
    }

    private static String renderScope(TaskContextState.Scope scope) {
        if (scope == null) return "";
        return "Task: "
                + escape(scope.taskId())
                + "\nObjective: "
                + escape(scope.objective())
                + "\nObjective source: "
                + escape(scope.sourceRef())
                + "\n";
    }
}
