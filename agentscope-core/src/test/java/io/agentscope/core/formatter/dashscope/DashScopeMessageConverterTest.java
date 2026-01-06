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
package io.agentscope.core.formatter.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests for DashScopeMessageConverter. */
@Tag("unit")
class DashScopeMessageConverterTest {

    private DashScopeMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter =
                new DashScopeMessageConverter(
                        blocks -> {
                            StringBuilder sb = new StringBuilder();
                            for (ContentBlock block : blocks) {
                                if (block instanceof TextBlock tb) {
                                    sb.append(tb.getText());
                                }
                            }
                            return sb.toString();
                        });
    }

    // ==================== Simple Content Tests ====================

    @Test
    void testConvertUserMessageSimple() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello, world!").build())
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("user", dsMsg.getRole());
        assertEquals("Hello, world!", dsMsg.getContentAsString());
        assertFalse(dsMsg.isMultimodal());
    }

    @Test
    void testConvertAssistantMessageSimple() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I'm here to help!").build())
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertEquals("I'm here to help!", dsMsg.getContentAsString());
    }

    @Test
    void testConvertSystemMessageSimple() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("You are a helpful assistant.").build())
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("system", dsMsg.getRole());
        assertEquals("You are a helpful assistant.", dsMsg.getContentAsString());
    }

    @Test
    void testConvertToolResultMessageSimple() {
        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("get_weather")
                        .output(List.of(TextBlock.builder().text("Sunny, 25°C").build()))
                        .build();

        Msg msg = Msg.builder().role(MsgRole.TOOL).content(List.of(toolResult)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("tool", dsMsg.getRole());
        assertEquals("call_123", dsMsg.getToolCallId());
        assertEquals("get_weather", dsMsg.getName());
        assertEquals("Sunny, 25°C", dsMsg.getContentAsString());
    }

    @Test
    void testConvertAssistantWithToolCallsSimple() {
        Map<String, Object> args = new HashMap<>();
        args.put("location", "Beijing");

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_abc").name("get_weather").input(args).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(TextBlock.builder().text("Let me check.").build(), toolUse))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertEquals("Let me check.", dsMsg.getContentAsString());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(1, dsMsg.getToolCalls().size());
        assertEquals("call_abc", dsMsg.getToolCalls().get(0).getId());
        assertEquals("get_weather", dsMsg.getToolCalls().get(0).getFunction().getName());
    }

    @Test
    void testConvertAssistantWithOnlyToolCallsSimple() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_xyz").name("search").input(args).build();

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolUse)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertNull(dsMsg.getContentAsString());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(1, dsMsg.getToolCalls().size());
    }

    // ==================== Multimodal Content Tests ====================

    @Test
    void testConvertUserMessageMultimodal() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is this?").build())
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("user", dsMsg.getRole());
        assertTrue(dsMsg.isMultimodal());
        List<DashScopeContentPart> parts = dsMsg.getContentAsList();
        assertEquals(1, parts.size());
        assertEquals("What is this?", parts.get(0).getText());
    }

    @Test
    void testConvertToolRoleMessageMultimodal() {
        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("tool_call_1")
                        .name("fetch_data")
                        .output(List.of(TextBlock.builder().text("Data retrieved").build()))
                        .build();

        Msg msg = Msg.builder().role(MsgRole.TOOL).content(List.of(toolResult)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("tool", dsMsg.getRole());
        assertEquals("tool_call_1", dsMsg.getToolCallId());
        assertEquals("fetch_data", dsMsg.getName());
        assertTrue(dsMsg.isMultimodal());
    }

    @Test
    void testConvertAssistantWithToolCallsMultimodal() {
        Map<String, Object> args = new HashMap<>();
        args.put("param", "value");

        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("call_multi").name("tool_name").input(args).build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(TextBlock.builder().text("Processing...").build(), toolUse))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("assistant", dsMsg.getRole());
        assertTrue(dsMsg.isMultimodal());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(1, dsMsg.getToolCalls().size());
    }

    @Test
    void testConvertMessageWithThinkingBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Let me think...").build(),
                                        TextBlock.builder().text("The answer is 42.").build()))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("assistant", dsMsg.getRole());
        // ThinkingBlock should be skipped
        List<DashScopeContentPart> parts = dsMsg.getContentAsList();
        assertEquals(1, parts.size());
        assertEquals("The answer is 42.", parts.get(0).getText());
    }

    @Test
    void testConvertMessageWithToolResultBlockInContent() {
        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("result_1")
                        .name("calculator")
                        .output(List.of(TextBlock.builder().text("Result: 100").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER) // Not TOOL role
                        .content(
                                List.of(
                                        TextBlock.builder().text("Previous result:").build(),
                                        toolResult))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("user", dsMsg.getRole());
        List<DashScopeContentPart> parts = dsMsg.getContentAsList();
        assertEquals(2, parts.size());
        assertEquals("Previous result:", parts.get(0).getText());
        assertEquals("Result: 100", parts.get(1).getText());
    }

    @Test
    void testConvertEmptyMessageMultimodal() {
        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of()).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("user", dsMsg.getRole());
        assertTrue(dsMsg.isMultimodal());
        // Should have empty text content to satisfy VL API
        List<DashScopeContentPart> parts = dsMsg.getContentAsList();
        assertEquals(1, parts.size());
        assertEquals("", parts.get(0).getText());
    }

    @Test
    void testConvertMessageWithMultipleTextBlocks() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First part.").build(),
                                        TextBlock.builder().text("Second part.").build(),
                                        TextBlock.builder().text("Third part.").build()))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("user", dsMsg.getRole());
        assertTrue(dsMsg.getContentAsString().contains("First part."));
        assertTrue(dsMsg.getContentAsString().contains("Second part."));
    }

    @Test
    void testConvertMessageWithUrlImageBlocks() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("http://example.com/image.png")
                                                                .build())
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/image.png")
                                                                .build())
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("oss://example.com/image.png")
                                                                .build())
                                                .build()))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("user", dsMsg.getRole());
        assertNotNull(dsMsg.getContentAsList());
        assertEquals(3, dsMsg.getContentAsList().size());
        assertEquals("http://example.com/image.png", dsMsg.getContentAsList().get(0).getImage());
        assertEquals("https://example.com/image.png", dsMsg.getContentAsList().get(1).getImage());
        assertEquals("oss://example.com/image.png", dsMsg.getContentAsList().get(2).getImage());
    }

    @Test
    void testConvertMessageWithUrlVideoBlocks() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("http://example.com/video.mp4")
                                                                .build())
                                                .build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/video.mp4")
                                                                .build())
                                                .build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("oss://example.com/video.mp4")
                                                                .build())
                                                .build()))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("user", dsMsg.getRole());
        assertNotNull(dsMsg.getContentAsList());
        assertEquals(3, dsMsg.getContentAsList().size());
        assertEquals(
                "http://example.com/video.mp4", dsMsg.getContentAsList().get(0).getVideoAsString());
        assertEquals(
                "https://example.com/video.mp4",
                dsMsg.getContentAsList().get(1).getVideoAsString());
        assertEquals(
                "oss://example.com/video.mp4", dsMsg.getContentAsList().get(2).getVideoAsString());
    }

    @Test
    void testConvertMessageWithUrlAudioBlocks() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("http://example.com/audio.wav")
                                                                .build())
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/audio.wav")
                                                                .build())
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("oss://example.com/audio.wav")
                                                                .build())
                                                .build()))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("user", dsMsg.getRole());
        assertNotNull(dsMsg.getContentAsList());
        assertEquals(3, dsMsg.getContentAsList().size());
        assertEquals("http://example.com/audio.wav", dsMsg.getContentAsList().get(0).getAudio());
        assertEquals("https://example.com/audio.wav", dsMsg.getContentAsList().get(1).getAudio());
        assertEquals("oss://example.com/audio.wav", dsMsg.getContentAsList().get(2).getAudio());
    }

    @Test
    void testConvertToolResultFromSystemRole() {
        // Tool result can also come from SYSTEM role
        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("sys_tool_1")
                        .name("system_tool")
                        .output(List.of(TextBlock.builder().text("System result").build()))
                        .build();

        Msg msg = Msg.builder().role(MsgRole.SYSTEM).content(List.of(toolResult)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("tool", dsMsg.getRole());
        assertEquals("sys_tool_1", dsMsg.getToolCallId());
        assertEquals("system_tool", dsMsg.getName());
    }

    @Test
    void testConvertToolRoleWithoutToolResultBlock() {
        // TOOL role but no ToolResultBlock (fallback case)
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(List.of(TextBlock.builder().text("Fallback content").build()))
                        .build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, true);

        assertEquals("tool", dsMsg.getRole());
        assertTrue(dsMsg.isMultimodal());
    }

    @Test
    void testConvertMultipleToolCalls() {
        ToolUseBlock tool1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("tool_a")
                        .input(Map.of("key", "value1"))
                        .build();
        ToolUseBlock tool2 =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("tool_b")
                        .input(Map.of("key", "value2"))
                        .build();

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(tool1, tool2)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(2, dsMsg.getToolCalls().size());
        assertEquals("tool_a", dsMsg.getToolCalls().get(0).getFunction().getName());
        assertEquals("tool_b", dsMsg.getToolCalls().get(1).getFunction().getName());
    }

    // ==================== Tool Call Content Priority Tests ====================

    @Test
    void testToolCallUsesContentFieldWhenPresent() {
        // Create a ToolUseBlock with both content (raw string) and input map
        // The content field should be used preferentially
        String rawContent = "{\"city\":\"Beijing\",\"unit\":\"celsius\"}";
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_content_test")
                        .name("get_weather")
                        .input(Map.of("city", "Shanghai", "unit", "fahrenheit"))
                        .content(rawContent)
                        .build();

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolUse)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(1, dsMsg.getToolCalls().size());
        // Should use the content field (raw string) instead of serializing input map
        assertEquals(rawContent, dsMsg.getToolCalls().get(0).getFunction().getArguments());
    }

    @Test
    void testToolCallFallbackToInputMapWhenContentNull() {
        // Create a ToolUseBlock with only input map (content is null)
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_fallback_test")
                        .name("get_weather")
                        .input(Map.of("city", "Beijing"))
                        .content(null)
                        .build();

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolUse)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(1, dsMsg.getToolCalls().size());
        // Should serialize the input map since content is null
        String args = dsMsg.getToolCalls().get(0).getFunction().getArguments();
        assertNotNull(args);
        assertTrue(args.contains("city"));
        assertTrue(args.contains("Beijing"));
    }

    @Test
    void testToolCallFallbackToInputMapWhenContentEmpty() {
        // Create a ToolUseBlock with empty content string
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call_empty_content_test")
                        .name("get_weather")
                        .input(Map.of("city", "Shanghai"))
                        .content("")
                        .build();

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(toolUse)).build();

        DashScopeMessage dsMsg = converter.convertToMessage(msg, false);

        assertEquals("assistant", dsMsg.getRole());
        assertNotNull(dsMsg.getToolCalls());
        assertEquals(1, dsMsg.getToolCalls().size());
        // Should serialize the input map since content is empty
        String args = dsMsg.getToolCalls().get(0).getFunction().getArguments();
        assertNotNull(args);
        assertTrue(args.contains("city"));
        assertTrue(args.contains("Shanghai"));
    }
}
