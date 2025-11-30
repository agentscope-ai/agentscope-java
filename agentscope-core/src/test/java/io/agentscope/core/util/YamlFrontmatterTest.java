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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    // ========== Special YAML Cases ==========

    @Test
    void testParseKeyWithoutValue() {
        // Scenario 4: Only key, no value (valid YAML - key: null)
        String content =
                """
                ---
                name:
                description:
                ---
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertEquals(2, metadata.size());
        assertEquals(null, metadata.get("name"), "Key without value should be null");
        assertEquals(null, metadata.get("description"));
    }

    @Test
    void testParseKeyWithEmptyString() {
        String content =
                """
                ---
                name: ""
                description: ''
                ---
                """;

        Map<String, Object> metadata = YamlFrontmatter.parse(content);

        assertNotNull(metadata);
        assertEquals(2, metadata.size());
        assertEquals("", metadata.get("name"), "Empty string value should be empty string");
        assertEquals("", metadata.get("description"));
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

    // ========== File Operations ==========

    @Test
    void testParseFromFile(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("test.md");
        String content =
                """
                ---
                name: file_test
                description: Test from file
                ---
                # Content
                """;
        Files.writeString(testFile, content);

        Map<String, Object> metadata = YamlFrontmatter.parseFile(testFile);

        assertNotNull(metadata);
        assertEquals("file_test", metadata.get("name"));
        assertEquals("Test from file", metadata.get("description"));
    }

    @Test
    void testParseFromFileNoFrontmatter(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("no-frontmatter.md");
        String content =
                """
                # Just Content
                No frontmatter.
                """;
        Files.writeString(testFile, content);

        Map<String, Object> metadata = YamlFrontmatter.parseFile(testFile);

        assertNotNull(metadata);
        assertTrue(metadata.isEmpty());
    }

    @Test
    void testParseFromNonExistentFile() {
        Path nonExistent = Path.of("/non/existent/file.md");

        assertThrows(
                IOException.class,
                () -> YamlFrontmatter.parseFile(nonExistent),
                "Should throw IOException for non-existent file");
    }
}
