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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MsgUtilsTest {

    @Test
    void serializeAndDeserializeMessageListsAndMapsRoundTrip() {
        List<Msg> messages =
                List.of(
                        AutoContextTestSupport.userMessage("hello"),
                        AutoContextTestSupport.assistantMessage("world"));
        Map<String, List<Msg>> byUuid = Map.of("uuid", messages);

        Object serializedList = MsgUtils.serializeMsgList(messages);
        Object serializedMap = MsgUtils.serializeMsgListMap(byUuid);

        assertInstanceOf(List.class, serializedList);
        assertInstanceOf(Map.class, serializedMap);

        Object deserializedList = MsgUtils.deserializeToMsgList(serializedList);
        Object deserializedMap = MsgUtils.deserializeToMsgListMap(serializedMap);

        assertInstanceOf(List.class, deserializedList);
        assertInstanceOf(Map.class, deserializedMap);
        @SuppressWarnings("unchecked")
        List<Msg> restoredMessages = (List<Msg>) deserializedList;
        assertEquals(2, restoredMessages.size());
        assertEquals("hello", restoredMessages.get(0).getTextContent());
        assertEquals("world", restoredMessages.get(1).getTextContent());
        assertEquals(MsgRole.USER, restoredMessages.get(0).getRole());
        assertEquals(MsgRole.ASSISTANT, restoredMessages.get(1).getRole());
        @SuppressWarnings("unchecked")
        Map<String, List<Msg>> restoredMap = (Map<String, List<Msg>>) deserializedMap;
        assertEquals(1, restoredMap.size());
        assertEquals("hello", restoredMap.get("uuid").get(0).getTextContent());
        assertEquals("world", restoredMap.get("uuid").get(1).getTextContent());

        assertSame("plain", MsgUtils.serializeMsgList("plain"));
        assertSame("plain", MsgUtils.deserializeToMsgList("plain"));
        assertSame("plain", MsgUtils.serializeMsgListMap("plain"));
        assertSame("plain", MsgUtils.deserializeToMsgListMap("plain"));
    }

    @Test
    void serializeAndDeserializeCompressionEventsSupportMetadataAndLegacyFields() {
        CompressionEvent event =
                new CompressionEvent(
                        "type",
                        1L,
                        2,
                        "prev",
                        "next",
                        "compressed",
                        Map.of("tokenBefore", 6, "tokenAfter", 2, "inputToken", 3, "time", 0.5d));
        Object serialized = MsgUtils.serializeCompressionEventList(List.of(event));
        Object deserialized = MsgUtils.deserializeToCompressionEventList(serialized);

        assertInstanceOf(List.class, deserialized);
        @SuppressWarnings("unchecked")
        List<CompressionEvent> events = (List<CompressionEvent>) deserialized;
        assertEquals(1, events.size());
        assertEquals("type", events.get(0).getEventType());
        assertEquals(6, events.get(0).getTokenBefore());
        assertEquals(2, events.get(0).getTokenAfter());

        Map<String, Object> legacyEvent = new HashMap<>();
        legacyEvent.put("eventType", "legacy");
        legacyEvent.put("timestamp", 2L);
        legacyEvent.put("compressedMessageCount", 1);
        legacyEvent.put("previousMessageId", "p");
        legacyEvent.put("nextMessageId", "n");
        legacyEvent.put("compressedMessageId", "c");
        legacyEvent.put("tokenBefore", 10);
        legacyEvent.put("tokenAfter", 4);
        legacyEvent.put("inputToken", 7);
        legacyEvent.put("outputToken", 3);
        legacyEvent.put("time", 1.25d);
        List<Map<String, Object>> legacy = List.of(legacyEvent);
        @SuppressWarnings("unchecked")
        List<CompressionEvent> legacyEvents =
                (List<CompressionEvent>) MsgUtils.deserializeToCompressionEventList(legacy);
        assertEquals(1, legacyEvents.size());
        assertEquals("legacy", legacyEvents.get(0).getEventType());
        assertEquals(10, legacyEvents.get(0).getTokenBefore());
        assertEquals(4, legacyEvents.get(0).getTokenAfter());
        assertEquals(7, legacyEvents.get(0).getCompressInputToken());
        assertEquals(3, legacyEvents.get(0).getCompressOutputToken());
    }

    @Test
    void replaceMsgAndToolPredicatesHandleContentTypesAndCompressedMarkers() {
        Msg plainAssistant = AutoContextTestSupport.assistantMessage("plain");
        Msg toolUse = toolUseMessage("create_plan", "plan-1");
        Msg toolResult = toolResultMessage("plan-1", "tool output");
        List<Msg> messages = new ArrayList<>(List.of(plainAssistant, toolUse, toolResult));

        MsgUtils.replaceMsg(messages, 1, 9, AutoContextTestSupport.assistantMessage("summary"));
        assertEquals(2, messages.size());
        assertEquals("summary", messages.get(1).getTextContent());

        MsgUtils.replaceMsg(messages, -1, 0, AutoContextTestSupport.assistantMessage("ignored"));
        MsgUtils.replaceMsg(messages, 5, 6, AutoContextTestSupport.assistantMessage("ignored"));
        assertEquals(2, messages.size());

        Map<String, Object> compressedMeta = new HashMap<>();
        compressedMeta.put("_compress_meta", Map.of("compressed_current_round", true));
        Msg compressedCurrentRound =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .textContent("compressed")
                        .metadata(compressedMeta)
                        .build();

        assertTrue(MsgUtils.isToolMessage(toolUse));
        assertTrue(MsgUtils.isToolMessage(toolResult));
        assertTrue(
                MsgUtils.isToolMessage(
                        Msg.builder().role(MsgRole.TOOL).name("tool").textContent("ok").build()));
        assertTrue(MsgUtils.isToolUseMessage(toolUse));
        assertFalse(MsgUtils.isToolUseMessage(toolResult));
        assertTrue(MsgUtils.isToolResultMessage(toolResult));
        assertFalse(MsgUtils.isToolResultMessage(plainAssistant));
        assertTrue(MsgUtils.isCompressedMessage(compressedCurrentRound));
        assertTrue(MsgUtils.isFinalAssistantResponse(plainAssistant));
        assertFalse(MsgUtils.isFinalAssistantResponse(toolUse));
        assertFalse(MsgUtils.isFinalAssistantResponse(compressedCurrentRound));
    }

    @Test
    void planRelatedToolHelpersFilterMatchingCallsAndResults() {
        Msg planToolUse = toolUseMessage("create_plan", "plan-1");
        Msg otherToolUse = toolUseMessage("search_web", "search-1");
        Msg planToolResult = toolResultMessage("plan-1", "plan result");
        Msg otherToolResult = toolResultMessage("search-1", "search result");
        List<Msg> filtered =
                MsgUtils.filterPlanRelatedToolCalls(
                        List.of(
                                AutoContextTestSupport.userMessage("start"),
                                planToolUse,
                                otherToolUse,
                                planToolResult,
                                otherToolResult));

        assertTrue(MsgUtils.isPlanRelatedTool("create_plan"));
        assertFalse(MsgUtils.isPlanRelatedTool("search_web"));
        assertTrue(MsgUtils.containsPlanRelatedToolCall(planToolUse));
        assertFalse(MsgUtils.containsPlanRelatedToolCall(otherToolUse));
        assertEquals(3, filtered.size());
        assertEquals("start", filtered.get(0).getTextContent());
        assertEquals(otherToolUse, filtered.get(1));
        assertEquals(otherToolResult, filtered.get(2));
    }

    @Test
    void calculateMessageCharCountCountsTextToolUseAndToolResultContent() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("lookup")
                        .input(Map.of("city", "beijing"))
                        .content("raw")
                        .build();
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call-1")
                        .name("lookup")
                        .output(List.of(TextBlock.builder().text("sunny").build()))
                        .build();
        Msg textMessage = AutoContextTestSupport.userMessage("hello");
        Msg toolUseMessage =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(toolUseBlock)
                        .build();
        Msg toolResultMessage =
                Msg.builder().role(MsgRole.TOOL).name("tool").content(toolResultBlock).build();

        int expectedToolUseChars =
                "lookup".length()
                        + "call-1".length()
                        + JsonUtils.getJsonCodec().toJson(Map.of("city", "beijing")).length()
                        + "raw".length();
        int expectedToolResultChars = "lookup".length() + "call-1".length() + "sunny".length();

        assertEquals(5, MsgUtils.calculateMessageCharCount(textMessage));
        assertEquals(expectedToolUseChars, MsgUtils.calculateMessageCharCount(toolUseMessage));
        assertEquals(
                expectedToolResultChars, MsgUtils.calculateMessageCharCount(toolResultMessage));
        assertEquals(
                5 + expectedToolUseChars + expectedToolResultChars,
                MsgUtils.calculateMessagesCharCount(
                        List.of(textMessage, toolUseMessage, toolResultMessage)));
    }

    @Test
    void finalAssistantResponseSupportsStructuredOutputMessages() {
        Msg structuredAssistant =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(TextBlock.builder().text("done").build())
                        .metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT, Map.of("ok", true)))
                        .build();

        assertTrue(MsgUtils.isFinalAssistantResponse(structuredAssistant));
    }

    private static Msg toolUseMessage(String toolName, String toolCallId) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        ToolUseBlock.builder()
                                .id(toolCallId)
                                .name(toolName)
                                .input(Map.of("q", "value"))
                                .build())
                .build();
    }

    private static Msg toolResultMessage(String toolCallId, String text) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .name("tool")
                .content(
                        ToolResultBlock.builder()
                                .id(toolCallId)
                                .name("tool")
                                .output(
                                        List.<ContentBlock>of(
                                                TextBlock.builder().text(text).build()))
                                .build())
                .build();
    }
}
