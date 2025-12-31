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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TokenCounterUtil.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Empty and null message handling</li>
 *   <li>Text message token estimation</li>
 *   <li>Thinking message token estimation</li>
 *   <li>Tool use message token estimation</li>
 *   <li>Tool result message token estimation</li>
 *   <li>Mixed content messages</li>
 *   <li>Multiple messages token calculation</li>
 *   <li>Edge cases (empty strings, null values)</li>
 * </ul>
 */
@DisplayName("TokenCounterUtil Tests")
class TokenCounterUtilTest {

    @Test
    @DisplayName("Should return 0 for null message list")
    void testCalculateTokenWithNullList() {
        int tokens = TokenCounterUtil.calculateToken(null);
        assertEquals(0, tokens, "Null message list should return 0 tokens");
    }

    @Test
    @DisplayName("Should return 0 for empty message list")
    void testCalculateTokenWithEmptyList() {
        int tokens = TokenCounterUtil.calculateToken(new ArrayList<>());
        assertEquals(0, tokens, "Empty message list should return 0 tokens");
    }

    @Test
    @DisplayName("Should calculate tokens for simple text message")
    void testCalculateTokenForTextMessage() {
        // Create a simple text message with 10 characters
        // Expected: MESSAGE_OVERHEAD (5) + role tokens + name tokens + text tokens (10/2.5 = 4)
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello World").build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Text message should have positive token count");
        // Minimum should be: 5 (overhead) + role + name + text tokens
        assertTrue(tokens >= 10, "Token count should include overhead and content");
    }

    @Test
    @DisplayName("Should calculate tokens for message with empty text")
    void testCalculateTokenForEmptyTextMessage() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("").build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        // Should still have overhead and role/name tokens
        assertTrue(tokens > 0, "Empty text message should still have overhead tokens");
    }

    @Test
    @DisplayName("Should calculate tokens for thinking message")
    void testCalculateTokenForThinkingMessage() {
        String thinking = "I need to think about this problem carefully.";
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking(thinking).build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Thinking message should have positive token count");
        // Should include thinking content tokens
        assertTrue(tokens >= thinking.length() / 3, "Token count should include thinking content");
    }

    @Test
    @DisplayName("Should calculate tokens for tool use message")
    void testCalculateTokenForToolUseMessage() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test query");
        input.put("limit", 10);

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .name("search_tool")
                                        .id("tool-call-123")
                                        .input(input)
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Tool use message should have positive token count");
        // Should include: overhead + tool call overhead + tool name + tool id + input params
        assertTrue(tokens >= 20, "Token count should include tool call structure");
    }

    @Test
    @DisplayName("Should calculate tokens for tool use message with empty input")
    void testCalculateTokenForToolUseMessageWithEmptyInput() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .name("simple_tool")
                                        .id("tool-call-456")
                                        .input(new HashMap<>())
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Tool use message with empty input should have tokens");
    }

    @Test
    @DisplayName("Should calculate tokens for tool result message")
    void testCalculateTokenForToolResultMessage() {
        String result = "The search returned 5 results.";
        Msg msg =
                Msg.builder()
                        .name("tool")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tool-call-123")
                                        .name("search_tool")
                                        .output(TextBlock.builder().text(result).build())
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Tool result message should have positive token count");
        // Should include: overhead + tool result overhead + tool name + tool id + output
        assertTrue(tokens >= 15, "Token count should include tool result structure");
    }

    @Test
    @DisplayName("Should calculate tokens for tool result message with empty output")
    void testCalculateTokenForToolResultMessageWithEmptyOutput() {
        Msg msg =
                Msg.builder()
                        .name("tool")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tool-call-789")
                                        .name("empty_tool")
                                        .output(new ArrayList<>())
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Tool result message with empty output should have tokens");
    }

    @Test
    @DisplayName("Should calculate tokens for message with multiple content blocks")
    void testCalculateTokenForMessageWithMultipleBlocks() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("I'll help you with that.").build(),
                                ToolUseBlock.builder()
                                        .name("calculator")
                                        .id("calc-1")
                                        .input(Map.of("operation", "add", "a", 5, "b", 3))
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Message with multiple blocks should have positive token count");
        // Should include tokens for both text and tool use blocks
        assertTrue(tokens >= 25, "Token count should include all content blocks");
    }

    @Test
    @DisplayName("Should calculate tokens for multiple messages")
    void testCalculateTokenForMultipleMessages() {
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build());
        messages.add(
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hi there!").build())
                        .build());

        int tokens = TokenCounterUtil.calculateToken(messages);
        assertTrue(tokens > 0, "Multiple messages should have positive token count");
        // Should be sum of tokens for both messages
        assertTrue(tokens >= 20, "Token count should include all messages");
    }

    @Test
    @DisplayName("Should handle message with null role")
    void testCalculateTokenForMessageWithNullRole() {
        Msg msg =
                Msg.builder()
                        .name("test")
                        .content(TextBlock.builder().text("Test message").build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Message with null role should still calculate tokens");
    }

    @Test
    @DisplayName("Should handle message with null name")
    void testCalculateTokenForMessageWithNullName() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test message").build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Message with null name should still calculate tokens");
    }

    @Test
    @DisplayName("Should handle message with null content")
    void testCalculateTokenForMessageWithNullContent() {
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        // Should still have overhead and role/name tokens
        assertTrue(tokens > 0, "Message with null content should still have overhead tokens");
    }

    @Test
    @DisplayName("Should calculate tokens for long text message")
    void testCalculateTokenForLongTextMessage() {
        // Create a long text (100 characters)
        String longText = "A".repeat(100);
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(longText).build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        // 100 characters / 2.5 = 40 tokens for text, plus overhead
        assertTrue(tokens >= 40, "Long text message should have appropriate token count");
    }

    @Test
    @DisplayName("Should calculate tokens for tool use with complex parameters")
    void testCalculateTokenForToolUseWithComplexParameters() {
        Map<String, Object> complexInput = new HashMap<>();
        complexInput.put("query", "complex search query with multiple words");
        complexInput.put("filters", Map.of("category", "tech", "date", "2024"));
        complexInput.put("limit", 50);
        complexInput.put("sort", "relevance");

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .name("advanced_search")
                                        .id("search-12345")
                                        .input(complexInput)
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Tool use with complex parameters should have tokens");
        // Should include all parameter tokens
        assertTrue(tokens >= 30, "Token count should include complex parameters");
    }

    @Test
    @DisplayName("Should calculate tokens for tool result with multiple output blocks")
    void testCalculateTokenForToolResultWithMultipleOutputs() {
        Msg msg =
                Msg.builder()
                        .name("tool")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tool-123")
                                        .name("multi_output_tool")
                                        .output(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("First result")
                                                                .build(),
                                                        TextBlock.builder()
                                                                .text("Second result")
                                                                .build()))
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Tool result with multiple outputs should have tokens");
        // Should include tokens for all output blocks
        assertTrue(tokens >= 20, "Token count should include all output blocks");
    }

    @Test
    @DisplayName("Should calculate tokens for Chinese text")
    void testCalculateTokenForChineseText() {
        String chineseText = "这是一个中文测试消息";
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(chineseText).build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Chinese text message should have positive token count");
        // Chinese characters typically use more tokens
        assertTrue(tokens >= 5, "Token count should account for Chinese characters");
    }

    @Test
    @DisplayName("Should calculate tokens for mixed English and Chinese text")
    void testCalculateTokenForMixedLanguageText() {
        String mixedText = "Hello 世界！This is a mixed language message.";
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(mixedText).build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        assertTrue(tokens > 0, "Mixed language message should have positive token count");
    }

    @Test
    @DisplayName("Should handle tool use with null name and id")
    void testCalculateTokenForToolUseWithNullFields() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(ToolUseBlock.builder().input(Map.of("param", "value")).build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        // Should still calculate tokens even with null name/id
        assertTrue(tokens > 0, "Tool use with null name/id should still calculate tokens");
    }

    @Test
    @DisplayName("Should handle tool result with null name and id")
    void testCalculateTokenForToolResultWithNullFields() {
        Msg msg =
                Msg.builder()
                        .name("tool")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .output(TextBlock.builder().text("Result").build())
                                        .build())
                        .build();

        int tokens = TokenCounterUtil.calculateToken(List.of(msg));
        // Should still calculate tokens even with null name/id
        assertTrue(tokens > 0, "Tool result with null name/id should still calculate tokens");
    }

    @Test
    @DisplayName("Should calculate tokens for large conversation")
    void testCalculateTokenForLargeConversation() {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(
                    Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Message " + i).build())
                            .build());
        }

        int tokens = TokenCounterUtil.calculateToken(messages);
        assertTrue(tokens > 0, "Large conversation should have positive token count");
        // Should be sum of all messages
        assertTrue(tokens >= 50, "Token count should scale with number of messages");
    }
}
