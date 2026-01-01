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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTest {

    @Test
    void testDefaultConstructor() {
        Plan plan = new Plan();

        assertNotNull(plan.getId());
        assertNotNull(plan.getCreatedAt());
        assertEquals(PlanState.TODO, plan.getState());
    }

    @Test
    void testParameterizedConstructor() {
        List<SubTask> subtasks =
                List.of(
                        new SubTask("Task1", "Desc1", "Expected1"),
                        new SubTask("Task2", "Desc2", "Expected2"));

        Plan plan = new Plan("TestPlan", "Description", "ExpectedOutcome", subtasks);

        assertEquals("TestPlan", plan.getName());
        assertEquals("Description", plan.getDescription());
        assertEquals("ExpectedOutcome", plan.getExpectedOutcome());
        assertEquals(2, plan.getSubtasks().size());
        assertNotNull(plan.getId());
        assertNotNull(plan.getCreatedAt());
        assertEquals(PlanState.TODO, plan.getState());
    }

    @Test
    void testFinishPlan_Done() {
        Plan plan = new Plan("Plan", "Desc", "Expected", new ArrayList<>());

        plan.finish(PlanState.DONE, "Completed successfully");

        assertEquals(PlanState.DONE, plan.getState());
        assertEquals("Completed successfully", plan.getOutcome());
        assertNotNull(plan.getFinishedAt());
    }

    @Test
    void testFinishPlan_Abandoned() {
        Plan plan = new Plan("Plan", "Desc", "Expected", new ArrayList<>());

        plan.finish(PlanState.ABANDONED, "User cancelled");

        assertEquals(PlanState.ABANDONED, plan.getState());
        assertEquals("User cancelled", plan.getOutcome());
        assertNotNull(plan.getFinishedAt());
    }

    @Test
    void testToMarkdown_Simple() {
        List<SubTask> subtasks =
                List.of(
                        new SubTask("Task1", "Desc1", "Expected1"),
                        new SubTask("Task2", "Desc2", "Expected2"));

        Plan plan = new Plan("TestPlan", "Description", "ExpectedOutcome", subtasks);

        String markdown = plan.toMarkdown(false);

        assertTrue(markdown.contains("# TestPlan"));
        assertTrue(markdown.contains("**Description**: Description"));
        assertTrue(markdown.contains("**Expected Outcome**: ExpectedOutcome"));
        assertTrue(markdown.contains("**State**: todo"));
        assertTrue(markdown.contains("## Subtasks"));
        assertTrue(markdown.contains("- [ ] Task1"));
        assertTrue(markdown.contains("- [ ] Task2"));
    }

    @Test
    void testToMarkdown_Detailed() {
        List<SubTask> subtasks =
                List.of(
                        new SubTask("Task1", "Desc1", "Expected1"),
                        new SubTask("Task2", "Desc2", "Expected2"));

        Plan plan = new Plan("TestPlan", "Description", "ExpectedOutcome", subtasks);

        String markdown = plan.toMarkdown(true);

        assertTrue(markdown.contains("# TestPlan"));
        assertTrue(markdown.contains("## Subtasks"));
        assertTrue(markdown.contains("- [ ] Task1"));
        assertTrue(markdown.contains("Description: Desc1"));
        assertTrue(markdown.contains("Expected Outcome: Expected1"));
        assertTrue(markdown.contains("- [ ] Task2"));
        assertTrue(markdown.contains("Description: Desc2"));
    }

    @Test
    void testTimeFormat() {
        Plan plan = new Plan("Plan", "Desc", "Expected", new ArrayList<>());
        String createdAt = plan.getCreatedAt();

        // Verify format: yyyy-MM-dd HH:mm:ss
        assertTrue(createdAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void testUniqueIds() {
        Plan plan1 = new Plan("Plan1", "Desc", "Expected", new ArrayList<>());
        Plan plan2 = new Plan("Plan2", "Desc", "Expected", new ArrayList<>());

        assertNotEquals(plan1.getId(), plan2.getId());
    }

    @Test
    void testSettersAndGetters() {
        Plan plan = new Plan();

        plan.setId("custom-id");
        assertEquals("custom-id", plan.getId());

        plan.setName("NewName");
        assertEquals("NewName", plan.getName());

        plan.setDescription("NewDesc");
        assertEquals("NewDesc", plan.getDescription());

        plan.setExpectedOutcome("NewOutcome");
        assertEquals("NewOutcome", plan.getExpectedOutcome());

        List<SubTask> subtasks = List.of(new SubTask("Task", "Desc", "Expected"));
        plan.setSubtasks(subtasks);
        assertEquals(1, plan.getSubtasks().size());

        plan.setState(PlanState.IN_PROGRESS);
        assertEquals(PlanState.IN_PROGRESS, plan.getState());

        plan.setOutcome("FinalOutcome");
        assertEquals("FinalOutcome", plan.getOutcome());

        plan.setCreatedAt("2024-01-01 12:00:00");
        assertEquals("2024-01-01 12:00:00", plan.getCreatedAt());

        plan.setFinishedAt("2024-01-01 13:00:00");
        assertEquals("2024-01-01 13:00:00", plan.getFinishedAt());
    }

    @Test
    void testSubtaskManagement() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Expected1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Expected2"));

        Plan plan = new Plan("Plan", "Desc", "Expected", subtasks);

        assertEquals(2, plan.getSubtasks().size());

        // Can modify subtasks list
        plan.getSubtasks().get(0).setState(SubTaskState.DONE);
        assertEquals(SubTaskState.DONE, plan.getSubtasks().get(0).getState());

        // Can add subtasks
        plan.getSubtasks().add(new SubTask("Task3", "Desc3", "Expected3"));
        assertEquals(3, plan.getSubtasks().size());
    }

    @Test
    void testStateTransitions() {
        Plan plan = new Plan("Plan", "Desc", "Expected", new ArrayList<>());

        // TODO -> IN_PROGRESS
        plan.setState(PlanState.IN_PROGRESS);
        assertEquals(PlanState.IN_PROGRESS, plan.getState());

        // IN_PROGRESS -> DONE via finish()
        plan.finish(PlanState.DONE, "Completed");
        assertEquals(PlanState.DONE, plan.getState());

        // Can set to ABANDONED
        plan.setState(PlanState.ABANDONED);
        assertEquals(PlanState.ABANDONED, plan.getState());
    }
}
