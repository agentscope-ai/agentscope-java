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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getRole());
        assertNotNull(result.get(0).getContent());
        assertTrue(result.get(0).getContent().contains("<history>"));
        assertTrue(result.get(0).getContent().contains("</history>"));
        assertTrue(result.get(0).getContent().contains("User Alice: Hello"));
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

        List<Message> result = formatter.format(msgs);

        assertEquals(1, result.size());
        String content = result.get(0).getContent();
        assertTrue(content.contains("User Alice: Hello Bob"));
        assertTrue(content.contains("Assistant Bob: Hi Alice"));
        assertTrue(content.contains("User Charlie: Hello all"));
    }

    @Test
    void testFormatMessageWithoutName() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).getContent().contains("User Unknown:"));
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        String content = result.get(0).getContent();
        // ThinkingBlock should be skipped when formatting messages for API
        // (matching Python implementation behavior)
        assertFalse(content.contains("Let me think..."));
        assertTrue(content.contains("Assistant AI: The answer is 42"));
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

        List<Message> result = formatter.format(List.of(msg));

        // TOOL messages go to toolSeq, not conversation history
        assertEquals(1, result.size());
        assertEquals("tool", result.get(0).getRole());
        assertEquals("call_123", result.get(0).getToolCallId());
        assertEquals("15", result.get(0).getContent());
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        Message sdkMsg = result.get(0);
        assertEquals("assistant", sdkMsg.getRole());
        assertEquals("Let me calculate", sdkMsg.getContent());
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        Message sdkMsg = result.get(0);
        assertEquals("tool", sdkMsg.getRole());
        assertEquals("call_456", sdkMsg.getToolCallId());
        assertEquals("42", sdkMsg.getContent());
    }

    @Test
    void testFormatToolResultWithoutToolResultBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(List.of(TextBlock.builder().text("Result text").build()))
                        .build();

        List<Message> result = formatter.format(List.of(msg));

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

        List<Message> result = formatter.format(msgs);

        assertEquals(3, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("assistant", result.get(1).getRole());
        assertEquals("tool", result.get(2).getRole());
    }

    @Test
    void testFormatEmptyMessageList() {
        List<Message> result = formatter.format(List.of());
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertTrue(result.get(0).getContent().contains("System System: You are helpful"));
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

        assertEquals("req_123", response.getId());
        assertNotNull(response.getContent());
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
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);
        when(message.getReasoningContent()).thenReturn("Let me think...");
        when(message.getContent()).thenReturn("The answer");
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
    void testParseResponseWithFragmentToolCall() {
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        ToolCallFunction tcf = new ToolCallFunction();
        tcf.setId("call_123");
        ToolCallFunction.CallFunction cf = tcf.new CallFunction();
        cf.setName(null); // Fragment has no name
        cf.setArguments("{\"partial\":true}");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
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
        GenerationResult genResult = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        Message message = mock(Message.class);

        ToolCallFunction tcf = new ToolCallFunction();
        tcf.setId("call_123");
        ToolCallFunction.CallFunction cf = tcf.new CallFunction();
        cf.setName("test");
        cf.setArguments("invalid json {");
        tcf.setFunction(cf);

        when(genResult.getOutput()).thenReturn(output);
        when(output.getChoices()).thenReturn(List.of(choice));
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
        GenerationResult genResult = mock(GenerationResult.class);
        when(genResult.getOutput()).thenReturn(null);
        when(genResult.getRequestId()).thenReturn("req_123");

        Instant start = Instant.now();
        ChatResponse response = formatter.parseResponse(genResult, start);

        assertNotNull(response);
        assertEquals(0, response.getContent().size());
    }

    @Test
    void testParseResponseException() {
        GenerationResult genResult = mock(GenerationResult.class);
        when(genResult.getOutput()).thenThrow(new RuntimeException("Test exception"));

        Instant start = Instant.now();
        assertThrows(RuntimeException.class, () -> formatter.parseResponse(genResult, start));
    }

    @Test
    void testGetCapabilities() {
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("DashScope", capabilities.getProviderName());
        assertTrue(capabilities.supportsToolsApi());
        assertTrue(capabilities.supportsMultiAgent());
        assertTrue(capabilities.supportsVision());
        assertTrue(capabilities.getSupportedBlocks().contains(TextBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolUseBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ToolResultBlock.class));
        assertTrue(capabilities.getSupportedBlocks().contains(ThinkingBlock.class));
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        String content = result.get(0).getContent();
        assertTrue(content.contains("User Alice: First"));
        assertTrue(content.contains("User Alice: Second"));
        assertTrue(content.contains("User Alice: Third"));
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

        List<Message> result = formatter.format(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).getToolCalls());
        assertEquals(2, result.get(0).getToolCalls().size());
    }
}
