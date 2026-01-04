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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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
            // Tool messages require tool_call_id for proper conversion
            ChatMessage chatMsg = ChatMessage.toolResult("call-123", "get_weather", "Tool result");
            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.TOOL, result.get(0).getRole());
            // Tool result content is stored in ToolResultBlock, not directly accessible via
            // getTextContent()
            // The role being TOOL is the key verification
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

    @Nested
    @DisplayName("Tool Call Conversion Tests")
    class ToolCallConversionTests {

        @Test
        @DisplayName("Should convert assistant message with tool calls")
        void shouldConvertAssistantMessageWithToolCalls() {
            ToolCall toolCall = new ToolCall("call-123", "get_weather", "{\"city\":\"Beijing\"}");
            ChatMessage chatMsg =
                    ChatMessage.assistantWithToolCalls("Checking weather", List.of(toolCall));

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.ASSISTANT, result.get(0).getRole());

            List<ContentBlock> contentBlocks = result.get(0).getContent();
            assertNotNull(contentBlocks);
            assertTrue(contentBlocks.size() >= 1);

            // Check for ToolUseBlock
            boolean hasToolUseBlock =
                    contentBlocks.stream().anyMatch(ToolUseBlock.class::isInstance);
            assertTrue(hasToolUseBlock);

            ToolUseBlock toolUseBlock =
                    contentBlocks.stream()
                            .filter(ToolUseBlock.class::isInstance)
                            .map(ToolUseBlock.class::cast)
                            .findFirst()
                            .orElseThrow();

            assertEquals("call-123", toolUseBlock.getId());
            assertEquals("get_weather", toolUseBlock.getName());
        }

        @Test
        @DisplayName("Should convert assistant message with multiple tool calls")
        void shouldConvertAssistantMessageWithMultipleToolCalls() {
            List<ToolCall> toolCalls =
                    List.of(
                            new ToolCall("call-1", "get_weather", "{\"city\":\"Beijing\"}"),
                            new ToolCall("call-2", "get_time", "{}"));
            ChatMessage chatMsg = ChatMessage.assistantWithToolCalls(null, toolCalls);

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            List<ContentBlock> contentBlocks = result.get(0).getContent();

            long toolUseCount =
                    contentBlocks.stream().filter(ToolUseBlock.class::isInstance).count();
            assertEquals(2, toolUseCount);
        }

        @Test
        @DisplayName("Should convert tool result message to ToolResultBlock")
        void shouldConvertToolResultMessageToToolResultBlock() {
            ChatMessage chatMsg = ChatMessage.toolResult("call-123", "get_weather", "25°C sunny");

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            assertEquals(MsgRole.TOOL, result.get(0).getRole());

            List<ContentBlock> contentBlocks = result.get(0).getContent();
            assertNotNull(contentBlocks);
            assertEquals(1, contentBlocks.size());
            assertTrue(contentBlocks.get(0) instanceof ToolResultBlock);

            ToolResultBlock resultBlock = (ToolResultBlock) contentBlocks.get(0);
            assertEquals("call-123", resultBlock.getId());
        }

        @Test
        @DisplayName("Should handle tool call with invalid JSON arguments")
        void shouldHandleToolCallWithInvalidJsonArguments() {
            ToolCall toolCall = new ToolCall("call-123", "func", "invalid json");
            ChatMessage chatMsg = ChatMessage.assistantWithToolCalls(null, List.of(toolCall));

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            // Should parse but with empty map for invalid JSON
            List<ContentBlock> contentBlocks = result.get(0).getContent();
            ToolUseBlock toolUseBlock =
                    contentBlocks.stream()
                            .filter(ToolUseBlock.class::isInstance)
                            .map(ToolUseBlock.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertTrue(toolUseBlock.getInput().isEmpty());
        }

        @Test
        @DisplayName("Should handle tool call with empty arguments")
        void shouldHandleToolCallWithEmptyArguments() {
            ToolCall toolCall = new ToolCall("call-123", "func", "");
            ChatMessage chatMsg = ChatMessage.assistantWithToolCalls(null, List.of(toolCall));

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            List<ContentBlock> contentBlocks = result.get(0).getContent();
            ToolUseBlock toolUseBlock =
                    contentBlocks.stream()
                            .filter(ToolUseBlock.class::isInstance)
                            .map(ToolUseBlock.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertTrue(toolUseBlock.getInput().isEmpty());
        }

        @Test
        @DisplayName("Should handle tool call with null arguments")
        void shouldHandleToolCallWithNullArguments() {
            ToolCall toolCall = new ToolCall("call-123", "func", null);
            ChatMessage chatMsg = ChatMessage.assistantWithToolCalls(null, List.of(toolCall));

            List<Msg> result = converter.convertMessages(List.of(chatMsg));

            assertEquals(1, result.size());
            List<ContentBlock> contentBlocks = result.get(0).getContent();
            ToolUseBlock toolUseBlock =
                    contentBlocks.stream()
                            .filter(ToolUseBlock.class::isInstance)
                            .map(ToolUseBlock.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertTrue(toolUseBlock.getInput().isEmpty());
        }
    }

    @Nested
    @DisplayName("Full Conversation Flow Tests")
    class FullConversationFlowTests {

        @Test
        @DisplayName("Should convert full conversation with tool calls")
        void shouldConvertFullConversationWithToolCalls() {
            List<ChatMessage> messages =
                    List.of(
                            new ChatMessage("system", "You are a helpful assistant"),
                            new ChatMessage("user", "What's the weather in Beijing?"),
                            ChatMessage.assistantWithToolCalls(
                                    null,
                                    List.of(
                                            new ToolCall(
                                                    "call-1",
                                                    "get_weather",
                                                    "{\"city\":\"Beijing\"}"))),
                            ChatMessage.toolResult("call-1", "get_weather", "25°C sunny"),
                            new ChatMessage(
                                    "assistant", "The weather in Beijing is 25°C and sunny!"));

            List<Msg> result = converter.convertMessages(messages);

            assertEquals(5, result.size());
            assertEquals(MsgRole.SYSTEM, result.get(0).getRole());
            assertEquals(MsgRole.USER, result.get(1).getRole());
            assertEquals(MsgRole.ASSISTANT, result.get(2).getRole());
            assertEquals(MsgRole.TOOL, result.get(3).getRole());
            assertEquals(MsgRole.ASSISTANT, result.get(4).getRole());

            // Verify tool call in assistant message
            List<ContentBlock> toolCallBlocks = result.get(2).getContent();
            assertTrue(toolCallBlocks.stream().anyMatch(ToolUseBlock.class::isInstance));

            // Verify tool result
            List<ContentBlock> toolResultBlocks = result.get(3).getContent();
            assertTrue(toolResultBlocks.get(0) instanceof ToolResultBlock);
        }
    }
}
