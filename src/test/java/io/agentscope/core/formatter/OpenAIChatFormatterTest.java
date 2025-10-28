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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAIChatFormatterTest {

    private OpenAIChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OpenAIChatFormatter();
    }

    @Test
    void testFormatSimpleUserMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatSystemMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("System prompt").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAssistantMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Response").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatUserMessageWithName() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("John")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAssistantMessageWithName() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("AI")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatToolMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("calculator")
                                                .output(TextBlock.builder().text("42").build())
                                                .build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAssistantMessageWithToolCalls() {
        Map<String, Object> args = new HashMap<>();
        args.put("a", 5);
        args.put("b", 10);

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Let me calculate").build(),
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("add")
                                                .input(args)
                                                .build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatMultipleMessages() {
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You are helpful")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(List.of(TextBlock.builder().text("Hi there").build()))
                                .build());

        var result = formatter.format(msgs);

        assertEquals(3, result.size());
    }

    @Test
    void testFormatMessageWithThinkingBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().text("Let me think...").build(),
                                        TextBlock.builder().text("The answer is 42").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatEmptyMessageList() {
        var result = formatter.format(List.of());
        assertEquals(0, result.size());
    }

    @Test
    void testFormatMessageWithMultipleTextBlocks() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First").build(),
                                        TextBlock.builder().text("Second").build(),
                                        TextBlock.builder().text("Third").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testParseResponseUnsupportedType() {
        String unsupportedResponse = "Invalid type";

        assertThrows(
                IllegalArgumentException.class,
                () -> formatter.parseResponse(unsupportedResponse, java.time.Instant.now()));
    }

    @Test
    void testGetCapabilities() {
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("OpenAI", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(!capabilities.supportsMultiAgent());
        assertTrue(capabilities.supportsVision());
        assertTrue(capabilities.getSupportedBlocks().contains(TextBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolUseBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolResultBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ThinkingBlock.class));
    }

    @Test
    void testFormatAssistantMessageWithEmptyContent() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of()).build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatToolMessageWithoutToolResult() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(List.of(TextBlock.builder().text("Result").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatMessageWithToolResultInToolMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_456")
                                                .name("test_tool")
                                                .output(TextBlock.builder().text("Success").build())
                                                .build(),
                                        TextBlock.builder().text("Extra text").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testParseCompletionResponseSimpleText() {
        ChatCompletion completion = mock(ChatCompletion.class);
        ChatCompletion.Choice choice = mock(ChatCompletion.Choice.class);
        ChatCompletionMessage message = mock(ChatCompletionMessage.class);

        when(completion.id()).thenReturn("chatcmpl-123");
        when(completion.choices()).thenReturn(List.of(choice));
        when(completion.usage()).thenReturn(Optional.empty());
        when(choice.message()).thenReturn(message);
        when(message.content()).thenReturn(Optional.of("Hello world"));
        when(message.toolCalls()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseCompletionResponse(completion, start);

        assertEquals("chatcmpl-123", response.getId());
        assertNotNull(response.getContent());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof TextBlock);
        assertEquals("Hello world", ((TextBlock) response.getContent().get(0)).getText());
    }

    @Test
    void testParseCompletionResponseEmptyContent() {
        ChatCompletion completion = mock(ChatCompletion.class);
        ChatCompletion.Choice choice = mock(ChatCompletion.Choice.class);
        ChatCompletionMessage message = mock(ChatCompletionMessage.class);

        when(completion.id()).thenReturn("chatcmpl-empty");
        when(completion.choices()).thenReturn(List.of(choice));
        when(completion.usage()).thenReturn(Optional.empty());
        when(choice.message()).thenReturn(message);
        when(message.content()).thenReturn(Optional.of(""));
        when(message.toolCalls()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseCompletionResponse(completion, start);

        assertEquals("chatcmpl-empty", response.getId());
        assertEquals(0, response.getContent().size());
    }

    @Test
    void testParseChunkResponseSimpleText() {
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);

        when(chunk.id()).thenReturn("chatcmpl-chunk-1");
        when(chunk.choices()).thenReturn(List.of(choice));
        when(chunk.usage()).thenReturn(Optional.empty());
        when(choice.delta()).thenReturn(delta);
        when(delta.content()).thenReturn(Optional.of("Hello"));
        when(delta.toolCalls()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseChunkResponse(chunk, start);

        assertEquals("chatcmpl-chunk-1", response.getId());
        assertNotNull(response.getContent());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof TextBlock);
        assertEquals("Hello", ((TextBlock) response.getContent().get(0)).getText());
    }

    @Test
    void testParseChunkResponseEmptyDelta() {
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);

        when(chunk.id()).thenReturn("chatcmpl-chunk-empty");
        when(chunk.choices()).thenReturn(List.of(choice));
        when(chunk.usage()).thenReturn(Optional.empty());
        when(choice.delta()).thenReturn(delta);
        when(delta.content()).thenReturn(Optional.empty());
        when(delta.toolCalls()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseChunkResponse(chunk, start);

        assertEquals("chatcmpl-chunk-empty", response.getId());
        assertEquals(0, response.getContent().size());
    }

    @Test
    void testParseChunkResponseWithToolCalls() {
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
        ChatCompletionChunk.Choice.Delta.ToolCall toolCall =
                mock(ChatCompletionChunk.Choice.Delta.ToolCall.class);
        ChatCompletionChunk.Choice.Delta.ToolCall.Function function =
                mock(ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

        when(chunk.id()).thenReturn("chatcmpl-chunk-tool");
        when(chunk.choices()).thenReturn(List.of(choice));
        when(chunk.usage()).thenReturn(Optional.empty());
        when(choice.delta()).thenReturn(delta);
        when(delta.content()).thenReturn(Optional.empty());
        when(delta.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));
        when(toolCall.function()).thenReturn(Optional.of(function));
        when(toolCall.id()).thenReturn(Optional.of("call_123"));
        when(function.name()).thenReturn(Optional.of("calculate"));
        when(function.arguments()).thenReturn(Optional.of("{\"x\":5}"));

        Instant start = Instant.now();
        ChatResponse response = formatter.parseChunkResponse(chunk, start);

        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof ToolUseBlock);
    }

    @Test
    void testParseChunkResponseWithPartialToolArguments() {
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
        ChatCompletionChunk.Choice.Delta.ToolCall toolCall =
                mock(ChatCompletionChunk.Choice.Delta.ToolCall.class);
        ChatCompletionChunk.Choice.Delta.ToolCall.Function function =
                mock(ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

        when(chunk.id()).thenReturn("chatcmpl-chunk-partial");
        when(chunk.choices()).thenReturn(List.of(choice));
        when(chunk.usage()).thenReturn(Optional.empty());
        when(choice.delta()).thenReturn(delta);
        when(delta.content()).thenReturn(Optional.empty());
        when(delta.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));
        when(toolCall.function()).thenReturn(Optional.of(function));
        when(toolCall.id()).thenReturn(Optional.of("call_partial"));
        when(function.name()).thenReturn(Optional.of("test_tool"));
        when(function.arguments()).thenReturn(Optional.of("{\"incomplete\":"));

        Instant start = Instant.now();
        ChatResponse response = formatter.parseChunkResponse(chunk, start);

        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) response.getContent().get(0);
        assertTrue(toolUse.getInput().isEmpty());
    }

    @Test
    void testParseChunkResponseWithMalformedChunk() {
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);

        when(chunk.id()).thenReturn("chatcmpl-malformed");
        when(chunk.choices()).thenThrow(new RuntimeException("Malformed chunk"));
        when(chunk.usage()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseChunkResponse(chunk, start);

        assertNull(response);
    }

    @Test
    void testParseCompletionResponseWithException() {
        ChatCompletion completion = mock(ChatCompletion.class);

        when(completion.id()).thenReturn("chatcmpl-error");
        when(completion.choices()).thenThrow(new RuntimeException("Parse error"));
        when(completion.usage()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseCompletionResponse(completion, start);

        assertEquals("chatcmpl-error", response.getId());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof TextBlock);
        assertTrue(
                ((TextBlock) response.getContent().get(0))
                        .getText()
                        .contains("Error parsing response"));
    }

    @Test
    void testFormatUserMessageWithImageBlock_RemoteUrl() {
        io.agentscope.core.message.ImageBlock imageBlock =
                io.agentscope.core.message.ImageBlock.builder()
                        .source(
                                io.agentscope.core.message.URLSource.builder()
                                        .url("https://example.com/image.png")
                                        .build())
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("What's in this image?").build(),
                                        imageBlock))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
        // Should use contentOfArrayOfContentParts for multimodal content
    }

    @Test
    void testFormatUserMessageWithImageBlock_Base64Source() {
        io.agentscope.core.message.ImageBlock imageBlock =
                io.agentscope.core.message.ImageBlock.builder()
                        .source(
                                io.agentscope.core.message.Base64Source.builder()
                                        .data(
                                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                                        .mediaType("image/png")
                                        .build())
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Analyze this image").build(),
                                        imageBlock))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatUserMessageWithAudioBlock_Base64Source() {
        io.agentscope.core.message.AudioBlock audioBlock =
                io.agentscope.core.message.AudioBlock.builder()
                        .source(
                                io.agentscope.core.message.Base64Source.builder()
                                        .data("//uQxAA...") // Sample base64 audio data
                                        .mediaType("audio/mp3")
                                        .build())
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Transcribe this audio").build(),
                                        audioBlock))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatUserMessagePureTextFastPath() {
        // Pure text should use the fast path (simple string content)
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Simple text message").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatUserMessageMixedContent() {
        // Multiple text blocks and images
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First text").build(),
                                        io.agentscope.core.message.ImageBlock.builder()
                                                .source(
                                                        io.agentscope.core.message.URLSource
                                                                .builder()
                                                                .url("https://example.com/img1.png")
                                                                .build())
                                                .build(),
                                        TextBlock.builder().text("Second text").build(),
                                        io.agentscope.core.message.ImageBlock.builder()
                                                .source(
                                                        io.agentscope.core.message.URLSource
                                                                .builder()
                                                                .url("https://example.com/img2.png")
                                                                .build())
                                                .build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }
}
