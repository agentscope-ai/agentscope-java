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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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
    void testConvertReasoningMessageToMsg() {
        AguiMessage aguiMsg = AguiMessage.reasoningMessage("msg-r1", "Think step by step");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-r1", msg.getId());
        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertTrue(msg.hasContentBlocks(ThinkingBlock.class));
        assertEquals(
                "Think step by step", msg.getFirstContentBlock(ThinkingBlock.class).getThinking());
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
    void testConvertMsgToAguiMessages() {
        Msg msg =
                Msg.builder()
                        .id("msg-5")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test message").build())
                        .build();

        List<AguiMessage> aguiMsgs = converter.toAguiMessages(msg);

        assertEquals(1, aguiMsgs.size());
        AguiMessage aguiMsg = aguiMsgs.get(0);
        assertEquals("msg-5", aguiMsg.getId());
        assertEquals("user", aguiMsg.getRole());
        assertEquals("Test message", aguiMsg.getContent());
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
        AguiMessage converted = converter.toAguiMessages(msg).get(0);

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

        List<AguiMessage> aguiMsgs = converter.toAguiMessages(msg);

        assertEquals(1, aguiMsgs.size());
        AguiMessage aguiMsg = aguiMsgs.get(0);
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

        List<AguiMessage> aguiMsgs = converter.toAguiMessages(msg);

        assertEquals(1, aguiMsgs.size());
        assertEquals("First part\nSecond part", aguiMsgs.get(0).getContent());
    }

    @Test
    void testConvertThinkingBlockToReasoningAguiMessage() {
        Msg msg =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder().thinking("Need to inspect weather").build())
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg);

        assertEquals(1, messages.size());
        assertEquals("msg-thinking", messages.get(0).getId());
        assertEquals("reasoning", messages.get(0).getRole());
        assertEquals("Need to inspect weather", messages.get(0).getContent());
        assertTrue(messages.get(0).isReasoningMessage());
    }

    @Test
    void testConvertThinkingBlockCanSkipReasoningAguiMessage() {
        Msg msg =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Hidden thought").build(),
                                        TextBlock.builder().text("Visible answer").build()))
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg, false);

        assertEquals(1, messages.size());
        assertEquals("msg-thinking", messages.get(0).getId());
        assertEquals("assistant", messages.get(0).getRole());
        assertEquals("Visible answer", messages.get(0).getContent());
    }

    @Test
    void testSplitMessagesPreserveOriginalMsgId() {
        Msg msg =
                Msg.builder()
                        .id("msg-preserve-id")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Thinking").build(),
                                        TextBlock.builder().text("Answer").build()))
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg);

        assertEquals(2, messages.size());
        assertEquals("msg-preserve-id", messages.get(0).getId());
        assertEquals("msg-preserve-id", messages.get(1).getId());
    }

    @Test
    void testConvertReActAssistantTurnToReasoningAndAssistantMessages() {
        Msg msg =
                Msg.builder()
                        .id("msg-mixed")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Need a tool").build(),
                                        TextBlock.builder().text("Let me check").build(),
                                        ToolUseBlock.builder()
                                                .id("tc-weather")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build()))
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg);

        assertEquals(3, messages.size());
        assertEquals("msg-mixed", messages.get(0).getId());
        assertEquals("reasoning", messages.get(0).getRole());
        assertEquals("Need a tool", messages.get(0).getContent());

        assertEquals("msg-mixed", messages.get(1).getId());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Let me check", messages.get(1).getContent());

        assertEquals("msg-mixed", messages.get(2).getId());
        assertEquals("assistant", messages.get(2).getRole());
        assertEquals("tc-weather", messages.get(2).getToolCalls().get(0).getId());
    }

    @Test
    void testConvertReActAssistantTurnAggregatesTextAndToolCalls() {
        Msg msg =
                Msg.builder()
                        .id("msg-aggregate")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("Need tools").build(),
                                        TextBlock.builder().text("First line").build(),
                                        TextBlock.builder().text("Second line").build(),
                                        ToolUseBlock.builder()
                                                .id("tc-weather")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("tc-calc")
                                                .name("calculate")
                                                .input(Map.of("expression", "1+1"))
                                                .build()))
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg);

        assertEquals(3, messages.size());
        assertEquals("msg-aggregate", messages.get(0).getId());
        assertEquals("reasoning", messages.get(0).getRole());
        assertEquals("Need tools", messages.get(0).getContent());

        assertEquals("msg-aggregate", messages.get(1).getId());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("First line\nSecond line", messages.get(1).getContent());

        assertEquals(2, messages.get(2).getToolCalls().size());
        assertEquals("tc-weather", messages.get(2).getToolCalls().get(0).getId());
        assertEquals("tc-calc", messages.get(2).getToolCalls().get(1).getId());
    }

    @Test
    void testConvertToolResultMsgToToolAguiMessage() {
        Msg msg =
                Msg.builder()
                        .id("msg-tool-result")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-weather")
                                        .output(TextBlock.builder().text("Rainy").build())
                                        .build())
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg);

        assertEquals(1, messages.size());
        assertEquals("msg-tool-result", messages.get(0).getId());
        assertEquals("tool", messages.get(0).getRole());
        assertEquals("Rainy", messages.get(0).getContent());
        assertEquals("tc-weather", messages.get(0).getToolCallId());
    }

    @Test
    void testToAguiMessageListFlattensSplitMessages() {
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .id("msg-user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build(),
                        Msg.builder()
                                .id("msg-assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("Thinking")
                                                        .build(),
                                                TextBlock.builder().text("Hi").build()))
                                .build());

        List<AguiMessage> messages = converter.toAguiMessageList(msgs);

        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("reasoning", messages.get(1).getRole());
        assertEquals("assistant", messages.get(2).getRole());
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

        List<AguiMessage> aguiMsgs = converter.toAguiMessages(msg);

        assertEquals(1, aguiMsgs.size());
        AguiMessage aguiMsg = aguiMsgs.get(0);
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

        List<AguiMessage> aguiMsgs = converter.toAguiMessages(msg);

        assertEquals(1, aguiMsgs.size());
        AguiMessage aguiMsg = aguiMsgs.get(0);
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
}
