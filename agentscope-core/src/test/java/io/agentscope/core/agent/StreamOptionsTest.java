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
        assertTrue(options.shouldStream(EventType.AGENT_RESULT));

        // Default should be incremental mode
        assertTrue(options.isIncremental());
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
        assertTrue(options.shouldStream(EventType.AGENT_RESULT));
    }

    @Test
    void testBuilderIncrementalMode() {
        // Test incremental mode (true)
        StreamOptions incrementalOptions = StreamOptions.builder().incremental(true).build();
        assertTrue(incrementalOptions.isIncremental());

        // Test cumulative mode (false)
        StreamOptions cumulativeOptions = StreamOptions.builder().incremental(false).build();
        assertFalse(cumulativeOptions.isIncremental());
    }

    @Test
    void testAgentResultRequiresExplicitOptIn() {
        // Even with ALL event types, AGENT_RESULT is not included by default
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.ALL).build();
        assertTrue(options.shouldStream(EventType.AGENT_RESULT));
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
                        .incremental(false)
                        .build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));
        assertFalse(options.isIncremental());
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
