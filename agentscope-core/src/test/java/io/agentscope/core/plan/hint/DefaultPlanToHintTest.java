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
package io.agentscope.core.plan.hint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultPlanToHintTest {

    private DefaultPlanToHint hintGenerator;

    @BeforeEach
    void setUp() {
        hintGenerator = new DefaultPlanToHint();
    }

    @Test
    void testNoPlanHint() {
        String hint = hintGenerator.generateHint(null);

        assertNotNull(hint);
        assertTrue(hint.startsWith("<system-hint>"));
        assertTrue(hint.endsWith("</system-hint>"));
        assertTrue(hint.contains("If the user's query is complex"));
        assertTrue(hint.contains("create a plan first by calling 'create_plan'"));
        assertTrue(hint.contains("programming a website, game or app"));
    }

    @Test
    void testAtTheBeginningHint() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.TODO),
                                createSubTask("Task2", SubTaskState.TODO)));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("<system-hint>"));
        assertTrue(hint.contains("The current plan:"));
        assertTrue(hint.contains("Mark the first subtask as 'in_progress'"));
        assertTrue(hint.contains("update_subtask_state"));
        assertTrue(hint.contains("subtask_idx=0"));
        assertTrue(hint.contains("revise the plan"));
        assertTrue(hint.contains("finish_plan"));
    }

    @Test
    void testSubtaskInProgressHint() {
        SubTask task1 = createSubTask("Task1", SubTaskState.IN_PROGRESS);
        SubTask task2 = createSubTask("Task2", SubTaskState.TODO);
        Plan plan = createPlan(List.of(task1, task2));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("Now the subtask at index 0, named 'Task1', is 'in_progress'"));
        assertTrue(hint.contains("Go on execute the subtask"));
        assertTrue(hint.contains("finish_subtask"));
        assertTrue(hint.contains("revise_current_plan"));
    }

    @Test
    void testSubtaskInProgressHint_AtIndexOne() {
        SubTask task1 = createSubTask("Task1", SubTaskState.DONE);
        SubTask task2 = createSubTask("Task2", SubTaskState.IN_PROGRESS);
        SubTask task3 = createSubTask("Task3", SubTaskState.TODO);
        Plan plan = createPlan(List.of(task1, task2, task3));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("Now the subtask at index 1, named 'Task2', is 'in_progress'"));
    }

    @Test
    void testNoSubtaskInProgressHint() {
        SubTask task1 = createSubTask("Task1", SubTaskState.DONE);
        SubTask task2 = createSubTask("Task2", SubTaskState.TODO);
        Plan plan = createPlan(List.of(task1, task2));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("The first 1 subtasks are done"));
        assertTrue(hint.contains("no subtask 'in_progress'"));
        assertTrue(hint.contains("Mark the next subtask as 'in_progress'"));
    }

    @Test
    void testNoSubtaskInProgressHint_MultipleDone() {
        SubTask task1 = createSubTask("Task1", SubTaskState.DONE);
        SubTask task2 = createSubTask("Task2", SubTaskState.DONE);
        SubTask task3 = createSubTask("Task3", SubTaskState.TODO);
        Plan plan = createPlan(List.of(task1, task2, task3));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("The first 2 subtasks are done"));
    }

    @Test
    void testAtTheEndHint() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.DONE),
                                createSubTask("Task2", SubTaskState.DONE)));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("All the subtasks are done"));
        assertTrue(hint.contains("Finish the plan by calling 'finish_plan'"));
        assertTrue(hint.contains("summarize the whole process"));
    }

    @Test
    void testAtTheEndHint_WithAbandonedSubtasks() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.DONE),
                                createSubTask("Task2", SubTaskState.ABANDONED),
                                createSubTask("Task3", SubTaskState.ABANDONED)));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("All the subtasks are done"));
    }

    @Test
    void testAtTheEndHint_AllAbandoned() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.ABANDONED),
                                createSubTask("Task2", SubTaskState.ABANDONED)));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("All the subtasks are done"));
    }

    @Test
    void testHintIncludesPlanMarkdown() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.TODO),
                                createSubTask("Task2", SubTaskState.TODO)));

        String hint = hintGenerator.generateHint(plan);

        assertTrue(hint.contains("TestPlan"));
        assertTrue(hint.contains("- [ ] Task1"));
        assertTrue(hint.contains("- [ ] Task2"));
    }

    @Test
    void testHintIncludesSubtaskDetails() {
        SubTask task = createSubTask("DetailedTask", SubTaskState.IN_PROGRESS);
        Plan plan = createPlan(List.of(task));

        String hint = hintGenerator.generateHint(plan);

        assertTrue(hint.contains("DetailedTask"));
        assertTrue(hint.contains("Created At:"));
        assertTrue(hint.contains("Description:"));
        assertTrue(hint.contains("Expected Outcome:"));
    }

    @Test
    void testHintFormatting() {
        String hint = hintGenerator.generateHint(null);

        assertTrue(hint.startsWith("<system-hint>"));
        assertTrue(hint.endsWith("</system-hint>"));
        assertFalse(hint.contains("<system-hint><system-hint>"));
        assertFalse(hint.contains("</system-hint></system-hint>"));
    }

    @Test
    void testEmptyPlan() {
        Plan plan = createPlan(new ArrayList<>());

        String hint = hintGenerator.generateHint(plan);

        // Empty plan has no subtasks, so should be treated as "at the beginning"
        // but since there are no subtasks, might return null or a specific message
        // Based on the logic, with 0 subtasks: nInProgress=0, nDone=0
        // This matches "at the beginning" condition
        assertNotNull(hint);
    }

    @Test
    void testSingleSubtaskPlan() {
        Plan plan = createPlan(List.of(createSubTask("OnlyTask", SubTaskState.TODO)));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("Mark the first subtask as 'in_progress'"));
    }

    @Test
    void testSingleSubtaskPlan_Done() {
        Plan plan = createPlan(List.of(createSubTask("OnlyTask", SubTaskState.DONE)));

        String hint = hintGenerator.generateHint(plan);

        assertNotNull(hint);
        assertTrue(hint.contains("All the subtasks are done"));
    }

    // Tests for needUserConfirm parameter

    @Test
    void testNoPlanHint_WithUserConfirmation() {
        String hint = hintGenerator.generateHint(null, true);

        assertNotNull(hint);
        assertTrue(hint.startsWith("<system-hint>"));
        assertTrue(hint.endsWith("</system-hint>"));
        assertTrue(hint.contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testNoPlanHint_WithoutUserConfirmation() {
        String hint = hintGenerator.generateHint(null, false);

        assertNotNull(hint);
        assertTrue(hint.startsWith("<system-hint>"));
        assertTrue(hint.endsWith("</system-hint>"));
        assertTrue(hint.contains("create a plan first by calling 'create_plan'"));
        assertFalse(hint.contains("WAIT FOR USER CONFIRMATION"));
        assertFalse(hint.contains("CRITICAL"));
    }

    @Test
    void testDefaultMethodDelegatesToOverload() {
        // Test that the default method generateHint(plan) calls generateHint(plan, true)
        String hintDefault = hintGenerator.generateHint(null);
        String hintWithConfirm = hintGenerator.generateHint(null, true);

        assertEquals(hintDefault, hintWithConfirm);
    }

    @Test
    void testAtTheBeginningHint_WithUserConfirmation() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.TODO),
                                createSubTask("Task2", SubTaskState.TODO)));

        String hint = hintGenerator.generateHint(plan, true);

        assertNotNull(hint);
        assertTrue(hint.contains("The current plan"));
        assertTrue(hint.contains("Your options include"));
        assertTrue(hint.contains("Mark the first subtask as 'in_progress'"));
        assertTrue(hint.contains("Update before processing each subtask"));
    }

    @Test
    void testAtTheBeginningHint_WithoutUserConfirmation() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.TODO),
                                createSubTask("Task2", SubTaskState.TODO)));

        String hint = hintGenerator.generateHint(plan, false);

        assertNotNull(hint);
        assertTrue(hint.contains("Mark the first subtask as 'in_progress'"));
        assertFalse(hint.contains("WAIT FOR USER CONFIRMATION"));
        assertFalse(hint.contains("DO NOT call 'update_subtask_state'"));
    }

    @Test
    void testSubtaskInProgressHint_WithUserConfirmation() {
        SubTask task1 = createSubTask("Task1", SubTaskState.IN_PROGRESS);
        SubTask task2 = createSubTask("Task2", SubTaskState.TODO);
        Plan plan = createPlan(List.of(task1, task2));

        String hint = hintGenerator.generateHint(plan, true);

        assertNotNull(hint);
        assertTrue(hint.contains("Now the subtask at index 0"));
        assertTrue(hint.contains("Go on execute the subtask"));
        // In-progress hints don't include the wait for confirmation rule
        assertFalse(hint.contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testSubtaskInProgressHint_WithoutUserConfirmation() {
        SubTask task1 = createSubTask("Task1", SubTaskState.IN_PROGRESS);
        SubTask task2 = createSubTask("Task2", SubTaskState.TODO);
        Plan plan = createPlan(List.of(task1, task2));

        String hint = hintGenerator.generateHint(plan, false);

        assertNotNull(hint);
        assertTrue(hint.contains("Now the subtask at index 0"));
        assertFalse(hint.contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testNoSubtaskInProgressHint_WithUserConfirmation() {
        SubTask task1 = createSubTask("Task1", SubTaskState.DONE);
        SubTask task2 = createSubTask("Task2", SubTaskState.TODO);
        Plan plan = createPlan(List.of(task1, task2));

        String hint = hintGenerator.generateHint(plan, true);

        assertNotNull(hint);
        assertTrue(hint.contains("The first 1 subtasks are done"));
        // This state doesn't include wait for confirmation rule
        assertFalse(hint.contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testAtTheEndHint_WithUserConfirmation() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.DONE),
                                createSubTask("Task2", SubTaskState.DONE)));

        String hint = hintGenerator.generateHint(plan, true);

        assertNotNull(hint);
        assertTrue(hint.contains("All the subtasks are done"));
        assertTrue(hint.contains("Finish the plan by calling 'finish_plan'"));
        // End state doesn't include wait for confirmation rule
        assertFalse(hint.contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testAtTheEndHint_WithoutUserConfirmation() {
        Plan plan =
                createPlan(
                        List.of(
                                createSubTask("Task1", SubTaskState.DONE),
                                createSubTask("Task2", SubTaskState.DONE)));

        String hint = hintGenerator.generateHint(plan, false);

        assertNotNull(hint);
        assertTrue(hint.contains("All the subtasks are done"));
        assertFalse(hint.contains("WAIT FOR USER CONFIRMATION"));
    }

    // Helper methods

    private Plan createPlan(List<SubTask> subtasks) {
        return new Plan("TestPlan", "Test Description", "Expected Outcome", subtasks);
    }

    private SubTask createSubTask(String name, SubTaskState state) {
        SubTask task = new SubTask(name, "Description for " + name, "Expected outcome for " + name);
        task.setState(state);
        return task;
    }
}
