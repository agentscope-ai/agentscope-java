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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.ToolContextState.SpawnEntry;
import org.junit.jupiter.api.Test;

/**
 * {@link SpawnEntry} wire shape: the entry carries the child's session and nothing per-turn, and the
 * JSON keys it writes are what a restored state is read back from.
 */
class SpawnEntrySerializationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anEntrySurvivesARoundTrip() throws Exception {
        SpawnEntry entry = new SpawnEntry("agent:worker:1", "worker", "sub-1", "reviewer", 1);

        SpawnEntry restored = json.readValue(json.writeValueAsString(entry), SpawnEntry.class);

        assertEquals(entry, restored);
        assertEquals("sub-1", restored.sessionId());
        assertEquals("reviewer", restored.label());
    }
}
