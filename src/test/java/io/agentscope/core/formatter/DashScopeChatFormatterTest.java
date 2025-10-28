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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashScopeChatFormatterTest {

    private DashScopeChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DashScopeChatFormatter();
    }

    @Test
    void testFormatSimpleUserMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("Hello", result.get(0).getContent());
    }

    @Test
    void testFormatAssistantMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hi there").build()))
                        .build();

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("assistant", result.get(0).getRole());
        assertEquals("Hi there", result.get(0).getContent());
    }

    @Test
    void testFormatSystemMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("System prompt").build()))
                        .build();

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("system", result.get(0).getRole());
        assertEquals("System prompt", result.get(0).getContent());
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("tool", result.get(0).getRole());
        assertEquals("42", result.get(0).getContent());
        assertEquals("call_123", result.get(0).getToolCallId());
    }

    @Test
    void testFormatMessageWithToolCalls() {
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("assistant", result.get(0).getRole());
        assertNotNull(result.get(0).getToolCalls());
        assertEquals(1, result.get(0).getToolCalls().size());

        ToolCallBase toolCall = result.get(0).getToolCalls().get(0);
        assertTrue(toolCall instanceof ToolCallFunction);
        ToolCallFunction tcf = (ToolCallFunction) toolCall;
        assertEquals("call_123", tcf.getId());
        assertEquals("add", tcf.getFunction().getName());
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

        List<Message> result = formatter.format(msgs);

        assertEquals(3, result.size());
        assertEquals("system", result.get(0).getRole());
        assertEquals("user", result.get(1).getRole());
        assertEquals("assistant", result.get(2).getRole());
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        // ThinkingBlock should be skipped when formatting messages for API
        // (matching Python implementation behavior)
        assertFalse(result.get(0).getContent().contains("Let me think..."));
        assertTrue(result.get(0).getContent().contains("The answer is 42"));
    }

    @Test
    void testParseResponseSimpleText() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);
        when(message.getContent()).thenReturn("Hello world");
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response);
        assertEquals("req_123", response.getId());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof TextBlock);
        assertEquals("Hello world", ((TextBlock) response.getContent().get(0)).getText());
    }

    @Test
    void testParseResponseWithUsage() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationUsage usage = mock(GenerationUsage.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn("Response");
        when(genResult.getUsage()).thenReturn(usage);
        when(usage.getInputTokens()).thenReturn(10);
        when(usage.getOutputTokens()).thenReturn(20);
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response.getUsage());
        assertEquals(10, response.getUsage().getInputTokens());
        assertEquals(20, response.getUsage().getOutputTokens());
        assertTrue(response.getUsage().getTime() >= 0);
    }

    @Test
    void testParseResponseWithThinkingContent() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);
        when(message.getReasoningContent()).thenReturn("Thinking...");
        when(message.getContent()).thenReturn("Answer");
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertEquals(2, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof ThinkingBlock);
        assertEquals("Thinking...", ((ThinkingBlock) response.getContent().get(0)).getThinking());
        assertTrue(response.getContent().get(1) instanceof TextBlock);
        assertEquals("Answer", ((TextBlock) response.getContent().get(1)).getText());
    }

    @Test
    void testParseResponseWithToolCalls() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        ToolCallFunction tcf = new ToolCallFunction();
        tcf.setId("call_123");
        ToolCallFunction.CallFunction cf = tcf.new CallFunction();
        cf.setName("add");
        cf.setArguments("{\"a\":5,\"b\":10}");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(tcf));
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        boolean foundToolUse = false;
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolUseBlock) {
                foundToolUse = true;
                ToolUseBlock toolUse = (ToolUseBlock) block;
                assertEquals("call_123", toolUse.getId());
                assertEquals("add", toolUse.getName());
                assertNotNull(toolUse.getInput());
            }
        }
        assertTrue(foundToolUse, "Should have found a ToolUseBlock");
    }

    @Test
    void testParseResponseWithFragmentToolCalls() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        // Fragment without name (subsequent chunk)
        ToolCallFunction fragment = new ToolCallFunction();
        fragment.setId("call_123");
        ToolCallFunction.CallFunction fragmentCf = fragment.new CallFunction();
        fragmentCf.setName(null); // No name in fragment
        fragmentCf.setArguments("{\"partial\":\"data\"}");
        fragment.setFunction(fragmentCf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(fragment));
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        boolean foundFragment = false;
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolUseBlock) {
                ToolUseBlock toolUse = (ToolUseBlock) block;
                if ("__fragment__".equals(toolUse.getName())) {
                    foundFragment = true;
                }
            }
        }
        assertTrue(foundFragment, "Should have found a fragment ToolUseBlock");
    }

    @Test
    void testParseResponseException() {
        GenerationResult genResult = mock(GenerationResult.class);
        when(genResult.getOutput()).thenThrow(new RuntimeException("Parse error"));

        Instant start = Instant.now();

        assertThrows(RuntimeException.class, () -> formatter.parseResponse(genResult, start));
    }

    @Test
    void testParseResponseWithNullUsageTokens() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationUsage usage = mock(GenerationUsage.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn("Response");
        when(genResult.getUsage()).thenReturn(usage);
        when(usage.getInputTokens()).thenReturn(null);
        when(usage.getOutputTokens()).thenReturn(null);
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response.getUsage());
        assertEquals(0, response.getUsage().getInputTokens());
        assertEquals(0, response.getUsage().getOutputTokens());
    }

    @Test
    void testParseResponseWithEmptyOutput() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn("");
        when(output.getChoices()).thenReturn(new ArrayList<>());
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response);
        assertEquals(0, response.getContent().size());
    }

    @Test
    void testParseResponseWithNullOutput() {
        GenerationResult genResult = mock(GenerationResult.class);

        when(genResult.getOutput()).thenReturn(null);
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response);
        assertEquals(0, response.getContent().size());
    }

    @Test
    void testGetCapabilities() {
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("DashScope", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(!capabilities.supportsMultiAgent());
        assertTrue(capabilities.supportsVision());
        assertTrue(capabilities.getSupportedBlocks().contains(TextBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolUseBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolResultBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ThinkingBlock.class));
    }

    @Test
    void testFormatEmptyMessageList() {
        List<Message> result = formatter.format(List.of());
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        String content = result.get(0).getContent();
        assertTrue(content.contains("First"));
        assertTrue(content.contains("Second"));
        assertTrue(content.contains("Third"));
    }

    @Test
    void testParseResponseWithInvalidToolCallJson() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        ToolCallFunction tcf = new ToolCallFunction();
        tcf.setId("call_123");
        ToolCallFunction.CallFunction cf = tcf.new CallFunction();
        cf.setName("test");
        cf.setArguments("invalid json {{{");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(tcf));
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        // Should still create a ToolUseBlock even with invalid JSON
        boolean foundToolUse = false;
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolUseBlock) {
                foundToolUse = true;
                ToolUseBlock toolUse = (ToolUseBlock) block;
                assertEquals("test", toolUse.getName());
                // Input map should be empty due to parsing failure
                assertNotNull(toolUse.getInput());
            }
        }
        assertTrue(foundToolUse);
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
        // Should use contents() for multimodal messages
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
                                        TextBlock.builder().text("Analyze this").build(),
                                        imageBlock))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatUserMessageWithAudioBlock_LogsWarning() {
        // Audio is not supported by DashScope Generation API
        io.agentscope.core.message.AudioBlock audioBlock =
                io.agentscope.core.message.AudioBlock.builder()
                        .source(
                                io.agentscope.core.message.Base64Source.builder()
                                        .data("//uQxAA...")
                                        .mediaType("audio/mp3")
                                        .build())
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Listen to this").build(),
                                        audioBlock))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
        // Should have placeholder text for unsupported audio
    }

    @Test
    void testFormatUserMessageWithVideoBlock_LogsWarning() {
        // Video is not supported by DashScope Generation API
        io.agentscope.core.message.VideoBlock videoBlock =
                io.agentscope.core.message.VideoBlock.builder()
                        .source(
                                io.agentscope.core.message.URLSource.builder()
                                        .url("https://example.com/video.mp4")
                                        .build())
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(TextBlock.builder().text("Watch this").build(), videoBlock))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatUserMessagePureText() {
        // Pure text should use the simple content() format
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Simple text").build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAssistantMessageWithImage() {
        // Assistant messages can also have images
        io.agentscope.core.message.ImageBlock imageBlock =
                io.agentscope.core.message.ImageBlock.builder()
                        .source(
                                io.agentscope.core.message.URLSource.builder()
                                        .url("https://example.com/output.png")
                                        .build())
                        .build();

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Here's the result").build(),
                                        imageBlock))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatMessageWithMultipleImages() {
        // Test multiple images in one message
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Compare these").build(),
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

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }
}
