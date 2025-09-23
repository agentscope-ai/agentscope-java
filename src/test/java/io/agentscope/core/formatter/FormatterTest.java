/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.FormattedMessage;
import io.agentscope.core.model.FormattedMessageList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class FormatterTest {

    @Test
    public void testOpenAIChatFormatterBasicFunctionality() throws Exception {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        // Test basic message formatting
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        TextBlock.builder()
                                                .text("You are a helpful assistant.")
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello, how are you?").build())
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        TextBlock.builder()
                                                .text("I'm doing well, thank you!")
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(3, formatted.size());

        // Check system message
        FormattedMessage systemMsg = formatted.get(0);
        assertEquals("system", systemMsg.getRole());
        assertEquals("You are a helpful assistant.", systemMsg.getContentAsString());

        // Check user message
        FormattedMessage userMsg = formatted.get(1);
        assertEquals("user", userMsg.getRole());
        assertEquals("Hello, how are you?", userMsg.getContentAsString());

        // Check assistant message
        FormattedMessage assistantMsg = formatted.get(2);
        assertEquals("assistant", assistantMsg.getRole());
        assertEquals("I'm doing well, thank you!", assistantMsg.getContentAsString());
    }

    @Test
    public void testOpenAIChatFormatterToolUse() throws Exception {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        // Create a tool use message
        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("query", "weather in New York");

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("tool_123").name("get_weather").input(toolInput).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUseBlock)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());

        FormattedMessage toolMsg = formatted.get(0);
        assertEquals("assistant", toolMsg.getRole());
        assertNull(toolMsg.getContent()); // OpenAI requires null content for tool calls

        List<Map<String, Object>> toolCalls = toolMsg.getToolCalls();
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());

        Map<String, Object> toolCall = toolCalls.get(0);
        assertEquals("tool_123", toolCall.get("id"));
        assertEquals("function", toolCall.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
        assertEquals("get_weather", function.get("name"));
        assertTrue(((String) function.get("arguments")).contains("weather in New York"));
    }

    @Test
    public void testOpenAIChatFormatterCapabilities() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("OpenAI", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertFalse(capabilities.supportsMultiAgent());
        assertTrue(capabilities.supportsVision());
        assertTrue(capabilities.supportsBlockType(TextBlock.class));
        assertTrue(capabilities.supportsBlockType(ToolUseBlock.class));
    }

    @Test
    public void testOpenAIMultiAgentFormatter() throws Exception {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();

        // Test multi-agent conversation formatting
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

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size()); // Multi-agent messages should be consolidated

        FormattedMessage conversationMsg = formatted.get(0);
        assertEquals("user", conversationMsg.getRole());

        String content = conversationMsg.getContentAsString();
        assertTrue(content.contains("<history>"));
        assertTrue(content.contains("</history>"));
        assertTrue(content.contains("User Alice: Hello everyone!"));
        assertTrue(content.contains("Assistant Bob: Hi Alice!"));
        assertTrue(content.contains("User Charlie: Good to see you all."));
    }

    @Test
    public void testOpenAIMultiAgentFormatterCapabilities() {
        OpenAIMultiAgentFormatter formatter = new OpenAIMultiAgentFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("OpenAI", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(capabilities.supportsMultiAgent()); // Key difference from regular formatter
        assertTrue(capabilities.supportsVision());
    }

    @Test
    public void testSimpleTokenCounter() {
        SimpleTokenCounter tokenCounter = SimpleTokenCounter.forOpenAI();

        assertEquals("OpenAI-Simple", tokenCounter.getName());

        // Test basic token counting
        int tokens = tokenCounter.countTokens("Hello world");
        assertTrue(tokens > 0);
        assertTrue(tokens < 10); // Should be reasonable for short text

        // Test empty text
        assertEquals(0, tokenCounter.countTokens((String) ""));
        assertEquals(0, tokenCounter.countTokens((String) null));

        // Test longer text
        String longText =
                "This is a longer piece of text that should result in more tokens being counted.";
        int longTokens = tokenCounter.countTokens(longText);
        assertTrue(longTokens > tokens);
    }

    @Test
    public void testTruncatedFormatterWithTokenLimits() throws Exception {
        SimpleTokenCounter tokenCounter = SimpleTokenCounter.forOpenAI();
        OpenAIChatFormatter formatter = new OpenAIChatFormatter(tokenCounter, 100);

        assertTrue(formatter.hasTokenCounting());
        assertEquals(100, formatter.getMaxTokens().intValue());

        // Test basic functionality (truncation logic would require more complex setup)
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Short message").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }
}
