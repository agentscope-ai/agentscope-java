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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskContextProjectionTest {
    @Test
    void oldReceiptIsReducedWithoutChangingHistory() {
        var state = AgentState.builder().build();
        var tools = new TodoTools();
        var first =
                tools.write(List.of(new TodoItem("old task", "pending", null)), state)
                        .withIdAndName("a", "todo_write");
        var second =
                tools.write(List.of(new TodoItem("new task", "pending", null)), state)
                        .withIdAndName("b", "todo_write");
        Msg old = Msg.builder().role(MsgRole.TOOL).content(first).build();
        Msg latest = Msg.builder().role(MsgRole.TOOL).content(second).build();
        var view = TaskContextProjection.project(List.of(old, latest), state.getTasksContext());
        assertEquals(2, view.size());
        var receipt = (ToolResultBlock) view.get(0).getContent().get(0);
        assertEquals("receipt", receipt.getMetadata().get("context.representation"));
        assertEquals("full", first.getMetadata().get("context.representation"));
        assertEquals(latest, view.get(1));
    }

    @Test
    void latestFullReceiptIsNotDuplicatedAndMissingReceiptIsRebuilt() {
        AgentState state = AgentState.builder().build();
        var result =
                new TodoTools()
                        .write(List.of(new TodoItem("verify", "in_progress", null)), state)
                        .withIdAndName("call-1", "todo_write");
        Msg receipt = Msg.builder().role(MsgRole.TOOL).content(result).build();
        assertEquals(
                List.of(receipt),
                TaskContextProjection.project(List.of(receipt), state.getTasksContext()));
        var projected = TaskContextProjection.project(List.of(), state.getTasksContext());
        assertEquals(1, projected.size());
        assertEquals(1, TaskContextProjection.project(projected, state.getTasksContext()).size());
        assertTrue(state.contextMutable().isEmpty());
    }

    @Test
    void truncatedReceiptDoesNotSuppressCurrentState() {
        AgentState state = AgentState.builder().build();
        var original =
                new TodoTools().write(List.of(new TodoItem("verify", "pending", null)), state);
        var truncated =
                ToolResultBlock.of(
                        "c",
                        "todo_write",
                        TextBlock.builder().text("offloaded").build(),
                        original.getMetadata());
        Msg receipt = Msg.builder().role(MsgRole.TOOL).content(truncated).build();
        assertEquals(
                2, TaskContextProjection.project(List.of(receipt), state.getTasksContext()).size());
    }

    @Test
    void clearAndFailedUpdateHaveCorrectRevisionsAndPersist() {
        AgentState state = AgentState.builder().build();
        TodoTools tool = new TodoTools();
        tool.write(List.of(new TodoItem("A", "pending", null)), state);
        tool.write(List.of(new TodoItem("B", "invalid", null)), state);
        assertEquals(1, state.getTasksContext().getRevision());
        tool.write(List.of(), state);
        assertEquals(2, state.getTasksContext().getRevision());
        var codec = JsonUtils.getJsonCodec();
        var restored =
                codec.fromJson(codec.toJson(state.getTasksContext()), TaskContextState.class);
        assertEquals(2, restored.getRevision());
        assertTrue(
                TaskContextProjection.project(List.of(), restored)
                        .get(0)
                        .getTextContent()
                        .contains("cleared"));
    }
}
