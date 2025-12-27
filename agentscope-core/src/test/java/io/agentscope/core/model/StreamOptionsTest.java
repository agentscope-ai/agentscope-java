/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
