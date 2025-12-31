/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for PlanNotebook's StateModule implementation (saveTo/loadFrom). */
@DisplayName("PlanNotebook StateModule Tests")
class PlanNotebookStateModuleTest {

    private InMemorySession session;
    private SessionKey sessionKey;

    @BeforeEach
    void setUp() {
        session = new InMemorySession();
        sessionKey = SimpleSessionKey.of("test_session");
    }

    @Nested
    @DisplayName("saveTo() and loadFrom()")
    class SaveToLoadFromTests {

        @Test
        @DisplayName("Should save and load plan via saveTo/loadFrom")
        void testSaveToLoadFrom() {
            PlanNotebook notebook = PlanNotebook.builder().build();

            // Create a plan
            notebook.createPlanWithSubTasks(
                            "Test Plan",
                            "Test description",
                            "Expected outcome",
                            List.of(
                                    new SubTask("Task 1", "Desc 1", "Outcome 1"),
                                    new SubTask("Task 2", "Desc 2", "Outcome 2")))
                    .block();

            notebook.saveTo(session, sessionKey);

            // Load into new notebook
            PlanNotebook loadedNotebook = PlanNotebook.builder().build();
            loadedNotebook.loadFrom(session, sessionKey);

            assertNotNull(loadedNotebook.getCurrentPlan());
            assertEquals("Test Plan", loadedNotebook.getCurrentPlan().getName());
            assertEquals("Test description", loadedNotebook.getCurrentPlan().getDescription());
            assertEquals(2, loadedNotebook.getCurrentPlan().getSubtasks().size());
            assertEquals("Task 1", loadedNotebook.getCurrentPlan().getSubtasks().get(0).getName());
            assertEquals("Task 2", loadedNotebook.getCurrentPlan().getSubtasks().get(1).getName());
        }

        @Test
        @DisplayName("Should handle null plan (no active plan)")
        void testNullPlan() {
            PlanNotebook notebook = PlanNotebook.builder().build();
            // No plan created
            notebook.saveTo(session, sessionKey);

            PlanNotebook loadedNotebook = PlanNotebook.builder().build();
            loadedNotebook.loadFrom(session, sessionKey);

            assertNull(loadedNotebook.getCurrentPlan());
        }

        @Test
        @DisplayName("Should preserve plan state across save/load")
        void testPlanStatePreservation() {
            PlanNotebook notebook = PlanNotebook.builder().build();

            // Create a plan and update subtask state
            notebook.createPlanWithSubTasks(
                            "Stateful Plan",
                            "Testing state",
                            "State outcome",
                            List.of(
                                    new SubTask("Task 1", "Desc 1", "Outcome 1"),
                                    new SubTask("Task 2", "Desc 2", "Outcome 2")))
                    .block();

            // Mark first subtask as in_progress
            notebook.updateSubtaskState(0, "in_progress").block();

            notebook.saveTo(session, sessionKey);

            PlanNotebook loadedNotebook = PlanNotebook.builder().build();
            loadedNotebook.loadFrom(session, sessionKey);

            assertNotNull(loadedNotebook.getCurrentPlan());
            assertEquals(
                    "IN_PROGRESS",
                    loadedNotebook.getCurrentPlan().getSubtasks().get(0).getState().name());
        }
    }

    @Nested
    @DisplayName("Custom keyPrefix")
    class CustomKeyPrefixTests {

        @Test
        @DisplayName("Should use custom keyPrefix for storage")
        void testCustomKeyPrefix() {
            PlanNotebook notebook1 = PlanNotebook.builder().keyPrefix("notebook1").build();
            notebook1
                    .createPlanWithSubTasks(
                            "Plan 1", "Desc 1", "Outcome 1", List.of(new SubTask("T1", "D1", "O1")))
                    .block();
            notebook1.saveTo(session, sessionKey);

            PlanNotebook notebook2 = PlanNotebook.builder().keyPrefix("notebook2").build();
            notebook2
                    .createPlanWithSubTasks(
                            "Plan 2", "Desc 2", "Outcome 2", List.of(new SubTask("T2", "D2", "O2")))
                    .block();
            notebook2.saveTo(session, sessionKey);

            // Load into separate notebooks
            PlanNotebook loaded1 = PlanNotebook.builder().keyPrefix("notebook1").build();
            loaded1.loadFrom(session, sessionKey);

            PlanNotebook loaded2 = PlanNotebook.builder().keyPrefix("notebook2").build();
            loaded2.loadFrom(session, sessionKey);

            assertEquals("Plan 1", loaded1.getCurrentPlan().getName());
            assertEquals("Plan 2", loaded2.getCurrentPlan().getName());
        }

        @Test
        @DisplayName("Different keyPrefix notebooks should be isolated")
        void testKeyPrefixIsolation() {
            PlanNotebook notebook = PlanNotebook.builder().keyPrefix("prefix_a").build();
            notebook.createPlanWithSubTasks(
                            "Plan A", "Desc A", "Outcome A", List.of(new SubTask("T", "D", "O")))
                    .block();
            notebook.saveTo(session, sessionKey);

            // Try to load with different prefix - should get no plan
            PlanNotebook loaded = PlanNotebook.builder().keyPrefix("prefix_b").build();
            loaded.loadFrom(session, sessionKey);

            assertNull(loaded.getCurrentPlan());
        }
    }

    @Nested
    @DisplayName("loadIfExists()")
    class LoadIfExistsTests {

        @Test
        @DisplayName("Should return true and load when session exists")
        void testLoadIfExistsTrue() {
            PlanNotebook notebook = PlanNotebook.builder().build();
            notebook.createPlanWithSubTasks(
                            "Existing Plan",
                            "Desc",
                            "Outcome",
                            List.of(new SubTask("Task", "Desc", "Outcome")))
                    .block();
            notebook.saveTo(session, sessionKey);

            PlanNotebook loadedNotebook = PlanNotebook.builder().build();
            boolean exists = loadedNotebook.loadIfExists(session, sessionKey);

            // loadIfExists returns true based on session.exists() which checks
            // if ANY data exists for that sessionKey
            assertTrue(exists);
            assertNotNull(loadedNotebook.getCurrentPlan());
            assertEquals("Existing Plan", loadedNotebook.getCurrentPlan().getName());
        }
    }

    @Nested
    @DisplayName("Plan modifications after save")
    class ModificationsAfterSaveTests {

        @Test
        @DisplayName("Should save updated state when modified and saved again")
        void testSaveAfterModification() {
            PlanNotebook notebook = PlanNotebook.builder().build();
            notebook.createPlanWithSubTasks(
                            "Original Plan",
                            "Original Desc",
                            "Original Outcome",
                            List.of(new SubTask("Original Task", "Desc", "Outcome")))
                    .block();
            notebook.saveTo(session, sessionKey);

            // Modify and save again
            notebook.updatePlanInfo("Modified Plan", null, null).block();
            notebook.saveTo(session, sessionKey);

            // Load should have modified values
            PlanNotebook freshLoad = PlanNotebook.builder().build();
            freshLoad.loadFrom(session, sessionKey);

            assertEquals("Modified Plan", freshLoad.getCurrentPlan().getName());
        }
    }
}
