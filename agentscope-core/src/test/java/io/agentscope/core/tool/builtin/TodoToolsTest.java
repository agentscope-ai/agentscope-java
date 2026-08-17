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
package io.agentscope.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodoToolsTest {

    private final TodoTools tool = new TodoTools();

    @Test
    void writesFullListIntoTasksContext() {
        AgentState state = AgentState.builder().build();
        String out =
                tool.todoWrite(
                        List.of(
                                new TodoItem("Investigate bug", "in_progress", "high"),
                                new TodoItem("Write fix", "pending", null),
                                new TodoItem("Add test", "pending", "low")),
                        state);

        List<Task> tasks = state.getTasksContext().getTasks();
        assertEquals(3, tasks.size());
        assertEquals(Task.State.IN_PROGRESS, tasks.get(0).getState());
        assertEquals("Investigate bug", tasks.get(0).getSubject());
        assertEquals("high", tasks.get(0).getMetadata().get("priority"));
        assertTrue(out.contains("Investigate bug"));
    }

    @Test
    void fullReplaceRemovesDroppedItems() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(
                        new TodoItem("A", "completed", null),
                        new TodoItem("B", "in_progress", null)),
                state);
        tool.todoWrite(List.of(new TodoItem("B", "completed", null)), state);

        List<Task> tasks = state.getTasksContext().getTasks();
        assertEquals(1, tasks.size());
        assertEquals("B", tasks.get(0).getSubject());
        assertEquals(Task.State.COMPLETED, tasks.get(0).getState());
    }

    @Test
    void preservesIdAcrossRewritesWhenContentMatches() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(List.of(new TodoItem("Stable", "pending", null)), state);
        String firstId = state.getTasksContext().getTasks().get(0).getId();

        tool.todoWrite(List.of(new TodoItem("Stable", "in_progress", null)), state);
        String secondId = state.getTasksContext().getTasks().get(0).getId();

        assertEquals(firstId, secondId);
    }

    @Test
    void rejectsMultipleInProgress() {
        AgentState state = AgentState.builder().build();
        String out =
                tool.todoWrite(
                        List.of(
                                new TodoItem("A", "in_progress", null),
                                new TodoItem("B", "in_progress", null)),
                        state);
        assertTrue(out.toLowerCase().contains("at most one"));
        // State must be left untouched on rejection.
        assertTrue(state.getTasksContext().getTasks().isEmpty());
    }

    @Test
    void rejectsInvalidStatus() {
        AgentState state = AgentState.builder().build();
        String out = tool.todoWrite(List.of(new TodoItem("A", "doing", null)), state);
        assertTrue(out.toLowerCase().contains("invalid status"));
        assertTrue(state.getTasksContext().getTasks().isEmpty());
    }

    @Test
    void clearingListWithEmptyInput() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(List.of(new TodoItem("A", "pending", null)), state);
        String out = tool.todoWrite(List.of(), state);
        assertTrue(state.getTasksContext().getTasks().isEmpty());
        assertTrue(out.toLowerCase().contains("cleared"));
    }

    @Test
    void missingStateReturnsError() {
        String out = tool.todoWrite(List.of(new TodoItem("A", "pending", null)), null);
        assertFalse(out.isBlank());
        assertTrue(out.toLowerCase().contains("error"));
    }
}
