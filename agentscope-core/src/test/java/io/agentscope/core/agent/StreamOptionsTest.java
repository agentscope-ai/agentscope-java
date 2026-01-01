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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link StreamOptions}. */
class StreamOptionsTest {

    @Test
    void testDefaults() {
        StreamOptions options = StreamOptions.defaults();

        // Default should include ALL event types (based on current StreamOptions implementation)
        assertTrue(options.shouldStream(EventType.REASONING));
        assertTrue(options.shouldStream(EventType.TOOL_RESULT));
        assertTrue(options.shouldStream(EventType.HINT));
        assertTrue(options.shouldStream(EventType.SUMMARY));
        assertTrue(options.shouldStream(EventType.AGENT_RESULT));

        // Default should be incremental mode
        assertTrue(options.isIncremental());

        // Default should include both reasoning chunk and reasoning result
        assertTrue(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());

        // Convenience helper should respect defaults
        assertTrue(options.shouldIncludeReasoningEmission(true)); // chunk
        assertTrue(options.shouldIncludeReasoningEmission(false)); // result
    }

    @Test
    void testBuilderEventTypes() {
        // Test single event type
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.REASONING).build();

        assertTrue(options.shouldStream(EventType.REASONING));
        assertFalse(options.shouldStream(EventType.TOOL_RESULT));
        assertFalse(options.shouldStream(EventType.HINT));
        assertFalse(options.shouldStream(EventType.SUMMARY));
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));
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
        assertFalse(options.shouldStream(EventType.AGENT_RESULT));
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

    @Test
    void testReasoningChunkAndResultFlags_DefaultsTrue() {
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.REASONING).build();

        assertTrue(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());

        assertTrue(options.shouldIncludeReasoningEmission(true)); // chunk
        assertTrue(options.shouldIncludeReasoningEmission(false)); // result
    }

    @Test
    void testReasoningChunkDisabled_ResultEnabled() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(false)
                        .includeReasoningResult(true)
                        .build();

        assertFalse(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());

        assertFalse(options.shouldIncludeReasoningEmission(true)); // chunk filtered
        assertTrue(options.shouldIncludeReasoningEmission(false)); // result allowed
    }

    @Test
    void testReasoningChunkEnabled_ResultDisabled() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(false)
                        .build();

        assertTrue(options.isIncludeReasoningChunk());
        assertFalse(options.isIncludeReasoningResult());

        assertTrue(options.shouldIncludeReasoningEmission(true)); // chunk allowed
        assertFalse(options.shouldIncludeReasoningEmission(false)); // result filtered
    }

    @Test
    void testReasoningChunkAndResultBothDisabled() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(false)
                        .includeReasoningResult(false)
                        .build();

        assertFalse(options.isIncludeReasoningChunk());
        assertFalse(options.isIncludeReasoningResult());

        assertFalse(options.shouldIncludeReasoningEmission(true)); // chunk filtered
        assertFalse(options.shouldIncludeReasoningEmission(false)); // result filtered
    }
}
