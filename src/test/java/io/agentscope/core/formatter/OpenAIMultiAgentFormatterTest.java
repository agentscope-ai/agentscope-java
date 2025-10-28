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
import com.openai.models.chat.completions.ChatCompletionMessageParam;
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

class OpenAIMultiAgentFormatterTest {

    private OpenAIMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OpenAIMultiAgentFormatter();
    }

    @Test
    void testFormatSimpleUserMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
        assertTrue(result.get(0).user().isPresent());
    }

    @Test
    void testFormatMultipleAgentsConversation() {
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("Alice")
                                .content(List.of(TextBlock.builder().text("Hello Bob").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("Bob")
                                .content(List.of(TextBlock.builder().text("Hi Alice").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("Charlie")
                                .content(List.of(TextBlock.builder().text("Hello all").build()))
                                .build());

        List<ChatCompletionMessageParam> result = formatter.format(msgs);

        assertEquals(1, result.size());
        assertTrue(result.get(0).user().isPresent());
    }

    @Test
    void testFormatMessageWithoutName() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).user().isPresent());
    }

    @Test
    void testFormatSystemMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("System")
                        .content(List.of(TextBlock.builder().text("You are helpful").build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).system().isPresent());
    }

    @Test
    void testFormatMessageWithThinkingBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("AI")
                        .content(
                                List.of(
                                        ThinkingBlock.builder().text("Let me think...").build(),
                                        TextBlock.builder().text("The answer is 42").build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).user().isPresent());
    }

    @Test
    void testFormatAssistantWithToolCall() {
        Map<String, Object> args = new HashMap<>();
        args.put("a", 5);
        args.put("b", 10);

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("AI")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Let me calculate").build(),
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("add")
                                                .input(args)
                                                .build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).assistant().isPresent());
        assertNotNull(result.get(0).assistant().get().toolCalls());
        assertEquals(1, result.get(0).assistant().get().toolCalls().get().size());
    }

    @Test
    void testFormatToolResultMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_456")
                                                .name("calculator")
                                                .output(TextBlock.builder().text("42").build())
                                                .build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).tool().isPresent());
        assertEquals("call_456", result.get(0).tool().get().toolCallId());
    }

    @Test
    void testFormatToolResultWithoutToolResultBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(List.of(TextBlock.builder().text("Result text").build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).tool().isPresent());
        assertNotNull(result.get(0).tool().get().toolCallId());
        assertTrue(result.get(0).tool().get().toolCallId().startsWith("tool_call_"));
    }

    @Test
    void testFormatMixedConversationAndToolCalls() {
        Map<String, Object> args = new HashMap<>();
        args.put("x", 5);

        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("Alice")
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call_1")
                                                        .name("tool1")
                                                        .input(args)
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("call_1")
                                                        .name("tool1")
                                                        .output(
                                                                TextBlock.builder()
                                                                        .text("result")
                                                                        .build())
                                                        .build()))
                                .build());

        List<ChatCompletionMessageParam> result = formatter.format(msgs);

        // User message, then tool sequence (assistant + tool)
        assertEquals(3, result.size());
        assertTrue(result.get(0).user().isPresent());
        assertTrue(result.get(1).assistant().isPresent());
        assertTrue(result.get(2).tool().isPresent());
    }

    @Test
    void testFormatEmptyMessageList() {
        List<ChatCompletionMessageParam> result = formatter.format(List.of());
        assertEquals(0, result.size());
    }

    @Test
    void testFormatMultipleTextBlocks() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("First").build(),
                                        TextBlock.builder().text("Second").build(),
                                        TextBlock.builder().text("Third").build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).user().isPresent());
    }

    @Test
    void testFormatAssistantWithMultipleToolCalls() {
        Map<String, Object> args1 = new HashMap<>();
        args1.put("x", 1);
        Map<String, Object> args2 = new HashMap<>();
        args2.put("y", 2);

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_1")
                                                .name("tool1")
                                                .input(args1)
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("call_2")
                                                .name("tool2")
                                                .input(args2)
                                                .build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).assistant().isPresent());
        assertNotNull(result.get(0).assistant().get().toolCalls());
        assertEquals(2, result.get(0).assistant().get().toolCalls().get().size());
    }

    @Test
    void testParseResponseUnsupportedType() {
        String unsupportedResponse = "Invalid type";

        assertThrows(
                IllegalArgumentException.class,
                () -> formatter.parseResponse(unsupportedResponse, Instant.now()));
    }

    @Test
    void testGetCapabilities() {
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("OpenAI", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(capabilities.supportsMultiAgent());
        assertTrue(capabilities.supportsVision());
        assertTrue(capabilities.getSupportedBlocks().contains(TextBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolUseBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolResultBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ThinkingBlock.class));
    }

    @Test
    void testFormatMultipleSystemMessages() {
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(List.of(TextBlock.builder().text("First system").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(List.of(TextBlock.builder().text("Second system").build()))
                                .build());

        List<ChatCompletionMessageParam> result = formatter.format(msgs);

        // Each system message should be in separate group
        assertEquals(2, result.size());
        assertTrue(result.get(0).system().isPresent());
        assertTrue(result.get(1).system().isPresent());
    }

    @Test
    void testFormatMessageWithToolResultInConversation() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Calculator")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("add")
                                                .output(TextBlock.builder().text("15").build())
                                                .build()))
                        .build();

        List<ChatCompletionMessageParam> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).user().isPresent());
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
        when(message.content()).thenReturn(Optional.of("Hello from multi-agent"));
        when(message.toolCalls()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseCompletionResponse(completion, start);

        assertEquals("chatcmpl-123", response.getId());
        assertNotNull(response.getContent());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof TextBlock);
        assertEquals(
                "Hello from multi-agent", ((TextBlock) response.getContent().get(0)).getText());
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
        when(delta.content()).thenReturn(Optional.of("Streaming text"));
        when(delta.toolCalls()).thenReturn(Optional.empty());

        Instant start = Instant.now();
        ChatResponse response = formatter.parseChunkResponse(chunk, start);

        assertEquals("chatcmpl-chunk-1", response.getId());
        assertNotNull(response.getContent());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof TextBlock);
        assertEquals("Streaming text", ((TextBlock) response.getContent().get(0)).getText());
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
        when(toolCall.id()).thenReturn(Optional.of("call_multi_123"));
        when(function.name()).thenReturn(Optional.of("calculate"));
        when(function.arguments()).thenReturn(Optional.of("{\"x\":10}"));

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
        when(function.name()).thenReturn(Optional.of("multi_tool"));
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
    void testFormatAgentConversationWithImages() {
        // Test multi-agent conversation with images preserved as ContentParts
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Look at this image").build(),
                                        io.agentscope.core.message.ImageBlock.builder()
                                                .source(
                                                        io.agentscope.core.message.URLSource
                                                                .builder()
                                                                .url("https://example.com/img1.png")
                                                                .build())
                                                .build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("I see the image").build()))
                        .build();

        var result = formatter.format(List.of(msg1, msg2));

        // Should consolidate into a single user message with multimodal content
        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationWithAudio() {
        // Test multi-agent conversation with audio
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Listen to this").build(),
                                        io.agentscope.core.message.AudioBlock.builder()
                                                .source(
                                                        io.agentscope.core.message.Base64Source
                                                                .builder()
                                                                .data("//uQxAA...")
                                                                .mediaType("audio/mp3")
                                                                .build())
                                                .build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Assistant")
                        .content(List.of(TextBlock.builder().text("Got it").build()))
                        .build();

        var result = formatter.format(List.of(msg1, msg2));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationMixedMultimedia() {
        // Test conversation with multiple types of media
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Here are some files").build(),
                                        io.agentscope.core.message.ImageBlock.builder()
                                                .source(
                                                        io.agentscope.core.message.URLSource
                                                                .builder()
                                                                .url("https://example.com/img1.png")
                                                                .build())
                                                .build(),
                                        io.agentscope.core.message.ImageBlock.builder()
                                                .source(
                                                        io.agentscope.core.message.URLSource
                                                                .builder()
                                                                .url("https://example.com/img2.png")
                                                                .build())
                                                .build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("Thanks").build()))
                        .build();

        var result = formatter.format(List.of(msg1, msg2));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationPureText() {
        // Verify pure text conversations still work (no multimodal content)
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("Hi").build()))
                        .build();

        var result = formatter.format(List.of(msg1, msg2));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }
}
