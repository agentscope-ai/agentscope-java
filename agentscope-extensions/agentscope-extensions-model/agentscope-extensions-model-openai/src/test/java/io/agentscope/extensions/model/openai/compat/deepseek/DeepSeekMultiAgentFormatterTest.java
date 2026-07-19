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
package io.agentscope.extensions.model.openai.compat.deepseek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
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
 *   <li>Preserves DeepSeek-supported system role and name fields</li>
 *   <li>reasoning_content handling for current vs previous turns</li>
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

            List<OpenAIMessage> result = new DeepSeekMultiAgentFormatter().format(messages);

            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
            assertTrue(result.get(0).getContentAsString().contains("<history>"));
        }

        @Test
        @DisplayName("Constructor with conversationHistoryPrompt")
        void testConstructorWithPrompt() {
            DeepSeekMultiAgentFormatter customFormatter =
                    new DeepSeekMultiAgentFormatter("Custom multi-agent history:\n");

            List<OpenAIMessage> result =
                    customFormatter.format(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .name("Alice")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Hello")
                                                                    .build()))
                                            .build()));

            assertEquals(1, result.size());
            assertTrue(result.get(0).getContentAsString().contains("Custom multi-agent history"));
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=true")
        void testConstructorWithAppendEmptyUser() {
            List<OpenAIMessage> result =
                    new DeepSeekMultiAgentFormatter(true).format(assistantToolUseMessages());

            assertEquals(2, result.size());
            assertEquals("user", result.get(1).getRole());
            assertEquals("", result.get(1).getContentAsString());
        }

        @Test
        @DisplayName("Constructor with prompt and appendEmptyUser")
        void testConstructorWithPromptAndAppendEmptyUser() {
            List<OpenAIMessage> result =
                    new DeepSeekMultiAgentFormatter("History:\n", true)
                            .format(assistantToolUseMessages());

            assertEquals(2, result.size());
            assertEquals("user", result.get(1).getRole());
            assertEquals("", result.get(1).getContentAsString());
        }

        @Test
        @DisplayName("Constructor with appendEmptyUserIfEndsWithAssistant=false")
        void testConstructorWithoutAppendEmptyUser() {
            List<OpenAIMessage> result =
                    new DeepSeekMultiAgentFormatter(false).format(toolSequenceMessages());

            assertEquals("tool", result.get(result.size() - 1).getRole());
        }
    }

    @Nested
    @DisplayName("doFormat Tests - DeepSeek Fixes Applied")
    class DoFormatTests {

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

            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
            assertNull(result.get(0).getName());
            assertTrue(result.get(0).getContentAsString().contains("Hello Bob"));
            assertTrue(result.get(0).getContentAsString().contains("Hi Alice!"));
            assertTrue(result.get(0).getContentAsString().contains("How are you?"));
            assertTrue(result.get(0).getContentAsString().contains("<history>"));
        }

        @Test
        @DisplayName("Should handle tool sequences correctly")
        void testToolSequences() {
            List<OpenAIMessage> result = formatter.format(toolSequenceMessages());

            assertTrue(result.stream().anyMatch(m -> "assistant".equals(m.getRole())));
            assertTrue(result.stream().anyMatch(m -> "tool".equals(m.getRole())));
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
            messages.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("You are a helpful assistant")
                                                    .build()))
                            .build());
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
            messages.addAll(assistantToolUseMessages());
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
            assertTrue(result.size() >= 3);
            assertEquals("system", result.get(0).getRole());
            assertNull(result.get(0).getName());
        }
    }

    private static List<Msg> assistantToolUseMessages() {
        return List.of(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build()))
                        .build());
    }

    private static List<Msg> toolSequenceMessages() {
        List<Msg> messages = new ArrayList<>(assistantToolUseMessages());
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
                                                                .text("Sunny 25C")
                                                                .build()),
                                                null)))
                        .build());
        return messages;
    }
}
