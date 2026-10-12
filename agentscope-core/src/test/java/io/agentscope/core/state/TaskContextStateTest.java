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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.builtin.TodoTools.TodoItem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskContextStateTest {
    @Test
    void proposalRequiresExplicitDecisionAndTodoDoesNotEraseIt() {
        var state = AgentState.builder().build();
        var context = state.getTasksContext();
        var candidate =
                context.propose(TaskRequirement.Kind.CONSTRAINT, "Do not deploy", "message:u1");
        assertEquals(TaskRequirement.Status.CANDIDATE, candidate.status());
        context.decide(
                candidate.id(),
                TaskRequirement.Status.CONFIRMED,
                new TaskRequirement.Decision(TaskRequirement.Authority.USER, "message:u2"),
                context.getRevision());
        new TodoTools().write(List.of(new TodoItem("implementation", "completed", null)), state);
        assertEquals(TaskRequirement.Status.CONFIRMED, context.getRequirements().get(0).status());
        assertEquals(3, context.getRevision());
        assertEquals(Task.State.COMPLETED, context.getTasks().get(0).getState());
        assertEquals(TaskRequirement.Status.CANDIDATE, candidate.status());
    }

    @Test
    void staleDecisionsAndTodoUpdatesFailWithoutMutation() {
        var context = new TaskContextState();
        var candidate =
                context.propose(
                        TaskRequirement.Kind.ACCEPTANCE_CRITERION, "Tests pass", "PLAN.md#tests");
        var before = context.snapshot();
        assertThrows(
                ConcurrentModificationException.class,
                () ->
                        context.decide(
                                candidate.id(),
                                TaskRequirement.Status.CONFIRMED,
                                new TaskRequirement.Decision(
                                        TaskRequirement.Authority.CALLER, "request:1"),
                                0));
        assertThrows(
                ConcurrentModificationException.class,
                () -> context.replaceTasks(List.of(task("new")), 0));
        assertEquals(before, context);
        assertThrows(
                NullPointerException.class,
                () -> context.decide(candidate.id(), TaskRequirement.Status.CONFIRMED, null, 1));
        assertEquals(before, context);
    }

    @Test
    void proposalsAreIdempotentAndCannotOverwriteConfirmedText() {
        var context = new TaskContextState();
        var candidate = context.propose(TaskRequirement.Kind.CONSTRAINT, "No deploy", "message:1");
        assertEquals(
                candidate,
                context.propose(candidate.kind(), candidate.text(), candidate.proposedSourceRef()));
        assertEquals(1, context.getRevision());
        context.decide(
                candidate.id(),
                TaskRequirement.Status.CONFIRMED,
                new TaskRequirement.Decision(TaskRequirement.Authority.USER, "message:2"),
                1);
        context.propose(candidate.kind(), "Deploy now", candidate.proposedSourceRef());
        assertEquals("No deploy", context.getRequirements().get(0).text());
        assertEquals(TaskRequirement.Status.CANDIDATE, context.getRequirements().get(1).status());
        assertThrows(UnsupportedOperationException.class, () -> context.getRequirements().clear());
    }

    @Test
    void explicitTaskSwitchResetsAllTaskOwnedState() {
        var context = new TaskContextState();
        var first = new TaskContextState.Scope("task1", "First objective", "request:1");
        context.beginTask(first, 0);
        context.propose(TaskRequirement.Kind.CONSTRAINT, "No deploy", "message:1");
        context.replaceTasks(List.of(task("a")), 2);
        context.beginTask(first, 3);
        assertEquals(3, context.getRevision());
        context.beginTask(new TaskContextState.Scope("task2", "Second objective", "request:2"), 3);
        assertTrue(context.getTasks().isEmpty());
        assertTrue(context.getRequirements().isEmpty());
        assertEquals(4, context.getRevision());
    }

    @Test
    void taskAggregateSurvivesSessionStoreReload(@TempDir Path directory) {
        var state = AgentState.builder().build();
        var context = state.getTasksContext();
        context.beginTask(new TaskContextState.Scope("task", "Build", "request:1"), 0);
        var candidate =
                context.propose(
                        TaskRequirement.Kind.ACCEPTANCE_CRITERION, "Tests pass", "message:1");
        context.decide(
                candidate.id(),
                TaskRequirement.Status.CONFIRMED,
                new TaskRequirement.Decision(TaskRequirement.Authority.CALLER, "request:1"),
                2);
        new JsonFileAgentStateStore(directory).save("user", "session", "agent", state);
        var restored =
                new JsonFileAgentStateStore(directory)
                        .get("user", "session", "agent", AgentState.class)
                        .orElseThrow();
        assertEquals(context, restored.getTasksContext());
        assertTrue(
                new JsonFileAgentStateStore(directory)
                        .get("user", "other", "agent", AgentState.class)
                        .isEmpty());
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private static Task task(String subject) {
        return Task.builder()
                .subject(subject)
                .description(subject + " desc")
                .id("id-" + subject)
                .createdAt("2026-01-01T00:00:00+00:00")
                .build();
    }

    @Test
    void emptyByDefault() {
        TaskContextState ctx = new TaskContextState();
        assertEquals(List.of(), ctx.getTasks());
    }

    @Test
    void getTasksReturnsDefensiveCopy() {
        TaskContextState ctx = new TaskContextState();
        ctx.replaceTasks(List.of(task("a")), ctx.getRevision());
        List<Task> snapshot = ctx.getTasks();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(task("b")));
        ctx.replaceTasks(List.of(task("a"), task("b")), ctx.getRevision());
        assertEquals(2, ctx.getTasks().size());
        // Snapshot is independent from internal storage
        assertNotSame(snapshot, ctx.getTasks());
    }

    @Test
    void copyConstructorTakesDefensiveCopy() {
        List<Task> input = new ArrayList<>(List.of(task("a")));
        TaskContextState ctx = new TaskContextState(input);
        input.add(task("b"));
        assertEquals(1, ctx.getTasks().size());
    }

    @Test
    void nullListInCopyConstructorBecomesEmpty() {
        TaskContextState ctx = new TaskContextState(null);
        assertEquals(List.of(), ctx.getTasks());
    }

    @Test
    void jsonRoundTrip() throws Exception {
        TaskContextState ctx = new TaskContextState(List.of(task("a"), task("b")));
        String json = mapper.writeValueAsString(ctx);
        assertTrue(json.contains("\"tasks\""), () -> json);
        TaskContextState decoded = mapper.readValue(json, TaskContextState.class);
        assertEquals(ctx, decoded);
    }
}
