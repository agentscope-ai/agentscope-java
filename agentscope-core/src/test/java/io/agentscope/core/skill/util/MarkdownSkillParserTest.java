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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
            Map<String, Object> metadata = parsed.getMetadata();
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
        @DisplayName("Should return empty metadata for invalid YAML frontmatter")
        void testInvalidYaml() {
            String markdown = "---\nname: test\nthis is not a valid line\n---\nContent";

            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);
            Map<String, Object> metadata = parsed.getMetadata();

            assertTrue(metadata.isEmpty());
            assertEquals("Content", parsed.getContent());
        }

        @Test
        @DisplayName("Should return empty metadata for invalid list-style frontmatter")
        void testListFormat() {
            String markdown = "---\nname: test_skill\n- item1\n- item2\n---\nContent";

            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);
            Map<String, Object> metadata = parsed.getMetadata();

            assertTrue(metadata.isEmpty());
        }

        @Test
        @DisplayName("Should keep flat scalar metadata and preserve complex YAML structures")
        void testParseAndIgnoreComplexMetadata() {
            String markdown =
                    """
                    ---
                    name: Agent Browser
                    description: A fast Rust-based headless browser automation CLI
                    read_when:
                      - Automating web interactions
                      - Extracting structured data from pages
                    metadata: {"clawdbot":{"emoji":"🌐"}}
                    allowed-tools: Bash(agent-browser:*)
                    ---

                    # Content
                    This is the content.\
                    """;

            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);
            Map<String, Object> metadata = parsed.getMetadata();

            assertEquals("Agent Browser", metadata.get("name"));
            assertEquals(
                    "A fast Rust-based headless browser automation CLI",
                    metadata.get("description"));
            assertEquals("Bash(agent-browser:*)", metadata.get("allowed-tools"));
            assertEquals(Map.of("clawdbot", Map.of("emoji", "🌐")), metadata.get("metadata"));
            assertEquals(
                    List.of("Automating web interactions", "Extracting structured data from pages"),
                    metadata.get("read_when"));

            assertTrue(parsed.getContent().contains("# Content"));
        }

        @Test
        @DisplayName("Should parse block-style scalar values")
        void testParseBlockStyleModifiers() {
            String markdown =
                    """
                    ---
                    name: test_skill
                    description: |
                      This is a multi-line description.
                      It should be ignored by the simple parser.
                    summary: >
                      This is a folded multi-line summary.
                      It should also be ignored.
                    version: "1.0"
                    ---
                    Content\
                    """;

            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);
            Map<String, Object> metadata = parsed.getMetadata();

            assertEquals("test_skill", metadata.get("name"));
            assertEquals("1.0", metadata.get("version"));
            assertEquals(
                    "This is a multi-line description.\n"
                            + "It should be ignored by the simple parser.\n",
                    metadata.get("description"));
            assertEquals(
                    "This is a folded multi-line summary. It should also be ignored.\n",
                    metadata.get("summary"));
        }

        @Test
        @DisplayName("Should return empty metadata when frontmatter exceeds size limit")
        void testFrontmatterSizeLimit() {
            String largeValue = "x".repeat(17_000);
            String markdown = "---\nname: " + largeValue + "\ndescription: desc\n---\nContent";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.getMetadata().isEmpty());
            assertEquals("Content", parsed.getContent());
        }
    }

    @Nested
    @DisplayName("Generate Tests")
    class GenerateTests {

        @Test
        @DisplayName("Should generate with metadata and content")
        void testGenerateBasic() {
            Map<String, Object> metadata = Map.of("name", "test_skill", "description", "Test");
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
            Map<String, Object> metadata = Map.of("name", "special");
            String content = "Content with special chars: @#$%^&*(){}[]|\\:;\"'<>?,./";

            String generated = MarkdownSkillParser.generate(metadata, content);

            assertTrue(generated.contains(content));
        }

        @Test
        @DisplayName("Should generate and quote values with special characters")
        void testGenerateQuotingSpecialChars() {
            Map<String, Object> metadata =
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
            Map<String, Object> metadata = Map.of("leading", "  spaces", "trailing", "spaces  ");

            String generated = MarkdownSkillParser.generate(metadata, "Content");
            ParsedMarkdown parsed = MarkdownSkillParser.parse(generated);

            assertEquals("  spaces", parsed.getMetadata().get("leading"));
            assertEquals("spaces  ", parsed.getMetadata().get("trailing"));
        }

        @Test
        @DisplayName("Should generate and quote values starting with YAML special chars")
        void testGenerateQuotingYAMLChars() {
            Map<String, Object> metadata =
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
            Map<String, Object> metadata = Map.of("empty", "");

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
            Map<String, Object> original =
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

        @Test
        @DisplayName("Should preserve metadata order for parse and generate")
        void testPreserveMetadataOrder() {
            String original =
                    "---\n"
                            + "name: trello\n"
                            + "description: Manage Trello boards\n"
                            + "homepage: https://developer.atlassian.com/cloud/trello/rest/\n"
                            + "metadata:\n"
                            + "  clawdbot:\n"
                            + "    emoji: 📋\n"
                            + "    requires:\n"
                            + "      bins:\n"
                            + "        - jq\n"
                            + "      env:\n"
                            + "        - TRELLO_API_KEY\n"
                            + "        - TRELLO_TOKEN\n"
                            + "---\n"
                            + "Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(original);

            assertEquals(
                    List.of("name", "description", "homepage", "metadata"),
                    List.copyOf(parsed.getMetadata().keySet()));

            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMetadata =
                    (Map<String, Object>) parsed.getMetadata().get("metadata");
            assertEquals(List.of("clawdbot"), List.copyOf(nestedMetadata.keySet()));

            @SuppressWarnings("unchecked")
            Map<String, Object> clawdbotMetadata =
                    (Map<String, Object>) nestedMetadata.get("clawdbot");
            assertEquals(List.of("emoji", "requires"), List.copyOf(clawdbotMetadata.keySet()));

            String generated =
                    MarkdownSkillParser.generate(parsed.getMetadata(), parsed.getContent());
            int nameIndex = generated.indexOf("name: trello");
            int descriptionIndex = generated.indexOf("description: Manage Trello boards");
            int homepageIndex =
                    generated.indexOf(
                            "homepage: https://developer.atlassian.com/cloud/trello/rest/");
            int metadataIndex = generated.indexOf("metadata:");

            assertTrue(nameIndex < descriptionIndex);
            assertTrue(descriptionIndex < homepageIndex);
            assertTrue(homepageIndex < metadataIndex);
        }

        @Test
        @DisplayName("Should reject non-string required metadata values")
        void testRejectNonStringRequiredMetadataValues() {
            String skillMd =
                    "---\n"
                            + "name:\n"
                            + "  - a\n"
                            + "  - b\n"
                            + "description: valid description\n"
                            + "---\n"
                            + "Content";

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
        }

        @Test
        @DisplayName("Should align parser code point limit with frontmatter limit")
        void testFrontmatterAtConfiguredCodePointLimit() {
            String value = "a".repeat(16_360);
            String markdown =
                    "---\n" + "name: test\n" + "description: " + value + "\n---\n" + "Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertEquals("test", parsed.getMetadata().get("name"));
            assertEquals(value, parsed.getMetadata().get("description"));
        }

        @Test
        @DisplayName("Should keep generated document unchanged after parse and regenerate")
        void testParseThenGenerateKeepsDocumentStable() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", "trello");
            metadata.put("description", "Manage Trello boards, lists, and cards.");
            metadata.put("homepage", "https://developer.atlassian.com/cloud/trello/rest/");
            metadata.put(
                    "metadata",
                    Map.of(
                            "clawdbot",
                            Map.of(
                                    "emoji",
                                    "📋",
                                    "requires",
                                    Map.of(
                                            "bins", List.of("jq"),
                                            "env", List.of("TRELLO_API_KEY", "TRELLO_TOKEN")))));

            String original = MarkdownSkillParser.generate(metadata, "# Content\nBody");

            ParsedMarkdown parsed = MarkdownSkillParser.parse(original);
            String regenerated =
                    MarkdownSkillParser.generate(parsed.getMetadata(), parsed.getContent());

            assertEquals(original, regenerated);
        }
    }

    @Nested
    @DisplayName("ParsedMarkdown Tests")
    class ParsedMarkdownTests {

        @Test
        @DisplayName("Should provide correct getters")
        void testGetters() {
            Map<String, Object> metadata = Map.of("key", "value");
            ParsedMarkdown parsed = new ParsedMarkdown(metadata, "content");

            assertEquals("value", parsed.getMetadata().get("key"));
            assertEquals("content", parsed.getContent());
            assertTrue(parsed.hasFrontmatter());
        }

        @Test
        @DisplayName("Should maintain immutability")
        void testImmutability() {
            Map<String, Object> originalMetadata = new HashMap<>();
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
            Map<String, Object> metadata = Map.of("name", "test");
            String content = "This is a very long content that should be truncated in toString";

            ParsedMarkdown parsed = new ParsedMarkdown(metadata, content);
            String toString = parsed.toString();

            assertTrue(toString.contains("ParsedMarkdown"));
            assertTrue(toString.contains("metadata"));
            assertTrue(toString.contains("content"));
        }

        @Test
        @DisplayName("Should keep metadata immutable")
        void testMetadataImmutable() {
            ParsedMarkdown parsed = new ParsedMarkdown(Map.of("name", "test"), "content");

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> parsed.getMetadata().put("description", "desc"));
        }
    }

    @Nested
    @DisplayName("YAML Auto-Repair Tests")
    class YamlAutoRepairTests {

        @Test
        @DisplayName("Should auto-repair description with unquoted colons")
        void testAutoRepairUnquotedColons() {
            String markdown =
                    "---\n"
                            + "name: test-skills\n"
                            + "description: test skills, node: cannot find EDI partner, EDI partner"
                            + " does not exist\n"
                            + "---\n"
                            + "# Skill Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertNotNull(parsed);
            assertTrue(parsed.hasFrontmatter());
            assertEquals("test-skills", parsed.getMetadata().get("name"));
            String description = (String) parsed.getMetadata().get("description");
            assertNotNull(description);
            assertTrue(description.contains("node:"));
            assertTrue(description.contains("cannot find EDI partner"));
        }

        @Test
        @DisplayName("Should auto-repair description with error message containing colon")
        void testAutoRepairErrorMessageWithColon() {
            String markdown =
                    "---\n"
                        + "name: edi-skill\n"
                        + "description: When error contains: Can't find the EDI Customer setup\n"
                        + "---\n"
                        + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            String description = (String) parsed.getMetadata().get("description");
            assertNotNull(description);
            assertTrue(description.contains("Can't find the EDI Customer setup"));
        }

        @Test
        @DisplayName("Should handle already quoted values without double-quoting")
        void testAlreadyQuotedValuesNotDoubleQuoted() {
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "description: \"Already quoted: with colon\"\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("Already quoted: with colon", parsed.getMetadata().get("description"));
        }

        @Test
        @DisplayName("Should handle multiple fields with unquoted colons")
        void testMultipleFieldsWithUnquotedColons() {
            String markdown =
                    "---\n"
                            + "name: multi-colon\n"
                            + "description: Error: something failed, detail: node: not found\n"
                            + "example: status: error, code: 500\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            String description = (String) parsed.getMetadata().get("description");
            assertNotNull(description);
            assertTrue(description.contains("Error:"));
            assertTrue(description.contains("detail:"));
            String example = (String) parsed.getMetadata().get("example");
            assertNotNull(example);
            assertTrue(example.contains("status:"));
            assertTrue(example.contains("code:"));
        }

        @Test
        @DisplayName("Should still parse valid YAML without repair")
        void testValidYamlNoRepairNeeded() {
            String markdown =
                    "---\n"
                            + "name: valid-yaml\n"
                            + "description: A normal description without colons\n"
                            + "version: 1.0.0\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("valid-yaml", parsed.getMetadata().get("name"));
            assertEquals(
                    "A normal description without colons", parsed.getMetadata().get("description"));
            assertEquals("1.0.0", parsed.getMetadata().get("version"));
        }

        @Test
        @DisplayName("Should handle long description with multiple colons")
        void testLongDescriptionWithMultipleColons() {
            String markdown =
                    "---\n"
                        + "name: edi-error-skill\n"
                        + "description: test skills, node: cannot find EDI partner, EDI partner"
                        + " does not exist, partner config error, order 850 not generated SO, order"
                        + " 850 error: Cannot find the EDI Customer setup in the EDI partner"
                        + " function, cannot find order 850. Use this skill to handle EDI 850 order"
                        + " errors when the EDI partner cannot be found, specifically when the 850"
                        + " error contains: Cannot find the EDI Customer setup in the EDI partner"
                        + " function.\n"
                        + "---\n"
                        + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("edi-error-skill", parsed.getMetadata().get("name"));
            String description = (String) parsed.getMetadata().get("description");
            assertNotNull(description);
            assertTrue(description.contains("cannot find EDI partner"));
            assertTrue(description.contains("order 850"));
            assertTrue(description.contains("EDI Customer setup"));
        }

        @Test
        @DisplayName("Should return empty metadata when key has space (not repaired)")
        void testKeyWithSpaceNotRepaired() {
            // When a "key" contains space, repair skips it; YAML parse still fails -> empty
            // metadata
            String markdown =
                    "---\n" + "name: test\n" + "some text: value: here\n" + "---\n" + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.getMetadata().isEmpty());
        }

        @Test
        @DisplayName("Should handle colon at start of line (firstColon == 0)")
        void testColonAtLineStart() {
            // When firstColon == 0, repair condition is false
            String markdown = "---\n" + "name: test\n" + ": weird line\n" + "---\n" + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            // Invalid YAML, repair won't help since firstColon == 0
            assertTrue(parsed.getMetadata().isEmpty());
        }

        @Test
        @DisplayName("Should handle colon at end of line (no value after)")
        void testColonAtLineEnd() {
            // When line.length() == firstColon + 1, repair condition is false
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "description: text ending with colon:\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            // This is invalid YAML (mapping expects value after colon), repair skips it
            assertTrue(parsed.getMetadata().isEmpty());
        }

        @Test
        @DisplayName("Should handle URL without space after colon (no repair needed)")
        void testColonNoSpaceAfter() {
            // URL with colon but no space after - should NOT trigger needsQuoting
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "url: http://example.com\n"
                            + "description: normal text\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("http://example.com", parsed.getMetadata().get("url"));
            assertEquals("normal text", parsed.getMetadata().get("description"));
        }

        @Test
        @DisplayName("Should handle empty trimmed value in needsQuoting")
        void testEmptyValueNoQuoting() {
            // Value that trims to empty should not trigger quoting
            String markdown = "---\n" + "name: test\n" + "description: \n" + "---\n" + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("test", parsed.getMetadata().get("name"));
        }

        @Test
        @DisplayName("Should handle value with only colons no spaces")
        void testColonsWithoutSpaces() {
            // Value contains colons but no ": " pattern - should not trigger needsQuoting
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "data: key1:value1,key2:value2\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("key1:value1,key2:value2", parsed.getMetadata().get("data"));
        }

        @Test
        @DisplayName("Should handle multiple repairable lines with mixed quoting")
        void testMultipleLinesMixedQuoting() {
            // Mix of already-quoted and unquoted colon patterns
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "description: \"already quoted: safe\"\n"
                            + "detail: error: something: happened\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("already quoted: safe", parsed.getMetadata().get("description"));
            String detail = (String) parsed.getMetadata().get("detail");
            assertNotNull(detail);
            assertTrue(detail.contains("error:"));
        }

        @Test
        @DisplayName("Should repair value containing double quotes")
        void testRepairWithDoubleQuotes() {
            // Value contains double quotes that need escaping during repair
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "description: error: \"not found\": retry\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            assertEquals("test", parsed.getMetadata().get("name"));
            String description = (String) parsed.getMetadata().get("description");
            assertNotNull(description);
            assertTrue(description.contains("error:"));
        }

        @Test
        @DisplayName("Should repair value containing backslash")
        void testRepairWithBackslash() {
            // Value contains backslash that needs escaping during repair
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "path: C:\\Users\\admin\\error: not found\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.hasFrontmatter());
            String path = (String) parsed.getMetadata().get("path");
            assertNotNull(path);
            assertTrue(path.contains("error:"));
        }

        @Test
        @DisplayName("Should return empty metadata when repair still fails after quoting")
        void testRepairStillFailsAfterQuoting() {
            // YAML that fails initial parse, repair modifies it, but re-parse still fails
            // Invalid YAML: mixing mapping and sequence at same level
            String markdown =
                    "---\n"
                            + "name: test\n"
                            + "detail: error: something\n"
                            + "- broken item\n"
                            + "---\n"
                            + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            // Repair can't fix this - it's fundamentally broken YAML structure
            assertTrue(parsed.getMetadata().isEmpty());
        }

        @Test
        @DisplayName("Should return empty metadata when YAML parses to null")
        void testYamlParsesToNull() {
            // Empty YAML content between --- markers parses to null
            String markdown = "---\n" + " \n" + "---\n" + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.getMetadata().isEmpty());
        }

        @Test
        @DisplayName("Should return empty metadata for non-map top-level YAML")
        void testNonMapTopLevelYaml() {
            // YAML list as top-level instead of map
            String markdown =
                    "---\n" + "- item1\n" + "- item2\n" + "- item3\n" + "---\n" + "# Content";

            ParsedMarkdown parsed = MarkdownSkillParser.parse(markdown);

            assertTrue(parsed.getMetadata().isEmpty());
        }
    }
}
