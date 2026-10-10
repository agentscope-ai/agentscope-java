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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.agui.model.AudioInputContent;
import io.agentscope.core.agui.model.DocumentInputContent;
import io.agentscope.core.agui.model.ImageInputContent;
import io.agentscope.core.agui.model.InputContent;
import io.agentscope.core.agui.model.InputContentDataSource;
import io.agentscope.core.agui.model.InputContentUrlSource;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.agui.model.VideoInputContent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
                        "msg-4",
                        "assistant",
                        new MessageContent.Text("Let me check the weather."),
                        List.of(toolCall),
                        null);

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
        assertEquals("Test message", aguiMsg.getTextContent());
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
        assertEquals("Calling tool...", aguiMsg.getTextContent());
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
        assertEquals(original.getTextContent(), converted.getTextContent());
    }

    @Test
    void testConvertToolMessageToMsg() {
        AguiMessage aguiMsg = AguiMessage.toolMessage("msg-t1", "tc-1", "Tool result here");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-t1", msg.getId());
        assertEquals(MsgRole.TOOL, msg.getRole());
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tc-1", result.getId());
        assertEquals(ToolResultState.SUCCESS, result.getState());
    }

    @Test
    void testConvertToolMessageWithEmptyContentStillProducesResultBlock() {
        AguiMessage aguiMsg = AguiMessage.toolMessage("msg-t1", "tc-1", "");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals(MsgRole.TOOL, msg.getRole());
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tc-1", result.getId());
        assertEquals(ToolResultState.SUCCESS, result.getState());
    }

    @Test
    void testConvertToolMessageWithNullContentStillProducesResultBlock() {
        AguiMessage aguiMsg = new AguiMessage("msg-t1", "tool", null, null, "tc-1");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals(MsgRole.TOOL, msg.getRole());
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tc-1", result.getId());
        assertEquals(ToolResultState.SUCCESS, result.getState());
    }

    @Test
    void testConvertToolMessageWithErrorProducesErrorState() {
        AguiMessage aguiMsg =
                new AguiMessage("msg-t1", "tool", null, null, "tc-1", "sandbox unavailable");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals(MsgRole.TOOL, msg.getRole());
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tc-1", result.getId());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("sandbox unavailable", resultText(result));
    }

    @Test
    void testConvertToolMessageWithErrorAndContentKeepsBoth() {
        AguiMessage aguiMsg =
                new AguiMessage(
                        "msg-t1",
                        "tool",
                        new MessageContent.Text("partial output"),
                        null,
                        "tc-1",
                        "sandbox unavailable");

        Msg msg = converter.toMsg(aguiMsg);

        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tc-1", result.getId());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("sandbox unavailable\npartial output", resultText(result));
    }

    @Test
    void testConvertToolMessageWithBlankErrorStaysSuccess() {
        AguiMessage aguiMsg =
                new AguiMessage(
                        "msg-t1",
                        "tool",
                        new MessageContent.Text("Tool result here"),
                        null,
                        "tc-1",
                        "   ");

        Msg msg = converter.toMsg(aguiMsg);

        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("Tool result here", resultText(result));
    }

    @Test
    void testDeserializeToolMessageWithErrorField() {
        // The AG-UI `error` field was dropped silently: the codec disables
        // FAIL_ON_UNKNOWN_PROPERTIES and the model had no binding for it.
        String json =
                "{\"id\":\"msg-t1\",\"role\":\"tool\",\"toolCallId\":\"tc-1\","
                        + "\"error\":\"sandbox unavailable\"}";

        AguiMessage aguiMsg = JsonUtils.getJsonCodec().fromJson(json, AguiMessage.class);

        assertEquals("msg-t1", aguiMsg.getId());
        assertEquals("tc-1", aguiMsg.getToolCallId());
        assertEquals("sandbox unavailable", aguiMsg.getError());

        ToolResultBlock result =
                converter.toMsg(aguiMsg).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
    }

    @Test
    void testToolMessageSerialisesNonNullContent() {
        Msg failed =
                Msg.builder()
                        .id("msg-t13")
                        .role(MsgRole.TOOL)
                        .content(ToolResultBlock.error("tc-1", "sandbox unavailable"))
                        .build();

        String json = JsonUtils.getJsonCodec().toJson(converter.toAguiMessage(failed));
        Map<?, ?> wire = JsonUtils.getJsonCodec().fromJson(json, Map.class);

        assertNotNull(wire);
        assertTrue(
                wire.get("content") instanceof String,
                "tool message content must be text or parts, never null: " + json);
        assertEquals("[ERROR] sandbox unavailable", wire.get("error"));
    }

    @Test
    void testConvertMixedToolResultsExpandsEachResult() {
        Msg msg =
                Msg.builder()
                        .id("msg-t18")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.text("ok").withIdAndName("tc-0", "lookup"),
                                ToolResultBlock.error("tc-1", "first failed"),
                                ToolResultBlock.error("tc-2", "second failed"))
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg);

        assertEquals(3, messages.size());
        assertEquals("tc-0", messages.get(0).getToolCallId());
        assertNull(messages.get(0).getError());
        assertEquals("ok", messages.get(0).getTextContent());
        assertEquals("tc-1", messages.get(1).getToolCallId());
        assertEquals("[ERROR] first failed", messages.get(1).getError());
        assertEquals("", messages.get(1).getTextContent());
        assertEquals("tc-2", messages.get(2).getToolCallId());
        assertEquals("[ERROR] second failed", messages.get(2).getError());
        assertEquals("", messages.get(2).getTextContent());
    }

    @Test
    void testConvertToolMessageWithErrorMarkerOnlyInContentStaysSuccess() {
        // Marker-only content is not an AG-UI error report; the protocol's error field is the
        // authoritative signal for a failed frontend tool.
        AguiMessage aguiMsg =
                AguiMessage.toolMessage("msg-t19", "tc-1", "[ERROR] sandbox unavailable");

        ToolResultBlock result =
                converter.toMsg(aguiMsg).getFirstContentBlock(ToolResultBlock.class);

        assertNotNull(result);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("[ERROR] sandbox unavailable", resultText(result));
    }

    @Test
    void testConvertErrorToolResultOnAssistantRoleKeepsTextInContent() {
        // A provider server tool reports its failure as an ERROR result inside the assistant
        // response. `error` belongs to the protocol's tool message, so for this role the text has
        // to stay in the content rather than move to a field the role does not define.
        Msg msg =
                Msg.builder()
                        .id("msg-t20")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolResultBlock.error(
                                        "srv-1", "web search failed: too_many_requests"))
                        .build();

        AguiMessage aguiMsg = converter.toAguiMessage(msg);

        assertEquals("assistant", aguiMsg.getRole());
        assertNull(aguiMsg.getError());
        assertEquals("[ERROR] web search failed: too_many_requests", aguiMsg.getTextContent());
    }

    @Test
    void testToAguiMessageRejectsMultipleToolResults() {
        Msg msg =
                Msg.builder()
                        .id("msg-t22")
                        .role(MsgRole.TOOL)
                        .content(ToolResultBlock.text("first"), ToolResultBlock.text("second"))
                        .build();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> converter.toAguiMessage(msg));
        assertTrue(error.getMessage().contains("toAguiMessages"));
    }

    @Test
    void testErrorToolResultRoundTripsThroughBothDirections() {
        AguiMessage original =
                new AguiMessage(
                        "msg-t4",
                        "tool",
                        new MessageContent.Text("partial output"),
                        null,
                        "tc-1",
                        "sandbox unavailable");

        AguiMessage roundTripped = converter.toAguiMessage(converter.toMsg(original));

        // The payload sent alongside the error stays part of the error text, so the round trip
        // carries both rather than dropping the payload.
        assertEquals("sandbox unavailable\npartial output", roundTripped.getError());
        assertEquals("tc-1", roundTripped.getToolCallId());

        ToolResultBlock result =
                converter.toMsg(roundTripped).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("sandbox unavailable\npartial output", resultText(result));
    }

    @Test
    void testErrorOnNonToolRoleIsIgnored() {
        // `error` is read only for tool messages; on any other role it stays a no-op rather than
        // turning the message into a failed tool result.
        AguiMessage aguiMsg =
                new AguiMessage(
                        "msg-t5",
                        "assistant",
                        new MessageContent.Text("Calling tool..."),
                        null,
                        null,
                        "sandbox unavailable");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertFalse(msg.hasContentBlocks(ToolResultBlock.class));
        assertEquals("Calling tool...", msg.getFirstContentBlock(TextBlock.class).getText());
    }

    @Test
    void testConvertToolMessageRoleCaseInsensitive() {
        AguiMessage aguiMsg =
                new AguiMessage(
                        "msg-t1",
                        "TOOL",
                        new MessageContent.Text("Tool result here"),
                        null,
                        "tc-1");

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals(MsgRole.TOOL, msg.getRole());
        assertTrue(msg.hasContentBlocks(ToolResultBlock.class));
        assertFalse(msg.hasContentBlocks(TextBlock.class));
    }

    @Test
    void testConvertMessageWithEmptyContent() {
        AguiMessage aguiMsg =
                new AguiMessage("msg-empty", "user", new MessageContent.Text(""), null, null);

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
        assertEquals("Result: 42", aguiMsg.getTextContent());
    }

    @Test
    void testToAguiMessageListExpandsMultipleToolResults() {
        Msg msg =
                Msg.builder()
                        .id("msg-tr2")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.error("tc-1", "first failed"),
                                ToolResultBlock.error("tc-2", "second failed"))
                        .build();

        List<AguiMessage> messages = converter.toAguiMessageList(List.of(msg));

        assertEquals(2, messages.size());
        assertEquals("msg-tr2", messages.get(0).getId());
        assertEquals("msg-tr2-1", messages.get(1).getId());
        assertEquals("tc-1", messages.get(0).getToolCallId());
        assertEquals("tc-2", messages.get(1).getToolCallId());
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

        assertEquals("First part\nSecond part", aguiMsg.getTextContent());
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
    void testConvertRunInputResumeToToolResultMsgUsingKnownInterruptMapping() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "int-abc", "tool_call", "suspended", "tool-call-1", null, null, Map.of());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "int-abc",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("int-abc", interrupt));

        assertEquals(1, msgs.size());
        assertEquals(MsgRole.TOOL, msgs.get(0).getRole());
        ToolResultBlock result = msgs.get(0).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tool-call-1", result.getId());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("int-abc", result.getMetadata().get("agui.interruptId"));
        assertEquals(AguiResume.STATUS_RESOLVED, result.getMetadata().get("agui.resumeStatus"));
        assertTrue(resultText(result).contains("\"approved\":true"));
    }

    @Test
    void testConvertRunInputResumeInfersToolCallIdFromGeneratedInterruptId() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                "done")))
                        .build();

        List<Msg> msgs = converter.toMsgList(input);

        ToolResultBlock result = msgs.get(0).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tool-call-1", result.getId());
        assertEquals("done", resultText(result));
    }

    @Test
    void testConvertCancelledResumeToInterruptedToolResult() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_CANCELLED,
                                                null)))
                        .build();

        List<Msg> msgs = converter.toMsgList(input);

        ToolResultBlock result = msgs.get(0).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals(ToolResultState.INTERRUPTED, result.getState());
        assertEquals("Interrupt cancelled by user", resultText(result));
    }

    @Test
    void testConvertWithInvalidRoleDefaultsToUser() {
        AguiMessage aguiMsg =
                new AguiMessage(
                        "msg-1", "unknown_role", new MessageContent.Text("Test"), null, null);

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
        AguiMessage aguiMsg =
                new AguiMessage("msg-1", "tool", new MessageContent.Text("Result"), null, null);

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
        // This used to assert null. The protocol requires `content` on a tool message and does
        // not accept null, so the field is now an empty string when the result has no output.
        assertEquals("", aguiMsg.getTextContent());
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

    // ===== Multimodal / Blocks content tests =====

    @Test
    void testConvertBlocksContentWithTextOnly() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1", List.of(new TextInputContent("Hello from blocks")));

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-1", msg.getId());
        assertEquals(MsgRole.USER, msg.getRole());
        assertTrue(msg.hasContentBlocks(TextBlock.class));
        TextBlock tb = msg.getFirstContentBlock(TextBlock.class);
        assertEquals("Hello from blocks", tb.getText());
    }

    @Test
    void testConvertBlocksContentRejectedForNonUserMessage() {
        AguiMessage aguiMsg =
                AguiMessage.blocksMessage(
                        "msg-1",
                        "tool",
                        List.of(new TextInputContent("not a valid tool content block")),
                        null,
                        "tc-1");

        assertThrows(IllegalArgumentException.class, () -> converter.toMsg(aguiMsg));
    }

    @Test
    void testConvertBlocksContentWithTextAndImage() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1",
                        List.of(
                                new TextInputContent("Describe this image"),
                                new ImageInputContent(
                                        new InputContentUrlSource("https://example.com/img.png"),
                                        null)));

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-1", msg.getId());
        assertTrue(msg.hasContentBlocks(TextBlock.class));
        assertTrue(msg.hasContentBlocks(ImageBlock.class));

        TextBlock tb = msg.getFirstContentBlock(TextBlock.class);
        assertEquals("Describe this image", tb.getText());

        ImageBlock ib = msg.getFirstContentBlock(ImageBlock.class);
        URLSource source = (URLSource) ib.getSource();
        assertEquals("https://example.com/img.png", source.getUrl());
    }

    @Test
    void testConvertBlocksContentWithAllSupportedInputTypes() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1",
                        List.of(
                                new TextInputContent("text part"),
                                new ImageInputContent(
                                        new InputContentUrlSource("https://example.com/img.png"),
                                        null),
                                new AudioInputContent(
                                        new InputContentUrlSource("https://example.com/audio.mp3"),
                                        null),
                                new VideoInputContent(
                                        new InputContentUrlSource("https://example.com/video.mp4"),
                                        null)));

        Msg msg = converter.toMsg(aguiMsg);

        assertEquals("msg-1", msg.getId());
        assertTrue(msg.hasContentBlocks(TextBlock.class));
        assertTrue(msg.hasContentBlocks(ImageBlock.class));
        assertTrue(msg.hasContentBlocks(AudioBlock.class));
        assertTrue(msg.hasContentBlocks(VideoBlock.class));
    }

    @Test
    void testNullInputContentIsRejectedWithoutDereferencingIt() throws Exception {
        // Blocks rejects null elements, so exercise this defensive guard directly.
        Method method =
                AguiMessageConverter.class.getDeclaredMethod("toContentBlock", InputContent.class);
        method.setAccessible(true);

        InvocationTargetException exception =
                assertThrows(
                        InvocationTargetException.class,
                        () -> method.invoke(converter, new Object[] {null}));

        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertEquals("Unhandled InputContent type: null", exception.getCause().getMessage());
    }

    @Test
    void testConvertDocumentInputContentIsRejectedForUrlSource() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1",
                        List.of(
                                new DocumentInputContent(
                                        new InputContentUrlSource("https://example.com/doc.pdf"),
                                        Map.of("private", "private-metadata"))));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.toMsg(aguiMsg));
        assertEquals(
                "Unsupported AG-UI input content type 'document': document input is not supported"
                        + " yet",
                exception.getMessage());
    }

    @Test
    void testConvertDocumentInputContentIsRejectedForDataSource() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1",
                        List.of(
                                new DocumentInputContent(
                                        new InputContentDataSource("dGVzdA==", "application/pdf"),
                                        Map.of("private", "private-metadata"))));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.toMsg(aguiMsg));
        assertEquals(
                "Unsupported AG-UI input content type 'document': document input is not supported"
                        + " yet",
                exception.getMessage());
    }

    @Test
    void testConvertBlocksContentWithImageBase64Source() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1",
                        List.of(
                                new ImageInputContent(
                                        new InputContentDataSource("iVBORw0KGgo=", "image/png"),
                                        null)));

        Msg msg = converter.toMsg(aguiMsg);

        assertTrue(msg.hasContentBlocks(ImageBlock.class));
        ImageBlock ib = msg.getFirstContentBlock(ImageBlock.class);
        Base64Source source = (Base64Source) ib.getSource();
        assertEquals("image/png", source.getMediaType());
        assertEquals("iVBORw0KGgo=", source.getData());
    }

    @Test
    void testConvertBlocksContentNullContent() {
        AguiMessage aguiMsg = new AguiMessage("msg-1", "user", null, null, null);

        Msg msg = converter.toMsg(aguiMsg);

        assertFalse(msg.hasContentBlocks(TextBlock.class));
        assertFalse(msg.hasContentBlocks(ImageBlock.class));
    }

    @Test
    void testConvertBlocksContentEmptyArray() {
        AguiMessage aguiMsg =
                new AguiMessage("msg-1", "user", new MessageContent.Blocks(List.of()), null, null);

        Msg msg = converter.toMsg(aguiMsg);

        // Empty blocks list should not produce any content blocks
        assertTrue(msg.getContent().isEmpty());
    }

    @Test
    void testConvertConfirmationResumeResolvedBuildsConfirmResultWithNonNullContent() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "Tool 'echo' requires user confirmation before execution",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName", "echo",
                                "toolInput", Map.of("message", "hello"),
                                "toolContent", "{\"message\":\"hello\"}",
                                "replyId", "reply-1",
                                "agentscope.interruptKind", "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        assertEquals(1, msgs.size());
        Msg confirmMsg = msgs.get(0);
        assertEquals(MsgRole.USER, confirmMsg.getRole());

        Object raw = confirmMsg.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertNotNull(raw);
        assertTrue(raw instanceof List);
        List<?> results = (List<?>) raw;
        assertEquals(1, results.size());

        ConfirmResult cr = (ConfirmResult) results.get(0);
        assertTrue(cr.isConfirmed());
        ToolUseBlock toolCall = cr.getToolCall();
        assertNotNull(toolCall);
        assertEquals("tool-call-1", toolCall.getId());
        assertEquals("echo", toolCall.getName());
        // Content must be non-null to avoid the resume validation failure.
        assertNotNull(toolCall.getContent());
        assertTrue(toolCall.getContent().contains("hello"));
        assertEquals("hello", toolCall.getInput().get("message"));
    }

    @Test
    void testConvertConfirmationResumeWithEditedArgsReplacesToolInputAndContent() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "Tool 'echo' requires user confirmation before execution",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName", "echo",
                                "toolInput", Map.of("message", "hello", "drop", true),
                                "toolContent", "{\"message\":\"hello\",\"drop\":true}",
                                "replyId", "reply-1",
                                "agentscope.interruptKind", "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of(
                                                        "approved",
                                                        true,
                                                        "editedArgs",
                                                        Map.of("message", "goodbye")))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        ConfirmResult cr =
                (ConfirmResult)
                        ((List<?>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
                                .get(0);
        ToolUseBlock toolCall = cr.getToolCall();
        assertTrue(cr.isConfirmed());
        assertEquals(Map.of("message", "goodbye"), toolCall.getInput());
        assertFalse(toolCall.getInput().containsKey("drop"));
        assertTrue(toolCall.getContent().contains("goodbye"));
        assertFalse(toolCall.getContent().contains("hello"));
    }

    @Test
    void testConvertConfirmationResumeRejectsInvalidEditedArgs() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "confirm",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName",
                                "echo",
                                "toolContent",
                                "{\"message\":\"hi\"}",
                                "agentscope.interruptKind",
                                "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true, "editedArgs", "oops"))))
                        .build();

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt)));
        assertTrue(error.getMessage().contains("editedArgs"));
    }

    @Test
    void testConvertConfirmationResumeCancelledBuildsDeniedConfirmResult() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "confirm",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName",
                                "echo",
                                "toolContent",
                                "{\"message\":\"hi\"}",
                                "agentscope.interruptKind",
                                "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_CANCELLED,
                                                null)))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        ConfirmResult cr =
                (ConfirmResult)
                        ((List<?>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
                                .get(0);
        assertFalse(cr.isConfirmed());
        assertNotNull(cr.getToolCall().getContent());
    }

    @Test
    void testConfirmationResumeRespectsExplicitApprovedFalsePayload() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "confirm",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName",
                                "echo",
                                "toolContent",
                                "{\"message\":\"hi\"}",
                                "agentscope.interruptKind",
                                "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", false))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        ConfirmResult cr =
                (ConfirmResult)
                        ((List<?>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
                                .get(0);
        assertFalse(cr.isConfirmed());
    }

    @Test
    void testConvertConfirmationResumeWithReasonCarriesDenialReason() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "confirm",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName",
                                "shell",
                                "toolContent",
                                "{\"command\":\"date\"}",
                                "agentscope.interruptKind",
                                "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of(
                                                        "approved",
                                                        false,
                                                        "reason",
                                                        "production command is not allowed"))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        ConfirmResult cr =
                (ConfirmResult)
                        ((List<?>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
                                .get(0);
        assertFalse(cr.isConfirmed());
        assertEquals("production command is not allowed", cr.getReason());
    }

    @Test
    void testConfirmationResumeWithoutApprovedFieldIsDenied() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "confirm",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName",
                                "echo",
                                "toolContent",
                                "{\"message\":\"hi\"}",
                                "agentscope.interruptKind",
                                "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("comment", "looks fine"))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        ConfirmResult cr =
                (ConfirmResult)
                        ((List<?>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
                                .get(0);
        assertFalse(cr.isConfirmed());
    }

    @Test
    void testConfirmationResumeWithNonBooleanApprovedFieldIsDenied() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "confirm",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName",
                                "echo",
                                "toolContent",
                                "{\"message\":\"hi\"}",
                                "agentscope.interruptKind",
                                "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", "true"))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        ConfirmResult cr =
                (ConfirmResult)
                        ((List<?>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS))
                                .get(0);
        assertFalse(cr.isConfirmed());
    }

    @Test
    void testResumeWithoutConfirmationInterruptStillUsesToolResultPath() {
        AguiEvent.Interrupt suspendInterrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "suspended",
                        "tool-call-1",
                        null,
                        null,
                        null);
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                "done")))
                        .build();

        List<Msg> msgs =
                converter.toMsgList(input, Map.of("reply-1:tool-call-1", suspendInterrupt));

        // Non-confirmation interrupt must still produce a TOOL-role ToolResultBlock message.
        assertEquals(MsgRole.TOOL, msgs.get(0).getRole());
        assertNotNull(msgs.get(0).getFirstContentBlock(ToolResultBlock.class));
    }

    @Test
    void testCopilotKitDualPathSkipsDuplicateToolResultFromResume() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:call_x",
                        "tool_call",
                        "Need approval",
                        "call_x",
                        null,
                        null,
                        Map.of("toolName", "requestHumanApproval"));
        String payload = "{\"approved\":true,\"reason\":\"ok\"}";
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .messages(List.of(AguiMessage.toolMessage("msg-tool", "call_x", payload)))
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:call_x",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true, "reason", "ok"))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:call_x", interrupt));

        List<String> toolResultIds =
                msgs.stream()
                        .filter(m -> m.getRole() == MsgRole.TOOL)
                        .map(m -> m.getFirstContentBlock(ToolResultBlock.class))
                        .filter(r -> r != null)
                        .map(ToolResultBlock::getId)
                        .toList();
        assertEquals(List.of("call_x"), toolResultIds);
        assertEquals(1, msgs.size());
        assertEquals(payload, resultText(msgs.get(0).getFirstContentBlock(ToolResultBlock.class)));
    }

    @Test
    void testPermissionConfirmResumeKeptWhenToolMessageAlreadyPresent() {
        AguiEvent.Interrupt interrupt =
                new AguiEvent.Interrupt(
                        "reply-1:tool-call-1",
                        "tool_call",
                        "Confirm?",
                        "tool-call-1",
                        null,
                        null,
                        Map.of(
                                "toolName", "deploy_release",
                                "toolInput", Map.of("env", "prod"),
                                "toolContent", "{\"env\":\"prod\"}",
                                "agentscope.interruptKind", "permission_confirm"));
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .messages(
                                List.of(
                                        AguiMessage.toolMessage(
                                                "msg-tool", "tool-call-1", "{\"approved\":true}")))
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "reply-1:tool-call-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("reply-1:tool-call-1", interrupt));

        assertEquals(2, msgs.size());
        assertEquals(MsgRole.TOOL, msgs.get(0).getRole());
        assertEquals(MsgRole.USER, msgs.get(1).getRole());
        assertNotNull(msgs.get(1).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS));
    }

    private static String resultText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }
}
