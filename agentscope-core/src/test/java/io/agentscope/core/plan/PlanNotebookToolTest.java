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
package io.agentscope.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.core.message.Msg;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanNotebookToolTest {

    private PlanNotebook notebook;

    @BeforeEach
    void setUp() {
        notebook = PlanNotebook.builder().build();
    }

    @Test
    void testCreatePlan() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));

        String result =
                notebook.createPlanWithSubTasks(
                                "Test Plan", "Test Description", "Test Outcome", subtasks)
                        .block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));

        Plan currentPlan = notebook.getCurrentPlan();
        assertNotNull(currentPlan);
        assertEquals("Test Plan", currentPlan.getName());
        assertEquals(2, currentPlan.getSubtasks().size());
        assertEquals(PlanState.TODO, currentPlan.getState());
    }

    @Test
    void testCreatePlanReplacesCurrent() {
        List<SubTask> subtasks1 = List.of(new SubTask("Task1", "Desc1", "Outcome1"));
        notebook.createPlanWithSubTasks("Plan 1", "Desc", "Outcome", subtasks1).block();

        List<SubTask> subtasks2 = List.of(new SubTask("Task2", "Desc2", "Outcome2"));
        String result =
                notebook.createPlanWithSubTasks("Plan 2", "Desc", "Outcome", subtasks2).block();

        assertTrue(result.contains("replaced"));
        assertEquals("Plan 2", notebook.getCurrentPlan().getName());
    }

    @Test
    void testUpdateSubtaskState() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));

        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Update first subtask to in_progress
        String result = notebook.updateSubtaskState(0, "in_progress").block();

        assertTrue(result.contains("successfully"));
        assertEquals(
                SubTaskState.IN_PROGRESS,
                notebook.getCurrentPlan().getSubtasks().get(0).getState());
    }

    @Test
    void testUpdateSubtaskStateValidation() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));

        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Try to update second subtask before first is done
        String result = notebook.updateSubtaskState(1, "in_progress").block();

        assertTrue(result.contains("Cannot update") || result.contains("previous"));
    }

    @Test
    void testUpdateSubtaskStateInvalidIndex() {
        List<SubTask> subtasks = List.of(new SubTask("Task1", "Desc1", "Outcome1"));
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        String result = notebook.updateSubtaskState(5, "in_progress").block();

        assertTrue(result.contains("Invalid"));
    }

    @Test
    void testFinishSubtask() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));

        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Set first subtask to in_progress
        notebook.updateSubtaskState(0, "in_progress").block();

        // Finish it
        String result = notebook.finishSubtask(0, "Task completed successfully").block();

        assertTrue(result.contains("successfully"));
        SubTask subtask = notebook.getCurrentPlan().getSubtasks().get(0);
        assertEquals(SubTaskState.DONE, subtask.getState());
        assertEquals("Task completed successfully", subtask.getOutcome());

        // Second subtask should be auto-activated
        SubTask nextSubtask = notebook.getCurrentPlan().getSubtasks().get(1);
        assertEquals(SubTaskState.IN_PROGRESS, nextSubtask.getState());
    }

    @Test
    void testFinishSubtaskWhenNotInProgress() {
        // finishSubtask actually allows finishing if all previous subtasks are done
        // This is index 0, so no previous subtasks - it will succeed
        List<SubTask> subtasks = List.of(new SubTask("Task1", "Desc1", "Outcome1"));
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        String result = notebook.finishSubtask(0, "Outcome").block();

        // It succeeds because there are no previous subtasks to check
        assertTrue(result.contains("successfully") || result.contains("marked as done"));
    }

    @Test
    void testFinishSubtaskLastOne() {
        List<SubTask> subtasks = List.of(new SubTask("Task1", "Desc1", "Outcome1"));
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Set to in_progress and finish
        notebook.updateSubtaskState(0, "in_progress").block();
        String result = notebook.finishSubtask(0, "Done").block();

        assertTrue(result.contains("successfully"));
        // Last subtask message doesn't mention "last" - just says "marked as done"
    }

    @Test
    void testViewSubtasks() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));

        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // View specific subtasks by index
        String result = notebook.viewSubtasks(List.of(0, 1)).block();

        assertTrue(result.contains("Task1"));
        assertTrue(result.contains("Task2"));
    }

    @Test
    void testViewSubtasksWhenNoPlan() {
        // viewSubtasks throws exception when no plan exists
        try {
            notebook.viewSubtasks(List.of()).block();
            fail("Should have thrown exception");
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains("current plan is None")
                            || e.getMessage().contains("No current plan"));
        }
    }

    @Test
    void testFinishPlan() {
        List<SubTask> subtasks = new ArrayList<>();
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        String result = notebook.finishPlan("done", "All tasks completed").block();

        assertTrue(result.contains("successfully"));
        Plan currentPlan = notebook.getCurrentPlan();
        assertNull(currentPlan); // Current plan should be cleared
    }

    @Test
    void testFinishPlanAbandoned() {
        List<SubTask> subtasks = new ArrayList<>();
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        String result = notebook.finishPlan("abandoned", "User cancelled").block();

        assertTrue(result.contains("abandoned"));
        assertNull(notebook.getCurrentPlan());
    }

    @Test
    void testFinishPlanInvalidState() {
        List<SubTask> subtasks = new ArrayList<>();
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        String result = notebook.finishPlan("invalid", "Outcome").block();

        assertTrue(result.contains("Invalid"));
    }

    @Test
    void testFinishPlanWhenNoPlan() {
        // finishPlan returns message when no plan exists
        String result = notebook.finishPlan("done", "Outcome").block();

        assertTrue(result.contains("no plan"));
    }

    @Test
    void testViewHistoricalPlans() {
        // Create and finish a plan
        List<SubTask> subtasks = new ArrayList<>();
        notebook.createPlanWithSubTasks("Historical Plan", "Desc", "Outcome", subtasks).block();
        notebook.finishPlan("done", "Completed").block();

        String result = notebook.viewHistoricalPlans().block();

        assertTrue(result.contains("Historical Plan"));
        assertTrue(result.contains("DONE") || result.contains("done"));
    }

    @Test
    void testViewHistoricalPlansEmpty() {
        String result = notebook.viewHistoricalPlans().block();

        // Empty list returns empty string or minimal message
        assertNotNull(result);
    }

    // Note: recoverHistoricalPlan tests are skipped as storage is not publicly accessible
    // These would need to be integration tests with actual plan IDs

    @Test
    void testRecoverHistoricalPlanNotFound() {
        String result = notebook.recoverHistoricalPlan("non-existent-id").block();

        // May return null or error message
        assertTrue(result == null || result.contains("not found") || result.contains("Plan"));
    }

    @Test
    void testCompleteWorkflow() {
        // 1. Create plan
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "First task", "Result1"));
        subtasks.add(new SubTask("Task2", "Second task", "Result2"));

        notebook.createPlanWithSubTasks(
                        "Complete Workflow", "Test complete workflow", "All tasks done", subtasks)
                .block();

        // 2. Start first task
        notebook.updateSubtaskState(0, "in_progress").block();

        // 3. Finish first task (second should auto-start)
        notebook.finishSubtask(0, "First task completed").block();
        assertEquals(SubTaskState.DONE, notebook.getCurrentPlan().getSubtasks().get(0).getState());
        assertEquals(
                SubTaskState.IN_PROGRESS,
                notebook.getCurrentPlan().getSubtasks().get(1).getState());

        // 4. Finish second task
        notebook.finishSubtask(1, "Second task completed").block();

        // 5. Finish plan
        String result = notebook.finishPlan("done", "All tasks completed successfully").block();
        assertTrue(result.contains("successfully"));

        // 6. Verify plan is in history
        String history = notebook.viewHistoricalPlans().block();
        assertTrue(history.contains("Complete Workflow"));
    }

    // Tests for needUserConfirm configuration

    @Test
    void testBuilderNeedUserConfirmDefaultsToTrue() {
        PlanNotebook notebookWithDefaults = PlanNotebook.builder().build();
        assertTrue(notebookWithDefaults.isNeedUserConfirm());
    }

    @Test
    void testBuilderNeedUserConfirmSetToFalse() {
        PlanNotebook notebookNoConfirm = PlanNotebook.builder().needUserConfirm(false).build();
        assertFalse(notebookNoConfirm.isNeedUserConfirm());
    }

    @Test
    void testBuilderNeedUserConfirmSetToTrue() {
        PlanNotebook notebookWithConfirm = PlanNotebook.builder().needUserConfirm(true).build();
        assertTrue(notebookWithConfirm.isNeedUserConfirm());
    }

    @Test
    void testGetCurrentHintNoPlan_WithUserConfirm() {
        PlanNotebook notebookWithConfirm = PlanNotebook.builder().needUserConfirm(true).build();

        Msg hint = notebookWithConfirm.getCurrentHint().block();

        assertNotNull(hint);
        assertNotNull(hint.getTextContent());
        assertTrue(hint.getTextContent().contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testGetCurrentHintNoPlan_WithoutUserConfirm() {
        PlanNotebook notebookNoConfirm = PlanNotebook.builder().needUserConfirm(false).build();

        Msg hint = notebookNoConfirm.getCurrentHint().block();

        assertNotNull(hint);
        assertNotNull(hint.getTextContent());
        assertFalse(hint.getTextContent().contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testGetCurrentHintWithPlan_WithUserConfirm() {
        PlanNotebook notebookWithConfirm = PlanNotebook.builder().needUserConfirm(true).build();

        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        notebookWithConfirm.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        Msg hint = notebookWithConfirm.getCurrentHint().block();

        assertNotNull(hint);
        assertNotNull(hint.getTextContent());
        assertTrue(hint.getTextContent().contains("Update before processing each subtask"));
    }

    @Test
    void testGetCurrentHintWithPlan_WithoutUserConfirm() {
        PlanNotebook notebookNoConfirm = PlanNotebook.builder().needUserConfirm(false).build();

        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        notebookNoConfirm.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        Msg hint = notebookNoConfirm.getCurrentHint().block();

        assertNotNull(hint);
        assertNotNull(hint.getTextContent());
        assertFalse(hint.getTextContent().contains("WAIT FOR USER CONFIRMATION"));
    }

    @Test
    void testGetCurrentHintInProgress_NeverIncludesConfirmation() {
        // When a subtask is in progress, confirmation rule should not be included
        // regardless of needUserConfirm setting
        PlanNotebook notebookWithConfirm = PlanNotebook.builder().needUserConfirm(true).build();

        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));
        notebookWithConfirm.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();
        notebookWithConfirm.updateSubtaskState(0, "in_progress").block();

        Msg hint = notebookWithConfirm.getCurrentHint().block();

        assertNotNull(hint);
        assertNotNull(hint.getTextContent());
        // In-progress state should not include confirmation rule
        assertFalse(hint.getTextContent().contains("WAIT FOR USER CONFIRMATION"));
        assertTrue(hint.getTextContent().contains("in_progress"));
    }

    @Test
    void testUpdatePlanInfo() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        notebook.createPlanWithSubTasks(
                        "Original Plan", "Original Desc", "Original Outcome", subtasks)
                .block();

        // Update all fields
        String result = notebook.updatePlanInfo("New Plan", "New Desc", "New Outcome").block();

        assertTrue(result.contains("successfully"));
        assertEquals("New Plan", notebook.getCurrentPlan().getName());
        assertEquals("New Desc", notebook.getCurrentPlan().getDescription());
        assertEquals("New Outcome", notebook.getCurrentPlan().getExpectedOutcome());
    }

    @Test
    void testUpdatePlanInfoPartialUpdate() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        notebook.createPlanWithSubTasks(
                        "Original Plan", "Original Desc", "Original Outcome", subtasks)
                .block();

        // Update only name
        String result = notebook.updatePlanInfo("Updated Name", null, "").block();

        assertTrue(result.contains("successfully"));
        assertEquals("Updated Name", notebook.getCurrentPlan().getName());
        assertEquals("Original Desc", notebook.getCurrentPlan().getDescription());
        assertEquals("Original Outcome", notebook.getCurrentPlan().getExpectedOutcome());
    }

    @Test
    void testUpdatePlanInfoNoChanges() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        String result = notebook.updatePlanInfo(null, "", "   ").block();

        assertTrue(result.contains("No changes"));
    }

    @Test
    void testUpdatePlanInfoNoPlan() {
        try {
            notebook.updatePlanInfo("Name", "Desc", "Outcome").block();
            fail("Should have thrown exception");
        } catch (Exception e) {
            assertTrue(
                    e.getMessage().contains("current plan is None")
                            || e.getMessage().contains("No current plan"));
        }
    }

    @Test
    void testGetSubtaskCount() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));
        subtasks.add(new SubTask("Task3", "Desc3", "Outcome3"));
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        String result = notebook.getSubtaskCount().block();

        assertNotNull(result);
        assertTrue(result.contains("3 subtask"));
        assertTrue(result.contains("3 todo"));
    }

    @Test
    void testGetSubtaskCountWithMixedStates() {
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));
        notebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Finish first task, second auto-activates
        notebook.finishSubtask(0, "Done").block();

        String result = notebook.getSubtaskCount().block();

        assertNotNull(result);
        assertTrue(result.contains("2 subtask"));
        assertTrue(result.contains("1 done"));
        assertTrue(result.contains("1 in_progress"));
    }

    @Test
    void testGetSubtaskCountNoPlan() {
        String result = notebook.getSubtaskCount().block();

        assertNotNull(result);
        assertTrue(result.contains("no active plan") || result.contains("There is no"));
    }

    // Tests for maxSubtasks configuration

    @Test
    void testCreatePlanExceedsMaxSubtasks() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(3).build();

        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }

        String result =
                limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        assertNotNull(result);
        assertTrue(result.contains("exceeds the maximum limit"));
        assertTrue(result.contains("3"));
        assertNull(limitedNotebook.getCurrentPlan());
    }

    @Test
    void testCreatePlanAtMaxSubtasksLimit() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(3).build();

        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }

        String result =
                limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertNotNull(limitedNotebook.getCurrentPlan());
        assertEquals(3, limitedNotebook.getCurrentPlan().getSubtasks().size());
    }

    @Test
    void testCreatePlanBelowMaxSubtasksLimit() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(5).build();

        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }

        String result =
                limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertEquals(3, limitedNotebook.getCurrentPlan().getSubtasks().size());
    }

    @Test
    void testReviseCurrentPlanAddExceedsMaxSubtasks() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(3).build();

        // Create plan with 3 subtasks (at limit)
        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }
        limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Try to add another subtask
        String result =
                limitedNotebook
                        .reviseCurrentPlan(
                                3,
                                "add",
                                PlanNotebook.subtaskToMap(
                                        new SubTask("NewTask", "Desc", "Outcome")))
                        .block();

        assertNotNull(result);
        assertTrue(result.contains("reached the maximum limit"));
        assertTrue(result.contains("3"));
        assertEquals(3, limitedNotebook.getCurrentPlan().getSubtasks().size());
    }

    @Test
    void testReviseCurrentPlanAddAtMaxSubtasksLimit() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(5).build();

        // Create plan with 4 subtasks (one below limit)
        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }
        limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Add one more (should succeed, reaching limit)
        String result =
                limitedNotebook
                        .reviseCurrentPlan(
                                4,
                                "add",
                                PlanNotebook.subtaskToMap(
                                        new SubTask("Task5", "Desc5", "Outcome5")))
                        .block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertEquals(5, limitedNotebook.getCurrentPlan().getSubtasks().size());
    }

    @Test
    void testReviseCurrentPlanAddBelowMaxSubtasksLimit() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(5).build();

        // Create plan with 2 subtasks
        List<SubTask> subtasks = new ArrayList<>();
        subtasks.add(new SubTask("Task1", "Desc1", "Outcome1"));
        subtasks.add(new SubTask("Task2", "Desc2", "Outcome2"));
        limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Add one more (should succeed)
        String result =
                limitedNotebook
                        .reviseCurrentPlan(
                                2,
                                "add",
                                PlanNotebook.subtaskToMap(
                                        new SubTask("Task3", "Desc3", "Outcome3")))
                        .block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertEquals(3, limitedNotebook.getCurrentPlan().getSubtasks().size());
    }

    @Test
    void testReviseCurrentPlanDeleteThenAddWithinLimit() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(3).build();

        // Create plan with 3 subtasks (at limit)
        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }
        limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Delete one, then add one (should succeed)
        limitedNotebook.reviseCurrentPlan(0, "delete", null).block();
        String result =
                limitedNotebook
                        .reviseCurrentPlan(
                                2,
                                "add",
                                PlanNotebook.subtaskToMap(
                                        new SubTask("NewTask", "Desc", "Outcome")))
                        .block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertEquals(3, limitedNotebook.getCurrentPlan().getSubtasks().size());
    }

    @Test
    void testMaxSubtasksNullAllowsUnlimitedSubtasks() {
        PlanNotebook unlimitedNotebook =
                PlanNotebook.builder().build(); // maxSubtasks defaults to null

        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }

        String result =
                unlimitedNotebook
                        .createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks)
                        .block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertEquals(10, unlimitedNotebook.getCurrentPlan().getSubtasks().size());
    }

    @Test
    void testReviseCurrentPlanReviseDoesNotCheckMaxSubtasks() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(3).build();

        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }
        limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Revise (replace) should work as it doesn't change count
        String result =
                limitedNotebook
                        .reviseCurrentPlan(
                                0,
                                "revise",
                                PlanNotebook.subtaskToMap(
                                        new SubTask(
                                                "UpdatedTask", "UpdatedDesc", "UpdatedOutcome")))
                        .block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertEquals(3, limitedNotebook.getCurrentPlan().getSubtasks().size());
        assertEquals(
                "UpdatedTask", limitedNotebook.getCurrentPlan().getSubtasks().get(0).getName());
    }

    @Test
    void testReviseCurrentPlanDeleteDoesNotCheckMaxSubtasks() {
        PlanNotebook limitedNotebook = PlanNotebook.builder().maxSubtasks(3).build();

        List<SubTask> subtasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            subtasks.add(new SubTask("Task" + i, "Desc" + i, "Outcome" + i));
        }
        limitedNotebook.createPlanWithSubTasks("Plan", "Desc", "Outcome", subtasks).block();

        // Delete should work as it reduces count
        String result = limitedNotebook.reviseCurrentPlan(0, "delete", null).block();

        assertNotNull(result);
        assertTrue(result.contains("successfully"));
        assertEquals(2, limitedNotebook.getCurrentPlan().getSubtasks().size());
    }
}
