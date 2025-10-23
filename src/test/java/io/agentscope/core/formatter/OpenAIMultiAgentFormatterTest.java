/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.FormattedMessageList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class OpenAIMultiAgentFormatterTest {

    @Test
    public void testMultiAgentConversationCollapse() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello everyone!").build())
                                .build(),
                        Msg.builder()
                                .name("Bob")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Hi Alice!").build())
                                .build(),
                        Msg.builder()
                                .name("Charlie")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Good to see you all.").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testMultiAgentWithToolUse() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("query", "test");

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Run the query").build())
                                .build(),
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("run_query")
                                                .input(toolInput)
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("tool")
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("Query result: success")
                                                                .build())
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testMultiAgentWithThinkingBlock() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().text("Let me think about this...").build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("What do you think?").build())
                                .build(),
                        Msg.builder()
                                .name("Bob")
                                .role(MsgRole.ASSISTANT)
                                .content(thinkingBlock)
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testMultiAgentWithSystemMessage() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text("System prompt").build())
                                .build(),
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testComplexMultiAgentScenario() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("action", "search");

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("I need information").build())
                                .build(),
                        Msg.builder()
                                .name("Bob")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("I'll help you search").build())
                                .build(),
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_search")
                                                .name("search_tool")
                                                .input(toolInput)
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("tool")
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_search")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("Found results")
                                                                .build())
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("Charlie")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Thanks!").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testCapabilities() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("OpenAI", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(capabilities.supportsMultiAgent());
        assertTrue(capabilities.supportsVision());
    }

    @Test
    public void testWithTokenCounter() {
        SimpleTokenCounter tokenCounter = SimpleTokenCounter.forOpenAI();
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter(tokenCounter, 1000);

        assertTrue(formatter.hasTokenCounting());
        assertEquals(1000, formatter.getMaxTokens().intValue());
    }

    @Test
    public void testEmptyMessages() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages = List.of();

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(0, formatted.size());
    }

    @Test
    public void testSingleUserMessage() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testAlternatingUserAssistant() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Question 1").build())
                                .build(),
                        Msg.builder()
                                .name("Bob")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Answer 1").build())
                                .build(),
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Question 2").build())
                                .build(),
                        Msg.builder()
                                .name("Bob")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Answer 2").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testMultipleUsersInSequence() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build(),
                        Msg.builder()
                                .name("Bob")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hi").build())
                                .build(),
                        Msg.builder()
                                .name("Charlie")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hey").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testMultipleAssistantsInSequence() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Question").build())
                                .build(),
                        Msg.builder()
                                .name("Bob")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Answer 1").build())
                                .build(),
                        Msg.builder()
                                .name("Charlie")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Answer 2").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testNullContent() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content((List<io.agentscope.core.message.ContentBlock>) null)
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testNullName() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name(null)
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testToolCallsInMultiAgent() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        Map<String, Object> toolInput1 = new HashMap<>();
        toolInput1.put("param1", "value1");

        Map<String, Object> toolInput2 = new HashMap<>();
        toolInput2.put("param2", "value2");

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Do two things").build())
                                .build(),
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("tool1")
                                                .input(toolInput1)
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("tool")
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_1")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("Result 1")
                                                                .build())
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_2")
                                                .name("tool2")
                                                .input(toolInput2)
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("tool")
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_2")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("Result 2")
                                                                .build())
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }
}
