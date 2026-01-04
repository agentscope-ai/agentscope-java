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
package io.agentscope.core.plan.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SubTaskTest {

    @Test
    void testDefaultConstructor() {
        SubTask task = new SubTask();

        assertNotNull(task.getCreatedAt());
        assertEquals(SubTaskState.TODO, task.getState());
        assertNull(task.getName());
        assertNull(task.getDescription());
        assertNull(task.getExpectedOutcome());
    }

    @Test
    void testParameterizedConstructor() {
        SubTask task = new SubTask("Task1", "Description1", "Expected1");

        assertEquals("Task1", task.getName());
        assertEquals("Description1", task.getDescription());
        assertEquals("Expected1", task.getExpectedOutcome());
        assertEquals(SubTaskState.TODO, task.getState());
        assertNotNull(task.getCreatedAt());
    }

    @Test
    void testFinishSubTask() {
        SubTask task = new SubTask("Task", "Desc", "Expected");

        task.finish("Actual outcome");

        assertEquals(SubTaskState.DONE, task.getState());
        assertEquals("Actual outcome", task.getOutcome());
        assertNotNull(task.getFinishedAt());
    }

    @Test
    void testFinishSubTaskWithState() {
        SubTask task = new SubTask("Task", "Desc", "Expected");

        task.finish(SubTaskState.ABANDONED, "Given up");

        assertEquals(SubTaskState.ABANDONED, task.getState());
        assertEquals("Given up", task.getOutcome());
        assertNotNull(task.getFinishedAt());
    }

    @Test
    void testFinishSubTaskWithDoneState() {
        SubTask task = new SubTask("Task", "Desc", "Expected");

        task.finish(SubTaskState.DONE, "Success");

        assertEquals(SubTaskState.DONE, task.getState());
        assertEquals("Success", task.getOutcome());
        assertNotNull(task.getFinishedAt());
    }

    @Test
    void testFinishSubTaskWithInvalidState() {
        SubTask task = new SubTask("Task", "Desc", "Expected");

        // Should throw exception when attempting to finish with TODO
        Exception exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> {
                            task.finish(SubTaskState.TODO, "Not actually done");
                        });
        assertTrue(exception.getMessage().contains("TODO"));

        // Should throw exception when attempting to finish with IN_PROGRESS
        exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> {
                            task.finish(SubTaskState.IN_PROGRESS, "Still working");
                        });
        assertTrue(exception.getMessage().contains("IN_PROGRESS"));
    }

    @Test
    void testToOneLineMarkdown_TodoState() {
        SubTask task = new SubTask("Task1", "Desc", "Expected");

        String markdown = task.toOneLineMarkdown();

        assertEquals("- [ ] Task1", markdown);
    }

    @Test
    void testToOneLineMarkdown_InProgressState() {
        SubTask task = new SubTask("Task1", "Desc", "Expected");
        task.setState(SubTaskState.IN_PROGRESS);

        String markdown = task.toOneLineMarkdown();

        assertEquals("- [ ] [WIP] Task1", markdown);
    }

    @Test
    void testToOneLineMarkdown_DoneState() {
        SubTask task = new SubTask("Task1", "Desc", "Expected");
        task.finish("Done");

        String markdown = task.toOneLineMarkdown();

        assertEquals("- [x] Task1", markdown);
    }

    @Test
    void testToOneLineMarkdown_AbandonedState() {
        SubTask task = new SubTask("Task1", "Desc", "Expected");
        task.setState(SubTaskState.ABANDONED);

        String markdown = task.toOneLineMarkdown();

        assertEquals("- [ ] [Abandoned] Task1", markdown);
    }

    @Test
    void testToMarkdown_Simple() {
        SubTask task = new SubTask("Task1", "Desc", "Expected");

        String markdown = task.toMarkdown(false);

        assertEquals("- [ ] Task1", markdown);
    }

    @Test
    void testToMarkdown_Detailed() {
        SubTask task = new SubTask("Task1", "Description", "Expected");

        String markdown = task.toMarkdown(true);

        assertTrue(markdown.contains("- [ ] Task1"));
        assertTrue(markdown.contains("Created At:"));
        assertTrue(markdown.contains("Description: Description"));
        assertTrue(markdown.contains("Expected Outcome: Expected"));
        assertTrue(markdown.contains("State: todo"));
    }

    @Test
    void testToMarkdown_Detailed_WhenDone() {
        SubTask task = new SubTask("Task1", "Desc", "Expected");
        task.finish("Actual outcome");

        String markdown = task.toMarkdown(true);

        assertTrue(markdown.contains("- [x] Task1"));
        assertTrue(markdown.contains("Finished At:"));
        assertTrue(markdown.contains("Actual Outcome: Actual outcome"));
    }

    @Test
    void testTimeFormat() {
        SubTask task = new SubTask("Task", "Desc", "Expected");
        String createdAt = task.getCreatedAt();

        // Verify format: yyyy-MM-dd HH:mm:ss
        assertTrue(createdAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void testSettersAndGetters() {
        SubTask task = new SubTask();

        task.setName("NewName");
        assertEquals("NewName", task.getName());

        task.setDescription("NewDesc");
        assertEquals("NewDesc", task.getDescription());

        task.setExpectedOutcome("NewOutcome");
        assertEquals("NewOutcome", task.getExpectedOutcome());

        task.setState(SubTaskState.IN_PROGRESS);
        assertEquals(SubTaskState.IN_PROGRESS, task.getState());

        task.setOutcome("ActualOutcome");
        assertEquals("ActualOutcome", task.getOutcome());

        task.setCreatedAt("2024-01-01 12:00:00");
        assertEquals("2024-01-01 12:00:00", task.getCreatedAt());

        task.setFinishedAt("2024-01-01 13:00:00");
        assertEquals("2024-01-01 13:00:00", task.getFinishedAt());
    }

    @Test
    void testStateTransitions() {
        SubTask task = new SubTask("Task", "Desc", "Expected");

        // TODO -> IN_PROGRESS
        task.setState(SubTaskState.IN_PROGRESS);
        assertEquals(SubTaskState.IN_PROGRESS, task.getState());

        // IN_PROGRESS -> DONE via finish()
        task.finish("Completed");
        assertEquals(SubTaskState.DONE, task.getState());

        // Can also set to ABANDONED
        task.setState(SubTaskState.ABANDONED);
        assertEquals(SubTaskState.ABANDONED, task.getState());
    }
}
