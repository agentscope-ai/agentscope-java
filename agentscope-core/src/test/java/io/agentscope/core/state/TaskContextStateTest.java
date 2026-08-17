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
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskContextStateTest {

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
        ctx.tasksMutable().add(task("a"));
        List<Task> snapshot = ctx.getTasks();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(task("b")));
        // Mutation through tasksMutable is reflected in subsequent snapshots
        ctx.tasksMutable().add(task("b"));
        assertEquals(2, ctx.getTasks().size());
        // Snapshot is independent from internal storage
        assertNotSame(snapshot, ctx.getTasks());
    }

    @Test
    void copyConstructorTakesDefensiveCopy() {
        List<Task> input = new java.util.ArrayList<>(List.of(task("a")));
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
