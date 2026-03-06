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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParseResult}.
 *
 * <p>Tests cover factory methods, validation logic, and utility methods.
 *
 * @since 1.0.10
 */
@DisplayName("ParseResult Tests")
class ParseResultTest {

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("success() should create successful ParseResult")
        void successShouldCreateSuccessfulResult() {
            ParseResult result = ParseResult.success("{\"key\":\"value\"}", ParseStage.DIRECT);

            assertTrue(result.isSuccess());
            assertEquals("{\"key\":\"value\"}", result.parsedArguments());
            assertEquals(ParseStage.DIRECT, result.stage());
            assertEquals(null, result.errorMessage());
        }

        @Test
        @DisplayName("success() should throw on null parsedArguments")
        void successShouldThrowOnNullParsedArguments() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ParseResult.success(null, ParseStage.DIRECT));
        }

        @Test
        @DisplayName("success() should throw on ORIGINAL stage")
        void successShouldThrowOnOriginalStage() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ParseResult.success("{\"key\":\"value\"}", ParseStage.ORIGINAL));
        }

        @Test
        @DisplayName("failure() should create failed ParseResult")
        void failureShouldCreateFailedResult() {
            ParseResult result =
                    ParseResult.failure("invalid json", "Parse error: unexpected token");

            assertFalse(result.isSuccess());
            assertEquals("invalid json", result.parsedArguments());
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertEquals("Parse error: unexpected token", result.errorMessage());
        }

        @Test
        @DisplayName("failure() should throw on null errorMessage")
        void failureShouldThrowOnNullErrorMessage() {
            assertThrows(
                    IllegalArgumentException.class, () -> ParseResult.failure("invalid", null));
        }

        @Test
        @DisplayName("failure() should handle empty original input")
        void failureShouldHandleEmptyOriginal() {
            ParseResult result = ParseResult.failure("", "Empty input");

            assertFalse(result.isSuccess());
            assertEquals("", result.parsedArguments());
            assertEquals("Empty input", result.errorMessage());
        }
    }

    @Nested
    @DisplayName("Compact Constructor Validation Tests")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should throw on null parsedArguments")
        void shouldThrowOnNullParsedArguments() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ParseResult(null, ParseStage.DIRECT, null));
        }

        @Test
        @DisplayName("Should throw on null stage")
        void shouldThrowOnNullStage() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ParseResult("{\"key\":\"value\"}", null, null));
        }

        @Test
        @DisplayName("Should throw on null errorMessage with ORIGINAL stage")
        void shouldThrowOnNullErrorMessageWithOriginalStage() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ParseResult("{\"key\":\"value\"}", ParseStage.ORIGINAL, null));
        }

        @Test
        @DisplayName("Should throw on non-null errorMessage with non-ORIGINAL stage")
        void shouldThrowOnErrorWithNonOriginalStage() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ParseResult("{\"key\":\"value\"}", ParseStage.DIRECT, "Some error"));
        }

        @Test
        @DisplayName("Should accept valid successful result")
        void shouldAcceptValidSuccessfulResult() {
            ParseResult result = new ParseResult("{\"key\":\"value\"}", ParseStage.DIRECT, null);

            assertNotNull(result);
            assertEquals("{\"key\":\"value\"}", result.parsedArguments());
            assertEquals(ParseStage.DIRECT, result.stage());
            assertEquals(null, result.errorMessage());
        }

        @Test
        @DisplayName("Should accept valid failed result")
        void shouldAcceptValidFailedResult() {
            ParseResult result = new ParseResult("invalid", ParseStage.ORIGINAL, "Parse error");

            assertNotNull(result);
            assertEquals("invalid", result.parsedArguments());
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertEquals("Parse error", result.errorMessage());
        }
    }

    @Nested
    @DisplayName("Utility Method Tests")
    class UtilityMethodTests {

        @Test
        @DisplayName("isSuccess() should return true for successful results")
        void isSuccessShouldReturnTrueForSuccessfulResults() {
            ParseResult result = ParseResult.success("{\"key\":\"value\"}", ParseStage.DIRECT);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("isSuccess() should return false for failed results")
        void isSuccessShouldReturnFalseForFailedResults() {
            ParseResult result = ParseResult.failure("invalid", "Error message");

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("isDirectSuccess() should return true for DIRECT stage success")
        void isDirectSuccessShouldReturnTrueForDirectStage() {
            ParseResult result = ParseResult.success("{\"key\":\"value\"}", ParseStage.DIRECT);

            assertTrue(result.isDirectSuccess());
        }

        @Test
        @DisplayName("isDirectSuccess() should return false for non-DIRECT stage")
        void isDirectSuccessShouldReturnFalseForNonDirectStage() {
            ParseResult result =
                    ParseResult.success("{\"key\":\"value\"}", ParseStage.MARKDOWN_CLEAN);

            assertFalse(result.isDirectSuccess());
        }

        @Test
        @DisplayName("isDirectSuccess() should return false for failed results")
        void isDirectSuccessShouldReturnFalseForFailedResults() {
            ParseResult result = ParseResult.failure("invalid", "Error");

            assertFalse(result.isDirectSuccess());
        }

        @Test
        @DisplayName("requiredMultipleStages() should return true for non-DIRECT success")
        void requiredMultipleStagesShouldReturnTrueForNonDirectSuccess() {
            ParseResult result =
                    ParseResult.success("{\"key\":\"value\"}", ParseStage.MARKDOWN_CLEAN);

            assertTrue(result.requiredMultipleStages());
        }

        @Test
        @DisplayName("requiredMultipleStages() should return false for DIRECT success")
        void requiredMultipleStagesShouldReturnFalseForDirectSuccess() {
            ParseResult result = ParseResult.success("{\"key\":\"value\"}", ParseStage.DIRECT);

            assertFalse(result.requiredMultipleStages());
        }

        @Test
        @DisplayName("requiredMultipleStages() should return false for failed results")
        void requiredMultipleStagesShouldReturnFalseForFailedResults() {
            ParseResult result = ParseResult.failure("invalid", "Error");

            assertFalse(result.requiredMultipleStages());
        }
    }

    @Nested
    @DisplayName("Record Component Access Tests")
    class RecordComponentAccessTests {

        @Test
        @DisplayName("Should access all components correctly")
        void shouldAccessAllComponents() {
            ParseResult result = ParseResult.success("{\"key\":\"value\"}", ParseStage.DIRECT);

            assertEquals("{\"key\":\"value\"}", result.parsedArguments());
            assertEquals(ParseStage.DIRECT, result.stage());
            assertEquals(null, result.errorMessage());
        }

        @Test
        @DisplayName("Should access components from failed result")
        void shouldAccessComponentsFromFailedResult() {
            ParseResult result = ParseResult.failure("invalid", "Error message");

            assertEquals("invalid", result.parsedArguments());
            assertEquals(ParseStage.ORIGINAL, result.stage());
            assertEquals("Error message", result.errorMessage());
        }
    }
}
