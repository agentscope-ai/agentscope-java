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
 * Unit tests for {@link ChatMessage}.
 */
@DisplayName("ChatMessage Tests")
class ChatMessageTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create message with default constructor")
        void shouldCreateMessageWithDefaultConstructor() {
            ChatMessage message = new ChatMessage();

            assertNull(message.getRole());
            assertNull(message.getContent());
            assertNull(message.getToolCalls());
            assertNull(message.getToolCallId());
            assertNull(message.getName());
        }

        @Test
        @DisplayName("Should create message with role and content")
        void shouldCreateMessageWithRoleAndContent() {
            ChatMessage message = new ChatMessage("user", "Hello world");

            assertEquals("user", message.getRole());
            assertEquals("Hello world", message.getContent());
            assertNull(message.getToolCalls());
            assertNull(message.getToolCallId());
        }
    }

    @Nested
    @DisplayName("Static Factory Methods")
    class StaticFactoryMethods {

        @Test
        @DisplayName("Should create assistant message with tool calls")
        void shouldCreateAssistantMessageWithToolCalls() {
            ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"Beijing\"}");
            ChatMessage message =
                    ChatMessage.assistantWithToolCalls("Checking weather", List.of(toolCall));

            assertEquals("assistant", message.getRole());
            assertEquals("Checking weather", message.getContent());
            assertNotNull(message.getToolCalls());
            assertEquals(1, message.getToolCalls().size());
            assertEquals("call-1", message.getToolCalls().get(0).getId());
        }

        @Test
        @DisplayName("Should create assistant message with null content and tool calls")
        void shouldCreateAssistantMessageWithNullContentAndToolCalls() {
            ToolCall toolCall = new ToolCall("call-1", "calculate", "{}");
            ChatMessage message = ChatMessage.assistantWithToolCalls(null, List.of(toolCall));

            assertEquals("assistant", message.getRole());
            assertNull(message.getContent());
            assertNotNull(message.getToolCalls());
        }

        @Test
        @DisplayName("Should create tool result message")
        void shouldCreateToolResultMessage() {
            ChatMessage message = ChatMessage.toolResult("call-123", "get_weather", "25°C sunny");

            assertEquals("tool", message.getRole());
            assertEquals("25°C sunny", message.getContent());
            assertEquals("call-123", message.getToolCallId());
            assertEquals("get_weather", message.getName());
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should set and get all properties")
        void shouldSetAndGetAllProperties() {
            ChatMessage message = new ChatMessage();

            message.setRole("assistant");
            message.setContent("Hello");
            message.setToolCalls(List.of(new ToolCall("id-1", "func", "{}")));
            message.setToolCallId("call-id");
            message.setName("tool-name");

            assertEquals("assistant", message.getRole());
            assertEquals("Hello", message.getContent());
            assertNotNull(message.getToolCalls());
            assertEquals(1, message.getToolCalls().size());
            assertEquals("call-id", message.getToolCallId());
            assertEquals("tool-name", message.getName());
        }
    }

    @Nested
    @DisplayName("Role Variations")
    class RoleVariations {

        @Test
        @DisplayName("Should handle user role")
        void shouldHandleUserRole() {
            ChatMessage message = new ChatMessage("user", "What is the weather?");
            assertEquals("user", message.getRole());
        }

        @Test
        @DisplayName("Should handle system role")
        void shouldHandleSystemRole() {
            ChatMessage message = new ChatMessage("system", "You are a helpful assistant.");
            assertEquals("system", message.getRole());
        }

        @Test
        @DisplayName("Should handle assistant role")
        void shouldHandleAssistantRole() {
            ChatMessage message = new ChatMessage("assistant", "I can help with that.");
            assertEquals("assistant", message.getRole());
        }

        @Test
        @DisplayName("Should handle tool role")
        void shouldHandleToolRole() {
            ChatMessage message = ChatMessage.toolResult("call-1", "my_tool", "Result");
            assertEquals("tool", message.getRole());
        }
    }
}
