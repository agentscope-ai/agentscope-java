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
package io.agentscope.core.chat.completions.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatMessageConverter}.
 *
 * <p>These tests verify the converter's behavior for converting ChatMessage DTOs to framework Msg
 * objects.
 */
@DisplayName("ChatMessageConverter Tests")
class ChatMessageConverterTest {

    private ChatMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ChatMessageConverter();
    }

    @Nested
    @DisplayName("Convert Messages Tests")
    class ConvertMessagesTests {

        @Test
        @DisplayName("Should convert user message correctly")
        void shouldConvertUserMessageCorrectly() {
            ChatMessage chatMsg = new ChatMessage("user", "Hello");
            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.USER, result.get(0).getRole());
            assertEquals("Hello", result.get(0).getTextContent());
        }

        @Test
        @DisplayName("Should convert assistant message correctly")
        void shouldConvertAssistantMessageCorrectly() {
            ChatMessage chatMsg = new ChatMessage("assistant", "Hi there!");
            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.ASSISTANT, result.get(0).getRole());
            assertEquals("Hi there!", result.get(0).getTextContent());
        }

        @Test
        @DisplayName("Should convert system message correctly")
        void shouldConvertSystemMessageCorrectly() {
            ChatMessage chatMsg = new ChatMessage("system", "You are a helpful assistant");
            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.SYSTEM, result.get(0).getRole());
            assertEquals("You are a helpful assistant", result.get(0).getTextContent());
        }

        @Test
        @DisplayName("Should convert tool message correctly")
        void shouldConvertToolMessageCorrectly() {
            ChatMessage chatMsg = new ChatMessage("tool", "Tool result");
            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.TOOL, result.get(0).getRole());
            assertEquals("Tool result", result.get(0).getTextContent());
        }

        @Test
        @DisplayName("Should convert multiple messages correctly")
        void shouldConvertMultipleMessagesCorrectly() {
            List<ChatMessage> chatMessages =
                    List.of(
                            new ChatMessage("system", "You are helpful"),
                            new ChatMessage("user", "Hello"),
                            new ChatMessage("assistant", "Hi!"));

            List<Msg> result = converter.convertMessages(chatMessages);

            assertEquals(3, result.size());
            assertEquals(MsgRole.SYSTEM, result.get(0).getRole());
            assertEquals(MsgRole.USER, result.get(1).getRole());
            assertEquals(MsgRole.ASSISTANT, result.get(2).getRole());
        }

        @Test
        @DisplayName("Should handle case-insensitive roles")
        void shouldHandleCaseInsensitiveRoles() {
            List<ChatMessage> chatMessages =
                    List.of(
                            new ChatMessage("USER", "Hello"),
                            new ChatMessage("Assistant", "Hi"),
                            new ChatMessage("SYSTEM", "Help"));

            List<Msg> result = converter.convertMessages(chatMessages);

            assertEquals(3, result.size());
            assertEquals(MsgRole.USER, result.get(0).getRole());
            assertEquals(MsgRole.ASSISTANT, result.get(1).getRole());
            assertEquals(MsgRole.SYSTEM, result.get(2).getRole());
        }

        @Test
        @DisplayName("Should return empty list for null input")
        void shouldReturnEmptyListForNullInput() {
            List<Msg> result = converter.convertMessages(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            List<Msg> result = converter.convertMessages(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should filter out null messages")
        void shouldFilterOutNullMessages() {
            List<ChatMessage> chatMessages = new ArrayList<>();
            chatMessages.add(new ChatMessage("user", "Hello"));
            chatMessages.add(null);
            chatMessages.add(new ChatMessage("assistant", "Hi"));

            List<Msg> result = converter.convertMessages(chatMessages);

            assertEquals(2, result.size());
            assertEquals(MsgRole.USER, result.get(0).getRole());
            assertEquals(MsgRole.ASSISTANT, result.get(1).getRole());
        }

        @Test
        @DisplayName("Should default to USER role when role is null")
        void shouldDefaultToUserRoleWhenRoleIsNull() {
            ChatMessage chatMsg = new ChatMessage();
            chatMsg.setContent("Hello");
            // role is null

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.USER, result.get(0).getRole());
            assertEquals("Hello", result.get(0).getTextContent());
        }

        @Test
        @DisplayName("Should default to USER role when role is blank")
        void shouldDefaultToUserRoleWhenRoleIsBlank() {
            ChatMessage chatMsg = new ChatMessage("  ", "Hello");

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.USER, result.get(0).getRole());
        }

        @Test
        @DisplayName("Should handle null content")
        void shouldHandleNullContent() {
            ChatMessage chatMsg = new ChatMessage("user", null);

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.USER, result.get(0).getRole());
            assertTrue(result.get(0).getTextContent().isEmpty());
        }

        @Test
        @DisplayName("Should throw exception for unknown role")
        void shouldThrowExceptionForUnknownRole() {
            ChatMessage chatMsg = new ChatMessage("unknown", "Hello");

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> converter.convertMessages(List.of(chatMsg)));
            assertTrue(exception.getMessage().contains("Unknown message role"));
        }
    }
}
