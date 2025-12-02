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

package io.agentscope.core.tool.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for AgentSkillYamlReader.
 *
 * <p>Tests cover normal cases, edge cases with empty/missing frontmatter, and error cases with
 * invalid YAML syntax.
 */
@DisplayName("AgentSkillYamlReader Tests")
class AgentSkillYamlReaderTest {

    @Nested
    @DisplayName("Normal Cases - Valid YAML Frontmatter")
    class NormalCases {

        @Test
        @DisplayName("Should parse frontmatter with normal fields")
        void testParseMinimalFrontmatter() {
            String content =
                    """
                    ---
                    name: minimal_skill
                    description: Minimal description
                    ---
                    # Content
                    """;

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertEquals(2, metadata.size());
            assertEquals("minimal_skill", metadata.get("name"));
            assertEquals("Minimal description", metadata.get("description"));
        }

        @Test
        @DisplayName("Should parse frontmatter with complex nested structure")
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

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertEquals("complex_skill", metadata.get("name"));
            assertTrue(metadata.get("config") instanceof Map);
            assertTrue(metadata.get("tags") instanceof java.util.List);
        }

        @Test
        @DisplayName("Should parse frontmatter with different types of values")
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
            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);
            assertNotNull(metadata);
            assertEquals("test", metadata.get("name"));
            assertEquals("Test", metadata.get("description"));
            assertEquals(123, metadata.get("number"));
            assertEquals(true, metadata.get("boolean"));
            assertEquals(List.of("item1", "item2", "item3"), metadata.get("list"));
            assertEquals(Map.of("key1", "value1"), metadata.get("map"));
        }

        @Test
        @DisplayName("Should handle frontmatter with Windows line endings")
        void testParseWithWindowsLineEndings() {
            String content = "---\r\nname: test\r\ndescription: Test\r\n---\r\nContent";

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertEquals("test", metadata.get("name"));
            assertEquals("Test", metadata.get("description"));
        }

        @Test
        @DisplayName("Should handle frontmatter with mixed line endings")
        void testParseWithMixedLineEndings() {
            String content = "---\n\rname: test\r\ndescription: Test\n---\nContent";

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertEquals("test", metadata.get("name"));
        }
    }

    // ========== Edge Cases - Should Return Empty Map ==========

    @Nested
    @DisplayName("Edge Cases - Empty/Missing Frontmatter")
    class EdgeCases {

        @Test
        @DisplayName("Should return empty map for empty frontmatter")
        void testParseEmptyFrontmatter() {
            String content =
                    """
                    ---
                    ---
                    # Content
                    """;

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertTrue(metadata.isEmpty(), "Empty frontmatter should return empty Map");
        }

        @Test
        @DisplayName("Should return empty map when no frontmatter exists")
        void testParseNoFrontmatter() {
            String content =
                    """
                    # Just Content
                    No frontmatter here.
                    """;

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertTrue(metadata.isEmpty(), "Should return empty Map when no frontmatter");
        }

        @Test
        @DisplayName("Should return empty map for only opening delimiter")
        void testParseOnlyOpeningDelimiter() {
            String content =
                    """
                    ---
                    name: test
                    description: Test
                    """;

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertTrue(
                    metadata.isEmpty(),
                    "Should return empty Map when only opening --- exists (no closing)");
        }

        @Test
        @DisplayName("Should return empty map for only closing delimiter")
        void testParseOnlyClosingDelimiter() {
            String content =
                    """
                    name: test
                    description: Test
                    ---
                    """;

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertTrue(
                    metadata.isEmpty(), "Should return empty Map when --- is not at the beginning");
        }

        @Test
        @DisplayName("Should return empty map for delimiter in middle of content")
        void testParseDelimiterInMiddle() {
            String content =
                    """
                    Some content
                    ---
                    name: test
                    ---
                    More content
                    """;

            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);

            assertNotNull(metadata);
            assertTrue(
                    metadata.isEmpty(),
                    "Should return empty Map when frontmatter is not at the beginning");
        }

        @Test
        @DisplayName("Should return empty map for null content")
        void testParseNullContent() {
            Map<String, Object> metadata = AgentSkillYamlReader.parse(null);
            assertNotNull(metadata);
            assertTrue(metadata.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for empty string")
        void testParseEmptyString() {
            Map<String, Object> metadata = AgentSkillYamlReader.parse("");
            assertNotNull(metadata);
            assertTrue(metadata.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for whitespace only")
        void testParseWhitespaceOnly() {
            Map<String, Object> metadata = AgentSkillYamlReader.parse("   \n\t  \r\n  ");
            assertNotNull(metadata);
            assertTrue(metadata.isEmpty());
        }
    }

    // ========== Error Cases - Invalid YAML Syntax ==========

    @Nested
    @DisplayName("Error Cases - Invalid YAML")
    class ErrorCases {

        @Test
        @DisplayName("Should throw exception for invalid YAML syntax")
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
                            () -> AgentSkillYamlReader.parse(content),
                            "Should throw exception for invalid YAML syntax");

            assertTrue(exception.getMessage().contains("Invalid YAML frontmatter syntax"));
        }

        @Test
        @DisplayName("Should throw exception when YAML is a list instead of map")
        void testParseYamlList() {
            String content =
                    """
                    ---
                    - value1
                    - value2
                    - value3
                    ---
                    """;

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> AgentSkillYamlReader.parse(content),
                            "Should throw exception when YAML is a list instead of a map");

            assertTrue(
                    exception.getMessage().contains("YAML frontmatter must be a map"),
                    "Exception message should mention that frontmatter must be a map");
        }

        @Test
        @DisplayName("Should handle duplicate keys (SnakeYAML keeps last value)")
        void testParseDuplicateKeys() {
            String content =
                    """
                    ---
                    name: first
                    name: second
                    description: test
                    ---
                    """;

            // SnakeYAML handles duplicate keys by keeping the last value
            // This should still parse successfully
            Map<String, Object> metadata = AgentSkillYamlReader.parse(content);
            assertNotNull(metadata);
            // The last value for 'name' should be kept
            assertEquals("second", metadata.get("name"));
        }

        @Test
        @DisplayName("Should throw exception for YAML with unclosed quotes")
        void testParseUnclosedQuotes() {
            String content =
                    """
                    ---
                    name: "unclosed
                    description: test
                    ---
                    """;

            assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentSkillYamlReader.parse(content),
                    "Should throw exception for unclosed quotes");
        }
    }
}
