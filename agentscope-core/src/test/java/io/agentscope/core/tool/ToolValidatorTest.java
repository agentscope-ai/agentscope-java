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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("ToolValidator Tests")
class ToolValidatorTest {

    // ==================== validateInput Tests ====================

    @Nested
    @DisplayName("validateInput - Schema Edge Cases")
    class ValidateInputSchemaEdgeCases {

        @Test
        @DisplayName("Should pass when schema is null")
        void testNullSchema() {
            Map<String, Object> input = Map.of("name", "test");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), null);
            assertNull(result);
        }

        @Test
        @DisplayName("Should pass when input is empty object with empty schema")
        void testEmptyInputWithEmptySchema() {
            // This simulates a tool with no parameters (like getTime())
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of()); // Empty properties = no parameters

            String result = ToolValidator.validateInput("{}", schema);
            assertNull(result, "Empty object {} should pass validation with empty schema");
        }

        @Test
        @DisplayName("Should pass when schema is empty")
        void testEmptySchema() {
            Map<String, Object> input = Map.of("name", "test");
            String result =
                    ToolValidator.validateInput(
                            JsonUtils.getJsonCodec().toJson(input), Collections.emptyMap());
            assertNull(result);
        }

        @Test
        @DisplayName("Should fail when input is null")
        void testNullInputNoRequired() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("name", Map.of("type", "string")));
            String result = ToolValidator.validateInput(null, schema);
            // Null input should return validation error (content is null)
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("validateInput - Required Fields")
    class ValidateInputRequiredFields {

        @Test
        @DisplayName("Should fail when required field is missing")
        void testMissingRequiredField() {
            Map<String, Object> schema =
                    Map.of(
                            "type", "object",
                            "properties", Map.of("name", Map.of("type", "string")),
                            "required", List.of("name"));

            Map<String, Object> input = Map.of("other", "value");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
            assertTrue(result.contains("name"));
        }

        @Test
        @DisplayName("Should pass when all required fields are present")
        void testAllRequiredFieldsPresent() {
            Map<String, Object> schema =
                    Map.of(
                            "type", "object",
                            "properties",
                                    Map.of(
                                            "name", Map.of("type", "string"),
                                            "age", Map.of("type", "integer")),
                            "required", List.of("name", "age"));

            Map<String, Object> input = Map.of("name", "John", "age", 30);
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNull(result);
        }

        @Test
        @DisplayName("Should fail when null input with required fields")
        void testNullInputWithRequiredFields() {
            Map<String, Object> schema =
                    Map.of(
                            "type", "object",
                            "properties", Map.of("name", Map.of("type", "string")),
                            "required", List.of("name"));

            String result = ToolValidator.validateInput(null, schema);

            // Null input should return validation error (content is null)
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("validateInput - Type Validation")
    class ValidateInputTypeValidation {

        @Test
        @DisplayName("Should fail when string expected but number provided")
        void testStringTypeWithNumber() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("name", Map.of("type", "string")));

            Map<String, Object> input = Map.of("name", 123);
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
            assertTrue(result.toLowerCase().contains("type") || result.contains("string"));
        }

        @Test
        @DisplayName("Should fail when integer expected but string provided")
        void testIntegerTypeWithString() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("count", Map.of("type", "integer")));

            Map<String, Object> input = Map.of("count", "not a number");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should pass when correct types are provided")
        void testCorrectTypes() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "name", Map.of("type", "string"),
                                    "age", Map.of("type", "integer"),
                                    "score", Map.of("type", "number"),
                                    "active", Map.of("type", "boolean")));

            Map<String, Object> input =
                    Map.of("name", "John", "age", 30, "score", 95.5, "active", true);
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNull(result);
        }

        @Test
        @DisplayName("Should validate boolean type")
        void testBooleanType() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("enabled", Map.of("type", "boolean")));

            // Valid boolean
            assertNull(
                    ToolValidator.validateInput(
                            JsonUtils.getJsonCodec().toJson(Map.of("enabled", true)), schema));
            assertNull(
                    ToolValidator.validateInput(
                            JsonUtils.getJsonCodec().toJson(Map.of("enabled", false)), schema));

            // Invalid boolean
            assertNotNull(
                    ToolValidator.validateInput(
                            JsonUtils.getJsonCodec().toJson(Map.of("enabled", "true")), schema));
            assertNotNull(
                    ToolValidator.validateInput(
                            JsonUtils.getJsonCodec().toJson(Map.of("enabled", 1)), schema));
        }
    }

    @Nested
    @DisplayName("validateInput - Enum Validation")
    class ValidateInputEnumValidation {

        @Test
        @DisplayName("Should pass when value is in enum")
        void testValidEnumValue() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "status",
                                    Map.of(
                                            "type",
                                            "string",
                                            "enum",
                                            List.of("active", "inactive", "pending"))));

            Map<String, Object> input = Map.of("status", "active");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNull(result);
        }

        @Test
        @DisplayName("Should fail when value is not in enum")
        void testInvalidEnumValue() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "status",
                                    Map.of(
                                            "type",
                                            "string",
                                            "enum",
                                            List.of("active", "inactive", "pending"))));

            Map<String, Object> input = Map.of("status", "unknown");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("validateInput - Number Constraints")
    class ValidateInputNumberConstraints {

        @Test
        @DisplayName("Should fail when number is below minimum")
        void testBelowMinimum() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("age", Map.of("type", "integer", "minimum", 0)));

            Map<String, Object> input = Map.of("age", -1);
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should fail when number exceeds maximum")
        void testAboveMaximum() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("score", Map.of("type", "number", "maximum", 100)));

            Map<String, Object> input = Map.of("score", 150);
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should pass when number is within range")
        void testWithinRange() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "percentage",
                                    Map.of("type", "number", "minimum", 0, "maximum", 100)));

            Map<String, Object> input = Map.of("percentage", 50);
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("validateInput - String Constraints")
    class ValidateInputStringConstraints {

        @Test
        @DisplayName("Should fail when string is shorter than minLength")
        void testBelowMinLength() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("password", Map.of("type", "string", "minLength", 8)));

            Map<String, Object> input = Map.of("password", "short");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should fail when string exceeds maxLength")
        void testAboveMaxLength() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("code", Map.of("type", "string", "maxLength", 4)));

            Map<String, Object> input = Map.of("code", "toolong");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should pass when string length is within range")
        void testWithinLengthRange() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "code",
                                    Map.of("type", "string", "minLength", 2, "maxLength", 10)));

            Map<String, Object> input = Map.of("code", "valid");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNull(result);
        }

        @Test
        @DisplayName("Should fail when string does not match pattern")
        void testPatternMismatch() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "email",
                                    Map.of(
                                            "type",
                                            "string",
                                            "pattern",
                                            "^[a-z]+@[a-z]+\\.[a-z]+$")));

            Map<String, Object> input = Map.of("email", "invalid-email");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should pass when string matches pattern")
        void testPatternMatch() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "email",
                                    Map.of(
                                            "type",
                                            "string",
                                            "pattern",
                                            "^[a-z]+@[a-z]+\\.[a-z]+$")));

            Map<String, Object> input = Map.of("email", "test@example.com");
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("validateInput - Array Validation")
    class ValidateInputArrayValidation {

        @Test
        @DisplayName("Should pass when array items are valid")
        void testValidArrayItems() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "tags",
                                    Map.of("type", "array", "items", Map.of("type", "string"))));

            Map<String, Object> input = Map.of("tags", List.of("java", "kotlin", "scala"));
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNull(result);
        }

        @Test
        @DisplayName("Should fail when array item type is wrong")
        void testInvalidArrayItemType() {
            Map<String, Object> schema =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "numbers",
                                    Map.of("type", "array", "items", Map.of("type", "integer"))));

            Map<String, Object> input = Map.of("numbers", List.of(1, 2, "three"));
            String result =
                    ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(input), schema);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("validateInput - Nested Object Validation")
    class ValidateInputNestedObjectValidation {

        @Test
        @DisplayName("Should validate nested objects")
        void testNestedObjectValidation() {
            Map<String, Object> addressSchema =
                    Map.of(
                            "type", "object",
                            "properties",
                                    Map.of(
                                            "street", Map.of("type", "string"),
                                            "city", Map.of("type", "string")),
                            "required", List.of("city"));

            Map<String, Object> schema =
                    Map.of("type", "object", "properties", Map.of("address", addressSchema));

            // Valid nested object
            Map<String, Object> validInput =
                    Map.of("address", Map.of("street", "123 Main St", "city", "Boston"));
            assertNull(
                    ToolValidator.validateInput(
                            JsonUtils.getJsonCodec().toJson(validInput), schema));

            // Invalid - missing required nested field
            Map<String, Object> invalidInput = Map.of("address", Map.of("street", "123 Main St"));
            assertNotNull(
                    ToolValidator.validateInput(
                            JsonUtils.getJsonCodec().toJson(invalidInput), schema));
        }
    }

    // ==================== validateToolResultMatch Tests ====================

    @Nested
    @DisplayName("validateToolResultMatch Tests")
    class ValidateToolResultMatchTests {

        @Test
        @DisplayName("Should pass when assistantMsg is null")
        void testNullAssistantMsg() {
            assertDoesNotThrow(() -> ToolValidator.validateToolResultMatch(null, null));
        }

        @Test
        @DisplayName("Should pass when assistantMsg has no ToolUse blocks")
        void testNoToolUseBlocks() {
            Msg assistantMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            assertDoesNotThrow(() -> ToolValidator.validateToolResultMatch(assistantMsg, null));
        }

        @Test
        @DisplayName("Should throw when ToolUse blocks exist but no inputMsgs provided")
        void testToolUseWithoutInput() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("tool-1")
                            .name("search")
                            .input(Map.of("query", "test"))
                            .build();

            Msg assistantMsg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build();

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> ToolValidator.validateToolResultMatch(assistantMsg, null));

            assertTrue(exception.getMessage().contains("search"));
        }

        @Test
        @DisplayName("Should throw when ToolUse blocks exist but inputMsgs is empty")
        void testToolUseWithEmptyInput() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("tool-1")
                            .name("calculator")
                            .input(Map.of("a", 1))
                            .build();

            Msg assistantMsg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build();

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> ToolValidator.validateToolResultMatch(assistantMsg, List.of()));

            assertTrue(exception.getMessage().contains("calculator"));
        }

        @Test
        @DisplayName("Should pass when all ToolUse IDs have matching ToolResults")
        void testMatchingToolResults() {
            ToolUseBlock toolUse1 =
                    ToolUseBlock.builder()
                            .id("tool-1")
                            .name("search")
                            .input(Map.of("q", "a"))
                            .build();
            ToolUseBlock toolUse2 =
                    ToolUseBlock.builder()
                            .id("tool-2")
                            .name("fetch")
                            .input(Map.of("url", "b"))
                            .build();

            Msg assistantMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(List.of(toolUse1, toolUse2))
                            .build();

            ToolResultBlock result1 =
                    ToolResultBlock.of(
                            "tool-1", "search", TextBlock.builder().text("result1").build());
            ToolResultBlock result2 =
                    ToolResultBlock.of(
                            "tool-2", "fetch", TextBlock.builder().text("result2").build());

            Msg userMsg =
                    Msg.builder().role(MsgRole.USER).content(List.of(result1, result2)).build();

            assertDoesNotThrow(
                    () -> ToolValidator.validateToolResultMatch(assistantMsg, List.of(userMsg)));
        }

        @Test
        @DisplayName("Should throw when some ToolUse IDs are missing ToolResults")
        void testMissingToolResults() {
            ToolUseBlock toolUse1 =
                    ToolUseBlock.builder()
                            .id("tool-1")
                            .name("search")
                            .input(Map.of("q", "a"))
                            .build();
            ToolUseBlock toolUse2 =
                    ToolUseBlock.builder()
                            .id("tool-2")
                            .name("fetch")
                            .input(Map.of("url", "b"))
                            .build();

            Msg assistantMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(List.of(toolUse1, toolUse2))
                            .build();

            // Only provide result for tool-1, missing tool-2
            ToolResultBlock result1 =
                    ToolResultBlock.of(
                            "tool-1", "search", TextBlock.builder().text("result1").build());

            Msg userMsg = Msg.builder().role(MsgRole.USER).content(result1).build();

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    ToolValidator.validateToolResultMatch(
                                            assistantMsg, List.of(userMsg)));

            assertTrue(exception.getMessage().contains("tool-2"));
        }

        @Test
        @DisplayName("Should pass when ToolResults span multiple messages")
        void testToolResultsAcrossMultipleMessages() {
            ToolUseBlock toolUse1 =
                    ToolUseBlock.builder()
                            .id("tool-1")
                            .name("search")
                            .input(Map.of("q", "a"))
                            .build();
            ToolUseBlock toolUse2 =
                    ToolUseBlock.builder()
                            .id("tool-2")
                            .name("fetch")
                            .input(Map.of("url", "b"))
                            .build();

            Msg assistantMsg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(List.of(toolUse1, toolUse2))
                            .build();

            // Results in separate messages
            ToolResultBlock result1 =
                    ToolResultBlock.of(
                            "tool-1", "search", TextBlock.builder().text("result1").build());
            ToolResultBlock result2 =
                    ToolResultBlock.of(
                            "tool-2", "fetch", TextBlock.builder().text("result2").build());

            Msg userMsg1 = Msg.builder().role(MsgRole.USER).content(result1).build();
            Msg userMsg2 = Msg.builder().role(MsgRole.USER).content(result2).build();

            assertDoesNotThrow(
                    () ->
                            ToolValidator.validateToolResultMatch(
                                    assistantMsg, List.of(userMsg1, userMsg2)));
        }
    }
}
