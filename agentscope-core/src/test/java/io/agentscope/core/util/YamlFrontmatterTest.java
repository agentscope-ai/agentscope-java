/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlFrontmatterTest {

    // ========== Normal Cases ==========

    @Test
    void testParseValidFrontmatter() {
        String content =
                """
                ---
                name: test_skill
                description: Test skill description
                version: 1.0.0
                ---
                # Main Content
                This is the main content.
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertEquals(3, metadata.size());
        assertEquals("test_skill", metadata.get("name"));
        assertEquals("Test skill description", metadata.get("description"));
        assertEquals("1.0.0", metadata.get("version"));
    }

    @Test
    void testParseEmptyFrontmatter() {
        String content =
                """
                ---
                ---
                # Content
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertTrue(metadata.isEmpty(), "Empty frontmatter should return empty Map");
    }

    // ========== Edge Cases - Should Return Empty Map ==========

    @Test
    void testParseNoFrontmatter() {
        // Scenario 1: No frontmatter (NORMAL case - should return empty Map)
        String content =
                """
                # Just Content
                No frontmatter here.
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertTrue(metadata.isEmpty(), "Should return empty Map when no frontmatter");
    }

    @Test
    void testParseOnlyOpeningDelimiter() {
        // Scenario 2: Only opening --- (no closing ---)
        String content =
                """
                ---
                name: test
                description: Test
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertTrue(
                metadata.isEmpty(),
                "Should return empty Map when only opening --- exists (no closing)");
    }

    @Test
    void testParseOnlyClosingDelimiter() {
        // Scenario 3: Only closing --- (not at the beginning)
        String content =
                """
                name: test
                description: Test
                ---
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertTrue(metadata.isEmpty(), "Should return empty Map when --- is not at the beginning");
    }

    @Test
    void testParseDelimiterInMiddle() {
        String content =
                """
                Some content
                ---
                name: test
                ---
                More content
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertTrue(
                metadata.isEmpty(),
                "Should return empty Map when frontmatter is not at the beginning");
    }

    // ========== Error Cases - Invalid YAML Syntax ==========

    @Test
    void testParseInvalidYamlSyntax() {
        String content =
                """
                ---
                name: test
                description: [unclosed array
                ---
                """;

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> YamlFrontmatter.parse(content),
                        "Should throw exception for invalid YAML syntax");

        assertTrue(exception.getMessage().contains("Invalid YAML frontmatter syntax"));
    }

    @Test
    void testParseYamlList() {
        // Scenario 5: Only values (YAML list) - not a Map
        String content =
                """
                ---
                - value1
                - value2
                - value3
                ---
                """;

        // SnakeYAML will parse this as a List, not a Map
        // When we cast to Map<String, Object>, it should fail or return unexpected type
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> YamlFrontmatter.parse(content),
                        "Should throw exception when YAML is a list instead of a map");
    }

    @Test
    void testParseComplexNestedStructure() {
        String content =
                """
                ---
                name: complex_skill
                config:
                  timeout: 30
                  retries: 3
                tags:
                  - ai
                  - nlp
                ---
                Content
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertEquals("complex_skill", metadata.get("name"));
        assertTrue(metadata.get("config") instanceof Map);
        assertTrue(metadata.get("tags") instanceof java.util.List);
    }

    @Test
    void testParseDifferentTypesOfKeyValues() {
        String content =
                """
                ---
                name: test
                description: Test
                number: 123
                boolean: true
                list:
                  - item1
                  - item2
                  - item3
                map:
                  key1: value1
                ---
                """;
        Map<String, Object> metadata = YamlFrontmatter.parse(content);
        assertNotNull(metadata);
        assertEquals("test", metadata.get("name"));
        assertEquals("Test", metadata.get("description"));
        assertEquals(123, metadata.get("number"));
        assertEquals(true, metadata.get("boolean"));
        assertEquals(List.of("item1", "item2", "item3"), metadata.get("list"));
        assertEquals(Map.of("key1", "value1"), metadata.get("map"));
    }
}
