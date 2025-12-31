/*
 * Copyright 2024-2026 the original author or authors.
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

package io.agentscope.core.skill.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.util.MarkdownSkillParser.ParsedMarkdown;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarkdownSkillParserTest {

    @Test
    @DisplayName("Should parse with valid frontmatter")
    void testParseWithValidFrontmatter() {
        String markdown =
                "---\n"
                        + "name: test_skill\n"
                        + "description: A test skill\n"
                        + "version: 1.0.0\n"
                        + "---\n"
                        + "# Test Content\n"
                        + "This is the skill content.";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        assertTrue(parsed.hasFrontmatter());

        Map<String, Object> metadata = parsed.getMetadata();
        assertEquals("test_skill", metadata.get("name"));
        assertEquals("A test skill", metadata.get("description"));
        assertEquals("1.0.0", metadata.get("version"));

        String content = parsed.getContent();
        assertTrue(content.contains("# Test Content"));
        assertTrue(content.contains("This is the skill content."));
    }

    @Test
    @DisplayName("Should parse without frontmatter")
    void testParseWithoutFrontmatter() {
        String markdown = "# Just Content\nNo frontmatter here.";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        assertFalse(parsed.hasFrontmatter());
        assertTrue(parsed.getMetadata().isEmpty());
        assertEquals(markdown, parsed.getContent());
    }

    @Test
    @DisplayName("Should parse empty string")
    void testParseEmptyString() {
        ParsedMarkdown parsed = MarkdownSkillParser.parse("");

        assertNotNull(parsed);
        assertFalse(parsed.hasFrontmatter());
        assertTrue(parsed.getMetadata().isEmpty());
        assertEquals("", parsed.getContent());
    }

    @Test
    @DisplayName("Should parse null string")
    void testParseNullString() {
        ParsedMarkdown parsed = MarkdownSkillParser.parse(null);

        assertNotNull(parsed);
        assertFalse(parsed.hasFrontmatter());
        assertTrue(parsed.getMetadata().isEmpty());
        assertEquals("", parsed.getContent());
    }

    @Test
    @DisplayName("Should parse empty frontmatter")
    void testParseEmptyFrontmatter() {
        String markdown = "---\n---\n# Content";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        // Empty frontmatter is still recognized as frontmatter structure
        // but results in empty metadata
        assertFalse(parsed.hasFrontmatter());
        assertTrue(parsed.getMetadata().isEmpty());
        // Content should not include the frontmatter delimiters
        assertEquals("# Content", parsed.getContent());
        assertFalse(parsed.getContent().contains("---"));
    }

    @Test
    @DisplayName("Should parse with whitespace in frontmatter")
    void testParseWithWhitespaceInFrontmatter() {
        String markdown = "---  \n\nname: test\n\n---  \n\nContent";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        assertTrue(parsed.hasFrontmatter());
        assertEquals("test", parsed.getMetadata().get("name"));
        assertEquals("Content", parsed.getContent());
    }

    @Test
    @DisplayName("Should parse with nested yaml")
    void testParseWithNestedYaml() {
        String markdown =
                "---\n"
                        + "name: complex_skill\n"
                        + "metadata:\n"
                        + "  author: John Doe\n"
                        + "  tags:\n"
                        + "    - ai\n"
                        + "    - ml\n"
                        + "---\n"
                        + "Content";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        assertTrue(parsed.hasFrontmatter());
        assertEquals("complex_skill", parsed.getMetadata().get("name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) parsed.getMetadata().get("metadata");
        assertNotNull(metadata);
        assertEquals("John Doe", metadata.get("author"));
    }

    @Test
    @DisplayName("Should parse invalid yaml throws exception")
    void testParseInvalidYamlThrowsException() {
        String markdown = "---\nname: test\n  invalid: yaml: syntax\n---\nContent";

        assertThrows(IllegalArgumentException.class, () -> MarkdownSkillParser.parse(markdown));
    }

    @Test
    @DisplayName("Should parse non map yaml throws exception")
    void testParseNonMapYamlThrowsException() {
        String markdown = "---\n- item1\n- item2\n---\nContent";

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> MarkdownSkillParser.parse(markdown));
        assertTrue(exception.getMessage().contains("must be a map"));
    }

    @Test
    @DisplayName("Should parse with different line endings")
    void testParseWithDifferentLineEndings() {
        // Test with \r\n (Windows)
        String markdownCRLF = "---\r\nname: test\r\n---\r\nContent";
        ParsedMarkdown parsedCRLF = MarkdownSkillParser.parse(markdownCRLF);
        assertTrue(parsedCRLF.hasFrontmatter());
        assertEquals("test", parsedCRLF.getMetadata().get("name"));

        // Test with \r (old Mac)
        String markdownCR = "---\rname: test\r---\rContent";
        ParsedMarkdown parsedCR = MarkdownSkillParser.parse(markdownCR);
        assertTrue(parsedCR.hasFrontmatter());
        assertEquals("test", parsedCR.getMetadata().get("name"));
    }

    @Test
    @DisplayName("Should parse with multiline content")
    void testParseWithMultilineContent() {
        String markdown =
                "---\n"
                        + "name: multiline\n"
                        + "---\n"
                        + "Line 1\n"
                        + "Line 2\n"
                        + "Line 3\n"
                        + "\n"
                        + "Line 5";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        String content = parsed.getContent();
        assertTrue(content.contains("Line 1"));
        assertTrue(content.contains("Line 5"));
    }

    @Test
    @DisplayName("Should generate with metadata and content")
    void testGenerateWithMetadataAndContent() {
        Map<String, Object> metadata = Map.of("name", "test_skill", "description", "Test");
        String content = "# Skill Content";

        String generated = MarkdownSkillParser.generate(metadata, content);

        assertNotNull(generated);
        assertTrue(generated.startsWith("---\n"));
        assertTrue(generated.contains("name: test_skill"));
        assertTrue(generated.contains("description: Test"));
        assertTrue(generated.contains("---\n"));
        assertTrue(generated.contains("# Skill Content"));
    }

    @Test
    @DisplayName("Should generate with empty metadata")
    void testGenerateWithEmptyMetadata() {
        Map<String, Object> metadata = Map.of();
        String content = "Just content";

        String generated = MarkdownSkillParser.generate(metadata, content);

        assertNotNull(generated);
        assertFalse(generated.contains("---"));
        assertEquals("Just content", generated);
    }

    @Test
    @DisplayName("Should generate with null metadata")
    void testGenerateWithNullMetadata() {
        String content = "Just content";

        String generated = MarkdownSkillParser.generate(null, content);

        assertNotNull(generated);
        assertFalse(generated.contains("---"));
        assertEquals("Just content", generated);
    }

    @Test
    @DisplayName("Should generate with null content")
    void testGenerateWithNullContent() {
        Map<String, Object> metadata = Map.of("name", "test");

        String generated = MarkdownSkillParser.generate(metadata, null);

        assertNotNull(generated);
        assertTrue(generated.contains("---"));
        assertTrue(generated.contains("name: test"));
    }

    @Test
    @DisplayName("Should generate with empty content")
    void testGenerateWithEmptyContent() {
        Map<String, Object> metadata = Map.of("name", "test");

        String generated = MarkdownSkillParser.generate(metadata, "");

        assertNotNull(generated);
        assertTrue(generated.contains("---"));
        assertTrue(generated.contains("name: test"));
    }

    @Test
    @DisplayName("Should generate with nested metadata")
    void testGenerateWithNestedMetadata() {
        Map<String, Object> metadata =
                Map.of(
                        "name",
                        "complex",
                        "config",
                        Map.of("timeout", 30, "retries", 3),
                        "tags",
                        java.util.List.of("ai", "ml"));

        String content = "Content";

        String generated = MarkdownSkillParser.generate(metadata, content);

        assertNotNull(generated);
        assertTrue(generated.contains("name: complex"));
        assertTrue(generated.contains("config:"));
        assertTrue(generated.contains("timeout: 30"));
        assertTrue(generated.contains("tags:"));
    }

    @Test
    @DisplayName("Should parse and generate round trip")
    void testParseAndGenerateRoundTrip() {
        String original =
                "---\n"
                        + "name: roundtrip\n"
                        + "description: Test roundtrip\n"
                        + "---\n"
                        + "# Content\n"
                        + "Test content";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(original);
        String regenerated =
                MarkdownSkillParser.generate(parsed.getMetadata(), parsed.getContent());

        // Parse again to compare
        ParsedMarkdown reparsed = MarkdownSkillParser.parse(regenerated);

        assertEquals(parsed.getMetadata().get("name"), reparsed.getMetadata().get("name"));
        assertEquals(
                parsed.getMetadata().get("description"), reparsed.getMetadata().get("description"));
        assertEquals(parsed.getContent().trim(), reparsed.getContent().trim());
    }

    @Test
    @DisplayName("Should parsed markdown getters")
    void testParsedMarkdownGetters() {
        Map<String, Object> metadata = Map.of("key", "value");
        String content = "content";

        ParsedMarkdown parsed = new ParsedMarkdown(metadata, content);

        assertEquals("value", parsed.getMetadata().get("key"));
        assertEquals("content", parsed.getContent());
        assertTrue(parsed.hasFrontmatter());
    }

    @Test
    @DisplayName("Should parsed markdown immutability")
    void testParsedMarkdownImmutability() {
        Map<String, Object> originalMetadata = new java.util.HashMap<>();
        originalMetadata.put("key", "value");

        ParsedMarkdown parsed = new ParsedMarkdown(originalMetadata, "content");

        // Modify original map
        originalMetadata.put("key", "modified");
        originalMetadata.put("newkey", "newvalue");

        // Parsed metadata should not be affected
        assertEquals("value", parsed.getMetadata().get("key"));
        assertNull(parsed.getMetadata().get("newkey"));
    }

    @Test
    @DisplayName("Should parsed markdown to string")
    void testParsedMarkdownToString() {
        Map<String, Object> metadata = Map.of("name", "test");
        String content = "This is a very long content that should be truncated in toString";

        ParsedMarkdown parsed = new ParsedMarkdown(metadata, content);
        String toString = parsed.toString();

        assertNotNull(toString);
        assertTrue(toString.contains("ParsedMarkdown"));
        assertTrue(toString.contains("metadata"));
        assertTrue(toString.contains("content"));
    }

    @Test
    @DisplayName("Should parsed markdown with null inputs")
    void testParsedMarkdownWithNullInputs() {
        ParsedMarkdown parsed = new ParsedMarkdown(null, null);

        assertNotNull(parsed.getMetadata());
        assertTrue(parsed.getMetadata().isEmpty());
        assertEquals("", parsed.getContent());
        assertFalse(parsed.hasFrontmatter());
    }

    @Test
    @DisplayName("Should parse with only frontmatter")
    void testParseWithOnlyFrontmatter() {
        String markdown = "---\nname: test\n---";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        assertTrue(parsed.hasFrontmatter());
        assertEquals("test", parsed.getMetadata().get("name"));
        assertEquals("", parsed.getContent());
    }

    @Test
    @DisplayName("Should parse with frontmatter not at start")
    void testParseWithFrontmatterNotAtStart() {
        String markdown = "Some text\n---\nname: test\n---\nContent";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        // Should not recognize frontmatter if not at start
        assertFalse(parsed.hasFrontmatter());
        assertEquals(markdown, parsed.getContent());
    }

    @Test
    @DisplayName("Should parse with multiple frontmatter sections")
    void testParseWithMultipleFrontmatterSections() {
        String markdown = "---\nname: first\n---\nContent\n---\nname: second\n---";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        // Should only parse the first frontmatter
        assertTrue(parsed.hasFrontmatter());
        assertEquals("first", parsed.getMetadata().get("name"));
        assertTrue(parsed.getContent().contains("Content"));
    }

    @Test
    @DisplayName("Should generate with special characters in content")
    void testGenerateWithSpecialCharactersInContent() {
        Map<String, Object> metadata = Map.of("name", "special");
        String content = "Content with special chars: @#$%^&*(){}[]|\\:;\"'<>?,./";

        String generated = MarkdownSkillParser.generate(metadata, content);

        assertNotNull(generated);
        assertTrue(generated.contains(content));
    }

    @Test
    @DisplayName("Should parse with unicode characters")
    void testParseWithUnicodeCharacters() {
        String markdown = "---\nname: 测试技能\ndescription: テスト\n---\n内容: 한국어";

        ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

        assertNotNull(parsed);
        assertTrue(parsed.hasFrontmatter());
        assertEquals("测试技能", parsed.getMetadata().get("name"));
        assertEquals("テスト", parsed.getMetadata().get("description"));
        assertTrue(parsed.getContent().contains("한국어"));
    }
}
