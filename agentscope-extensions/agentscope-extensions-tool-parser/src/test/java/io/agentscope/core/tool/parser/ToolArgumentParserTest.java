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
package io.agentscope.core.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolArgumentParser}.
 *
 * <p>Tests cover all parsing stages and edge cases.
 *
 * @since 0.7.0
 */
@DisplayName("Tool Argument Parser Tests")
class ToolArgumentParserTest {

    @Nested
    @DisplayName("Stage 0: Direct Parsing (Standard JSON)")
    class DirectParsingTests {

        @Test
        @DisplayName("Should parse valid JSON directly")
        void shouldParseValidJsonDirectly() {
            String json = "{\"query\":\"test\",\"limit\":10}";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
            assertEquals(json, result.parsedArguments());
        }

        @Test
        @DisplayName("Should parse nested objects")
        void shouldParseNestedObjects() {
            String json = "{\"user\":{\"name\":\"test\",\"age\":30}}";

            ParseResult result = ToolArgumentParser.parse(json, "userTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
        }

        @Test
        @DisplayName("Should parse arrays")
        void shouldParseArrays() {
            String json = "{\"items\":[\"a\",\"b\",\"c\"]}";

            ParseResult result = ToolArgumentParser.parse(json, "arrayTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
        }
    }

    @Nested
    @DisplayName("Stage 1: Markdown Code Block Cleanup")
    class MarkdownCleanupTests {

        @Test
        @DisplayName("Should strip ```json code blocks")
        void shouldStripJsonCodeBlocks() {
            String json = "```json\n{\"query\":\"test\"}\n```";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.MARKDOWN_CLEAN, result.stage());
            assertEquals("{\"query\":\"test\"}", result.parsedArguments());
        }

        @Test
        @DisplayName("Should strip generic ``` code blocks")
        void shouldStripGenericCodeBlocks() {
            String json = "```\n{\"query\":\"test\"}\n```";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.MARKDOWN_CLEAN, result.stage());
        }

        @Test
        @DisplayName("Should handle Markdown with extra whitespace")
        void shouldHandleMarkdownWithExtraWhitespace() {
            String json = "```json\n\n  {\"query\":\"test\"}\n\n```";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.MARKDOWN_CLEAN, result.stage());
        }
    }

    @Nested
    @DisplayName("Stage 2: Comment Stripping")
    class CommentStrippingTests {

        @Test
        @DisplayName("Should strip single-line comments")
        void shouldStripSingleLineComments() {
            String json = "{\"query\":\"test\", // search keyword\n\"limit\":10}";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            // Jackson's ALLOW_COMMENTS handles this at DIRECT stage
            assertEquals(ParseStage.DIRECT, result.stage());
        }

        @Test
        @DisplayName("Should strip multi-line comments")
        void shouldStripMultiLineComments() {
            String json = "{\"data\":[1,2,3], /* data */\n\"count\":3}";

            ParseResult result = ToolArgumentParser.parse(json, "dataTool");

            assertTrue(result.isSuccess());
            // Jackson's ALLOW_COMMENTS handles this at DIRECT stage
            assertEquals(ParseStage.DIRECT, result.stage());
        }

        @Test
        @DisplayName("Should strip multiple comments")
        void shouldStripMultipleComments() {
            String json = "{\"query\":\"test\", // keyword\n\"limit\":10 /* max items */}";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            // Jackson's ALLOW_COMMENTS handles this at DIRECT stage
            assertEquals(ParseStage.DIRECT, result.stage());
        }
    }

    @Nested
    @DisplayName("Stage 3: Quote Fixing")
    class QuoteFixingTests {

        @Test
        @DisplayName("Should convert single quotes to double quotes")
        void shouldConvertSingleQuotes() {
            String json = "{'query':'test','limit':10}";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            // Jackson's ALLOW_SINGLE_QUOTES handles this at DIRECT stage
            assertEquals(ParseStage.DIRECT, result.stage());
            // The parsed JSON is still valid, Jackson accepts single quotes
        }
    }

    @Nested
    @DisplayName("Stage 4: JSON Repair")
    class JsonRepairTests {

        @Test
        @DisplayName("Should fix missing closing brace")
        void shouldFixMissingClosingBrace() {
            String json = "{\"query\":\"test\",\"limit\":10";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.JSON_REPAIR, result.stage());
            assertEquals("{\"query\":\"test\",\"limit\":10}", result.parsedArguments());
        }

        @Test
        @DisplayName("Should remove trailing comma")
        void shouldRemoveTrailingComma() {
            String json = "{\"query\":\"test\",}";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.JSON_REPAIR, result.stage());
            assertEquals("{\"query\":\"test\"}", result.parsedArguments());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should return failure for null input")
        void shouldHandleNullInput() {
            ParseResult result = ToolArgumentParser.parse(null, "testTool");

            assertTrue(!result.isSuccess()); // Changed: null input should fail
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertNotNull(result.errorMessage());
            assertTrue(result.errorMessage().contains("null or empty"));
        }

        @Test
        @DisplayName("Should return failure for empty string")
        void shouldHandleEmptyString() {
            ParseResult result = ToolArgumentParser.parse("", "testTool");

            assertTrue(!result.isSuccess()); // Changed: empty input should fail
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertNotNull(result.errorMessage());
        }

        @Test
        @DisplayName("Should return failure for whitespace-only string")
        void shouldHandleWhitespaceOnlyString() {
            ParseResult result = ToolArgumentParser.parse("   \n\t\r  ", "testTool");

            assertTrue(!result.isSuccess()); // Changed: whitespace-only should fail
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertNotNull(result.errorMessage());
        }

        @Test
        @DisplayName("Should fail for completely invalid JSON")
        void shouldFailForInvalidJson() {
            String json = "this is definitely not json";

            ParseResult result = ToolArgumentParser.parse(json, "testTool");

            assertTrue(!result.isSuccess());
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertNotNull(result.errorMessage());
        }
    }

    @Nested
    @DisplayName("ParseResult Methods")
    class ParseResultMethodTests {

        @Test
        @DisplayName("isSuccess should return true for successful parsing")
        void isSuccessShouldReturnTrueForSuccess() {
            ParseResult result = ToolArgumentParser.parse("{\"valid\":true}", "testTool");

            assertTrue(result.isSuccess());
            assertNull(result.errorMessage());
        }

        @Test
        @DisplayName("isSuccess should return false for failed parsing")
        void isSuccessShouldReturnFalseForFailure() {
            ParseResult result = ToolArgumentParser.parse("invalid json", "testTool");

            assertTrue(!result.isSuccess());
            assertNotNull(result.errorMessage());
        }

        @Test
        @DisplayName("isDirectSuccess should return true for direct parsing")
        void isDirectSuccessShouldReturnTrue() {
            ParseResult result = ToolArgumentParser.parse("{\"valid\":true}", "testTool");

            assertTrue(result.isDirectSuccess());
            assertTrue(result.requiredMultipleStages() == false);
        }

        @Test
        @DisplayName("isDirectSuccess should return false for multi-stage parsing")
        void isDirectSuccessShouldReturnFalseForMultiStage() {
            ParseResult result =
                    ToolArgumentParser.parse("```json\n{\"valid\":true}\n```", "testTool");

            assertTrue(result.isSuccess());
            assertTrue(result.isDirectSuccess() == false);
            assertTrue(result.requiredMultipleStages());
        }
    }

    @Nested
    @DisplayName("Security Tests")
    class SecurityTests {

        @Test
        @DisplayName("Should reject input exceeding MAX_ARGUMENT_SIZE")
        void shouldRejectOversizedInput() {
            // Create input exceeding 100KB limit
            String largeJson = "{\"data\":\"" + "x".repeat(101_000) + "\"}";

            ParseResult result = ToolArgumentParser.parse(largeJson, "testTool");

            assertTrue(!result.isSuccess());
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertTrue(result.errorMessage().contains("exceeds limit"));
        }

        @Test
        @DisplayName("Should accept input exactly at size limit")
        void shouldAcceptInputAtSizeLimit() {
            // Create input within 100KB limit
            String json = "{\"data\":\"" + "x".repeat(99_980) + "\"}";

            ParseResult result = ToolArgumentParser.parse(json, "testTool");

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should handle large but valid input")
        void shouldHandleLargeValidInput() {
            // Create a large but valid JSON object
            StringBuilder sb = new StringBuilder();
            sb.append("{\"items\":[");
            for (int i = 0; i < 1000; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"item").append(i).append("\"");
            }
            sb.append("],\"count\":1000}");

            String largeJson = sb.toString();

            ParseResult result = ToolArgumentParser.parse(largeJson, "testTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
        }
    }

    @Nested
    @DisplayName("Combined Cleanup Scenarios")
    class CombinedCleanupTests {

        @Test
        @DisplayName("Should handle Markdown + Comments")
        void shouldHandleMarkdownAndComments() {
            String json = "```json\n{\"query\":\"test\", // comment\n\"limit\":10}\n```";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            // Should succeed at COMMENT_STRIP or MARKDOWN_CLEAN stage
            assertTrue(
                    result.stage() == ParseStage.COMMENT_STRIP
                            || result.stage() == ParseStage.MARKDOWN_CLEAN);
        }

        @Test
        @DisplayName("Should handle Comments + Single Quotes")
        void shouldHandleCommentsAndSingleQuotes() {
            String json = "{'query':'test', // comment\n'limit':10}";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            // Jackson handles both at DIRECT stage with ALLOW_COMMENTS and ALLOW_SINGLE_QUOTES
            assertEquals(ParseStage.DIRECT, result.stage());
        }

        @Test
        @DisplayName("Should handle full cleanup pipeline")
        void shouldHandleFullPipeline() {
            String json = "```json\n{'items':[1,2,], /* comment */'count':3,}\n```";

            ParseResult result = ToolArgumentParser.parse(json, "complexTool");

            assertTrue(result.isSuccess());
            // Should succeed after multiple stages
            assertTrue(result.requiredMultipleStages());
        }

        @Test
        @DisplayName("Should handle nested brackets with strings")
        void shouldHandleNestedBracketsWithStrings() {
            // Test that brackets inside strings are not counted
            String json = "{\"text\":\"This has } and ] inside\", \"value\":123}";

            ParseResult result = ToolArgumentParser.parse(json, "testTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
        }
    }

    @Nested
    @DisplayName("Advanced Edge Cases")
    class AdvancedEdgeCaseTests {

        @Test
        @DisplayName("Should handle escaped characters")
        void shouldHandleEscapedCharacters() {
            String json =
                    "{\"text\":\"Line 1\\nLine 2\\tTabbed\",\"path\":\"C:\\\\Users\\\\test\"}";

            ParseResult result = ToolArgumentParser.parse(json, "testTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
        }

        @Test
        @DisplayName("Should handle Unicode characters")
        void shouldHandleUnicodeCharacters() {
            String json = "{\"text\":\"Hello 世界 🌍\",\"emoji\":\"😀\"}";

            ParseResult result = ToolArgumentParser.parse(json, "testTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
        }

        @Test
        @DisplayName("Should handle single quotes with Jackson ALLOW_SINGLE_QUOTES")
        void shouldHandleSingleQuotesWithJackson() {
            // Jackson's ALLOW_SINGLE_QUOTES feature should handle this
            String json = "{'query':'test','limit':10}";

            ParseResult result = ToolArgumentParser.parse(json, "searchTool");

            assertTrue(result.isSuccess());
            // May succeed at DIRECT if Jackson handles it, or QUOTE_FIX stage
        }

        @Test
        @DisplayName("Should not break single quotes inside strings")
        void shouldNotBreakSingleQuotesInsideStrings() {
            // Test that single quotes inside double-quoted strings are preserved
            String json = "{\"text\":\"It's a beautiful day\"}";

            ParseResult result = ToolArgumentParser.parse(json, "testTool");

            assertTrue(result.isSuccess());
            assertEquals(ParseStage.DIRECT, result.stage());
            assertEquals(json, result.parsedArguments());
        }
    }
}
