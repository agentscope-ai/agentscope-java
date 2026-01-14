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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MarkdownSkillParserTest {

    @Nested
    @DisplayName("Basic Parse Tests")
    class BasicParseTests {

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
            Map<String, String> metadata = parsed.getMetadata();
            assertEquals("test_skill", metadata.get("name"));
            assertEquals("A test skill", metadata.get("description"));
            assertEquals("1.0.0", metadata.get("version"));
            assertTrue(parsed.getContent().contains("# Test Content"));
        }

        @Test
        @DisplayName("Should parse without frontmatter")
        void testParseWithoutFrontmatter() {
            String markdown = "# Just Content\nNo frontmatter here.";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertFalse(parsed.hasFrontmatter());
            assertTrue(parsed.getMetadata().isEmpty());
            assertEquals(markdown, parsed.getContent());
        }

        @Test
        @DisplayName("Should parse null and empty strings")
        void testParseNullAndEmpty() {
            ParsedMarkdown parsedNull = MarkdownSkillParser.parse(null);
            ParsedMarkdown parsedEmpty = MarkdownSkillParser.parse("");

            assertFalse(parsedNull.hasFrontmatter());
            assertFalse(parsedEmpty.hasFrontmatter());
            assertEquals("", parsedNull.getContent());
            assertEquals("", parsedEmpty.getContent());
        }

        @Test
        @DisplayName("Should parse empty frontmatter")
        void testParseEmptyFrontmatter() {
            String markdown = "---\n---\n# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertFalse(parsed.hasFrontmatter());
            assertTrue(parsed.getMetadata().isEmpty());
            assertEquals("# Content", parsed.getContent());
            assertFalse(parsed.getContent().contains("---"));
        }

        @Test
        @DisplayName("Should parse with only frontmatter")
        void testParseWithOnlyFrontmatter() {
            String markdown = "---\nname: test\n---";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("test", parsed.getMetadata().get("name"));
            assertEquals("", parsed.getContent());
        }

        @Test
        @DisplayName("Should parse with whitespace in frontmatter")
        void testParseWithWhitespaceInFrontmatter() {
            String markdown = "---  \n\nname: test\n\n---  \n\nContent";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("test", parsed.getMetadata().get("name"));
            assertEquals("Content", parsed.getContent());
        }

        @Test
        @DisplayName("Should parse with frontmatter not at start")
        void testParseWithFrontmatterNotAtStart() {
            String markdown = "Some text\n---\nname: test\n---\nContent";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertFalse(parsed.hasFrontmatter());
            assertEquals(markdown, parsed.getContent());
        }

        @Test
        @DisplayName("Should parse with multiple frontmatter sections")
        void testParseWithMultipleFrontmatterSections() {
            String markdown = "---\nname: first\n---\nContent\n---\nname: second\n---";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("first", parsed.getMetadata().get("name"));
            assertTrue(parsed.getContent().contains("Content"));
        }
    }

    @Nested
    @DisplayName("Line Ending Tests")
    class LineEndingTests {

        @Test
        @DisplayName("Should parse with different line endings")
        void testParseWithDifferentLineEndings() {
            // Unix LF
            ParsedMarkdown parsedLF = MarkdownSkillParser.parse("---\nname: unix\n---\nContent");
            assertEquals("unix", parsedLF.getMetadata().get("name"));

            // Windows CRLF
            ParsedMarkdown parsedCRLF =
                    MarkdownSkillParser.parse("---\r\nname: windows\r\n---\r\nContent");
            assertEquals("windows", parsedCRLF.getMetadata().get("name"));

            // Old Mac CR
            ParsedMarkdown parsedCR = MarkdownSkillParser.parse("---\rname: mac\r---\rContent");
            assertEquals("mac", parsedCR.getMetadata().get("name"));
        }

        @Test
        @DisplayName("Should parse with mixed line endings")
        void testParseMixedLineEndings() {
            String markdown =
                    "---\r\nname: mixed\n" + "description: test\r\n" + "---\n" + "Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("mixed", parsed.getMetadata().get("name"));
            assertEquals("test", parsed.getMetadata().get("description"));
        }

        @Test
        @DisplayName("Should parse with empty lines in frontmatter")
        void testParseWithEmptyLines() {
            String markdown =
                    "---\r\n" + "\r\n" + "name: spaced\r\n" + "\r\n" + "---\r\n" + "Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("spaced", parsed.getMetadata().get("name"));
        }

        @Test
        @DisplayName("Should parse multiline content")
        void testParseMultilineContent() {
            String markdown = "---\nname: multiline\n---\nLine 1\nLine 2\nLine 3\n\nLine 5";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            String content = parsed.getContent();
            assertTrue(content.contains("Line 1"));
            assertTrue(content.contains("Line 5"));
        }
    }

    @Nested
    @DisplayName("Quoted Value Tests")
    class QuotedValueTests {

        @Test
        @DisplayName("Should parse double and single quoted values")
        void testParseQuotedValues() {
            String markdown =
                    "---\n"
                            + "double: \"quoted value\"\n"
                            + "single: 'single quoted'\n"
                            + "---\n"
                            + "Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("quoted value", parsed.getMetadata().get("double"));
            assertEquals("single quoted", parsed.getMetadata().get("single"));
        }

        @Test
        @DisplayName("Should parse escaped characters in double quotes")
        void testParseEscapedCharacters() {
            String markdown =
                    "---\n"
                            + "path: \"C:\\\\Users\\\\test\\\\file.txt\"\n"
                            + "message: \"Line 1\\n"
                            + "Line 2\\tTabbed\"\n"
                            + "---\n"
                            + "Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("C:\\Users\\test\\file.txt", parsed.getMetadata().get("path"));
            assertEquals("Line 1\nLine 2\tTabbed", parsed.getMetadata().get("message"));
        }

        @Test
        @DisplayName("Should parse Windows path without quotes")
        void testParseWindowsPath() {
            String markdown = "---\npath: C:\\Users\\test\\file.txt\n---\nContent";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("C:\\Users\\test\\file.txt", parsed.getMetadata().get("path"));
        }
    }

    @Nested
    @DisplayName("Comment and Special Character Tests")
    class CommentAndSpecialTests {

        @Test
        @DisplayName("Should parse with comments in frontmatter")
        void testParseWithComments() {
            String markdown =
                    "---\n# This is a comment\nname: test\n# Another comment\n---\nContent";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("test", parsed.getMetadata().get("name"));
            assertEquals(1, parsed.getMetadata().size());
        }

        @Test
        @DisplayName("Should parse with unicode characters")
        void testParseUnicodeCharacters() {
            String markdown = "---\nname: 测试技能\ndescription: テスト\n---\n内容: 한국어";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("测试技能", parsed.getMetadata().get("name"));
            assertEquals("テスト", parsed.getMetadata().get("description"));
            assertTrue(parsed.getContent().contains("한국어"));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should throw exception for invalid YAML")
        void testInvalidYaml() {
            String markdown = "---\nname: test\nthis is not a valid line\n---\nContent";

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> MarkdownSkillParser.parse(markdown));
            assertTrue(exception.getMessage().contains("Invalid YAML line"));
            assertTrue(exception.getMessage().contains("expected 'key: value' format"));
        }

        @Test
        @DisplayName("Should throw exception for list format")
        void testListFormat() {
            String markdown = "---\n- item1\n- item2\n---\nContent";

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> MarkdownSkillParser.parse(markdown));
            assertTrue(exception.getMessage().contains("Invalid YAML line"));
        }
    }

    @Nested
    @DisplayName("Generate Tests")
    class GenerateTests {

        @Test
        @DisplayName("Should generate with metadata and content")
        void testGenerateBasic() {
            Map<String, String> metadata = Map.of("name", "test_skill", "description", "Test");
            String content = "# Skill Content";

            String generated = MarkdownSkillParser.generate(metadata, content);

            assertTrue(generated.startsWith("---\n"));
            assertTrue(generated.contains("name: test_skill"));
            assertTrue(generated.contains("description: Test"));
            assertTrue(generated.contains("# Skill Content"));
        }

        @Test
        @DisplayName("Should generate with null or empty inputs")
        void testGenerateNullOrEmpty() {
            // Empty metadata
            String gen1 = MarkdownSkillParser.generate(Map.of(), "Just content");
            assertFalse(gen1.contains("---"));
            assertEquals("Just content", gen1);

            // Null metadata
            String gen2 = MarkdownSkillParser.generate(null, "Just content");
            assertFalse(gen2.contains("---"));

            // Null content
            String gen3 = MarkdownSkillParser.generate(Map.of("name", "test"), null);
            assertTrue(gen3.contains("---"));
            assertTrue(gen3.contains("name: test"));

            // Empty content
            String gen4 = MarkdownSkillParser.generate(Map.of("name", "test"), "");
            assertTrue(gen4.contains("---"));
        }

        @Test
        @DisplayName("Should generate with special characters in content")
        void testGenerateSpecialContent() {
            Map<String, String> metadata = Map.of("name", "special");
            String content = "Content with special chars: @#$%^&*(){}[]|\\:;\"'<>?,./";

            String generated = MarkdownSkillParser.generate(metadata, content);

            assertTrue(generated.contains(content));
        }

        @Test
        @DisplayName("Should generate and quote values with special characters")
        void testGenerateQuotingSpecialChars() {
            Map<String, String> metadata =
                    Map.of(
                            "colon", "http://example.com:8080",
                            "hash", "#important",
                            "newline", "Line 1\nLine 2",
                            "tab", "Col1\tCol2");

            String generated = MarkdownSkillParser.generate(metadata, "Content");
            ParsedMarkdown parsed = MarkdownSkillParser.parse(generated);

            assertEquals("http://example.com:8080", parsed.getMetadata().get("colon"));
            assertEquals("#important", parsed.getMetadata().get("hash"));
            assertEquals("Line 1\nLine 2", parsed.getMetadata().get("newline"));
            assertEquals("Col1\tCol2", parsed.getMetadata().get("tab"));
        }

        @Test
        @DisplayName("Should generate and quote values with whitespace")
        void testGenerateQuotingWhitespace() {
            Map<String, String> metadata = Map.of("leading", "  spaces", "trailing", "spaces  ");

            String generated = MarkdownSkillParser.generate(metadata, "Content");
            ParsedMarkdown parsed = MarkdownSkillParser.parse(generated);

            assertEquals("  spaces", parsed.getMetadata().get("leading"));
            assertEquals("spaces  ", parsed.getMetadata().get("trailing"));
        }

        @Test
        @DisplayName("Should generate and quote values starting with YAML special chars")
        void testGenerateQuotingYAMLChars() {
            Map<String, String> metadata =
                    Map.of(
                            "quote", "\"starts with quote",
                            "bracket", "[array",
                            "brace", "{object",
                            "pipe", "|multiline",
                            "star", "*anchor",
                            "amp", "&reference",
                            "exclaim", "!tag",
                            "percent", "%directive",
                            "at", "@symbol",
                            "backtick", "`code");

            String generated = MarkdownSkillParser.generate(metadata, "Content");
            ParsedMarkdown parsed = MarkdownSkillParser.parse(generated);

            assertEquals("\"starts with quote", parsed.getMetadata().get("quote"));
            assertEquals("[array", parsed.getMetadata().get("bracket"));
            assertEquals("{object", parsed.getMetadata().get("brace"));
            assertEquals("|multiline", parsed.getMetadata().get("pipe"));
            assertEquals("*anchor", parsed.getMetadata().get("star"));
            assertEquals("&reference", parsed.getMetadata().get("amp"));
            assertEquals("!tag", parsed.getMetadata().get("exclaim"));
            assertEquals("%directive", parsed.getMetadata().get("percent"));
            assertEquals("@symbol", parsed.getMetadata().get("at"));
            assertEquals("`code", parsed.getMetadata().get("backtick"));
        }

        @Test
        @DisplayName("Should generate with empty value")
        void testGenerateEmptyValue() {
            Map<String, String> metadata = Map.of("empty", "");

            String generated = MarkdownSkillParser.generate(metadata, "Content");
            ParsedMarkdown parsed = MarkdownSkillParser.parse(generated);

            assertEquals("", parsed.getMetadata().get("empty"));
        }
    }

    @Nested
    @DisplayName("Round Trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Should round trip with basic frontmatter")
        void testRoundTripBasic() {
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
            ParsedMarkdown reparsed = MarkdownSkillParser.parse(regenerated);

            assertEquals(parsed.getMetadata().get("name"), reparsed.getMetadata().get("name"));
            assertEquals(
                    parsed.getMetadata().get("description"),
                    reparsed.getMetadata().get("description"));
            assertEquals(parsed.getContent().trim(), reparsed.getContent().trim());
        }

        @Test
        @DisplayName("Should round trip with special characters")
        void testRoundTripSpecialCharacters() {
            Map<String, String> original =
                    Map.of(
                            "url", "http://example.com:8080",
                            "tag", "#important",
                            "path", "C:\\Users\\test",
                            "message", "Line 1\nLine 2");

            String generated = MarkdownSkillParser.generate(original, "Content");
            ParsedMarkdown parsed = MarkdownSkillParser.parse(generated);

            assertEquals(original.get("url"), parsed.getMetadata().get("url"));
            assertEquals(original.get("tag"), parsed.getMetadata().get("tag"));
            assertEquals(original.get("path"), parsed.getMetadata().get("path"));
            assertEquals(original.get("message"), parsed.getMetadata().get("message"));
        }
    }

    @Nested
    @DisplayName("ParsedMarkdown Tests")
    class ParsedMarkdownTests {

        @Test
        @DisplayName("Should provide correct getters")
        void testGetters() {
            Map<String, String> metadata = Map.of("key", "value");
            ParsedMarkdown parsed = new ParsedMarkdown(metadata, "content");

            assertEquals("value", parsed.getMetadata().get("key"));
            assertEquals("content", parsed.getContent());
            assertTrue(parsed.hasFrontmatter());
        }

        @Test
        @DisplayName("Should maintain immutability")
        void testImmutability() {
            Map<String, String> originalMetadata = new java.util.HashMap<>();
            originalMetadata.put("key", "value");

            ParsedMarkdown parsed = new ParsedMarkdown(originalMetadata, "content");

            originalMetadata.put("key", "modified");
            originalMetadata.put("newkey", "newvalue");

            assertEquals("value", parsed.getMetadata().get("key"));
            assertNull(parsed.getMetadata().get("newkey"));
        }

        @Test
        @DisplayName("Should handle null inputs")
        void testNullInputs() {
            ParsedMarkdown parsed = new ParsedMarkdown(null, null);

            assertNotNull(parsed.getMetadata());
            assertTrue(parsed.getMetadata().isEmpty());
            assertEquals("", parsed.getContent());
            assertFalse(parsed.hasFrontmatter());
        }

        @Test
        @DisplayName("Should provide meaningful toString")
        void testToString() {
            Map<String, String> metadata = Map.of("name", "test");
            String content = "This is a very long content that should be truncated in toString";

            ParsedMarkdown parsed = new ParsedMarkdown(metadata, content);
            String toString = parsed.toString();

            assertTrue(toString.contains("ParsedMarkdown"));
            assertTrue(toString.contains("metadata"));
            assertTrue(toString.contains("content"));
        }
    }
}
