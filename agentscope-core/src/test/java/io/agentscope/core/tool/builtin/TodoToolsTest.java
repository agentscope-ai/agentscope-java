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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskRequirement;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import io.agentscope.core.util.JsonSchemaUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodoToolsTest {
    @Test
    void modelToolOnlyProposesAndSchemaHasNoConfirmationFields() {
        var state = AgentState.builder().build();
        var result =
                new RequirementTools()
                        .proposeRequirement(
                                TaskRequirement.Kind.CONSTRAINT,
                                "No deployment",
                                "message:1",
                                state);
        assertFalse(result.getState() == ToolResultState.ERROR);
        assertEquals(
                TaskRequirement.Status.CANDIDATE,
                state.getTasksContext().getRequirements().get(0).status());
        var toolkit = new Toolkit();
        toolkit.registerTool(new RequirementTools());
        var schema =
                toolkit.getToolSchemas().stream()
                        .filter(tool -> tool.getName().equals("task_requirement_propose"))
                        .findFirst()
                        .orElseThrow();
        var properties = (Map<?, ?>) schema.getParameters().get("properties");
        assertEquals(3, properties.size());
        assertTrue(properties.containsKey("kind"));
        assertTrue(properties.containsKey("text"));
        assertTrue(properties.containsKey("source_ref"));
        assertFalse(
                toolkit.getToolSchemas().stream()
                        .anyMatch(tool -> tool.getName().contains("confirm")));
    }

    @Test
    void invalidProposalAndInvalidTodoDoNotMutateState() {
        var state = AgentState.builder().build();
        assertEquals(
                ToolResultState.ERROR,
                new RequirementTools()
                        .proposeRequirement(TaskRequirement.Kind.CONSTRAINT, "", "message:1", state)
                        .getState());
        assertEquals(
                ToolResultState.ERROR,
                new TodoTools()
                        .write(List.of(new TodoItem("task", "invalid", null)), state)
                        .getState());
        assertEquals(0, state.getTasksContext().getRevision());
    }

    private final TodoTools tool = new TodoTools();

    @Test
    void successfulWritePreservesContextReceiptAndLegacyTextApi() {
        AgentState state = AgentState.builder().build();
        ToolResultBlock result = tool.write(List.of(new TodoItem("Task", "pending", null)), state);
        String text = assertText(result, ToolResultState.SUCCESS);
        assertEquals("tasksContext", result.getMetadata().get("context.state_key"));
        assertEquals(
                state.getTasksContext().getRevision(),
                result.getMetadata().get("context.state_revision"));
        assertEquals("full", result.getMetadata().get("context.representation"));
        assertEquals(text, tool.todoWrite(List.of(new TodoItem("Task", "pending", null)), state));
    }

    @Test
    void nullTodoDoesNotReplaceExistingTasks() {
        AgentState state = AgentState.builder().build();
        tool.write(List.of(new TodoItem("Keep me", "pending", null)), state);
        assertText(
                tool.write(java.util.Arrays.asList((TodoItem) null), state), ToolResultState.ERROR);
        assertEquals("Keep me", state.getTasksContext().getTasks().get(0).getSubject());
    }

    @Test
    void rejectsBlankContentWithoutReplacingExistingTasks() {
        AgentState state = AgentState.builder().build();
        tool.write(List.of(new TodoItem("Keep me", "pending", null)), state);
        assertText(
                tool.write(List.of(new TodoItem(" ", "pending", null)), state),
                ToolResultState.ERROR);
        assertEquals("Keep me", state.getTasksContext().getTasks().get(0).getSubject());
    }

    @Test
    void errorLookingTodoContentIsSuccessfulData() {
        AgentState state = AgentState.builder().build();
        String text =
                assertText(
                        tool.write(
                                List.of(
                                        new TodoItem(
                                                "[ERROR] investigate log entry", "pending", null)),
                                state),
                        ToolResultState.SUCCESS);
        assertTrue(text.contains("[ERROR] investigate log entry"));
    }

    @Test
    void writesFullListIntoTasksContext() {
        AgentState state = AgentState.builder().build();
        String out =
                assertText(
                        tool.write(
                                List.of(
                                        new TodoItem("Investigate bug", "in_progress", "high"),
                                        new TodoItem("Write fix", "pending", null),
                                        new TodoItem("Add test", "pending", "low")),
                                state),
                        ToolResultState.SUCCESS);

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
        tool.write(
                List.of(
                        new TodoItem("A", "completed", null),
                        new TodoItem("B", "in_progress", null)),
                state);
        tool.write(List.of(new TodoItem("B", "completed", null)), state);

        List<Task> tasks = state.getTasksContext().getTasks();
        assertEquals(1, tasks.size());
        assertEquals("B", tasks.get(0).getSubject());
        assertEquals(Task.State.COMPLETED, tasks.get(0).getState());
    }

    @Test
    void preservesIdAcrossRewritesWhenContentMatches() {
        AgentState state = AgentState.builder().build();
        tool.write(List.of(new TodoItem("Stable", "pending", null)), state);
        String firstId = state.getTasksContext().getTasks().get(0).getId();

        tool.write(List.of(new TodoItem("Stable", "in_progress", null)), state);
        String secondId = state.getTasksContext().getTasks().get(0).getId();

        assertEquals(firstId, secondId);
    }

    @Test
    void rejectsMultipleInProgress() {
        AgentState state = AgentState.builder().build();
        String out =
                assertText(
                        tool.write(
                                List.of(
                                        new TodoItem("A", "in_progress", null),
                                        new TodoItem("B", "in_progress", null)),
                                state),
                        ToolResultState.ERROR);
        assertTrue(out.toLowerCase().contains("at most one"));
        // State must be left untouched on rejection.
        assertTrue(state.getTasksContext().getTasks().isEmpty());
    }

    @Test
    void rejectsInvalidStatus() {
        AgentState state = AgentState.builder().build();
        String out =
                assertText(
                        tool.write(List.of(new TodoItem("A", "doing", null)), state),
                        ToolResultState.ERROR);
        assertTrue(out.toLowerCase().contains("invalid status"));
        assertTrue(state.getTasksContext().getTasks().isEmpty());
    }

    @Test
    void clearingListWithEmptyInput() {
        AgentState state = AgentState.builder().build();
        tool.write(List.of(new TodoItem("A", "pending", null)), state);
        String out = assertText(tool.write(List.of(), state), ToolResultState.SUCCESS);
        assertTrue(state.getTasksContext().getTasks().isEmpty());
        assertTrue(out.toLowerCase().contains("cleared"));
    }

    @Test
    void missingStateReturnsError() {
        String out =
                assertText(
                        tool.write(List.of(new TodoItem("A", "pending", null)), null),
                        ToolResultState.ERROR);
        assertFalse(out.isBlank());
        assertTrue(out.toLowerCase().contains("error"));
    }

    @Test
    void displayFieldsSurviveToolInputAndStateSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TodoItem item =
                mapper.readValue(
                        """
                        {"content":"Compare notices","status":"completed","priority":"high",
                         "description":"Compare Anhui and Nanjing notices",
                         "expectedOutcome":"A concise comparison","outcome":"Found two differences",
                         "metadata":{"region":"east","display":{"expanded":true}}}
                        """,
                        TodoItem.class);
        AgentState state = AgentState.builder().build();
        String result = assertText(tool.write(List.of(item), state), ToolResultState.SUCCESS);
        AgentState restored = mapper.readValue(mapper.writeValueAsString(state), AgentState.class);
        Task task = restored.getTasksContext().getTasks().get(0);
        assertEquals("Compare Anhui and Nanjing notices", task.getDescription());
        assertEquals("A concise comparison", task.getMetadata().get("expectedOutcome"));
        assertEquals("Found two differences", task.getMetadata().get("outcome"));
        assertEquals("east", task.getMetadata().get("region"));
        assertEquals(Map.of("expanded", true), task.getMetadata().get("display"));
        assertEquals("high", task.getMetadata().get("priority"));
        assertTrue(result.contains("Compare Anhui and Nanjing notices"));
        assertTrue(result.contains("Found two differences"));
    }

    @Test
    void optionalDisplayFieldsDoNotBecomeRequired() {
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(TodoItem.class);
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        for (String name : List.of("description", "expectedOutcome", "outcome", "metadata")) {
            assertTrue(properties.containsKey(name), name);
            assertFalse(((List<?>) schema.getOrDefault("required", List.of())).contains(name));
        }
    }

    @Test
    void statusOnlyRewritePreservesDisplayFieldsAndAllowsExplicitUpdates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentState state = AgentState.builder().build();
        TodoItem item =
                mapper.readValue(
                        """
                        {"content":"Task","description":"Details","expectedOutcome":"Expected",
                         "outcome":"Working","metadata":{"region":"east","priority":"invalid"}}
                        """,
                        TodoItem.class);
        tool.write(List.of(item), state);
        Task first = state.getTasksContext().getTasks().get(0);
        assertFalse(first.getMetadata().containsKey("priority"));
        tool.write(List.of(new TodoItem("Task", "completed", "low")), state);
        Task updated = state.getTasksContext().getTasks().get(0);
        assertEquals(first.getId(), updated.getId());
        assertEquals(first.getCreatedAt(), updated.getCreatedAt());
        assertEquals("Details", updated.getDescription());
        assertEquals("Expected", updated.getMetadata().get("expectedOutcome"));
        assertEquals("Working", updated.getMetadata().get("outcome"));
        assertEquals("east", updated.getMetadata().get("region"));
        assertEquals(Task.State.COMPLETED, updated.getState());
        TodoItem cleared =
                mapper.readValue(
                        """
                        {"content":"Task","status":"completed","description":"","outcome":"Done",
                         "metadata":{"region":null,"expectedOutcome":null,"priority":"invalid"}}
                        """,
                        TodoItem.class);
        tool.write(List.of(cleared), state);
        Task last = state.getTasksContext().getTasks().get(0);
        assertEquals("", last.getDescription());
        assertEquals(Map.of("priority", "low", "outcome", "Done"), last.getMetadata());
    }

    @Test
    void legacyJsonRetainsExistingTextAndDescription() throws Exception {
        TodoItem item =
                new ObjectMapper()
                        .readValue(
                                "{\"content\":\"Task\",\"status\":\"pending\",\"priority\":\"high\"}",
                                TodoItem.class);
        AgentState state = AgentState.builder().build();
        assertEquals(
                "1 open todo(s):\n- [ ] Task (priority: high)",
                assertText(tool.write(List.of(item), state), ToolResultState.SUCCESS));
        assertEquals("Task", state.getTasksContext().getTasks().get(0).getDescription());
    }

    @Test
    void toolkitAcceptsDisplayMetadataThroughModelFacingSchema() {
        AgentState state = AgentState.builder().build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        Map<String, Object> input =
                Map.of(
                        "todos",
                        List.of(
                                Map.of(
                                        "content",
                                        "Task",
                                        "status",
                                        "pending",
                                        "description",
                                        "Details",
                                        "expectedOutcome",
                                        "Expected",
                                        "outcome",
                                        "Working",
                                        "metadata",
                                        Map.of("display", Map.of("expanded", true)))));
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("todo-call")
                                                        .name("todo_write")
                                                        .input(input)
                                                        .build())
                                        .runtimeContext(
                                                RuntimeContext.builder().agentState(state).build())
                                        .build())
                        .block();
        assertText(result, ToolResultState.SUCCESS);
        Task task = state.getTasksContext().getTasks().get(0);
        assertEquals("Details", task.getDescription());
        assertEquals(Map.of("expanded", true), task.getMetadata().get("display"));
    }

    private static String assertText(ToolResultBlock result, ToolResultState expected) {
        assertEquals(expected, result.getState());
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
