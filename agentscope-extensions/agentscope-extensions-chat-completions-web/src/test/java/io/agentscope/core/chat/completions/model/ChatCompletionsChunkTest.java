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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatCompletionsChunk}.
 *
 * <p>These tests verify the chunk's behavior for streaming responses.
 */
@DisplayName("ChatCompletionsChunk Tests")
class ChatCompletionsChunkTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create chunk with default values")
        void shouldCreateChunkWithDefaultValues() {
            ChatCompletionsChunk chunk = new ChatCompletionsChunk();

            assertEquals("chat.completion.chunk", chunk.getObject());
            assertTrue(chunk.getCreated() > 0);
            assertNull(chunk.getId());
            assertNull(chunk.getModel());
            assertNull(chunk.getChoices());
        }

        @Test
        @DisplayName("Should create chunk with id and model")
        void shouldCreateChunkWithIdAndModel() {
            ChatCompletionsChunk chunk = new ChatCompletionsChunk("test-id", "test-model");

            assertEquals("test-id", chunk.getId());
            assertEquals("test-model", chunk.getModel());
            assertEquals("chat.completion.chunk", chunk.getObject());
            assertTrue(chunk.getCreated() > 0);
        }
    }

    @Nested
    @DisplayName("Text Chunk Tests")
    class TextChunkTests {

        @Test
        @DisplayName("Should create text chunk correctly")
        void shouldCreateTextChunkCorrectly() {
            ChatCompletionsChunk chunk =
                    ChatCompletionsChunk.textChunk("req-123", "gpt-4", "Hello, world!");

            assertEquals("req-123", chunk.getId());
            assertEquals("gpt-4", chunk.getModel());
            assertEquals("chat.completion.chunk", chunk.getObject());
            assertNotNull(chunk.getChoices());
            assertEquals(1, chunk.getChoices().size());

            ChatChoice choice = chunk.getChoices().get(0);
            assertEquals(0, choice.getIndex());
            assertNotNull(choice.getDelta());
            assertEquals("assistant", choice.getDelta().getRole());
            assertEquals("Hello, world!", choice.getDelta().getContent());
            assertNull(choice.getFinishReason());
        }

        @Test
        @DisplayName("Should handle empty text content")
        void shouldHandleEmptyTextContent() {
            ChatCompletionsChunk chunk = ChatCompletionsChunk.textChunk("req-123", "gpt-4", "");

            assertNotNull(chunk.getChoices());
            assertEquals("", chunk.getChoices().get(0).getDelta().getContent());
        }
    }

    @Nested
    @DisplayName("Tool Call Chunk Tests")
    class ToolCallChunkTests {

        @Test
        @DisplayName("Should create tool call chunk correctly")
        void shouldCreateToolCallChunkCorrectly() {
            ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"Hangzhou\"}");
            ChatCompletionsChunk chunk =
                    ChatCompletionsChunk.toolCallChunk("req-123", "gpt-4", List.of(toolCall));

            assertEquals("req-123", chunk.getId());
            assertEquals("gpt-4", chunk.getModel());
            assertNotNull(chunk.getChoices());
            assertEquals(1, chunk.getChoices().size());

            ChatChoice choice = chunk.getChoices().get(0);
            assertEquals(0, choice.getIndex());
            assertNotNull(choice.getDelta());
            assertEquals("assistant", choice.getDelta().getRole());
            assertNotNull(choice.getDelta().getToolCalls());
            assertEquals(1, choice.getDelta().getToolCalls().size());

            ToolCall resultToolCall = choice.getDelta().getToolCalls().get(0);
            assertEquals("call-1", resultToolCall.getId());
            assertEquals("get_weather", resultToolCall.getFunction().getName());
            assertEquals("{\"city\":\"Hangzhou\"}", resultToolCall.getFunction().getArguments());
        }

        @Test
        @DisplayName("Should handle multiple tool calls")
        void shouldHandleMultipleToolCalls() {
            ToolCall toolCall1 = new ToolCall("call-1", "get_weather", "{\"city\":\"Beijing\"}");
            ToolCall toolCall2 = new ToolCall("call-2", "get_time", "{}");
            ChatCompletionsChunk chunk =
                    ChatCompletionsChunk.toolCallChunk(
                            "req-123", "gpt-4", List.of(toolCall1, toolCall2));

            assertEquals(2, chunk.getChoices().get(0).getDelta().getToolCalls().size());
        }
    }

    @Nested
    @DisplayName("Finish Chunk Tests")
    class FinishChunkTests {

        @Test
        @DisplayName("Should create finish chunk with stop reason")
        void shouldCreateFinishChunkWithStopReason() {
            ChatCompletionsChunk chunk =
                    ChatCompletionsChunk.finishChunk("req-123", "gpt-4", "stop");

            assertEquals("req-123", chunk.getId());
            assertEquals("gpt-4", chunk.getModel());
            assertNotNull(chunk.getChoices());
            assertEquals(1, chunk.getChoices().size());

            ChatChoice choice = chunk.getChoices().get(0);
            assertEquals(0, choice.getIndex());
            assertEquals("stop", choice.getFinishReason());
            assertNotNull(choice.getDelta());
        }

        @Test
        @DisplayName("Should create finish chunk with tool_calls reason")
        void shouldCreateFinishChunkWithToolCallsReason() {
            ChatCompletionsChunk chunk =
                    ChatCompletionsChunk.finishChunk("req-123", "gpt-4", "tool_calls");

            assertEquals("tool_calls", chunk.getChoices().get(0).getFinishReason());
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should set and get all properties")
        void shouldSetAndGetAllProperties() {
            ChatCompletionsChunk chunk = new ChatCompletionsChunk();

            chunk.setId("custom-id");
            chunk.setObject("custom-object");
            chunk.setCreated(1234567890L);
            chunk.setModel("custom-model");
            chunk.setChoices(List.of(new ChatChoice()));

            assertEquals("custom-id", chunk.getId());
            assertEquals("custom-object", chunk.getObject());
            assertEquals(1234567890L, chunk.getCreated());
            assertEquals("custom-model", chunk.getModel());
            assertEquals(1, chunk.getChoices().size());
        }
    }
}
