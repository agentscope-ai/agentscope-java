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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatCompletionsRequest}.
 */
@DisplayName("ChatCompletionsRequest Tests")
class ChatCompletionsRequestTest {

    @Nested
    @DisplayName("Default State Tests")
    class DefaultStateTests {

        @Test
        @DisplayName("Should have null default values")
        void shouldHaveNullDefaultValues() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();

            assertNull(request.getModel());
            assertNull(request.getMessages());
            assertNull(request.getStream());
        }
    }

    @Nested
    @DisplayName("Model Tests")
    class ModelTests {

        @Test
        @DisplayName("Should set and get model")
        void shouldSetAndGetModel() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("gpt-4");

            assertEquals("gpt-4", request.getModel());
        }

        @Test
        @DisplayName("Should handle various model names")
        void shouldHandleVariousModelNames() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();

            request.setModel("qwen-plus");
            assertEquals("qwen-plus", request.getModel());

            request.setModel("gpt-3.5-turbo");
            assertEquals("gpt-3.5-turbo", request.getModel());

            request.setModel("claude-3-opus");
            assertEquals("claude-3-opus", request.getModel());
        }
    }

    @Nested
    @DisplayName("Messages Tests")
    class MessagesTests {

        @Test
        @DisplayName("Should set and get messages")
        void shouldSetAndGetMessages() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));

            request.setMessages(messages);

            assertNotNull(request.getMessages());
            assertEquals(1, request.getMessages().size());
            assertEquals("user", request.getMessages().get(0).getRole());
        }

        @Test
        @DisplayName("Should handle empty messages list")
        void shouldHandleEmptyMessagesList() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setMessages(new ArrayList<>());

            assertNotNull(request.getMessages());
            assertTrue(request.getMessages().isEmpty());
        }

        @Test
        @DisplayName("Should handle multi-turn conversation")
        void shouldHandleMultiTurnConversation() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            List<ChatMessage> messages =
                    List.of(
                            new ChatMessage("system", "You are a helpful assistant."),
                            new ChatMessage("user", "Hello"),
                            new ChatMessage("assistant", "Hi! How can I help?"),
                            new ChatMessage("user", "What's the weather?"));

            request.setMessages(messages);

            assertEquals(4, request.getMessages().size());
            assertEquals("system", request.getMessages().get(0).getRole());
            assertEquals("user", request.getMessages().get(1).getRole());
            assertEquals("assistant", request.getMessages().get(2).getRole());
            assertEquals("user", request.getMessages().get(3).getRole());
        }

        @Test
        @DisplayName("Should handle conversation with tool messages")
        void shouldHandleConversationWithToolMessages() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            List<ChatMessage> messages =
                    List.of(
                            new ChatMessage("user", "What's the weather?"),
                            ChatMessage.assistantWithToolCalls(
                                    null, List.of(new ToolCall("call-1", "get_weather", "{}"))),
                            ChatMessage.toolResult("call-1", "get_weather", "25°C"));

            request.setMessages(messages);

            assertEquals(3, request.getMessages().size());
            assertEquals("tool", request.getMessages().get(2).getRole());
        }
    }

    @Nested
    @DisplayName("Stream Tests")
    class StreamTests {

        @Test
        @DisplayName("Should set stream to true")
        void shouldSetStreamToTrue() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setStream(true);

            assertTrue(request.getStream());
        }

        @Test
        @DisplayName("Should set stream to false")
        void shouldSetStreamToFalse() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setStream(false);

            assertFalse(request.getStream());
        }

        @Test
        @DisplayName("Should handle null stream (default)")
        void shouldHandleNullStream() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();

            assertNull(request.getStream());
        }
    }

    @Nested
    @DisplayName("Complete Request Tests")
    class CompleteRequestTests {

        @Test
        @DisplayName("Should build complete request")
        void shouldBuildCompleteRequest() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("gpt-4");
            request.setMessages(
                    List.of(
                            new ChatMessage("system", "You are helpful."),
                            new ChatMessage("user", "Hello")));
            request.setStream(false);

            assertEquals("gpt-4", request.getModel());
            assertEquals(2, request.getMessages().size());
            assertFalse(request.getStream());
        }
    }
}
