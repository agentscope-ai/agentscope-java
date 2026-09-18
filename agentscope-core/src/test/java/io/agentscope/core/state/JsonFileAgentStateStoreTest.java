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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for JsonFileAgentStateStore. */
@DisplayName("JsonFileAgentStateStore Tests")
class JsonFileAgentStateStoreTest {

    @Test
    @DisplayName("Should sanitize invalid Unicode when saving single state")
    void saveSanitizesInvalidUnicode(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);

        store.save(null, "session1", "agent_state", new TestState("before \uD800 after", 1));

        var loaded = store.get(null, "session1", "agent_state", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("before \uFFFD after", loaded.get().value());
    }

    @Test
    @DisplayName("Should sanitize invalid Unicode when saving list state")
    void saveListSanitizesInvalidUnicode(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);

        store.save(
                null,
                "session1",
                "memory_messages",
                List.of(new TestState("before \uD800 after", 1)));

        List<TestState> loaded =
                store.getList(null, "session1", "memory_messages", TestState.class);
        assertEquals(1, loaded.size());
        assertEquals("before \uFFFD after", loaded.get(0).value());
    }

    @Test
    @DisplayName("Should sanitize invalid Unicode when rewriting list state")
    void rewriteListSanitizesInvalidUnicode(@TempDir Path tempDir) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);

        store.save(
                null,
                "session1",
                "memory_messages",
                List.of(new TestState("ok", 1), new TestState("ok2", 2)));

        // Shrink list to force rewriteEntireList(...)
        store.save(
                null,
                "session1",
                "memory_messages",
                List.of(new TestState("before \uD800 after", 1)));

        List<TestState> loaded =
                store.getList(null, "session1", "memory_messages", TestState.class);
        assertEquals(1, loaded.size());
        assertEquals("before \uFFFD after", loaded.get(0).value());
    }

    public record TestState(String value, int count) implements State {}
}
