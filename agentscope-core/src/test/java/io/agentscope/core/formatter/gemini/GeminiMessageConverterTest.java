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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
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
 * <p>These tests verify the message conversion logic including:
 * <ul>
 *   <li>Text message conversion</li>
 *   <li>Tool use and tool result conversion</li>
 *   <li>Multimodal content (image, audio, video) conversion</li>
 *   <li>Role mapping (USER/ASSISTANT/SYSTEM to Gemini roles)</li>
 *   <li>Tool result formatting (single vs multiple outputs)</li>
 *   <li>Media block to text reference conversion</li>
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
        List<Content> result = converter.convertMessages(new ArrayList<>());

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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("user", content.role().get());
        assertEquals(1, content.parts().get().size());
        assertEquals("Hello, world!", content.parts().get().get(0).text().get());
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

        List<Content> result = converter.convertMessages(List.of(msg1, msg2));

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).role().get());
        assertEquals("model", result.get(1).role().get());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals("model", result.get(0).role().get());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals("user", result.get(0).role().get());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals("user", result.get(0).role().get());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("model", content.role().get());

        Part part = content.parts().get().get(0);
        assertNotNull(part.functionCall().get());
        assertEquals("call_123", part.functionCall().get().id().get());
        assertEquals("search", part.functionCall().get().name().get());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("user", content.role().get());

        Part part = content.parts().get().get(0);
        assertNotNull(part.functionResponse().get());
        assertEquals("call_123", part.functionResponse().get().id().get());
        assertEquals("search", part.functionResponse().get().name().get());
        assertEquals("Result text", part.functionResponse().get().response().get().get("output"));
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

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
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

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
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

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
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

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
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

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
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

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
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

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(1, content.parts().get().size());
        // Media converter handles the actual conversion
        assertNotNull(content.parts().get().get(0));
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).parts().get().get(0));
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).parts().get().get(0));
    }

    @Test
    @DisplayName("Should skip ThinkingBlock")
    void testSkipThinkingBlock() {
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(1, content.parts().get().size());
        assertEquals("Visible response", content.parts().get().get(0).text().get());
    }

    @Test
    @DisplayName("Should skip message with only ThinkingBlock")
    void testSkipMessageWithOnlyThinkingBlock() {
        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().thinking("Internal reasoning").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(thinkingBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertTrue(result.isEmpty());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(3, content.parts().get().size());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(2, content.parts().get().size());
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

        List<Content> result = converter.convertMessages(List.of(msg));

        // Should have 2 Content objects: tool result added first, then text parts
        assertEquals(2, result.size());

        // First content should be the tool result (added during block processing)
        Content toolResultContent = result.get(0);
        assertEquals("user", toolResultContent.role().get());
        assertNotNull(toolResultContent.parts().get().get(0).functionResponse().get());

        // Second content should have text parts before and after
        Content textContent = result.get(1);
        assertEquals(2, textContent.parts().get().size());
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

        List<Content> result = converter.convertMessages(List.of(userMsg, assistantMsg, systemMsg));

        assertEquals(3, result.size());
        assertEquals("user", result.get(0).role().get());
        assertEquals("model", result.get(1).role().get());
        assertEquals("user", result.get(2).role().get());
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

        List<Content> result =
                converter.convertMessages(
                        List.of(userMsg, toolCallMsg, toolResultMsg, responseMsg));

        assertEquals(4, result.size());

        // Verify roles
        assertEquals("user", result.get(0).role().get());
        assertEquals("model", result.get(1).role().get());
        assertEquals("user", result.get(2).role().get()); // tool result
        assertEquals("model", result.get(3).role().get());

        // Verify tool call
        assertNotNull(result.get(1).parts().get().get(0).functionCall().get());
        assertEquals(
                "get_weather",
                result.get(1).parts().get().get(0).functionCall().get().name().get());

        // Verify tool result
        assertNotNull(result.get(2).parts().get().get(0).functionResponse().get());
        assertEquals(
                "Sunny, 25°C",
                result.get(2)
                        .parts()
                        .get()
                        .get(0)
                        .functionResponse()
                        .get()
                        .response()
                        .get()
                        .get("output"));
    }

    @Test
    @DisplayName("Should convert ToolUseBlock with thoughtSignature")
    void testConvertToolUseBlockWithThoughtSignature() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        byte[] thoughtSignature = "test-signature".getBytes();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_with_sig")
                        .name("search")
                        .input(input)
                        .metadata(metadata)
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("model", content.role().get());

        Part part = content.parts().get().get(0);
        assertNotNull(part.functionCall().get());
        assertEquals("call_with_sig", part.functionCall().get().id().get());
        assertEquals("search", part.functionCall().get().name().get());

        // Verify thought signature is attached to Part
        assertTrue(part.thoughtSignature().isPresent());
        assertArrayEquals(thoughtSignature, part.thoughtSignature().get());
    }

    @Test
    @DisplayName("Should convert ToolUseBlock without thoughtSignature")
    void testConvertToolUseBlockWithoutThoughtSignature() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("call_no_sig").name("search").input(input).build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);

        assertNotNull(part.functionCall().get());
        // Verify thought signature is NOT present
        assertFalse(part.thoughtSignature().isPresent());
    }

    @Test
    @DisplayName("Should handle round-trip of thoughtSignature in function calling flow")
    void testThoughtSignatureRoundTrip() {
        // This test simulates:
        // 1. Model returns function call with thoughtSignature (parsed by ResponseParser)
        // 2. We store it in ToolUseBlock metadata
        // 3. Later we send the function call back with the signature (via MessageConverter)

        Map<String, Object> input = new HashMap<>();
        input.put("location", "Tokyo");

        byte[] signature = "gemini3-thought-sig-abc123".getBytes();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, signature);

        // Simulate assistant message with tool call (as would be constructed from parsed response)
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_roundtrip")
                        .name("get_weather")
                        .input(input)
                        .metadata(metadata)
                        .build();

        Msg assistantMsg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        // Convert to Gemini format (for sending in next request)
        List<Content> result = converter.convertMessages(List.of(assistantMsg));

        // Verify the signature is preserved in the output
        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);

        assertNotNull(part.functionCall().get());
        assertEquals("get_weather", part.functionCall().get().name().get());

        // The signature should be attached to the Part
        assertTrue(part.thoughtSignature().isPresent());
        assertArrayEquals(signature, part.thoughtSignature().get());
    }
}
