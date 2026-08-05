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
        assertTrue(msg.hasContentBlocks(ToolResultBlock.class));
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

        List<Msg> msgs = converter.toMsgList(input, Map.of("int-abc", "tool-call-1"));

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
    void testPermissionResumePutsPayloadIntoConfirmMsgContent() {
        String interruptId = "6ff307dec5de48719df664ba0293c6a8:call_a18a1355f8b445bbbb562829";
        String toolCallId = "call_a18a1355f8b445bbbb562829";
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                interruptId,
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of(
                                                        "approved",
                                                        true,
                                                        "reason",
                                                        "Approved in the CopilotKit HITL demo.")),
                                        new AguiResume(
                                                "int-2:" + toolCallId + "-b",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of(
                                                        "approved",
                                                        false,
                                                        "reason",
                                                        "Denied by reviewer."))))
                        .build();
        Map<String, AguiEvent.Interrupt> interrupts =
                Map.of(
                        interruptId,
                        new AguiEvent.Interrupt(
                                interruptId,
                                "permission_required",
                                "Confirm tool",
                                toolCallId,
                                null,
                                null,
                                Map.of(
                                        "source",
                                        "permission",
                                        "toolName",
                                        "danger_tool",
                                        "toolInput",
                                        Map.of("x", 1))),
                        "int-2:" + toolCallId + "-b",
                        new AguiEvent.Interrupt(
                                "int-2:" + toolCallId + "-b",
                                "permission_required",
                                "Confirm tool",
                                toolCallId + "-b",
                                null,
                                null,
                                Map.of(
                                        "source",
                                        "permission",
                                        "toolName",
                                        "other_tool",
                                        "toolInput",
                                        Map.of())));

        List<Msg> msgs = converter.toMsgList(input, Map.of(), interrupts);

        assertEquals(1, msgs.size());
        Msg confirm = msgs.get(0);
        assertEquals("agui-resume-confirm", confirm.getId());
        assertEquals(MsgRole.USER, confirm.getRole());
        String text = confirm.getTextContent();
        assertTrue(text.contains("\"approved\":true"));
        assertTrue(text.contains("Approved in the CopilotKit HITL demo."));
        assertTrue(text.contains("\"approved\":false"));
        assertTrue(text.contains("Denied by reviewer."));
        Object metadata = confirm.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertTrue(metadata instanceof List<?>);
        assertEquals(2, ((List<?>) metadata).size());
        ConfirmResult first = (ConfirmResult) ((List<?>) metadata).get(0);
        ConfirmResult second = (ConfirmResult) ((List<?>) metadata).get(1);
        assertTrue(first.isConfirmed());
        assertEquals("danger_tool", first.getToolCall().getName());
        assertEquals(Map.of("x", 1), first.getToolCall().getInput());
        assertEquals(toolCallId, first.getToolCall().getId());
        assertFalse(second.isConfirmed());
        assertEquals("other_tool", second.getToolCall().getName());
    }

    @Test
    void testPermissionResumeDeniedCreatesUnconfirmedConfirmResult() {
        String interruptId = "reply-1:tool-1";
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                interruptId,
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", false, "reason", "too risky"))))
                        .build();
        Map<String, AguiEvent.Interrupt> interrupts =
                Map.of(
                        interruptId,
                        new AguiEvent.Interrupt(
                                interruptId,
                                "tool_call",
                                "Approve tool call: danger_tool?",
                                "tool-1",
                                null,
                                null,
                                Map.of(
                                        "source",
                                        "permission",
                                        "toolName",
                                        "danger_tool",
                                        "toolInput",
                                        Map.of("cmd", "rm"))));

        List<Msg> msgs = converter.toMsgList(input, Map.of(), interrupts);

        assertEquals(1, msgs.size());
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isConfirmed());
        assertEquals("danger_tool", results.get(0).getToolCall().getName());
        assertTrue(msgs.get(0).getTextContent().contains("too risky"));
    }

    @Test
    void testCancelledPermissionResumeCreatesUnconfirmedConfirmResult() {
        String interruptId = "reply-1:tool-1";
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                interruptId, AguiResume.STATUS_CANCELLED, null)))
                        .build();
        Map<String, AguiEvent.Interrupt> interrupts =
                Map.of(
                        interruptId,
                        new AguiEvent.Interrupt(
                                interruptId,
                                "tool_call",
                                "Approve?",
                                "tool-1",
                                null,
                                null,
                                Map.of("source", "permission", "toolName", "danger_tool")));

        List<Msg> msgs = converter.toMsgList(input, Map.of(), interrupts);

        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertFalse(results.get(0).isConfirmed());
        assertEquals("Interrupt cancelled by user", msgs.get(0).getTextContent());
    }

    @Test
    void testRejectedToolResumeBecomesInterruptedToolResult() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "int-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", false, "reason", "deny"))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("int-1", "tool-call-1"));

        ToolResultBlock result = msgs.get(0).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals(ToolResultState.INTERRUPTED, result.getState());
        assertTrue(resultText(result).contains("\"approved\":false"));
    }

    @Test
    void testResumeSkipsSynthesizedToolResultWhenClientAlreadySentToolMessage() {
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .messages(
                                List.of(
                                        AguiMessage.toolMessage(
                                                "msg-tool", "tool-call-1", "client result")))
                        .resume(
                                List.of(
                                        new AguiResume(
                                                "int-1",
                                                AguiResume.STATUS_RESOLVED,
                                                Map.of("approved", true))))
                        .build();

        List<Msg> msgs = converter.toMsgList(input, Map.of("int-1", "tool-call-1"));

        assertEquals(1, msgs.size());
        assertEquals(MsgRole.TOOL, msgs.get(0).getRole());
        ToolResultBlock result = msgs.get(0).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("tool-call-1", result.getId());
        assertEquals("client result", resultText(result));
    }

    @Test
    void testToolSuspendedResumeUsesInterruptToolCallIdOverMapping() {
        String interruptId = "reply-1:actual-tool";
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-2")
                        .resume(
                                List.of(
                                        new AguiResume(
                                                interruptId,
                                                AguiResume.STATUS_RESOLVED,
                                                "external done")))
                        .build();
        Map<String, AguiEvent.Interrupt> interrupts =
                Map.of(
                        interruptId,
                        new AguiEvent.Interrupt(
                                interruptId,
                                "tool_call",
                                "Execute externally",
                                "actual-tool",
                                null,
                                null,
                                Map.of("source", "tool_suspended", "toolName", "lookup")));

        List<Msg> msgs =
                converter.toMsgList(input, Map.of(interruptId, "stale-mapping"), interrupts);

        ToolResultBlock result = msgs.get(0).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(result);
        assertEquals("actual-tool", result.getId());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("external done", resultText(result));
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
    void testConvertDocumentInputContentIsRejectedForUrlSource() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1",
                        List.of(
                                new DocumentInputContent(
                                        new InputContentUrlSource("https://example.com/doc.pdf"),
                                        null)));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.toMsg(aguiMsg));
        assertTrue(exception.getMessage().startsWith("Unhandled InputContent type:"));
    }

    @Test
    void testConvertDocumentInputContentIsRejectedForDataSource() {
        AguiMessage aguiMsg =
                AguiMessage.userMessage(
                        "msg-1",
                        List.of(
                                new DocumentInputContent(
                                        new InputContentDataSource("dGVzdA==", "application/pdf"),
                                        null)));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> converter.toMsg(aguiMsg));
        assertTrue(exception.getMessage().startsWith("Unhandled InputContent type:"));
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

    private static String resultText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }
}
