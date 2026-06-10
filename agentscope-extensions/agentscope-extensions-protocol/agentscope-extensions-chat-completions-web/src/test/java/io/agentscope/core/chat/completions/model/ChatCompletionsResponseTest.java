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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatCompletionsResponse}.
 */
@DisplayName("ChatCompletionsResponse Tests")
class ChatCompletionsResponseTest {

    @Nested
    @DisplayName("Default State Tests")
    class DefaultStateTests {

        @Test
        @DisplayName("Should have default object value")
        void shouldHaveDefaultObjectValue() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();

            assertEquals("chat.completion", response.getObject());
            assertNull(response.getId());
            assertEquals(0, response.getCreated());
            assertNull(response.getModel());
            assertNull(response.getChoices());
            assertNull(response.getUsage());
        }
    }

    @Nested
    @DisplayName("Basic Fields Tests")
    class BasicFieldsTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            response.setId("chatcmpl-123456");

            assertEquals("chatcmpl-123456", response.getId());
        }

        @Test
        @DisplayName("Should set and get object")
        void shouldSetAndGetObject() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            response.setObject("chat.completion");

            assertEquals("chat.completion", response.getObject());
        }

        @Test
        @DisplayName("Should set and get created timestamp")
        void shouldSetAndGetCreatedTimestamp() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            long timestamp = 1704067200L;
            response.setCreated(timestamp);

            assertEquals(timestamp, response.getCreated());
        }

        @Test
        @DisplayName("Should set and get model")
        void shouldSetAndGetModel() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            response.setModel("gpt-4");

            assertEquals("gpt-4", response.getModel());
        }
    }

    @Nested
    @DisplayName("Choices Tests")
    class ChoicesTests {

        @Test
        @DisplayName("Should set and get choices")
        void shouldSetAndGetChoices() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            ChatChoice choice = new ChatChoice();
            choice.setIndex(0);
            choice.setMessage(new ChatMessage("assistant", "Hello!"));
            choice.setFinishReason("stop");

            response.setChoices(List.of(choice));

            assertNotNull(response.getChoices());
            assertEquals(1, response.getChoices().size());
            assertEquals(0, response.getChoices().get(0).getIndex());
            assertEquals("stop", response.getChoices().get(0).getFinishReason());
        }

        @Test
        @DisplayName("Should handle multiple choices")
        void shouldHandleMultipleChoices() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();

            ChatChoice choice1 = new ChatChoice();
            choice1.setIndex(0);
            choice1.setMessage(new ChatMessage("assistant", "Response 1"));

            ChatChoice choice2 = new ChatChoice();
            choice2.setIndex(1);
            choice2.setMessage(new ChatMessage("assistant", "Response 2"));

            response.setChoices(List.of(choice1, choice2));

            assertEquals(2, response.getChoices().size());
            assertEquals(0, response.getChoices().get(0).getIndex());
            assertEquals(1, response.getChoices().get(1).getIndex());
        }
    }

    @Nested
    @DisplayName("Usage Tests")
    class UsageTests {

        @Test
        @DisplayName("Should set and get usage with constructor")
        void shouldSetAndGetUsageWithConstructor() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            ChatCompletionsResponse.Usage usage = new ChatCompletionsResponse.Usage(100, 50, 150);

            response.setUsage(usage);

            assertNotNull(response.getUsage());
            assertEquals(100, response.getUsage().getPromptTokens());
            assertEquals(50, response.getUsage().getCompletionTokens());
            assertEquals(150, response.getUsage().getTotalTokens());
        }

        @Test
        @DisplayName("Should create usage with default constructor and setters")
        void shouldCreateUsageWithDefaultConstructorAndSetters() {
            ChatCompletionsResponse.Usage usage = new ChatCompletionsResponse.Usage();
            usage.setPromptTokens(200);
            usage.setCompletionTokens(100);
            usage.setTotalTokens(300);

            assertEquals(200, usage.getPromptTokens());
            assertEquals(100, usage.getCompletionTokens());
            assertEquals(300, usage.getTotalTokens());
        }

        @Test
        @DisplayName("Should handle null usage values")
        void shouldHandleNullUsageValues() {
            ChatCompletionsResponse.Usage usage = new ChatCompletionsResponse.Usage();

            assertNull(usage.getPromptTokens());
            assertNull(usage.getCompletionTokens());
            assertNull(usage.getTotalTokens());
        }
    }

    @Nested
    @DisplayName("Complete Response Tests")
    class CompleteResponseTests {

        @Test
        @DisplayName("Should build complete response")
        void shouldBuildCompleteResponse() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            response.setId("chatcmpl-abc123");
            response.setObject("chat.completion");
            response.setCreated(1704067200L);
            response.setModel("gpt-4");

            ChatChoice choice = new ChatChoice();
            choice.setIndex(0);
            choice.setMessage(new ChatMessage("assistant", "Hello! How can I help you?"));
            choice.setFinishReason("stop");
            response.setChoices(List.of(choice));

            response.setUsage(new ChatCompletionsResponse.Usage(10, 15, 25));

            assertEquals("chatcmpl-abc123", response.getId());
            assertEquals("chat.completion", response.getObject());
            assertEquals(1704067200L, response.getCreated());
            assertEquals("gpt-4", response.getModel());
            assertEquals(1, response.getChoices().size());
            assertEquals(25, response.getUsage().getTotalTokens());
        }

        @Test
        @DisplayName("Should build response with tool calls")
        void shouldBuildResponseWithToolCalls() {
            ChatCompletionsResponse response = new ChatCompletionsResponse();
            response.setId("chatcmpl-xyz789");
            response.setModel("gpt-4");

            ChatMessage message =
                    ChatMessage.assistantWithToolCalls(
                            null,
                            List.of(new ToolCall("call-1", "get_weather", "{\"city\":\"NYC\"}")));

            ChatChoice choice = new ChatChoice();
            choice.setIndex(0);
            choice.setMessage(message);
            choice.setFinishReason("tool_calls");
            response.setChoices(List.of(choice));

            assertEquals("tool_calls", response.getChoices().get(0).getFinishReason());
            assertNotNull(response.getChoices().get(0).getMessage().getToolCalls());
        }
    }
}
