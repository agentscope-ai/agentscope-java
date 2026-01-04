/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.chat.completions.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolCall}.
 *
 * <p>These tests verify the tool call model's behavior.
 */
@DisplayName("ToolCall Tests")
class ToolCallTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create tool call with default constructor")
        void shouldCreateToolCallWithDefaultConstructor() {
            ToolCall toolCall = new ToolCall();

            assertNull(toolCall.getId());
            assertEquals("function", toolCall.getType());
            assertNull(toolCall.getFunction());
        }

        @Test
        @DisplayName("Should create tool call with all parameters")
        void shouldCreateToolCallWithAllParameters() {
            ToolCall toolCall = new ToolCall("call-123", "get_weather", "{\"city\":\"Hangzhou\"}");

            assertEquals("call-123", toolCall.getId());
            assertEquals("function", toolCall.getType());
            assertNotNull(toolCall.getFunction());
            assertEquals("get_weather", toolCall.getFunction().getName());
            assertEquals("{\"city\":\"Hangzhou\"}", toolCall.getFunction().getArguments());
        }
    }

    @Nested
    @DisplayName("FunctionCall Tests")
    class FunctionCallTests {

        @Test
        @DisplayName("Should create function call with default constructor")
        void shouldCreateFunctionCallWithDefaultConstructor() {
            ToolCall.FunctionCall function = new ToolCall.FunctionCall();

            assertNull(function.getName());
            assertNull(function.getArguments());
        }

        @Test
        @DisplayName("Should create function call with parameters")
        void shouldCreateFunctionCallWithParameters() {
            ToolCall.FunctionCall function =
                    new ToolCall.FunctionCall("calculate", "{\"a\":1,\"b\":2}");

            assertEquals("calculate", function.getName());
            assertEquals("{\"a\":1,\"b\":2}", function.getArguments());
        }

        @Test
        @DisplayName("Should set and get function properties")
        void shouldSetAndGetFunctionProperties() {
            ToolCall.FunctionCall function = new ToolCall.FunctionCall();
            function.setName("test_function");
            function.setArguments("{\"key\":\"value\"}");

            assertEquals("test_function", function.getName());
            assertEquals("{\"key\":\"value\"}", function.getArguments());
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should set and get all properties")
        void shouldSetAndGetAllProperties() {
            ToolCall toolCall = new ToolCall();

            toolCall.setId("custom-id");
            toolCall.setType("custom-type");
            toolCall.setFunction(new ToolCall.FunctionCall("func", "{}"));

            assertEquals("custom-id", toolCall.getId());
            assertEquals("custom-type", toolCall.getType());
            assertNotNull(toolCall.getFunction());
            assertEquals("func", toolCall.getFunction().getName());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle empty arguments")
        void shouldHandleEmptyArguments() {
            ToolCall toolCall = new ToolCall("call-1", "no_args", "{}");

            assertEquals("{}", toolCall.getFunction().getArguments());
        }

        @Test
        @DisplayName("Should handle complex JSON arguments")
        void shouldHandleComplexJsonArguments() {
            String complexArgs =
                    "{\"nested\":{\"key\":\"value\"},\"array\":[1,2,3],\"unicode\":\"你好\"}";
            ToolCall toolCall = new ToolCall("call-1", "complex_func", complexArgs);

            assertEquals(complexArgs, toolCall.getFunction().getArguments());
        }
    }
}
