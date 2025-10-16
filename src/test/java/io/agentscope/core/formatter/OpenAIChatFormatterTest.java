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

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.FormattedMessageList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class OpenAIChatFormatterTest {

    @Test
    public void testBasicTextMessage() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("user", formatted.get(0).getRole());
    }

    @Test
    public void testSystemMessage() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text("System prompt").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("system", formatted.get(0).getRole());
    }

    @Test
    public void testImageBlock() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        URLSource urlSource = URLSource.builder().url("http://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();

        List<Msg> messages =
                List.of(Msg.builder().name("user").role(MsgRole.USER).content(imageBlock).build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testThinkingBlock() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().text("Let me think about this...").build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(thinkingBlock)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolUseBlock() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("query", "test");

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_123").name("search").input(toolInput).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolResultBlock() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("search")
                        .output(TextBlock.builder().text("Result").build())
                        .build();

        List<Msg> messages =
                List.of(Msg.builder().name("tool").role(MsgRole.TOOL).content(toolResult).build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("tool", formatted.get(0).getRole());
    }

    @Test
    public void testToolResultFallbackWithMessageName() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("tool_result:call_456")
                                .role(MsgRole.TOOL)
                                .content(TextBlock.builder().text("Fallback result").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("tool", formatted.get(0).getRole());
        // The content contains the result text
        assertTrue(formatted.get(0).getContentAsString().contains("Fallback result"));
    }

    @Test
    public void testNullToolInput() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_null").name("no_param_tool").input(null).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testEmptyToolInput() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_empty")
                        .name("no_param_tool")
                        .input(new HashMap<>())
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testComplexToolInput() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("stringParam", "value");
        toolInput.put("intParam", 123);
        toolInput.put("boolParam", true);

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_complex")
                        .name("complex_tool")
                        .input(toolInput)
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testEmptyMessages() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> messages = List.of();

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(0, formatted.size());
    }

    @Test
    public void testMultipleMessages() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text("System prompt").build())
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Hi there!").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(3, formatted.size());
    }

    @Test
    public void testCapabilities() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("OpenAI", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(capabilities.supportsVision());
    }

    @Test
    public void testWithTokenCounter() {
        SimpleTokenCounter tokenCounter = SimpleTokenCounter.forOpenAI();
        OpenAIChatFormatter formatter = new OpenAIChatFormatter(tokenCounter, 1000);

        assertTrue(formatter.hasTokenCounting());
        assertEquals(1000, formatter.getMaxTokens().intValue());
    }

    @Test
    public void testConvertInputToJsonWithNumbers() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("count", 42);
        toolInput.put("price", 19.99);

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_numbers")
                        .name("test_tool")
                        .input(toolInput)
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testConvertInputToJsonWithBooleans() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("enabled", true);
        toolInput.put("disabled", false);

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_bools").name("test_tool").input(toolInput).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testConvertInputToJsonWithObject() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put(
                "object",
                new Object() {
                    @Override
                    public String toString() {
                        return "custom_object";
                    }
                });

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_object").name("test_tool").input(toolInput).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testFormatToolSequence() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("query", "test");

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .input(toolInput)
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("tool")
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .output(TextBlock.builder().text("Result").build())
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(2, formatted.size());
    }

    @Test
    public void testFormatAgentMessageWithUnknownRole() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testNullContent() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        List<Msg> messages =
                List.of(Msg.builder().name("user").role(MsgRole.USER).content(null).build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolResultWithNonTextOutput() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_789")
                        .name("calculator")
                        .output(ThinkingBlock.builder().text("Calculating result...").build())
                        .build();

        List<Msg> messages =
                List.of(Msg.builder().name("tool").role(MsgRole.TOOL).content(toolResult).build());

        FormattedMessageList formatted = formatter.format(messages).block();

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }
}
