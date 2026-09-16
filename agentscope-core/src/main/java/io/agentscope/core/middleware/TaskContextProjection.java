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
                                    result.getState()));
                    changed = true;
                } else blocks.add(block);
                position++;
            }
            messages.add(changed ? message.withContent(blocks) : message);
        }
        if (!visible && (!state.getTasks().isEmpty() || state.getRevision() > 0)) {
            messages.add(
                    Msg.builder()
                            .id("harness:todo_state")
                            .role(MsgRole.USER)
                            .name("system")
                            .textContent(
                                    "<TASK_STATE revision=\""
                                            + state.getRevision()
                                            + "\">\n"
                                            + "Current todo status (agent-maintained, not"
                                            + " independent verification):\n"
                                            + rendered.replace("<", "&lt;").replace(">", "&gt;")
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
}
