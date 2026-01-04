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

import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeChoice;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashScopeMultiAgentFormatterTest {

    private DashScopeMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DashScopeMultiAgentFormatter();
    }

    @Test
    void testFormatSimpleUserMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
        assertNotNull(result.get(0).getContent());
        assertTrue(result.get(0).getContentAsString().contains("<history>"));
        assertTrue(result.get(0).getContentAsString().contains("</history>"));
        // Multi-agent formatter uses only name, no role prefix
        assertTrue(result.get(0).getContentAsString().contains("Alice: Hello"));
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

        List<DashScopeMessage> result = formatter.format(msgs);

        assertEquals(1, result.size());
        String content = result.get(0).getContentAsString();
        // Multi-agent formatter uses only name, no role prefix
        assertTrue(content.contains("Alice: Hello Bob"));
        assertTrue(content.contains("Bob: Hi Alice"));
        assertTrue(content.contains("Charlie: Hello all"));
    }

    @Test
    void testFormatMessageWithoutName() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        // Multi-agent formatter uses only name (defaults to "Unknown"), no role prefix
        assertTrue(result.get(0).getContentAsString().contains("Unknown: Hello"));
    }

    @Test
    void testFormatMessageWithThinkingBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("AI")
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Let me think...").build(),
                                        TextBlock.builder().text("The answer is 42").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        String content = result.get(0).getContentAsString();
        // ThinkingBlock should be skipped when formatting messages for API
        assertFalse(content.contains("Let me think..."));
        // Multi-agent formatter uses only name, no role prefix
        assertTrue(content.contains("AI: The answer is 42"));
    }

    @Test
    void testFormatMessageWithToolResultBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .name("Calculator")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("add")
                                                .output(TextBlock.builder().text("15").build())
                                                .build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        // TOOL messages go to toolSeq, not conversation history
        assertEquals(1, result.size());
        assertEquals("tool", result.get(0).getRole());
        assertEquals("call_123", result.get(0).getToolCallId());
        assertEquals("15", result.get(0).getContentAsString());
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        DashScopeMessage sdkMsg = result.get(0);
        assertEquals("assistant", sdkMsg.getRole());
        assertEquals("Let me calculate", sdkMsg.getContentAsString());
        assertNotNull(sdkMsg.getToolCalls());
        assertEquals(1, sdkMsg.getToolCalls().size());
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        DashScopeMessage sdkMsg = result.get(0);
        assertEquals("tool", sdkMsg.getRole());
        assertEquals("call_456", sdkMsg.getToolCallId());
        assertEquals("42", sdkMsg.getContentAsString());
    }

    @Test
    void testFormatToolResultWithoutToolResultBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(List.of(TextBlock.builder().text("Result text").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("tool", result.get(0).getRole());
        assertNotNull(result.get(0).getToolCallId());
        assertTrue(result.get(0).getToolCallId().startsWith("tool_call_"));
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

        List<DashScopeMessage> result = formatter.format(msgs);

        assertEquals(3, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("assistant", result.get(1).getRole());
        assertEquals("tool", result.get(2).getRole());
    }

    @Test
    void testFormatEmptyMessageList() {
        List<DashScopeMessage> result = formatter.format(List.of());
        assertEquals(0, result.size());
    }

    @Test
    void testFormatSystemMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("System")
                        .content(List.of(TextBlock.builder().text("You are helpful").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("system", result.get(0).getRole());
        // System messages are output separately without history tags or name prefix
        assertEquals("You are helpful", result.get(0).getContentAsString());
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
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertEquals("req_123", response.getId());
        assertNotNull(response.getContent());
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
        when(genResult.getRequestId()).thenReturn("req_123");
        when(genResult.getUsage()).thenReturn(usage);
        when(usage.getInputTokens()).thenReturn(100);
        when(usage.getOutputTokens()).thenReturn(50);

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response.getUsage());
        assertEquals(100, response.getUsage().getInputTokens());
        assertEquals(50, response.getUsage().getOutputTokens());
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
        when(message.getReasoningContent()).thenReturn("Let me think...");
        when(message.getContentAsString()).thenReturn("The answer");
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertEquals(2, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof ThinkingBlock);
        assertEquals(
                "Let me think...", ((ThinkingBlock) response.getContent().get(0)).getThinking());
        assertTrue(response.getContent().get(1) instanceof TextBlock);
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
    void testParseResponseWithFragmentToolCall() {
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeChoice choice = mock(DashScopeChoice.class);
        DashScopeMessage message = mock(DashScopeMessage.class);

        DashScopeToolCall tcf = new DashScopeToolCall();
        tcf.setId("call_123");
        DashScopeFunction cf = new DashScopeFunction();
        cf.setName(null); // Fragment has no name
        cf.setArguments("{\"partial\":true}");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(output.getFirstChoice()).thenReturn(choice);
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(tcf));
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
    void testParseResponseWithInvalidJsonArguments() {
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        DashScopeOutput output = mock(DashScopeOutput.class);
        DashScopeChoice choice = mock(DashScopeChoice.class);
        DashScopeMessage message = mock(DashScopeMessage.class);

        DashScopeToolCall tcf = new DashScopeToolCall();
        tcf.setId("call_123");
        DashScopeFunction cf = new DashScopeFunction();
        cf.setName("test");
        cf.setArguments("invalid json {");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(output.getFirstChoice()).thenReturn(choice);
        when(choice.getMessage()).thenReturn(message);
        when(message.getToolCalls()).thenReturn(List.of(tcf));
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        // Should still create ToolUseBlock with empty input map but raw content preserved
        boolean foundToolUse = false;
        for (ContentBlock block : response.getContent()) {
            if (block instanceof ToolUseBlock) {
                foundToolUse = true;
                ToolUseBlock toolUse = (ToolUseBlock) block;
                assertEquals("call_123", toolUse.getId());
                assertEquals("test", toolUse.getName());
                assertNotNull(toolUse.getContent());
            }
        }
        assertTrue(foundToolUse);
    }

    @Test
    void testParseResponseEmptyOutput() {
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        when(genResult.getOutput()).thenReturn(null);
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response);
        assertEquals(0, response.getContent().size());
    }

    @Test
    void testParseResponseException() {
        DashScopeResponse genResult = mock(DashScopeResponse.class);
        when(genResult.getOutput()).thenThrow(new RuntimeException("Test exception"));

        Instant start = Instant.now();
        assertThrows(RuntimeException.class, () -> formatter.parseResponse(genResult, start));
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        String content = result.get(0).getContentAsString();
        // Each TextBlock has name prefix (no role prefix in multi-agent formatter)
        assertTrue(content.contains("Alice: First"));
        assertTrue(content.contains("Alice: Second"));
        assertTrue(content.contains("Alice: Third"));
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

        List<DashScopeMessage> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).getToolCalls());
        assertEquals(2, result.get(0).getToolCalls().size());
    }

    @Test
    void testFormatAgentConversationWithImages() {
        // Test multi-agent conversation with images
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Look at this").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("https://example.com/img1.png")
                                                                .build())
                                                .build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("I see it").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg1, msg2));

        // Should consolidate into single user message with multimodal content
        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationWithMultipleImages() {
        // Test conversation with multiple images
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
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

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Assistant")
                        .content(List.of(TextBlock.builder().text("They are different").build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg1, msg2));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationWithImageBase64() {
        // Test with base64 encoded image
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Check this").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data(
                                                                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                                                                .mediaType("image/png")
                                                                .build())
                                                .build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg1));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationWithAudio_LogsWarning() {
        // Audio should log warning (not supported by DashScope)
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Listen").build(),
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data("//uQxAA...")
                                                                .mediaType("audio/mp3")
                                                                .build())
                                                .build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg1));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationWithVideo_LogsWarning() {
        // Video should log warning (not supported by DashScope)
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Watch").build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/video.mp4")
                                                                .build())
                                                .build()))
                        .build();

        List<DashScopeMessage> result = formatter.format(List.of(msg1));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void testFormatAgentConversationPureText() {
        // Verify pure text still works without multimodal content
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

        List<DashScopeMessage> result = formatter.format(List.of(msg1, msg2));

        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    // ========== formatMultiModal() Tests ==========

    @Test
    void testFormatMultiModal_SimpleConversation() {
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
                        .content(List.of(TextBlock.builder().text("Hi Alice").build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg1, msg2));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        assertEquals("user", multiModalMsg.getRole());
        assertNotNull(multiModalMsg.getContentAsList());
        // Should contain history text with both messages
        assertTrue(multiModalMsg.getContentAsList().get(0).getText() != null);
        String text = multiModalMsg.getContentAsList().get(0).getText();
        assertTrue(text.contains("<history>"));
        assertTrue(text.contains("</history>"));
        assertTrue(text.contains("Alice"));
        assertTrue(text.contains("Bob"));
    }

    @Test
    void testFormatMultiModal_ConversationWithImage() {
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Look at this").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("https://example.com/cat.jpg")
                                                                .build())
                                                .build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Bob")
                        .content(List.of(TextBlock.builder().text("Nice cat!").build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg1, msg2));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        // Should contain text part and image part
        assertTrue(multiModalMsg.getContentAsList().size() >= 2);
        assertTrue(multiModalMsg.getContentAsList().get(0).getText() != null);
        assertTrue(multiModalMsg.getContentAsList().get(1).getImage() != null);
    }

    @Test
    void testFormatMultiModal_ConversationWithVideo() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
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
        assertTrue(multiModalMsg.getContentAsList().size() >= 2);
        assertTrue(multiModalMsg.getContentAsList().get(0).getText() != null);
        assertTrue(multiModalMsg.getContentAsList().get(1).getVideo() != null);
    }

    @Test
    void testFormatMultiModal_ToolSequence() {
        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("query", "test");

        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Agent")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Searching").build(),
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .input(toolInput)
                                                .build()))
                        .build();

        Msg toolMsg =
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
                                                                        .text("Found results")
                                                                        .build()))
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(assistantMsg, toolMsg));

        assertEquals(2, result.size());
        // First is assistant with tool call
        assertEquals("assistant", result.get(0).getRole());
        assertNotNull(result.get(0).getToolCalls());
        // Second is tool result
        assertEquals("tool", result.get(1).getRole());
        assertEquals("call_123", result.get(1).getToolCallId());
    }

    @Test
    void testFormatMultiModal_EmptyContent() {
        Msg msg = Msg.builder().role(MsgRole.USER).name("Alice").content(List.of()).build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        // Should contain {"text": null} for empty content
        assertEquals(1, multiModalMsg.getContentAsList().size());
        assertTrue(multiModalMsg.getContentAsList().get(0).getText() != null);
    }

    @Test
    void testFormatMultiModal_MixedConversationAndTools() {
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("User")
                        .content(List.of(TextBlock.builder().text("Search for info").build()))
                        .build();

        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("query", "info");

        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Agent")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_456")
                                                .name("search")
                                                .input(toolInput)
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(userMsg, assistantMsg));

        // User conversation and tool sequence should be separated
        assertEquals(2, result.size());
        // First is conversation
        assertEquals("user", result.get(0).getRole());
        // Second is assistant tool call
        assertEquals("assistant", result.get(1).getRole());
    }

    @Test
    void testFormatMultiModal_MultipleImages() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Compare these").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("https://example.com/img1.jpg")
                                                                .build())
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url("https://example.com/img2.jpg")
                                                                .build())
                                                .build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        var multiModalMsg = result.get(0);
        // Should have multiple content parts including text and 2 images
        assertTrue(multiModalMsg.getContentAsList().size() >= 3);
        // Count images
        long imageCount =
                multiModalMsg.getContentAsList().stream()
                        .filter(content -> content.getImage() != null)
                        .count();
        assertEquals(2, imageCount);
    }

    @Test
    void testFormatMultiModal_WithThinkingBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("Agent")
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Let me think").build(),
                                        TextBlock.builder().text("Here's my answer").build()))
                        .build();

        var result = formatter.formatMultiModal(List.of(msg));

        assertEquals(1, result.size());
        // ThinkingBlock should be skipped
        var multiModalMsg = result.get(0);
        String text = multiModalMsg.getContentAsList().get(0).getText();
        assertFalse(text.contains("Let me think"));
        assertTrue(text.contains("Here's my answer"));
    }

    @Test
    void testFormatMultiModal_ImageWithBase64() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
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
        assertTrue(multiModalMsg.getContentAsList().size() >= 2); // history text + image
        boolean hasImage = false;
        for (var content : multiModalMsg.getContentAsList()) {
            if (content.getImage() != null) {
                hasImage = true;
                String imageUrl = content.getImage();
                assertTrue(imageUrl.startsWith("data:image/png;base64,"));
            }
        }
        assertTrue(hasImage);
    }
}
