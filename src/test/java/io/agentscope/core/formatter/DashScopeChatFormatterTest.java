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

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.FormattedMessageList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DashScopeChatFormatterTest {

    @Test
    public void testBasicTextMessage() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("user", formatted.get(0).getRole());
    }

    @Test
    public void testSystemMessage() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text("System prompt").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("system", formatted.get(0).getRole());
    }

    @Test
    public void testImageBlock() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        URLSource urlSource = URLSource.builder().url("http://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();

        List<Msg> messages =
                List.of(Msg.builder().name("user").role(MsgRole.USER).content(imageBlock).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testAudioBlock() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        URLSource urlSource = URLSource.builder().url("http://example.com/audio.mp3").build();
        AudioBlock audioBlock = AudioBlock.builder().source(urlSource).build();

        List<Msg> messages =
                List.of(Msg.builder().name("user").role(MsgRole.USER).content(audioBlock).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testVideoBlock() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        URLSource urlSource = URLSource.builder().url("http://example.com/video.mp4").build();
        VideoBlock videoBlock = VideoBlock.builder().source(urlSource).build();

        List<Msg> messages =
                List.of(Msg.builder().name("user").role(MsgRole.USER).content(videoBlock).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testThinkingBlock() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().text("Let me think about this...").build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(thinkingBlock)
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolUseBlock() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

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

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolResultBlock() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("search")
                        .output(TextBlock.builder().text("Result").build())
                        .build();

        List<Msg> messages =
                List.of(Msg.builder().name("tool").role(MsgRole.TOOL).content(toolResult).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("tool", formatted.get(0).getRole());
    }

    @Test
    public void testToolResultFallback() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("tool")
                                .role(MsgRole.TOOL)
                                .content(TextBlock.builder().text("Fallback result").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        assertEquals("tool", formatted.get(0).getRole());
        // The content is the text, not the tool_call_id
        assertTrue(formatted.get(0).getContentAsString().contains("Fallback result"));
    }

    @Test
    public void testNullToolInput() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_null").name("no_param_tool").input(null).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testEmptyToolInput() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

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

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testComplexToolInput() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("stringParam", "value");
        toolInput.put("intParam", 123);
        toolInput.put("boolParam", true);
        toolInput.put("nullParam", null);

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

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolInputWithSpecialCharacters() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("text", "This is a \"quoted\" string");

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_special")
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

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testEmptyMessages() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages = List.of();

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(0, formatted.size());
    }

    @Test
    public void testMultipleMessages() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

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

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(3, formatted.size());
    }

    @Test
    public void testCapabilities() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("DashScope", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(capabilities.supportsVision());
    }

    @Test
    public void testWithTokenCounter() {
        SimpleTokenCounter tokenCounter = SimpleTokenCounter.forOpenAI();
        DashScopeChatFormatter formatter = new DashScopeChatFormatter(tokenCounter, 1000);

        assertTrue(formatter.hasTokenCounting());
        assertEquals(1000, formatter.getMaxTokens().intValue());
    }

    @Test
    public void testContentCollapsing() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
        // Content should be collapsed to string
        assertTrue(formatted.get(0).getContentAsString().contains("Hello"));
    }

    @Test
    public void testNullContent() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages =
                List.of(Msg.builder().name("user").role(MsgRole.USER).content(null).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testToolResultBlockInAgentMessage() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_456")
                        .name("calculator")
                        .output(TextBlock.builder().text("Result: 42").build())
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolResult)
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testContentCollapsingWithMultipleTextBlocks() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Part 1").build())
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Part 2").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(2, formatted.size());
    }

    @Test
    public void testFormatAgentMessage() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Hi").build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(2, formatted.size());
    }

    @Test
    public void testFormatToolSequence() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("param", "value");

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call_789")
                                                .name("test")
                                                .input(toolInput)
                                                .build())
                                .build(),
                        Msg.builder()
                                .name("tool")
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call_789")
                                                .output(TextBlock.builder().text("Success").build())
                                                .build())
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(2, formatted.size());
    }

    @Test
    public void testToolResultBlockWithNonTextOutput() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_999")
                        .name("thinker")
                        .output(
                                ThinkingBlock.builder()
                                        .text("Thinking about the problem...")
                                        .build())
                        .build();

        List<Msg> messages =
                List.of(Msg.builder().name("tool").role(MsgRole.TOOL).content(toolResult).build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testUnknownContentBlockType() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        // Using ThinkingBlock as non-standard content for user message
        ThinkingBlock thinkingBlock = ThinkingBlock.builder().text("User is thinking...").build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(thinkingBlock)
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }

    @Test
    public void testJsonConversionException() {
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("key1", "value1");
        toolInput.put("key2", 123);
        toolInput.put("key3", true);
        toolInput.put("key4", null);
        toolInput.put("key5", false);

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_json").name("json_test").input(toolInput).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(toolUse)
                                .build());

        FormattedMessageList formatted = formatter.format(messages);

        assertNotNull(formatted);
        assertEquals(1, formatted.size());
    }
}
