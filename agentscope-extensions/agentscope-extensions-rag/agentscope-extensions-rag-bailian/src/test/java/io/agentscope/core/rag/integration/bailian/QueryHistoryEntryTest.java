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
package io.agentscope.core.rag.integration.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QueryHistoryEntryTest {

    @Test
    void testUserEntry() {
        QueryHistoryEntry entry = QueryHistoryEntry.user("test message");
        assertEquals("user", entry.getRole());
        assertEquals("test message", entry.getContent());
    }

    @Test
    void testAssistantEntry() {
        QueryHistoryEntry entry = QueryHistoryEntry.assistant("test response");
        assertEquals("assistant", entry.getRole());
        assertEquals("test response", entry.getContent());
    }

    @Test
    void testConstructor() {
        QueryHistoryEntry entry = new QueryHistoryEntry("user", "content");
        assertEquals("user", entry.getRole());
        assertEquals("content", entry.getContent());
    }

    @Test
    void testInvalidRole() {
        assertThrows(
                IllegalArgumentException.class, () -> new QueryHistoryEntry("invalid", "content"));
    }

    @Test
    void testNullRole() {
        assertThrows(IllegalArgumentException.class, () -> new QueryHistoryEntry(null, "content"));
    }

    @Test
    void testNullContent() {
        QueryHistoryEntry entry = new QueryHistoryEntry("user", null);
        assertEquals("user", entry.getRole());
        assertNull(entry.getContent());
    }

    @Test
    void testEmptyContent() {
        QueryHistoryEntry entry = new QueryHistoryEntry("user", "");
        assertEquals("user", entry.getRole());
        assertEquals("", entry.getContent());
    }
}
