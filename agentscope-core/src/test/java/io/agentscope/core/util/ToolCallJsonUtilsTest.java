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
package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ToolCallJsonUtils}. */
@DisplayName("ToolCallJsonUtils Tests")
class ToolCallJsonUtilsTest {

    @Test
    @DisplayName("Should parse valid JSON object payloads")
    void testParseJsonObjectOrEmptyWithValidObject() {
        Map<String, Object> parsed =
                ToolCallJsonUtils.parseJsonObjectOrEmpty("{\"query\":\"hello\",\"page\":2}");

        assertEquals("hello", parsed.get("query"));
        assertEquals(2, parsed.get("page"));
    }

    @Test
    @DisplayName("Should return empty map for invalid JSON object payloads")
    void testParseJsonObjectOrEmptyWithInvalidObject() {
        Map<String, Object> parsed = ToolCallJsonUtils.parseJsonObjectOrEmpty("{\"query\":\"hello");

        assertTrue(parsed.isEmpty());
    }

    @Test
    @DisplayName("Should recognize valid JSON objects")
    void testIsValidJsonObject() {
        assertTrue(ToolCallJsonUtils.isValidJsonObject("{\"query\":\"hello\"}"));
        assertTrue(ToolCallJsonUtils.isValidJsonObject("{}"));
        assertFalse(ToolCallJsonUtils.isValidJsonObject("{\"query\":\"hello"));
        assertFalse(ToolCallJsonUtils.isValidJsonObject("[]"));
    }

    @Test
    @DisplayName("Should preserve valid raw JSON arguments")
    void testSanitizeArgumentsJsonPreservesValidContent() {
        String sanitized =
                ToolCallJsonUtils.sanitizeArgumentsJson(
                        "{\"query\":\"hello\"}", Map.of("query", "ignored"));

        assertEquals("{\"query\":\"hello\"}", sanitized);
    }

    @Test
    @DisplayName("Should fallback to structured input when raw JSON is invalid")
    void testSanitizeArgumentsJsonFallsBackToStructuredInput() {
        String sanitized =
                ToolCallJsonUtils.sanitizeArgumentsJson(
                        "{\"query\":\"hello", Map.of("query", "hello", "page", 2));

        assertTrue(sanitized.contains("hello"));
        assertTrue(sanitized.contains("page"));
    }

    @Test
    @DisplayName("Should fallback to empty object when both raw JSON and input are missing")
    void testSanitizeArgumentsJsonFallsBackToEmptyObject() {
        String sanitized = ToolCallJsonUtils.sanitizeArgumentsJson("{\"query\":\"hello", Map.of());

        assertEquals("{}", sanitized);
    }
}
