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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DeepSeekFormatter.
 *
 * <p>Tests verify DeepSeek-specific requirements:
 * <ul>
 *   <li>No name field in messages</li>
 *   <li>System messages converted to user</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>reasoning_content handling for current vs previous turns</li>
 *   <li>Optional empty user message appending</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("DeepSeekFormatter Unit Tests")
class DeepSeekFormatterTest {

    private DeepSeekFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DeepSeekFormatter();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor should not append empty user message")
        void testDefaultConstructor() {
            DeepSeekFormatter defaultFormatter = new DeepSeekFormatter();
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = defaultFormatter.format(messages);

            // Should not append empty user message by default
            assertEquals(2, result.size());
            assertEquals("assistant", result.get(result.size() - 1).getRole());
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=true")
        void testConstructorWithAppendEmptyUser() {
            DeepSeekFormatter appendFormatter = new DeepSeekFormatter(true);
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = appendFormatter.format(messages);

            // Should append empty user message
            assertEquals(3, result.size());
            assertEquals("user", result.get(result.size() - 1).getRole());
            assertEquals("", result.get(result.size() - 1).getContentAsString());
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=false")
        void testConstructorWithoutAppendEmptyUser() {
            DeepSeekFormatter noAppendFormatter = new DeepSeekFormatter(false);
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = noAppendFormatter.format(messages);

            assertEquals(2, result.size());
            assertEquals("assistant", result.get(result.size() - 1).getRole());
        }
    }

    @Nested
    @DisplayName("supportsStrict Tests")
    class SupportsStrictTests {

        @Test
        @DisplayName("supportsStrict should return false")
        void testSupportsStrictReturnsFalse() {
            assertFalse(formatter.supportsStrict());
        }

        @Test
        @DisplayName("applyTools should not include strict parameter")
        void testApplyToolsWithoutStrict() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("deepseek-chat").messages(List.of()).build();

            ToolSchema tool =
                    ToolSchema.builder()
                            .name("test_tool")
                            .description("Test tool")
                            .strict(true)
                            .build();

            formatter.applyTools(request, List.of(tool));

            assertNotNull(request.getTools());
            assertEquals(1, request.getTools().size());
            // Strict should not be set because DeepSeek doesn't support it
            assertNull(request.getTools().get(0).getFunction().getStrict());
        }
    }

    @Nested
    @DisplayName("applyDeepSeekFixes Tests")
    class ApplyDeepSeekFixesTests {

        @Test
        @DisplayName("Should remove name field from messages")
        void testRemoveNameField() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("user")
                                    .name("Alice")
                                    .content("Hello")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(1, result.size());
            assertNull(result.get(0).getName());
            assertEquals("Hello", result.get(0).getContentAsString());
        }

        @Test
        @DisplayName("Should convert system message to user message")
        void testConvertSystemToUser() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("system")
                                    .content("You are helpful")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
            assertEquals("You are helpful", result.get(0).getContentAsString());
        }

        @Test
        @DisplayName("Should keep reasoning_content for current turn")
        void testKeepReasoningContentForCurrentTurn() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("Question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Answer")
                                    .reasoningContent("My thinking")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(2, result.size());
            // Current turn (after last user) should keep reasoning_content
            assertEquals("My thinking", result.get(1).getReasoningContent());
        }

        @Test
        @DisplayName("Should remove reasoning_content for previous turns")
        void testRemoveReasoningContentForPreviousTurns() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("user").content("First question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("First answer")
                                    .reasoningContent("First thinking")
                                    .build(),
                            OpenAIMessage.builder().role("user").content("Second question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Second answer")
                                    .reasoningContent("Second thinking")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(4, result.size());
            // Previous turn should have reasoning_content removed
            assertNull(result.get(1).getReasoningContent());
            // Current turn should keep reasoning_content
            assertEquals("Second thinking", result.get(3).getReasoningContent());
        }

        @Test
        @DisplayName("Should preserve tool calls in messages")
        void testPreserveToolCalls() {
            OpenAIToolCall toolCall =
                    OpenAIToolCall.builder()
                            .id("call_123")
                            .type("function")
                            .function(
                                    io.agentscope.core.formatter.openai.dto.OpenAIFunction.of(
                                            "test_tool", "{}"))
                            .build();

            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .name("Agent")
                                    .toolCalls(List.of(toolCall))
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(1, result.size());
            assertNull(result.get(0).getName());
            assertNotNull(result.get(0).getToolCalls());
            assertEquals(1, result.get(0).getToolCalls().size());
            assertEquals("call_123", result.get(0).getToolCalls().get(0).getId());
        }

        @Test
        @DisplayName("Should preserve tool call ID in tool messages")
        void testPreserveToolCallId() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("tool")
                                    .toolCallId("call_123")
                                    .content("Tool result")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(1, result.size());
            assertEquals("call_123", result.get(0).getToolCallId());
        }

        @Test
        @DisplayName("Should handle content as list")
        void testHandleContentAsList() {
            List<OpenAIContentPart> contentParts =
                    List.of(OpenAIContentPart.text("Hello"), OpenAIContentPart.text("World"));

            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("system")
                                    .name("System")
                                    .content(contentParts)
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
            assertNull(result.get(0).getName());
            assertTrue(result.get(0).getContent() instanceof List);
        }

        @Test
        @DisplayName("Should return message unchanged if no fixes needed")
        void testReturnUnchangedIfNoFixesNeeded() {
            OpenAIMessage original = OpenAIMessage.builder().role("user").content("Hello").build();
            List<OpenAIMessage> messages = List.of(original);

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(1, result.size());
            // Same object reference if no changes
            assertEquals(original, result.get(0));
        }

        @Test
        @DisplayName("Should handle no user messages - treat all as current turn")
        void testNoUserMessages() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Answer")
                                    .reasoningContent("Thinking")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(messages);

            assertEquals(1, result.size());
            // No user message, so treat all as current turn - keep reasoning
            assertEquals("Thinking", result.get(0).getReasoningContent());
        }
    }

    @Nested
    @DisplayName("appendEmptyUserIfNeeded Tests")
    class AppendEmptyUserIfNeededTests {

        @Test
        @DisplayName("Should append empty user if ends with assistant")
        void testAppendWhenEndsWithAssistant() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());
            messages.add(OpenAIMessage.builder().role("assistant").content("Hi").build());

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertEquals(3, result.size());
            assertEquals("user", result.get(2).getRole());
            assertEquals("", result.get(2).getContentAsString());
        }

        @Test
        @DisplayName("Should not append if ends with user")
        void testNoAppendWhenEndsWithUser() {
            List<OpenAIMessage> messages =
                    List.of(OpenAIMessage.builder().role("user").content("Hello").build());

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Should not append if list is empty")
        void testNoAppendWhenEmpty() {
            List<OpenAIMessage> messages = List.of();

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should not append if ends with tool")
        void testNoAppendWhenEndsWithTool() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("tool")
                                    .toolCallId("call_123")
                                    .content("Result")
                                    .build());

            List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(messages);

            assertEquals(1, result.size());
            assertEquals("tool", result.get(0).getRole());
        }
    }

    @Nested
    @DisplayName("doFormat Integration Tests")
    class DoFormatTests {

        @Test
        @DisplayName("Should apply all DeepSeek fixes during format")
        void testFormatAppliesFixes() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("You are helpful")
                                                            .build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("Alice")
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            // System converted to user
            assertEquals("user", result.get(0).getRole());
            // Name removed
            assertNull(result.get(1).getName());
        }

        @Test
        @DisplayName("Should format empty message list")
        void testFormatEmptyList() {
            List<OpenAIMessage> result = formatter.format(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should handle multiple system messages")
        void testMultipleSystemMessages() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(List.of(TextBlock.builder().text("System 1").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(List.of(TextBlock.builder().text("System 2").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            // Both should be converted to user
            assertEquals("user", result.get(0).getRole());
            assertEquals("user", result.get(1).getRole());
        }
    }
}
