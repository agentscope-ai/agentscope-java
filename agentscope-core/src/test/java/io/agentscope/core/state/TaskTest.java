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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsAreApplied() {
        Task t =
                Task.builder()
                        .subject("Refactor parser")
                        .description("Split into AST + lexer")
                        .build();
        assertEquals("Refactor parser", t.getSubject());
        assertEquals("Split into AST + lexer", t.getDescription());
        assertEquals(Task.State.PENDING, t.getState());
        assertNotNull(t.getId());
        assertEquals(32, t.getId().length(), "default id should be 32-char uuid hex");
        assertNotNull(t.getCreatedAt());
        assertEquals(Map.of(), t.getMetadata());
        assertEquals(List.of(), t.getBlocks());
        assertEquals(List.of(), t.getBlockedBy());
        assertNull(t.getOwner());
    }

    @Test
    void subjectAndDescriptionRequired() {
        assertThrows(NullPointerException.class, () -> Task.builder().description("d").build());
        assertThrows(NullPointerException.class, () -> Task.builder().subject("s").build());
    }

    @Test
    void explicitFieldsWin() {
        Task t =
                Task.builder()
                        .subject("S")
                        .description("D")
                        .id("fixed-id")
                        .state(Task.State.IN_PROGRESS)
                        .owner("alice")
                        .createdAt("2026-01-01T00:00:00+00:00")
                        .metadata(Map.of("k", "v"))
                        .blocks(List.of("b1"))
                        .blockedBy(List.of("u1", "u2"))
                        .build();
        assertEquals("fixed-id", t.getId());
        assertEquals(Task.State.IN_PROGRESS, t.getState());
        assertEquals("alice", t.getOwner());
        assertEquals("2026-01-01T00:00:00+00:00", t.getCreatedAt());
        assertEquals(Map.of("k", "v"), t.getMetadata());
        assertEquals(List.of("b1"), t.getBlocks());
        assertEquals(List.of("u1", "u2"), t.getBlockedBy());
    }

    @Test
    void stateWireFormat() {
        assertEquals("pending", Task.State.PENDING.getWire());
        assertEquals("in_progress", Task.State.IN_PROGRESS.getWire());
        assertEquals("completed", Task.State.COMPLETED.getWire());
        assertEquals(Task.State.IN_PROGRESS, Task.State.fromWire("in_progress"));
        assertEquals(Task.State.IN_PROGRESS, Task.State.fromWire("IN_PROGRESS"));
        assertNull(Task.State.fromWire(null));
        assertThrows(IllegalArgumentException.class, () -> Task.State.fromWire("bogus"));
    }

    @Test
    void jsonRoundTrip() throws Exception {
        Task original =
                Task.builder()
                        .subject("ship it")
                        .description("ship the release")
                        .id("abc123")
                        .state(Task.State.COMPLETED)
                        .owner("bob")
                        .createdAt("2026-05-25T10:00:00+08:00")
                        .metadata(Map.of("priority", "high"))
                        .blocks(List.of("t-2"))
                        .blockedBy(List.of("t-0"))
                        .build();
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"state\":\"completed\""), () -> json);
        assertTrue(json.contains("\"blocked_by\""), () -> json);
        assertTrue(json.contains("\"created_at\""), () -> json);
        Task decoded = mapper.readValue(json, Task.class);
        assertEquals(original, decoded);
    }

    @Test
    void equalityIgnoresInstanceIdentity() {
        Task a =
                Task.builder()
                        .subject("S")
                        .description("D")
                        .id("same-id")
                        .createdAt("2026-01-01T00:00:00+00:00")
                        .build();
        Task b =
                Task.builder()
                        .subject("S")
                        .description("D")
                        .id("same-id")
                        .createdAt("2026-01-01T00:00:00+00:00")
                        .build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
