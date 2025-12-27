package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StreamOptions}.
 *
 * <p>Tests builder method.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
class StreamOptionsTest {

    @Test
    void testBuilder() {
        Map<String, Object> additionalProperty = Map.of("k", "v");
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .includeObfuscation(true)
                        .includeUsage(false)
                        .additionalProperty(additionalProperty)
                        .build();

        assertTrue(streamOptions.getIncludeObfuscation());
        assertFalse(streamOptions.getIncludeUsage());
        assertEquals(additionalProperty, streamOptions.getAdditionalProperties());
    }

    @Test
    void testAdditionalProperty() {
        Map<String, Object> additionalProperty = Map.of("k0", "v0");
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .additionalProperty(additionalProperty)
                        .additionalProperty("k1", "v1")
                        .build();

        assertNotNull(streamOptions.getAdditionalProperties());
        assertEquals(2, streamOptions.getAdditionalProperties().size());
        assertEquals("v0", streamOptions.getAdditionalProperties().get("k0"));
        assertEquals("v1", streamOptions.getAdditionalProperties().get("k1"));
    }
}
