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
                                new TodoItem(
                                        "Investigate bug", "in_progress", "high", null, null, null,
                                        null),
                                new TodoItem("Write fix", "pending", null, null, null, null, null),
                                new TodoItem(
                                        "Add test", "pending", "low", null, null, null, null)),
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
                        new TodoItem("A", "completed", null, null, null, null, null),
                        new TodoItem("B", "in_progress", null, null, null, null, null)),
                state);
        tool.todoWrite(
                List.of(new TodoItem("B", "completed", null, null, null, null, null)), state);

        List<Task> tasks = state.getTasksContext().getTasks();
        assertEquals(1, tasks.size());
        assertEquals("B", tasks.get(0).getSubject());
        assertEquals(Task.State.COMPLETED, tasks.get(0).getState());
    }

    @Test
    void preservesIdAcrossRewritesWhenContentMatches() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(new TodoItem("Stable", "pending", null, null, null, null, null)), state);
        String firstId = state.getTasksContext().getTasks().get(0).getId();

        tool.todoWrite(
                List.of(new TodoItem("Stable", "in_progress", null, null, null, null, null)),
                state);
        String secondId = state.getTasksContext().getTasks().get(0).getId();

        assertEquals(firstId, secondId);
    }

    @Test
    void rejectsMultipleInProgress() {
        AgentState state = AgentState.builder().build();
        String out =
                tool.todoWrite(
                        List.of(
                                new TodoItem("A", "in_progress", null, null, null, null, null),
                                new TodoItem("B", "in_progress", null, null, null, null, null)),
                        state);
        assertTrue(out.toLowerCase().contains("at most one"));
        // State must be left untouched on rejection.
        assertTrue(state.getTasksContext().getTasks().isEmpty());
    }

    @Test
    void rejectsInvalidStatus() {
        AgentState state = AgentState.builder().build();
        String out =
                tool.todoWrite(
                        List.of(new TodoItem("A", "doing", null, null, null, null, null)), state);
        assertTrue(out.toLowerCase().contains("invalid status"));
        assertTrue(state.getTasksContext().getTasks().isEmpty());
    }

    @Test
    void clearingListWithEmptyInput() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(new TodoItem("A", "pending", null, null, null, null, null)), state);
        String out = tool.todoWrite(List.of(), state);
        assertTrue(state.getTasksContext().getTasks().isEmpty());
        assertTrue(out.toLowerCase().contains("cleared"));
    }

    @Test
    void missingStateReturnsError() {
        String out =
                tool.todoWrite(
                        List.of(new TodoItem("A", "pending", null, null, null, null, null)), null);
        assertFalse(out.isBlank());
        assertTrue(out.toLowerCase().contains("error"));
    }

    @Test
    void descriptionMapsToTaskDescription() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(
                        new TodoItem(
                                "Query Anhui notices",
                                "pending",
                                null,
                                "Retrieve current Anhui market notice list.",
                                null,
                                null,
                                null)),
                state);
        Task task = state.getTasksContext().getTasks().get(0);
        assertEquals("Query Anhui notices", task.getSubject());
        assertEquals("Retrieve current Anhui market notice list.", task.getDescription());
    }

    @Test
    void nullDescriptionFallsBackToContent() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(new TodoItem("Do something", "pending", null, null, null, null, null)),
                state);
        Task task = state.getTasksContext().getTasks().get(0);
        assertEquals("Do something", task.getDescription());
    }

    @Test
    void expectedOutcomeAndOutcomeStoredInMetadata() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(
                        new TodoItem(
                                "Compare notices",
                                "completed",
                                null,
                                null,
                                "A list of differences",
                                "Found 3 key differences",
                                null)),
                state);
        Task task = state.getTasksContext().getTasks().get(0);
        assertEquals("A list of differences", task.getMetadata().get("expectedOutcome"));
        assertEquals("Found 3 key differences", task.getMetadata().get("outcome"));
    }

    @Test
    void extraMetadataMergedIntoTaskMetadata() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(
                        new TodoItem(
                                "Summarize",
                                "pending",
                                "high",
                                null,
                                null,
                                null,
                                java.util.Map.of("region", "Anhui", "source", "official"))),
                state);
        Task task = state.getTasksContext().getTasks().get(0);
        assertEquals("high", task.getMetadata().get("priority"));
        assertEquals("Anhui", task.getMetadata().get("region"));
        assertEquals("official", task.getMetadata().get("source"));
    }

    @Test
    void blankOutcomeNotStoredInMetadata() {
        AgentState state = AgentState.builder().build();
        tool.todoWrite(
                List.of(
                        new TodoItem(
                                "Step A", "pending", null, null, "expected result", "   ", null)),
                state);
        Task task = state.getTasksContext().getTasks().get(0);
        assertEquals("expected result", task.getMetadata().get("expectedOutcome"));
        assertFalse(task.getMetadata().containsKey("outcome"));
    }
}
