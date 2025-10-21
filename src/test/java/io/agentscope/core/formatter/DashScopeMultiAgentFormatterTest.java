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
import io.agentscope.core.model.FormattedMessage;
import io.agentscope.core.model.FormattedMessageList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DashScopeMultiAgentFormatterTest {

    @Test
    public void testMultiAgentConversationCollapse() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

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
        assertEquals(1, formatted.size());

        FormattedMessage msg = formatted.get(0);
        assertEquals("user", msg.getRole());

        String content = msg.getContentAsString();
        assertTrue(content.contains("<history>"));
        assertTrue(content.contains("</history>"));
        assertTrue(content.contains("User Alice: Hello everyone!"));
        assertTrue(content.contains("Assistant Bob: Hi Alice!"));
        assertTrue(content.contains("User Charlie: Good to see you all."));
    }

    @Test
    public void testMultiAgentWithToolUse() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

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
        // Should have: 1 conversation message + 1 tool call + 1 tool result
        assertEquals(3, formatted.size());
    }

    @Test
    public void testMultiAgentWithThinkingBlock() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

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
        assertEquals(1, formatted.size());

        String content = formatted.get(0).getContentAsString();
        assertTrue(content.contains("Let me think about this..."));
    }

    @Test
    public void testMultiAgentWithToolResultInConversation() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_456")
                        .name("calculator")
                        .output(TextBlock.builder().text("Result: 42").build())
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Calculate this").build())
                                .build(),
                        Msg.builder().name("Tool").role(MsgRole.USER).content(toolResult).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        String content = formatted.get(0).getContentAsString();
        assertTrue(content.contains("calculator"));
        assertTrue(content.contains("Result: 42"));
    }

    @Test
    public void testMultiAgentWithSystemMessage() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

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
        String content = formatted.get(0).getContentAsString();
        assertTrue(content.contains("System system: System prompt"));
    }

    @Test
    public void testMultiAgentWithUnknownRole() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name(null)
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        String content = formatted.get(0).getContentAsString();
        assertTrue(content.contains("Unknown"));
    }

    @Test
    public void testComplexMultiAgentScenario() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

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
        // Conversation messages collapse into 1, tool call and result are separate
        assertTrue(formatted.size() >= 3);
    }

    @Test
    public void testCapabilities() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("DashScope", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(capabilities.supportsMultiAgent());
        assertTrue(capabilities.supportsVision());
    }

    @Test
    public void testWithTokenCounter() {
        SimpleTokenCounter tokenCounter = SimpleTokenCounter.forOpenAI();
        DashScopeMultiAgentFormatter formatter =
                new DashScopeMultiAgentFormatter(tokenCounter, 1000);

        assertTrue(formatter.hasTokenCounting());
        assertEquals(1000, formatter.getMaxTokens().intValue());
    }

    @Test
    public void testEmptyMessages() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        List<Msg> messages = List.of();

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(0, formatted.size());
    }

    @Test
    public void testNullToolInput() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_null")
                                                .name("no_param_tool")
                                                .input(null)
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testEmptyToolInput() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_empty")
                                                .name("no_param_tool")
                                                .input(new HashMap<>())
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testComplexToolInputWithDifferentTypes() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("stringParam", "value");
        toolInput.put("intParam", 123);
        toolInput.put("boolParam", true);
        toolInput.put("nullParam", null);

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_complex")
                                                .name("complex_tool")
                                                .input(toolInput)
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolResultFallbackWithTextBlock() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("tool_result")
                                .role(MsgRole.TOOL)
                                .content(TextBlock.builder().text("Fallback result").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolResultBlockWithNonTextOutput() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_thinking")
                        .name("thinker")
                        .output(ThinkingBlock.builder().text("Processing thoughts...").build())
                        .build();

        List<Msg> messages =
                List.of(Msg.builder().name("tool").role(MsgRole.TOOL).content(toolResult).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testFormatAgentMessageReturnsEmpty() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Alice")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Test").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
    }

    @Test
    public void testJsonConversionWithAllTypes() {
        DashScopeMultiAgentFormatter formatter = new DashScopeMultiAgentFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("string", "text");
        toolInput.put("integer", 42);
        toolInput.put("double", 3.14);
        toolInput.put("boolean", false);
        toolInput.put("nullValue", null);

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("Assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_types")
                                                .name("type_test")
                                                .input(toolInput)
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }
}
