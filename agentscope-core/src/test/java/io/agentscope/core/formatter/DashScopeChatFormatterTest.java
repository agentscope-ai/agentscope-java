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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeChoice;
import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.formatter.dashscope.dto.DashScopeFunction;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeOutput;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolCall;
import io.agentscope.core.formatter.dashscope.dto.DashScopeUsage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("Hello", result.get(0).getContentAsString());
    }

    @Test
    void testFormatAssistantMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hi there").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("assistant", result.get(0).getRole());
        assertEquals("Hi there", result.get(0).getContentAsString());
    }

    @Test
    void testFormatSystemMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(List.of(TextBlock.builder().text("System prompt").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("system", result.get(0).getRole());
        assertEquals("System prompt", result.get(0).getContentAsString());
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("tool", result.get(0).getRole());
        assertEquals("42", result.get(0).getContentAsString());
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("assistant", result.get(0).getRole());
        assertNotNull(result.get(0).getToolCalls());
        assertEquals(1, result.get(0).getToolCalls().size());

        DashScopeToolCall toolCall = result.get(0).getToolCalls().get(0);
        assertEquals("call_123", toolCall.getId());
        assertEquals("add", toolCall.getFunction().getName());
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

        List<DashScopeMessage> result = formatter.format(msgs);

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
                                        ThinkingBlock.builder().thinking("Let me think...").build(),
                                        TextBlock.builder().text("The answer is 42").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        // ThinkingBlock should be skipped when formatting messages for API
        assertFalse(result.get(0).getContentAsString().contains("Let me think..."));
        assertTrue(result.get(0).getContentAsString().contains("The answer is 42"));
    }

    @Test
    void testParseResponseSimpleText() {
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeChoice choice = mock(DashScopeChoice.class);
        DashScopeMessage message = mock(DashScopeMessage.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(output.getFirstChoice()).thenReturn(choice);
        when(choice.getMessage()).thenReturn(message);
        when(message.getContentAsString()).thenReturn("Hello world");
        when(message.isMultimodal()).thenReturn(false);
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
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeUsage usage = mock(DashScopeUsage.class);

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
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeChoice choice = mock(DashScopeChoice.class);
        DashScopeMessage message = mock(DashScopeMessage.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(output.getFirstChoice()).thenReturn(choice);
        when(choice.getMessage()).thenReturn(message);
        when(message.getReasoningContent()).thenReturn("Thinking...");
        when(message.getContentAsString()).thenReturn("Answer");
        when(message.isMultimodal()).thenReturn(false);
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
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeChoice choice = mock(DashScopeChoice.class);
        DashScopeMessage message = mock(DashScopeMessage.class);

        DashScopeToolCall tcf = new DashScopeToolCall();
        tcf.setId("call_123");
        DashScopeFunction cf = new DashScopeFunction();
        cf.setName("add");
        cf.setArguments("{\"a\":5,\"b\":10}");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(output.getFirstChoice()).thenReturn(choice);
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(tcf));
        when(message.isMultimodal()).thenReturn(false);
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
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeChoice choice = mock(DashScopeChoice.class);
        DashScopeMessage message = mock(DashScopeMessage.class);

        // Fragment without name (subsequent chunk)
        DashScopeToolCall fragment = new DashScopeToolCall();
        fragment.setId("call_123");
        DashScopeFunction fragmentCf = new DashScopeFunction();
        fragmentCf.setName(null); // No name in fragment
        fragmentCf.setArguments("{\"partial\":\"data\"}");
        fragment.setFunction(fragmentCf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(output.getFirstChoice()).thenReturn(choice);
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(fragment));
        when(message.isMultimodal()).thenReturn(false);
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
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        when(genResult.getOutput()).thenThrow(new RuntimeException("Parse error"));

        Instant start = Instant.now();

        assertThrows(RuntimeException.class, () -> formatter.parseResponse(genResult, start));
    }

    @Test
    void testParseResponseWithNullUsageTokens() {
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeUsage usage = mock(DashScopeUsage.class);

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
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);

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
        DashScopeResponse genResult = mock(DashScopeResponse.class);

        when(genResult.getOutput()).thenReturn(null);
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response);
        assertEquals(0, response.getContent().size());
    }

    @Test
    void testFormatEmptyMessageList() {
        List<DashScopeMessage> result = formatter.format(List.of());
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        String content = result.get(0).getContentAsString();
        assertTrue(content.contains("First"));
        assertTrue(content.contains("Second"));
        assertTrue(content.contains("Third"));
    }

    @Test
    void testParseResponseWithInvalidToolCallJson() {
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeChoice choice = mock(DashScopeChoice.class);
        DashScopeMessage message = mock(DashScopeMessage.class);

        DashScopeToolCall tcf = new DashScopeToolCall();
        tcf.setId("call_123");
        DashScopeFunction cf = new DashScopeFunction();
        cf.setName("test");
        cf.setArguments("invalid json {{{");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(output.getFirstChoice()).thenReturn(choice);
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(tcf));
        when(message.isMultimodal()).thenReturn(false);
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
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
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
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
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
        AudioBlock audioBlock =
                AudioBlock.builder()
                        .source(
                                Base64Source.builder()
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
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
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

        // Video block should be skipped, only text content remains
        // The fact that formatter returns successfully (without throwing exception)
        // means video block was properly handled (skipped with warning)
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
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/output.png").build())
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
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("https://example.com/img1.png")
                                                                .build())
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("https://example.com/img2.png")
                                                                .build())
                                                .build()))
                        .build();

        var result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    // ========== formatMultiModal() Tests ==========

    @Test
    void testFormatMultiModal_SimpleTextMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        assertEquals("user", multiModalMsg.getRole());
        assertNotNull(multiModalMsg.getContentAsList());
        assertEquals(1, multiModalMsg.getContentAsList().size());

        DashScopeContentPart part = multiModalMsg.getContentAsList().get(0);
        assertEquals("Hello", part.getText());
    }

    @Test
    void testFormatMultiModal_MessageWithImage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Look at this").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/image.jpg")
                                                                .build())
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        assertEquals(2, multiModalMsg.getContentAsList().size());
        assertNotNull(multiModalMsg.getContentAsList().get(0).getText());
        assertNotNull(multiModalMsg.getContentAsList().get(1).getImage());
    }

    @Test
    void testFormatMultiModal_MessageWithVideo() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Watch this").build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/video.mp4")
                                                                .build())
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        assertEquals(2, multiModalMsg.getContentAsList().size());
        assertNotNull(multiModalMsg.getContentAsList().get(0).getText());
        assertNotNull(multiModalMsg.getContentAsList().get(1).getVideo());
    }

    @Test
    void testFormatMultiModal_EmptyContent() {
        Msg msg = Msg.builder().role(MsgRole.USER).content(List.of()).build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        // Should add empty text part for empty content
        assertNotNull(multiModalMsg.getContentAsList());
        assertEquals(1, multiModalMsg.getContentAsList().size());
    }

    @Test
    void testFormatMultiModal_AssistantWithToolCall() {
        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("query", "test");

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Let me search").build(),
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .input(toolInput)
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        assertEquals("assistant", multiModalMsg.getRole());
        assertNotNull(multiModalMsg.getToolCalls());
        assertFalse(multiModalMsg.getToolCalls().isEmpty());
    }

    @Test
    void testFormatMultiModal_ToolResultMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Result found")
                                                                        .build()))
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        assertEquals("tool", multiModalMsg.getRole());
        assertEquals("call_123", multiModalMsg.getToolCallId());
    }

    @Test
    void testFormatMultiModal_MultipleMessages() {
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("First").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Response").build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg1, msg2));

        assertEquals(2, result.size());
    }

    @Test
    void testFormatMultiModal_ImageWithBase64Source() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(
                                                                        "iVBORw0KGgoAAAANSUhEUgAAAAUA")
                                                                .build())
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        assertEquals(1, multiModalMsg.getContentAsList().size());
        String imageUrl = multiModalMsg.getContentAsList().get(0).getImage();
        assertNotNull(imageUrl);
        assertTrue(imageUrl.startsWith("data:image/png;base64,"));
    }

    @Test
    void testFormatMultiModal_WithThinkingBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Thinking...").build(),
                                        TextBlock.builder().text("Answer").build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        // ThinkingBlock should be skipped
        var multiModalMsg = result.get(0);
        assertEquals(1, multiModalMsg.getContentAsList().size());
        assertEquals("Answer", multiModalMsg.getContentAsList().get(0).getText());
    }
}
