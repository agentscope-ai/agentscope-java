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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for State record classes: AgentMetaState, ToolkitState, PlanNotebookState. */
@DisplayName("State Records Tests")
class StateRecordsTest {

    @Nested
    @DisplayName("AgentMetaState Tests")
    class AgentMetaStateTests {

        @Test
        @DisplayName("Should create AgentMetaState with all fields")
        void testCreate() {
            AgentMetaState state =
                    new AgentMetaState(
                            "agent_001", "Assistant", "A helpful assistant", "You are helpful.");
            assertEquals("agent_001", state.id());
            assertEquals("Assistant", state.name());
            assertEquals("A helpful assistant", state.description());
            assertEquals("You are helpful.", state.systemPrompt());
        }

        @Test
        @DisplayName("Should allow null values")
        void testNullValues() {
            AgentMetaState state = new AgentMetaState(null, null, null, null);
            assertNull(state.id());
            assertNull(state.name());
            assertNull(state.description());
            assertNull(state.systemPrompt());
        }

        @Test
        @DisplayName("Should be usable as State type")
        void testUsableAsState() {
            State state = new AgentMetaState("id", "name", "desc", "prompt");
            assertEquals("id", ((AgentMetaState) state).id());
        }

        @Test
        @DisplayName("Should be equal when all fields match")
        void testEquality() {
            AgentMetaState state1 = new AgentMetaState("id", "name", "desc", "prompt");
            AgentMetaState state2 = new AgentMetaState("id", "name", "desc", "prompt");
            assertEquals(state1, state2);
            assertEquals(state1.hashCode(), state2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when fields differ")
        void testInequality() {
            AgentMetaState state1 = new AgentMetaState("id1", "name", "desc", "prompt");
            AgentMetaState state2 = new AgentMetaState("id2", "name", "desc", "prompt");
            assertNotEquals(state1, state2);
        }
    }

    @Nested
    @DisplayName("ToolkitState Tests")
    class ToolkitStateTests {

        @Test
        @DisplayName("Should create ToolkitState with active groups")
        void testCreate() {
            List<String> groups = List.of("web", "file", "calculator");
            ToolkitState state = new ToolkitState(groups);
            assertEquals(groups, state.activeGroups());
            assertEquals(3, state.activeGroups().size());
        }

        @Test
        @DisplayName("Should create ToolkitState with empty list")
        void testEmptyList() {
            ToolkitState state = new ToolkitState(List.of());
            assertTrue(state.activeGroups().isEmpty());
        }

        @Test
        @DisplayName("Should allow null activeGroups")
        void testNullGroups() {
            ToolkitState state = new ToolkitState(null);
            assertNull(state.activeGroups());
        }

        @Test
        @DisplayName("Should be usable as State type")
        void testUsableAsState() {
            State state = new ToolkitState(List.of("group1"));
            assertEquals(1, ((ToolkitState) state).activeGroups().size());
        }

        @Test
        @DisplayName("Should be equal when activeGroups match")
        void testEquality() {
            ToolkitState state1 = new ToolkitState(List.of("a", "b"));
            ToolkitState state2 = new ToolkitState(List.of("a", "b"));
            assertEquals(state1, state2);
            assertEquals(state1.hashCode(), state2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when activeGroups differ")
        void testInequality() {
            ToolkitState state1 = new ToolkitState(List.of("a", "b"));
            ToolkitState state2 = new ToolkitState(List.of("a", "c"));
            assertNotEquals(state1, state2);
        }
    }

    @Nested
    @DisplayName("PlanNotebookState Tests")
    class PlanNotebookStateTests {

        @Test
        @DisplayName("Should create PlanNotebookState with plan")
        void testCreateWithPlan() {
            List<SubTask> subtasks =
                    List.of(
                            new SubTask("Task 1", "Description 1", "Outcome 1"),
                            new SubTask("Task 2", "Description 2", "Outcome 2"));
            Plan plan = new Plan("My Plan", "Plan description", "Expected outcome", subtasks);
            PlanNotebookState state = new PlanNotebookState(plan);
            assertEquals(plan, state.currentPlan());
            assertEquals("My Plan", state.currentPlan().getName());
        }

        @Test
        @DisplayName("Should allow null plan")
        void testNullPlan() {
            PlanNotebookState state = new PlanNotebookState(null);
            assertNull(state.currentPlan());
        }

        @Test
        @DisplayName("Should be usable as State type")
        void testUsableAsState() {
            State state = new PlanNotebookState(null);
            assertNull(((PlanNotebookState) state).currentPlan());
        }

        @Test
        @DisplayName("Should be equal when plans match")
        void testEquality() {
            Plan plan = new Plan("Plan", "Desc", "Outcome", List.of());
            PlanNotebookState state1 = new PlanNotebookState(plan);
            PlanNotebookState state2 = new PlanNotebookState(plan);
            assertEquals(state1, state2);
            assertEquals(state1.hashCode(), state2.hashCode());
        }

        @Test
        @DisplayName("Should handle plan with subtasks")
        void testPlanWithSubtasks() {
            SubTask subtask = new SubTask("SubTask", "SubTask desc", "SubTask outcome");
            Plan plan = new Plan("Plan", "Desc", "Outcome", List.of(subtask));
            PlanNotebookState state = new PlanNotebookState(plan);
            assertEquals(1, state.currentPlan().getSubtasks().size());
            assertEquals("SubTask", state.currentPlan().getSubtasks().get(0).getName());
        }
    }
}
