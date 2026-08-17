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
package io.agentscope.core.chat.completions.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatCompletionsResponseBuilder}.
 *
 * <p>These tests verify the builder's behavior for constructing response objects.
 */
@DisplayName("ChatCompletionsResponseBuilder Tests")
class ChatCompletionsResponseBuilderTest {

    private ChatCompletionsResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ChatCompletionsResponseBuilder();
    }

    @Nested
    @DisplayName("Build Response Tests")
    class BuildResponseTests {

        @Test
        @DisplayName("Should build successful response correctly")
        void shouldBuildSuccessfulResponseCorrectly() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");
            request.setMessages(List.of(new ChatMessage("user", "Hello")));

            Msg reply =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hi there!").build())
                            .build();

            ChatCompletionsResponse response = builder.buildResponse(request, reply, "test-id");

            assertNotNull(response);
            assertEquals("test-id", response.getId());
            assertEquals("test-model", response.getModel());
            assertTrue(response.getCreated() > 0);
            assertEquals(1, response.getChoices().size());
            assertEquals(0, response.getChoices().get(0).getIndex());
            assertEquals("stop", response.getChoices().get(0).getFinishReason());
            assertEquals("assistant", response.getChoices().get(0).getMessage().getRole());
            assertEquals("Hi there!", response.getChoices().get(0).getMessage().getContent());
        }

        @Test
        @DisplayName("Should handle null reply message")
        void shouldHandleNullReplyMessage() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            ChatCompletionsResponse response = builder.buildResponse(request, null, "test-id");

            assertNotNull(response);
            assertEquals(1, response.getChoices().size());
            assertTrue(response.getChoices().get(0).getMessage().getContent().isEmpty());
        }

        @Test
        @DisplayName("Should handle empty reply content")
        void shouldHandleEmptyReplyContent() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            Msg reply =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("").build())
                            .build();

            ChatCompletionsResponse response = builder.buildResponse(request, reply, "test-id");

            assertNotNull(response);
            assertTrue(response.getChoices().get(0).getMessage().getContent().isEmpty());
        }
    }

    @Nested
    @DisplayName("Build Error Response Tests")
    class BuildErrorResponseTests {

        @Test
        @DisplayName("Should build error response correctly")
        void shouldBuildErrorResponseCorrectly() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            RuntimeException error = new RuntimeException("Test error message");

            ChatCompletionsResponse response =
                    builder.buildErrorResponse(request, error, "test-id");

            assertNotNull(response);
            assertEquals("test-id", response.getId());
            assertEquals("test-model", response.getModel());
            assertTrue(response.getCreated() > 0);
            assertEquals(1, response.getChoices().size());
            assertEquals(0, response.getChoices().get(0).getIndex());
            assertEquals("error", response.getChoices().get(0).getFinishReason());
            String content = response.getChoices().get(0).getMessage().getContent();
            assertTrue(content.contains("Error:"));
            assertTrue(content.contains("Test error message"));
        }

        @Test
        @DisplayName("Should handle null error")
        void shouldHandleNullError() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            ChatCompletionsResponse response = builder.buildErrorResponse(request, null, "test-id");

            assertNotNull(response);
            assertTrue(
                    response.getChoices()
                            .get(0)
                            .getMessage()
                            .getContent()
                            .contains("Unknown error occurred"));
        }
    }

    @Nested
    @DisplayName("Extract Text Content Tests")
    class ExtractTextContentTests {

        @Test
        @DisplayName("Should extract text content correctly")
        void shouldExtractTextContentCorrectly() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello world").build())
                            .build();

            String result = builder.extractTextContent(msg);

            assertEquals("Hello world", result);
        }

        @Test
        @DisplayName("Should return empty string for null message")
        void shouldReturnEmptyStringForNullMessage() {
            String result = builder.extractTextContent(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty string for null content")
        void shouldReturnEmptyStringForNullContent() {
            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).build();

            String result = builder.extractTextContent(msg);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Convert Msg To ChatMessage Tests")
    class ConvertMsgToChatMessageTests {

        @Test
        @DisplayName("Should convert null message to empty assistant message")
        void shouldConvertNullMessageToEmptyAssistantMessage() {
            ChatMessage result = builder.convertMsgToChatMessage(null);

            assertNotNull(result);
            assertEquals("assistant", result.getRole());
            assertEquals("", result.getContent());
        }

        @Test
        @DisplayName("Should convert message with tool calls")
        void shouldConvertMessageWithToolCalls() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-123")
                            .name("get_weather")
                            .input(Map.of("city", "Beijing"))
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            ChatMessage result = builder.convertMsgToChatMessage(msg);

            assertNotNull(result);
            assertEquals("assistant", result.getRole());
            assertNotNull(result.getToolCalls());
            assertEquals(1, result.getToolCalls().size());
            assertEquals("call-123", result.getToolCalls().get(0).getId());
            assertEquals("get_weather", result.getToolCalls().get(0).getFunction().getName());
        }

        @Test
        @DisplayName("Should convert message with text and tool calls")
        void shouldConvertMessageWithTextAndToolCalls() {
            TextBlock textBlock = TextBlock.builder().text("Let me check the weather").build();
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-456")
                            .name("get_weather")
                            .input(Map.of("city", "Shanghai"))
                            .build();

            Msg msg =
                    Msg.builder().role(MsgRole.ASSISTANT).content(textBlock, toolUseBlock).build();

            ChatMessage result = builder.convertMsgToChatMessage(msg);

            assertNotNull(result);
            assertEquals("assistant", result.getRole());
            assertEquals("Let me check the weather", result.getContent());
            assertNotNull(result.getToolCalls());
            assertEquals(1, result.getToolCalls().size());
        }

        @Test
        @DisplayName("Should convert user message correctly")
        void shouldConvertUserMessageCorrectly() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            ChatMessage result = builder.convertMsgToChatMessage(msg);

            assertEquals("user", result.getRole());
            assertEquals("Hello", result.getContent());
        }

        @Test
        @DisplayName("Should convert system message correctly")
        void shouldConvertSystemMessageCorrectly() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text("You are helpful").build())
                            .build();

            ChatMessage result = builder.convertMsgToChatMessage(msg);

            assertEquals("system", result.getRole());
            assertEquals("You are helpful", result.getContent());
        }

        @Test
        @DisplayName("Should handle message with default role")
        void shouldHandleMessageWithDefaultRole() {
            // Msg builder defaults to USER role when not specified
            Msg msg = Msg.builder().content(TextBlock.builder().text("Test").build()).build();

            ChatMessage result = builder.convertMsgToChatMessage(msg);

            // Msg builder defaults to USER, so converted message should be "user"
            assertEquals("user", result.getRole());
        }

        @Test
        @DisplayName("Should handle tool use with empty input")
        void shouldHandleToolUseWithEmptyInput() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-789")
                            .name("simple_tool")
                            .input(Map.of())
                            .build();

            Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            ChatMessage result = builder.convertMsgToChatMessage(msg);

            assertNotNull(result.getToolCalls());
            assertEquals(1, result.getToolCalls().size());
            assertEquals("{}", result.getToolCalls().get(0).getFunction().getArguments());
        }
    }

    @Nested
    @DisplayName("Build Response With Tool Calls Tests")
    class BuildResponseWithToolCallsTests {

        @Test
        @DisplayName("Should set finish_reason to tool_calls when tools are called")
        void shouldSetFinishReasonToToolCallsWhenToolsAreCalled() {
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel("test-model");

            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("call-123")
                            .name("get_weather")
                            .input(Map.of("city", "Beijing"))
                            .build();

            Msg reply = Msg.builder().role(MsgRole.ASSISTANT).content(toolUseBlock).build();

            ChatCompletionsResponse response = builder.buildResponse(request, reply, "test-id");

            assertNotNull(response);
            assertEquals("tool_calls", response.getChoices().get(0).getFinishReason());
            assertNotNull(response.getChoices().get(0).getMessage().getToolCalls());
        }
    }
}
