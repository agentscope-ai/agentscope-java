/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.hook.ChunkMode;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StreamOptions}.
 */
class StreamOptionsTest {

    @Test
    void testDefaults() {
        StreamOptions options = StreamOptions.defaults();

        // Default should include all event types except AGENT_RESULT
        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertTrue(options.shouldStream(EventType.SUMMARY));
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));

        // Default chunk mode should be INCREMENTAL
        assertEquals(ChunkMode.INCREMENTAL, options.getChunkMode());

        // Default should not include agent result
        assertFalse(options.isIncludeAgentResult());
    }

    @Test
    void testBuilderEventTypes() {
        // Test single event type
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.REASONING).build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertFalse(options.shouldStream(EventType.TOOL_RESULT));
        assertFalse(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
    }

    @Test
    void testBuilderMultipleEventTypes() {
        // Test multiple event types
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertFalse(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
    }

    @Test
    void testBuilderAllEventTypes() {
        // Test ALL event type
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.ALL).build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertTrue(options.shouldStream(EventType.SUMMARY));
        // AGENT_RESULT still requires explicit opt-in
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));
    }

    @Test
    void testBuilderChunkMode() {
        // Test INCREMENTAL mode
        StreamOptions incrementalOptions =
                StreamOptions.builder().chunkMode(ChunkMode.INCREMENTAL).build();
        assertEquals(ChunkMode.INCREMENTAL, incrementalOptions.getChunkMode());

        // Test CUMULATIVE mode
        StreamOptions cumulativeOptions =
                StreamOptions.builder().chunkMode(ChunkMode.CUMULATIVE).build();
        assertEquals(ChunkMode.CUMULATIVE, cumulativeOptions.getChunkMode());
    }

    @Test
    void testBuilderIncludeAgentResult() {
        // Test with includeAgentResult = false
        StreamOptions withoutResult =
                StreamOptions.builder().eventTypes(EventType.ALL).includeAgentResult(false).build();
        assertFalse(withoutResult.shouldStream(EventType.AGENT_RESULT));
        assertFalse(withoutResult.isIncludeAgentResult());

        // Test with includeAgentResult = true
        StreamOptions withResult =
                StreamOptions.builder().eventTypes(EventType.ALL).includeAgentResult(true).build();
        assertTrue(withResult.shouldStream(EventType.AGENT_RESULT));
        assertTrue(withResult.isIncludeAgentResult());
    }

    @Test
    void testAgentResultRequiresExplicitOptIn() {
        // Even with ALL event types, AGENT_RESULT is not included by default
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.ALL).build();
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));

        // Must explicitly set includeAgentResult(true)
        StreamOptions optedIn =
                StreamOptions.builder().eventTypes(EventType.ALL).includeAgentResult(true).build();
        assertTrue(optedIn.shouldStream(EventType.AGENT_RESULT));
    }

    @Test
    void testGetEventTypes() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .build();

        assertEquals(2, options.getEventTypes().size());
        assertTrue(options.getEventTypes().contains(EventType.REASONING));
        assertTrue(options.getEventTypes().contains(EventType.TOOL_RESULT));
    }

    @Test
    void testCompleteConfiguration() {
        // Test all configuration options together
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.HINT)
                        .chunkMode(ChunkMode.CUMULATIVE)
                        .includeAgentResult(true)
                        .build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
        assertTrue(options.shouldStream(EventType.AGENT_RESULT));
        assertEquals(ChunkMode.CUMULATIVE, options.getChunkMode());
        assertTrue(options.isIncludeAgentResult());
    }

    @Test
    void testFilteringByEventType() {
        // Test that shouldStream correctly filters
        StreamOptions reasoningOnly =
                StreamOptions.builder().eventTypes(EventType.REASONING).build();

        assertTrue(reasoningOnly.shouldStream(EventType.REASONING));
        assertFalse(reasoningOnly.shouldStream(EventType.TOOL_RESULT));
        assertFalse(reasoningOnly.shouldStream(EventType.HINT));
        assertFalse(reasoningOnly.shouldStream(EventType.SUMMARY));
        assertFalse(reasoningOnly.shouldStream(EventType.AGENT_RESULT));
    }
}
