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

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DeepSeekMultiAgentFormatter.
 *
 * <p>Tests verify DeepSeek multi-agent specific requirements:
 * <ul>
 *   <li>Inherits multi-agent conversation merging from OpenAIMultiAgentFormatter</li>
 *   <li>Applies DeepSeek-specific fixes (no name, system to user, reasoning_content)</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>Optional empty user message appending</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("DeepSeekMultiAgentFormatter Unit Tests")
class DeepSeekMultiAgentFormatterTest {

    private DeepSeekMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DeepSeekMultiAgentFormatter();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor should use default prompt and not append empty user")
        void testDefaultConstructor() {
            DeepSeekMultiAgentFormatter defaultFormatter = new DeepSeekMultiAgentFormatter();

            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("Alice")
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .name("Bob")
                                    .content(List.of(TextBlock.builder().text("Hi").build()))
                                    .build());

            List<OpenAIMessage> result = defaultFormatter.format(messages);

            // Merged into one user message, no empty user appended
            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
            assertTrue(result.get(0).getContentAsString().contains("<history>"));
        }

        @Test
        @DisplayName("Constructor with conversationHistoryPrompt")
        void testConstructorWithPrompt() {
            String customPrompt = "Custom multi-agent history:\n";
            DeepSeekMultiAgentFormatter customFormatter =
                    new DeepSeekMultiAgentFormatter(customPrompt);

            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("Alice")
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build());

            List<OpenAIMessage> result = customFormatter.format(messages);

            assertEquals(1, result.size());
            assertTrue(result.get(0).getContentAsString().contains("Custom multi-agent history"));
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=true")
        void testConstructorWithAppendEmptyUser() {
            DeepSeekMultiAgentFormatter appendFormatter = new DeepSeekMultiAgentFormatter(true);

            // Create messages that end with assistant tool call (tool sequence stays as assistant)
            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("call_1")
                                                    .name("tool1")
                                                    .input(Map.of())
                                                    .build()))
                            .build());

            List<OpenAIMessage> result = appendFormatter.format(messages);

            // Tool sequence stays as assistant, then empty user should be appended
            // Should have at least 2 messages: assistant with tool call + empty user
            assertTrue(result.size() >= 2);
            assertEquals("user", result.get(result.size() - 1).getRole());
            assertEquals("", result.get(result.size() - 1).getContentAsString());
        }

        @Test
        @DisplayName("Constructor with prompt and appendEmptyUser")
        void testConstructorWithPromptAndAppendEmptyUser() {
            String customPrompt = "History:\n";
            DeepSeekMultiAgentFormatter fullFormatter =
                    new DeepSeekMultiAgentFormatter(customPrompt, true);

            // Use messages with tool call that end with assistant (tool sequence)
            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("call_1")
                                                    .name("tool1")
                                                    .input(Map.of())
                                                    .build()))
                            .build());

            List<OpenAIMessage> result = fullFormatter.format(messages);

            // Tool sequence stays as assistant, then empty user should be appended
            assertTrue(result.size() >= 2);
            assertEquals("user", result.get(result.size() - 1).getRole());
            assertEquals("", result.get(result.size() - 1).getContentAsString());
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=false")
        void testConstructorWithoutAppendEmptyUser() {
            DeepSeekMultiAgentFormatter noAppendFormatter = new DeepSeekMultiAgentFormatter(false);

            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("call_1")
                                                    .name("tool1")
                                                    .input(Map.of())
                                                    .build()))
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    List.of(
                                            new ToolResultBlock(
                                                    "call_1",
                                                    "tool1",
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Result")
                                                                    .build()),
                                                    null)))
                            .build());

            List<OpenAIMessage> result = noAppendFormatter.format(messages);

            // Last message should be tool result
            assertEquals("tool", result.get(result.size() - 1).getRole());
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
            assertNull(request.getTools().get(0).getFunction().getStrict());
        }
    }

    @Nested
    @DisplayName("doFormat Tests - DeepSeek Fixes Applied")
    class DoFormatTests {

        @Test
        @DisplayName("Should remove name field from merged messages")
        void testRemoveNameField() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("Alice")
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            // Merged into user message, name should be removed by DeepSeek fixes
            assertEquals(1, result.size());
            assertNull(result.get(0).getName());
        }

        @Test
        @DisplayName("Should convert system to user in merged output")
        void testConvertSystemToUser() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("System prompt")
                                                            .build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            // First message (system) should be converted to user
            assertEquals("user", result.get(0).getRole());
        }

        @Test
        @DisplayName("Should merge multi-agent conversation")
        void testMergeMultiAgentConversation() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("Alice")
                                    .content(List.of(TextBlock.builder().text("Hello Bob").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .name("Bob")
                                    .content(List.of(TextBlock.builder().text("Hi Alice!").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("Alice")
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("How are you?")
                                                            .build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            // Should be merged into single user message
            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
            String content = result.get(0).getContentAsString();
            assertTrue(content.contains("Hello Bob"));
            assertTrue(content.contains("Hi Alice!"));
            assertTrue(content.contains("How are you?"));
            assertTrue(content.contains("<history>"));
        }

        @Test
        @DisplayName("Should handle tool sequences correctly")
        void testToolSequences() {
            List<Msg> messages = new ArrayList<>();
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("User")
                            .content(List.of(TextBlock.builder().text("Check weather").build()))
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("call_123")
                                                    .name("get_weather")
                                                    .input(Map.of("city", "Beijing"))
                                                    .build()))
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    List.of(
                                            new ToolResultBlock(
                                                    "call_123",
                                                    "get_weather",
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Sunny 25C")
                                                                    .build()),
                                                    null)))
                            .build());

            List<OpenAIMessage> result = formatter.format(messages);

            // Should have: merged user conversation + assistant tool + tool result
            assertTrue(result.size() >= 2);
            // Tool messages should be present
            boolean hasToolMessage = result.stream().anyMatch(m -> "tool".equals(m.getRole()));
            assertTrue(hasToolMessage);
        }

        @Test
        @DisplayName("Should handle empty message list")
        void testEmptyMessageList() {
            List<OpenAIMessage> result = formatter.format(List.of());
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Integration with OpenAIMultiAgentFormatter Features")
    class IntegrationTests {

        @Test
        @DisplayName("Should format mixed system, conversation, and tool messages")
        void testFormatMixedMessages() {
            List<Msg> messages = new ArrayList<>();
            // System message
            messages.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("You are a helpful assistant")
                                                    .build()))
                            .build());
            // Agent conversation
            messages.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("Alice")
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("What's the weather?")
                                                    .build()))
                            .build());
            // Tool sequence
            messages.add(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("call_1")
                                                    .name("get_weather")
                                                    .input(Map.of())
                                                    .build()))
                            .build());
            messages.add(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    List.of(
                                            new ToolResultBlock(
                                                    "call_1",
                                                    "get_weather",
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Sunny")
                                                                    .build()),
                                                    null)))
                            .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertNotNull(result);
            // Should have: system (converted to user) + merged conversation + tool sequence
            assertTrue(result.size() >= 3);
            // First message should be user (converted from system)
            assertEquals("user", result.get(0).getRole());
        }

        @Test
        @DisplayName("Should build request with tools")
        void testBuildRequestWithTools() {
            List<OpenAIMessage> messages =
                    List.of(OpenAIMessage.builder().role("user").content("Hello").build());

            ToolSchema tool =
                    ToolSchema.builder()
                            .name("get_weather")
                            .description("Get weather")
                            .strict(true)
                            .build();

            OpenAIRequest request =
                    OpenAIRequest.builder().model("deepseek-chat").messages(messages).build();

            formatter.applyTools(request, List.of(tool));

            assertNotNull(request.getTools());
            assertEquals(1, request.getTools().size());
            // Strict should not be applied for DeepSeek
            assertNull(request.getTools().get(0).getFunction().getStrict());
        }
    }
}
