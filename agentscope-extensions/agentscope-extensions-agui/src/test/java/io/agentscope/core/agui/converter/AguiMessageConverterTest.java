/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AguiMessageConverter.
 */
class AguiMessageConverterTest {

    private AguiMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AguiMessageConverter();
    }

    @Test
    void testConvertUserMessageToMsg() {
        AguiMessage aguiMsg = AguiMessage.userMessage("msg-1", "Hello, world!");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-1", msg.getId());
        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals("Hello, world!", msg.getTextContent());
    }

    @Test
    void testConvertAssistantMessageToMsg() {
        AguiMessage aguiMsg = AguiMessage.assistantMessage("msg-2", "Hello! How can I help?");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-2", msg.getId());
        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertEquals("Hello! How can I help?", msg.getTextContent());
    }

    @Test
    void testConvertSystemMessageToMsg() {
        AguiMessage aguiMsg = AguiMessage.systemMessage("msg-3", "You are a helpful assistant.");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-3", msg.getId());
        assertEquals(MsgRole.SYSTEM, msg.getRole());
        assertEquals("You are a helpful assistant.", msg.getTextContent());
    }

    @Test
    void testConvertAssistantMessageWithToolCalls() {
        AguiFunctionCall function = new AguiFunctionCall("get_weather", "{\"city\":\"Beijing\"}");
        AguiToolCall toolCall = new AguiToolCall("tc-1", function);
        AguiMessage aguiMsg =
                new AguiMessage(
                        "msg-4", "assistant", "Let me check the weather.", List.of(toolCall), null);

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-4", msg.getId());
        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertTrue(msg.hasContentBlocks(TextBlock.class));
        assertTrue(msg.hasContentBlocks(ToolUseBlock.class));

        ToolUseBlock tub = msg.getFirstContentBlock(ToolUseBlock.class);
        assertEquals("tc-1", tub.getId());
        assertEquals("get_weather", tub.getName());
        assertEquals("Beijing", tub.getInput().get("city"));
    }

    @Test
    void testConvertMsgToAguiMessage() {
        Msg msg =
                Msg.builder()
                        .id("msg-5")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test message").build())
                        .build();

        AguiMessage aguiMsg = converter.toAguiMessage(msg);

        assertEquals("msg-5", aguiMsg.getId());
        assertEquals("user", aguiMsg.getRole());
        assertEquals("Test message", aguiMsg.getContent());
    }

    @Test
    void testConvertMsgWithToolUseToAguiMessage() {
        Msg msg =
                Msg.builder()
                        .id("msg-6")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Calling tool...").build(),
                                        ToolUseBlock.builder()
                                                .id("tc-2")
                                                .name("calculate")
                                                .input(Map.of("expression", "2+2"))
                                                .build()))
                        .build();

        AguiMessage aguiMsg = converter.toAguiMessage(msg);

        assertEquals("msg-6", aguiMsg.getId());
        assertEquals("assistant", aguiMsg.getRole());
        assertEquals("Calling tool...", aguiMsg.getContent());
        assertTrue(aguiMsg.hasToolCalls());
        assertEquals(1, aguiMsg.getToolCalls().size());

        AguiToolCall tc = aguiMsg.getToolCalls().get(0);
        assertEquals("tc-2", tc.getId());
        assertEquals("calculate", tc.getFunction().getName());
    }

    @Test
    void testConvertListOfMessages() {
        List<AguiMessage> aguiMsgs =
                List.of(
                        AguiMessage.systemMessage("m1", "System prompt"),
                        AguiMessage.userMessage("m2", "Hello"),
                        AguiMessage.assistantMessage("m3", "Hi there!"));

        List<Msg> msgs = converter.toMsgList(aguiMsgs);

        assertEquals(3, msgs.size());
        assertEquals(MsgRole.SYSTEM, msgs.get(0).getRole());
        assertEquals(MsgRole.USER, msgs.get(1).getRole());
        assertEquals(MsgRole.ASSISTANT, msgs.get(2).getRole());
    }

    @Test
    void testRoundTripConversion() {
        AguiMessage original = AguiMessage.userMessage("msg-rt", "Round trip test");

        Msg msg = converter.toMsg(original);
        AguiMessage converted = converter.toAguiMessage(msg);

        assertEquals(original.getId(), converted.getId());
        assertEquals(original.getRole(), converted.getRole());
        assertEquals(original.getContent(), converted.getContent());
    }

    @Test
    void testConvertToolMessageToMsg() {
        AguiMessage aguiMsg = AguiMessage.toolMessage("msg-t1", "tc-1", "Tool result here");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-t1", msg.getId());
        assertEquals(MsgRole.TOOL, msg.getRole());
        assertTrue(msg.hasContentBlocks(ToolResultBlock.class));
    }

    @Test
    void testConvertMessageWithEmptyContent() {
        AguiMessage aguiMsg = new AguiMessage("msg-empty", "user", "", null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-empty", msg.getId());
        // Empty string content should not create blocks
        assertFalse(msg.hasContentBlocks(TextBlock.class));
    }

    @Test
    void testConvertMessageWithNullContent() {
        AguiMessage aguiMsg = new AguiMessage("msg-null", "user", null, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-null", msg.getId());
        assertFalse(msg.hasContentBlocks(TextBlock.class));
    }

    @Test
    void testConvertMsgWithToolResultToAguiMessage() {
        Msg msg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-1")
                                        .output(TextBlock.builder().text("Result: 42").build())
                                        .build())
                        .build();

        AguiMessage aguiMsg = converter.toAguiMessage(msg);

        assertEquals("msg-tr1", aguiMsg.getId());
        assertEquals("tool", aguiMsg.getRole());
        assertEquals("tc-1", aguiMsg.getToolCallId());
        assertEquals("Result: 42", aguiMsg.getContent());
    }

    @Test
    void testConvertMsgWithMultipleTextBlocks() {
        Msg msg =
                Msg.builder()
                        .id("msg-multi")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First part").build(),
                                        TextBlock.builder().text("Second part").build()))
                        .build();

        AguiMessage aguiMsg = converter.toAguiMessage(msg);

        assertEquals("First part\nSecond part", aguiMsg.getContent());
    }

    @Test
    void testToAguiMessageListEmpty() {
        List<Msg> emptyList = Collections.emptyList();

        List<AguiMessage> result = converter.toAguiMessageList(emptyList);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testToMsgListEmpty() {
        List<AguiMessage> emptyList = Collections.emptyList();

        List<Msg> result = converter.toMsgList(emptyList);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testConvertWithInvalidRoleDefaultsToUser() {
        AguiMessage aguiMsg = new AguiMessage("msg-1", "unknown_role", "Test", null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals(MsgRole.USER, msg.getRole());
    }

    @Test
    void testConvertToolCallWithEmptyArguments() {
        AguiFunctionCall function = new AguiFunctionCall("test_tool", "");
        AguiToolCall toolCall = new AguiToolCall("tc-1", function);
        AguiMessage aguiMsg = new AguiMessage("msg-1", "assistant", null, List.of(toolCall), null);

        Msg msg = converter.toMsg(aguiMsg);

        ToolUseBlock tub = msg.getFirstContentBlock(ToolUseBlock.class);
        assertNotNull(tub);
        assertTrue(tub.getInput().isEmpty());
    }

    @Test
    void testConvertToolCallWithNullArguments() {
        AguiFunctionCall function = new AguiFunctionCall("test_tool", null);
        AguiToolCall toolCall = new AguiToolCall("tc-1", function);
        AguiMessage aguiMsg = new AguiMessage("msg-1", "assistant", null, List.of(toolCall), null);

        Msg msg = converter.toMsg(aguiMsg);

        ToolUseBlock tub = msg.getFirstContentBlock(ToolUseBlock.class);
        assertNotNull(tub);
        assertTrue(tub.getInput().isEmpty());
    }

    @Test
    void testConvertMsgWithEmptyToolUseInputToAguiMessage() {
        Msg msg =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test")
                                        .input(Map.of())
                                        .build())
                        .build();

        AguiMessage aguiMsg = converter.toAguiMessage(msg);

        assertTrue(aguiMsg.hasToolCalls());
        assertEquals("{}", aguiMsg.getToolCalls().get(0).getFunction().getArguments());
    }

    @Test
    void testCustomObjectMapper() {
        AguiMessageConverter customConverter = new AguiMessageConverter();

        AguiMessage aguiMsg = AguiMessage.userMessage("msg-1", "Test");
        Msg msg = customConverter.toMsg(aguiMsg);

        assertEquals("msg-1", msg.getId());
    }

    @Test
    void testConvertToolMessageWithNullToolCallId() {
        // Tool message without toolCallId - should still convert properly
        AguiMessage aguiMsg = new AguiMessage("msg-1", "tool", "Result", null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals(MsgRole.TOOL, msg.getRole());
        // Without toolCallId, content is just text
        assertTrue(msg.hasContentBlocks(TextBlock.class));
    }

    @Test
    void testConvertMsgWithToolResultNoOutput() {
        Msg msg =
                Msg.builder()
                        .id("msg-tr2")
                        .role(MsgRole.TOOL)
                        .content(ToolResultBlock.builder().id("tc-1").build())
                        .build();

        AguiMessage aguiMsg = converter.toAguiMessage(msg);

        assertEquals("tc-1", aguiMsg.getToolCallId());
        assertNull(aguiMsg.getContent());
    }

    @Test
    void testConvertToolCallWithInvalidJson() {
        // Invalid JSON should be handled gracefully
        AguiFunctionCall function = new AguiFunctionCall("test_tool", "{invalid json");
        AguiToolCall toolCall = new AguiToolCall("tc-1", function);
        AguiMessage aguiMsg = new AguiMessage("msg-1", "assistant", null, List.of(toolCall), null);

        Msg msg = converter.toMsg(aguiMsg);

        ToolUseBlock tub = msg.getFirstContentBlock(ToolUseBlock.class);
        assertNotNull(tub);
        // Invalid JSON should result in empty map
        assertTrue(tub.getInput().isEmpty());
    }

    // ===== Multimodal content conversion tests =====

    @Test
    void testConvertMultimodalTextContent() {
        List<Map<String, Object>> parts =
                List.of(Map.of("type", "text", "text", "Hello multimodal"));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-1", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-mm-1", msg.getId());
        assertEquals(MsgRole.USER, msg.getRole());
        assertTrue(msg.hasContentBlocks(TextBlock.class));
        assertEquals("Hello multimodal", msg.getTextContent());
    }

    @Test
    void testConvertMultimodalImageWithUrl() {
        Map<String, Object> source = Map.of("type", "url", "value", "https://example.com/img.png");
        List<Map<String, Object>> parts = List.of(Map.of("type", "image", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-2", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(ImageBlock.class));
        ImageBlock imageBlock = msg.getFirstContentBlock(ImageBlock.class);
        assertNotNull(imageBlock);
        assertInstanceOf(URLSource.class, imageBlock.getSource());
        assertEquals("https://example.com/img.png", ((URLSource) imageBlock.getSource()).getUrl());
    }

    @Test
    void testConvertMultimodalImageWithBase64() {
        Map<String, Object> source =
                Map.of("type", "data", "value", "iVBORw0KGgo=", "mimeType", "image/png");
        List<Map<String, Object>> parts = List.of(Map.of("type", "image", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-3", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(ImageBlock.class));
        ImageBlock imageBlock = msg.getFirstContentBlock(ImageBlock.class);
        assertNotNull(imageBlock);
        assertInstanceOf(Base64Source.class, imageBlock.getSource());
    }

    @Test
    void testConvertMultimodalVideoWithUrl() {
        Map<String, Object> source =
                Map.of("type", "url", "value", "https://example.com/video.mp4");
        List<Map<String, Object>> parts = List.of(Map.of("type", "video", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-4", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(VideoBlock.class));
        VideoBlock videoBlock = msg.getFirstContentBlock(VideoBlock.class);
        assertNotNull(videoBlock);
        assertInstanceOf(URLSource.class, videoBlock.getSource());
        assertEquals(
                "https://example.com/video.mp4", ((URLSource) videoBlock.getSource()).getUrl());
    }

    @Test
    void testConvertMultimodalAudioWithUrl() {
        Map<String, Object> source =
                Map.of("type", "url", "value", "https://example.com/audio.wav");
        List<Map<String, Object>> parts = List.of(Map.of("type", "audio", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-5", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(AudioBlock.class));
        AudioBlock audioBlock = msg.getFirstContentBlock(AudioBlock.class);
        assertNotNull(audioBlock);
        assertInstanceOf(URLSource.class, audioBlock.getSource());
        assertEquals(
                "https://example.com/audio.wav", ((URLSource) audioBlock.getSource()).getUrl());
    }

    @Test
    void testConvertMultimodalDocument() {
        Map<String, Object> source =
                Map.of(
                        "type",
                        "url",
                        "value",
                        "https://example.com/doc.pdf",
                        "mimeType",
                        "application/pdf");
        List<Map<String, Object>> parts = List.of(Map.of("type", "document", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-6", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(TextBlock.class));
        assertEquals("[Document: application/pdf]", msg.getTextContent());
    }

    @Test
    void testConvertMultimodalMixedContent() {
        Map<String, Object> imgSource =
                Map.of("type", "url", "value", "https://example.com/img.jpg");
        List<Map<String, Object>> parts =
                List.of(
                        Map.of("type", "text", "text", "Look at this image:"),
                        Map.of("type", "image", "source", imgSource));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-7", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        List<ContentBlock> blocks = msg.getContent();
        assertEquals(2, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(0));
        assertInstanceOf(ImageBlock.class, blocks.get(1));
    }

    @Test
    void testConvertMultimodalUnknownType() {
        List<Map<String, Object>> parts = List.of(Map.of("type", "unknown_type", "data", "xyz"));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-8", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // Unknown type should be skipped, resulting in no content blocks
        assertTrue(msg.getContent().isEmpty());
    }

    @Test
    void testConvertMultimodalNullType() {
        List<Map<String, Object>> parts = List.of(Map.of("data", "xyz"));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-9", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.getContent().isEmpty());
    }

    @Test
    void testConvertMultimodalImageWithNullSource() {
        List<Map<String, Object>> parts = List.of(Map.of("type", "image"));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-10", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // Image without source should be skipped
        assertFalse(msg.hasContentBlocks(ImageBlock.class));
    }

    @Test
    void testConvertMultimodalTextWithNullText() {
        List<Map<String, Object>> parts = List.of(Map.of("type", "text"));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-11", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // Text without text value should be skipped
        assertFalse(msg.hasContentBlocks(TextBlock.class));
    }

    @Test
    void testConvertMultimodalBase64SourceMissingMimeType() {
        Map<String, Object> source = Map.of("type", "data", "value", "iVBORw0KGgo=");
        List<Map<String, Object>> parts = List.of(Map.of("type", "image", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-12", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // Base64 source without mimeType should be skipped
        assertFalse(msg.hasContentBlocks(ImageBlock.class));
    }

    @Test
    void testConvertMultimodalUrlSourceMissingValue() {
        Map<String, Object> source = Map.of("type", "url");
        List<Map<String, Object>> parts = List.of(Map.of("type", "image", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-13", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // URL source without value should be skipped
        assertFalse(msg.hasContentBlocks(ImageBlock.class));
    }

    @Test
    void testConvertMultimodalDocumentWithoutSource() {
        List<Map<String, Object>> parts = List.of(Map.of("type", "document"));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-14", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // Document without source should be skipped
        assertFalse(msg.hasContentBlocks(TextBlock.class));
    }

    @Test
    void testConvertMultimodalSourceWithUnknownSourceType() {
        Map<String, Object> source = Map.of("type", "ftp", "value", "ftp://file.dat");
        List<Map<String, Object>> parts = List.of(Map.of("type", "image", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-15", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // Unknown source type should be skipped
        assertFalse(msg.hasContentBlocks(ImageBlock.class));
    }

    @Test
    void testConvertMultimodalAudioWithBase64() {
        Map<String, Object> source =
                Map.of("type", "data", "value", "AAAA", "mimeType", "audio/wav");
        List<Map<String, Object>> parts = List.of(Map.of("type", "audio", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-16", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(AudioBlock.class));
        AudioBlock audioBlock = msg.getFirstContentBlock(AudioBlock.class);
        assertInstanceOf(Base64Source.class, audioBlock.getSource());
    }

    @Test
    void testConvertMultimodalVideoWithBase64() {
        Map<String, Object> source =
                Map.of("type", "data", "value", "AAAA", "mimeType", "video/mp4");
        List<Map<String, Object>> parts = List.of(Map.of("type", "video", "source", source));
        AguiMessage aguiMsg = new AguiMessage("msg-mm-17", "user", parts, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(VideoBlock.class));
        VideoBlock videoBlock = msg.getFirstContentBlock(VideoBlock.class);
        assertInstanceOf(Base64Source.class, videoBlock.getSource());
    }
}
