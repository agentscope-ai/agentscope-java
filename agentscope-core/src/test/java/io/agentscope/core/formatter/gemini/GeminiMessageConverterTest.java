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
package io.agentscope.core.formatter.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiPart;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GeminiMessageConverter.
 *
 * <p>
 * These tests verify the message conversion logic including:
 * <ul>
 * <li>Text message conversion</li>
 * <li>Tool use and tool result conversion</li>
 * <li>Multimodal content (image, audio, video) conversion</li>
 * <li>Role mapping (USER/ASSISTANT/SYSTEM to Gemini roles)</li>
 * <li>Tool result formatting (single vs multiple outputs)</li>
 * <li>Media block to text reference conversion</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("GeminiMessageConverter Unit Tests")
class GeminiMessageConverterTest {

    private GeminiMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new GeminiMessageConverter();
    }

    @Test
    @DisplayName("Should convert empty message list")
    void testConvertEmptyMessages() {
        List<GeminiContent> result = converter.convertMessages(new ArrayList<>());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should convert single text message")
    void testConvertSingleTextMessage() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("Hello, world!").build()))
                        .role(MsgRole.USER)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals("user", content.getRole());
        assertEquals(1, content.getParts().size());
        assertEquals("Hello, world!", content.getParts().get(0).getText());
    }

    @Test
    @DisplayName("Should convert multiple text messages")
    void testConvertMultipleTextMessages() {
        Msg msg1 =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("First message").build()))
                        .role(MsgRole.USER)
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Second message").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg1, msg2));

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("model", result.get(1).getRole());
    }

    @Test
    @DisplayName("Should convert ASSISTANT role to 'model'")
    void testConvertAssistantRole() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Response").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals("model", result.get(0).getRole());
    }

    @Test
    @DisplayName("Should convert USER role to 'user'")
    void testConvertUserRole() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("Question").build()))
                        .role(MsgRole.USER)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals("user", result.get(0).getRole());
    }

    @Test
    @DisplayName("Should convert SYSTEM role to 'user'")
    void testConvertSystemRole() {
        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(TextBlock.builder().text("System message").build()))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals("user", result.get(0).getRole());
    }

    @Test
    @DisplayName("Should convert ToolUseBlock to FunctionCall")
    void testConvertToolUseBlock() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("call_123").name("search").input(input).build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals("model", content.getRole());

        GeminiPart part = content.getParts().get(0);
        assertNotNull(part.getFunctionCall());
        assertEquals("call_123", part.getFunctionCall().getId());
        assertEquals("search", part.getFunctionCall().getName());
    }

    @Test
    @DisplayName("Should convert ToolResultBlock to independent Content with user role")
    void testConvertToolResultBlock() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("search")
                        .output(List.of(TextBlock.builder().text("Result text").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals("user", content.getRole());

        GeminiPart part = content.getParts().get(0);
        assertNotNull(part.getFunctionResponse());
        assertEquals("call_123", part.getFunctionResponse().getId());
        assertEquals("search", part.getFunctionResponse().getName());
        assertEquals("Result text", part.getFunctionResponse().getResponse().get("output"));
    }

    @Test
    @DisplayName("Should format tool result with single output")
    void testToolResultSingleOutput() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(TextBlock.builder().text("Single output").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        GeminiPart part = result.get(0).getParts().get(0);
        String output = (String) part.getFunctionResponse().getResponse().get("output");
        assertEquals("Single output", output);
    }

    @Test
    @DisplayName("Should format tool result with multiple outputs using dash prefix")
    void testToolResultMultipleOutputs() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(
                                List.of(
                                        TextBlock.builder().text("First output").build(),
                                        TextBlock.builder().text("Second output").build(),
                                        TextBlock.builder().text("Third output").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        GeminiPart part = result.get(0).getParts().get(0);
        String output = (String) part.getFunctionResponse().getResponse().get("output");
        assertEquals("- First output\n- Second output\n- Third output", output);
    }

    @Test
    @DisplayName("Should handle tool result with URL image")
    void testToolResultWithURLImage() {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(
                                List.of(
                                        TextBlock.builder().text("Here is the image:").build(),
                                        imageBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        GeminiPart part = result.get(0).getParts().get(0);
        String output = (String) part.getFunctionResponse().getResponse().get("output");
        assertTrue(output.contains("Here is the image:"));
        assertTrue(
                output.contains(
                        "The returned image can be found at: https://example.com/image.png"));
    }

    @Test
    @DisplayName("Should handle tool result with Base64 image")
    void testToolResultWithBase64Image() {
        String base64Data =
                java.util.Base64.getEncoder().encodeToString("fake image data".getBytes());

        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("image/png")
                                        .data(base64Data)
                                        .build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(imageBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        GeminiPart part = result.get(0).getParts().get(0);
        String output = (String) part.getFunctionResponse().getResponse().get("output");
        assertTrue(output.contains("The returned image can be found at:"));
        assertTrue(output.contains("agentscope_"));
        assertTrue(output.contains(".png"));
    }

    @Test
    @DisplayName("Should handle tool result with URL audio")
    void testToolResultWithURLAudio() {
        AudioBlock audioBlock =
                AudioBlock.builder()
                        .source(URLSource.builder().url("https://example.com/audio.mp3").build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(audioBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        GeminiPart part = result.get(0).getParts().get(0);
        String output = (String) part.getFunctionResponse().getResponse().get("output");
        assertTrue(
                output.contains(
                        "The returned audio can be found at: https://example.com/audio.mp3"));
    }

    @Test
    @DisplayName("Should handle tool result with URL video")
    void testToolResultWithURLVideo() {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(videoBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        GeminiPart part = result.get(0).getParts().get(0);
        String output = (String) part.getFunctionResponse().getResponse().get("output");
        assertTrue(
                output.contains(
                        "The returned video can be found at: https://example.com/video.mp4"));
    }

    @Test
    @DisplayName("Should handle empty tool result output")
    void testToolResultEmptyOutput() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(new ArrayList<>())
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        GeminiPart part = result.get(0).getParts().get(0);
        String output = (String) part.getFunctionResponse().getResponse().get("output");
        assertEquals("", output);
    }

    @Test
    @DisplayName("Should convert ImageBlock to inline data part")
    void testConvertImageBlock() {
        String base64Data = java.util.Base64.getEncoder().encodeToString("fake image".getBytes());

        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("image/jpeg")
                                        .data(base64Data)
                                        .build())
                        .build();

        Msg msg =
                Msg.builder().name("user").content(List.of(imageBlock)).role(MsgRole.USER).build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals(1, content.getParts().size());
        // Media converter handles the actual conversion
        assertNotNull(content.getParts().get(0));
    }

    @Test
    @DisplayName("Should convert AudioBlock to inline data part")
    void testConvertAudioBlock() {
        String base64Data = java.util.Base64.getEncoder().encodeToString("fake audio".getBytes());

        AudioBlock audioBlock =
                AudioBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("audio/wav")
                                        .data(base64Data)
                                        .build())
                        .build();

        Msg msg =
                Msg.builder().name("user").content(List.of(audioBlock)).role(MsgRole.USER).build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).getParts().get(0));
    }

    @Test
    @DisplayName("Should convert VideoBlock to inline data part")
    void testConvertVideoBlock() {
        String base64Data = java.util.Base64.getEncoder().encodeToString("fake video".getBytes());

        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("video/mp4")
                                        .data(base64Data)
                                        .build())
                        .build();

        Msg msg =
                Msg.builder().name("user").content(List.of(videoBlock)).role(MsgRole.USER).build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).getParts().get(0));
    }

    @Test
    @DisplayName("Should convert ThinkingBlock")
    void testConvertThinkingBlock() {
        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().thinking("Internal reasoning").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        thinkingBlock,
                                        TextBlock.builder().text("Visible response").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals(2, content.getParts().size());

        GeminiPart thoughtPart = content.getParts().get(0);
        assertTrue(thoughtPart.getThought());
        assertEquals("Internal reasoning", thoughtPart.getText());

        GeminiPart textPart = content.getParts().get(1);
        assertEquals("Visible response", textPart.getText());
    }

    @Test
    @DisplayName("Should convert message with only ThinkingBlock")
    void testConvertMessageWithOnlyThinkingBlock() {
        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().thinking("Internal reasoning").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(thinkingBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals(1, content.getParts().size());
        assertTrue(content.getParts().get(0).getThought());
        assertEquals("Internal reasoning", content.getParts().get(0).getText());
    }

    @Test
    @DisplayName("Should handle mixed content types")
    void testMixedContentTypes() {
        String base64Data = java.util.Base64.getEncoder().encodeToString("fake image".getBytes());

        Msg msg =
                Msg.builder()
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Here is an image:").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(base64Data)
                                                                .build())
                                                .build(),
                                        TextBlock.builder().text("What do you see?").build()))
                        .role(MsgRole.USER)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals(3, content.getParts().size());
    }

    @Test
    @DisplayName("Should handle message with text and tool use")
    void testMessageWithTextAndToolUse() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Let me search for that.").build(),
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .input(input)
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiContent content = result.get(0);
        assertEquals(2, content.getParts().size());
    }

    @Test
    @DisplayName("Should create separate Content for tool result")
    void testSeparateContentForToolResult() {
        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Before tool result").build(),
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("tool")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Result")
                                                                        .build()))
                                                .build(),
                                        TextBlock.builder().text("After tool result").build()))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        // Should have 2 Content objects: tool result added first, then text parts
        assertEquals(2, result.size());

        // First content should be the tool result (added during block processing)
        GeminiContent toolResultContent = result.get(0);
        assertEquals("user", toolResultContent.getRole());
        assertNotNull(toolResultContent.getParts().get(0).getFunctionResponse());

        // Second content should have text parts before and after
        GeminiContent textContent = result.get(1);
        assertEquals(2, textContent.getParts().size());
    }

    @Test
    @DisplayName("Should handle consecutive messages with different roles")
    void testConsecutiveMessagesWithDifferentRoles() {
        Msg userMsg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("Question").build()))
                        .role(MsgRole.USER)
                        .build();

        Msg assistantMsg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Answer").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        Msg systemMsg =
                Msg.builder()
                        .name("system")
                        .content(List.of(TextBlock.builder().text("System info").build()))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<GeminiContent> result =
                converter.convertMessages(List.of(userMsg, assistantMsg, systemMsg));

        assertEquals(3, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("model", result.get(1).getRole());
        assertEquals("user", result.get(2).getRole());
    }

    @Test
    @DisplayName("Should handle complex conversation flow")
    void testComplexConversationFlow() {
        // User question
        Msg userMsg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("What's the weather?").build()))
                        .role(MsgRole.USER)
                        .build();

        // Assistant tool call
        Map<String, Object> input = new HashMap<>();
        input.put("location", "Tokyo");

        Msg toolCallMsg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("get_weather")
                                                .input(input)
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        // Tool result
        Msg toolResultMsg =
                Msg.builder()
                        .name("system")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("get_weather")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Sunny, 25°C")
                                                                        .build()))
                                                .build()))
                        .role(MsgRole.SYSTEM)
                        .build();

        // Assistant response
        Msg responseMsg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The weather in Tokyo is sunny with 25°C.")
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result =
                converter.convertMessages(
                        List.of(userMsg, toolCallMsg, toolResultMsg, responseMsg));

        assertEquals(4, result.size());

        // Verify roles
        assertEquals("user", result.get(0).getRole());
        assertEquals("model", result.get(1).getRole());
        assertEquals("user", result.get(2).getRole()); // tool result
        assertEquals("model", result.get(3).getRole());

        // Verify tool call
        assertNotNull(result.get(1).getParts().get(0).getFunctionCall());
        assertEquals("get_weather", result.get(1).getParts().get(0).getFunctionCall().getName());

        // Verify tool result
        assertNotNull(result.get(2).getParts().get(0).getFunctionResponse());
        assertEquals(
                "Sunny, 25°C",
                result.get(2).getParts().get(0).getFunctionResponse().getResponse().get("output"));
    }

    // Commented out tests relying on thoughtSignature which is not yet supported in
    // DTOs
    /*
     * @Test
     *
     * @DisplayName("Should convert ToolUseBlock with thoughtSignature")
     * void testConvertToolUseBlockWithThoughtSignature() {
     * ...
     * }
     *
     * @Test
     *
     * @DisplayName("Should convert ToolUseBlock without thoughtSignature")
     * void testConvertToolUseBlockWithoutThoughtSignature() {
     * ...
     * }
     *
     * @Test
     *
     * @DisplayName("Should handle round-trip of thoughtSignature in function calling flow"
     * )
     * void testThoughtSignatureRoundTrip() {
     * ...
     * }
     */

    @Test
    @DisplayName("Should convert ThinkingBlock with signature")
    void testConvertThinkingBlockWithSignature() {
        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().thinking("Reasoning").signature("sig_123").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(thinkingBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiPart part = result.get(0).getParts().get(0);
        assertTrue(part.getThought());
        assertEquals("Reasoning", part.getText());
        assertEquals("sig_123", part.getSignature());
    }

    @Test
    @DisplayName("Should use content field when present for tool call arguments")
    void testToolCallUsesContentFieldWhenPresent() {
        // Create a ToolUseBlock with both content (raw string) and input map
        // The content field should be used preferentially
        String rawContent = "{\"city\":\"Beijing\",\"unit\":\"celsius\"}";
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Shanghai");
        inputMap.put("unit", "fahrenheit");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_content_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content(rawContent)
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiPart part = result.get(0).getParts().get(0);
        assertNotNull(part.getFunctionCall());

        // Should use the content field (parsed from raw string) instead of input map
        Map<String, Object> args = part.getFunctionCall().getArgs();
        assertEquals("Beijing", args.get("city"));
        assertEquals("celsius", args.get("unit"));
    }

    @Test
    @DisplayName("Should fallback to input map when content is null")
    void testToolCallFallbackToInputMapWhenContentNull() {
        // Create a ToolUseBlock with only input map (content is null)
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Beijing");
        inputMap.put("unit", "celsius");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_fallback_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content(null)
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiPart part = result.get(0).getParts().get(0);
        assertNotNull(part.getFunctionCall());

        // Should use the input map since content is null
        Map<String, Object> args = part.getFunctionCall().getArgs();
        assertEquals("Beijing", args.get("city"));
        assertEquals("celsius", args.get("unit"));
    }

    @Test
    @DisplayName("Should fallback to input map when content is empty")
    void testToolCallFallbackToInputMapWhenContentEmpty() {
        // Create a ToolUseBlock with empty content string
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Shanghai");
        inputMap.put("unit", "fahrenheit");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_empty_content_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content("")
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiPart part = result.get(0).getParts().get(0);
        assertNotNull(part.getFunctionCall());

        // Should use the input map since content is empty
        Map<String, Object> args = part.getFunctionCall().getArgs();
        assertEquals("Shanghai", args.get("city"));
        assertEquals("fahrenheit", args.get("unit"));
    }

    @Test
    @DisplayName("Should fallback to input map when content is invalid JSON")
    void testToolCallFallbackToInputMapWhenContentInvalidJson() {
        // Create a ToolUseBlock with invalid JSON content
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Tokyo");
        inputMap.put("unit", "celsius");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_invalid_json_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content("{invalid json}")
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<GeminiContent> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        GeminiPart part = result.get(0).getParts().get(0);
        assertNotNull(part.getFunctionCall());

        // Should fallback to input map since content is invalid JSON
        Map<String, Object> args = part.getFunctionCall().getArgs();
        assertEquals("Tokyo", args.get("city"));
        assertEquals("celsius", args.get("unit"));
    }
}
