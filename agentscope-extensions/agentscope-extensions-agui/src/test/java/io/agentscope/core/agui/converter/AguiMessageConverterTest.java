/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
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
        assertEquals("Test message", aguiMsg.getContent());
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
        assertEquals("Calling tool...", aguiMsg.getContent());
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
        assertEquals(original.getContent(), converted.getContent());
    }
}
