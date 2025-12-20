package io.agentscope.core.agent;

import org.junit.jupiter.api.Test;

class StreamOptionsTest {

    @Test
    void defaultOptions_includeBothReasoningTypes() {
        StreamOptions options = StreamOptions.defaults();

        assertTrue(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());
    }

    @Test
    void builder_canDisableReasoningChunk() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(false)
                        .includeReasoningResult(true)
                        .build();

        assertFalse(options.isIncludeReasoningChunk());
        assertTrue(options.isIncludeReasoningResult());

        assertFalse(options.shouldIncludeReasoningEmission(true)); // chunk
        assertTrue(options.shouldIncludeReasoningEmission(false)); // result
    }

    @Test
    void builder_canDisableReasoningResult() {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(false)
                        .build();

        assertTrue(options.shouldIncludeReasoningEmission(true)); // chunk
        assertFalse(options.shouldIncludeReasoningEmission(false)); // result
    }
}
