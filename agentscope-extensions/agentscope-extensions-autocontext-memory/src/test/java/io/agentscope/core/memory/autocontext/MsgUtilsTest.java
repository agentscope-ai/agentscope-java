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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for MsgUtils.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Message list serialization and deserialization (round-trip)</li>
 *   <li>Message list map serialization and deserialization (round-trip)</li>
 *   <li>Message replacement operations</li>
 *   <li>Edge cases (null, empty lists, invalid indices)</li>
 *   <li>Different message types (text, tool use, tool result)</li>
 * </ul>
 */
@DisplayName("MsgUtils Tests")
class MsgUtilsTest {

    @Test
    @DisplayName("Should serialize and deserialize empty message list")
    void testSerializeDeserializeEmptyList() {
        List<Msg> original = new ArrayList<>();
        Object serialized = MsgUtils.serializeMsgList(original);
        Object deserialized = MsgUtils.deserializeToMsgList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) deserialized;
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should serialize and deserialize text message list")
    void testSerializeDeserializeTextMessages() {
        List<Msg> original = new ArrayList<>();
        original.add(createTextMessage("Hello", MsgRole.USER));
        original.add(createTextMessage("World", MsgRole.ASSISTANT));

        Object serialized = MsgUtils.serializeMsgList(original);
        Object deserialized = MsgUtils.deserializeToMsgList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) deserialized;
        assertEquals(2, result.size());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals("Hello", result.get(0).getTextContent());
        assertEquals(MsgRole.ASSISTANT, result.get(1).getRole());
        assertEquals("World", result.get(1).getTextContent());
    }

    @Test
    @DisplayName("Should serialize and deserialize tool use messages")
    void testSerializeDeserializeToolUseMessages() {
        List<Msg> original = new ArrayList<>();
        original.add(createToolUseMessage("calculator", "call-1"));
        original.add(createToolUseMessage("search", "call-2"));

        Object serialized = MsgUtils.serializeMsgList(original);
        Object deserialized = MsgUtils.deserializeToMsgList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) deserialized;
        assertEquals(2, result.size());
        assertEquals(MsgRole.ASSISTANT, result.get(0).getRole());
        assertTrue(result.get(0).hasContentBlocks(ToolUseBlock.class));
        List<ToolUseBlock> toolUseBlocks = result.get(0).getContentBlocks(ToolUseBlock.class);
        assertEquals("calculator", toolUseBlocks.get(0).getName());
        assertEquals("call-1", toolUseBlocks.get(0).getId());
    }

    @Test
    @DisplayName("Should serialize and deserialize tool result messages")
    void testSerializeDeserializeToolResultMessages() {
        List<Msg> original = new ArrayList<>();
        original.add(createToolResultMessage("calculator", "call-1", "42"));
        original.add(createToolResultMessage("search", "call-2", "results"));

        Object serialized = MsgUtils.serializeMsgList(original);
        Object deserialized = MsgUtils.deserializeToMsgList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) deserialized;
        assertEquals(2, result.size());
        assertEquals(MsgRole.TOOL, result.get(0).getRole());
        assertTrue(result.get(0).hasContentBlocks(ToolResultBlock.class));
        List<ToolResultBlock> toolResultBlocks =
                result.get(0).getContentBlocks(ToolResultBlock.class);
        assertEquals("calculator", toolResultBlocks.get(0).getName());
        assertEquals("call-1", toolResultBlocks.get(0).getId());
    }

    @Test
    @DisplayName("Should serialize and deserialize mixed message types")
    void testSerializeDeserializeMixedMessages() {
        List<Msg> original = new ArrayList<>();
        original.add(createTextMessage("User query", MsgRole.USER));
        original.add(createToolUseMessage("search", "call-1"));
        original.add(createToolResultMessage("search", "call-1", "Search results"));
        original.add(createTextMessage("Assistant response", MsgRole.ASSISTANT));

        Object serialized = MsgUtils.serializeMsgList(original);
        Object deserialized = MsgUtils.deserializeToMsgList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) deserialized;
        assertEquals(4, result.size());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(MsgRole.ASSISTANT, result.get(1).getRole());
        assertTrue(result.get(1).hasContentBlocks(ToolUseBlock.class));
        assertEquals(MsgRole.TOOL, result.get(2).getRole());
        assertTrue(result.get(2).hasContentBlocks(ToolResultBlock.class));
        assertEquals(MsgRole.ASSISTANT, result.get(3).getRole());
    }

    @Test
    @DisplayName("Should return original object for non-list input in serializeMsgList")
    void testSerializeMsgListWithNonList() {
        String nonList = "not a list";
        Object result = MsgUtils.serializeMsgList(nonList);
        assertEquals(nonList, result);
    }

    @Test
    @DisplayName("Should return original object for non-list input in deserializeToMsgList")
    void testDeserializeToMsgListWithNonList() {
        String nonList = "not a list";
        Object result = MsgUtils.deserializeToMsgList(nonList);
        assertEquals(nonList, result);
    }

    @Test
    @DisplayName("Should serialize and deserialize message list map")
    void testSerializeDeserializeMsgListMap() {
        Map<String, List<Msg>> original = new HashMap<>();
        List<Msg> list1 = new ArrayList<>();
        list1.add(createTextMessage("Message 1", MsgRole.USER));
        list1.add(createTextMessage("Message 2", MsgRole.ASSISTANT));
        original.put("uuid-1", list1);

        List<Msg> list2 = new ArrayList<>();
        list2.add(createToolUseMessage("tool", "call-1"));
        original.put("uuid-2", list2);

        Object serialized = MsgUtils.serializeMsgListMap(original);
        Object deserialized = MsgUtils.deserializeToMsgListMap(serialized);

        assertTrue(deserialized instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, List<Msg>> result = (Map<String, List<Msg>>) deserialized;
        assertEquals(2, result.size());
        assertTrue(result.containsKey("uuid-1"));
        assertTrue(result.containsKey("uuid-2"));
        assertEquals(2, result.get("uuid-1").size());
        assertEquals(1, result.get("uuid-2").size());
        assertEquals("Message 1", result.get("uuid-1").get(0).getTextContent());
    }

    @Test
    @DisplayName("Should serialize and deserialize empty message list map")
    void testSerializeDeserializeEmptyMsgListMap() {
        Map<String, List<Msg>> original = new HashMap<>();
        Object serialized = MsgUtils.serializeMsgListMap(original);
        Object deserialized = MsgUtils.deserializeToMsgListMap(serialized);

        assertTrue(deserialized instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, List<Msg>> result = (Map<String, List<Msg>>) deserialized;
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return original object for non-map input in serializeMsgListMap")
    void testSerializeMsgListMapWithNonMap() {
        String nonMap = "not a map";
        Object result = MsgUtils.serializeMsgListMap(nonMap);
        assertEquals(nonMap, result);
    }

    @Test
    @DisplayName("Should return original object for non-map input in deserializeToMsgListMap")
    void testDeserializeToMsgListMapWithNonMap() {
        String nonMap = "not a map";
        Object result = MsgUtils.deserializeToMsgListMap(nonMap);
        assertEquals(nonMap, result);
    }

    @Test
    @DisplayName("Should replace single message in list")
    void testReplaceSingleMessage() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        messages.add(createTextMessage("Message 2", MsgRole.ASSISTANT));
        messages.add(createTextMessage("Message 3", MsgRole.USER));

        Msg newMsg = createTextMessage("Replaced", MsgRole.ASSISTANT);
        MsgUtils.replaceMsg(messages, 1, 1, newMsg);

        assertEquals(3, messages.size());
        assertEquals("Message 1", messages.get(0).getTextContent());
        assertEquals("Replaced", messages.get(1).getTextContent());
        assertEquals("Message 3", messages.get(2).getTextContent());
    }

    @Test
    @DisplayName("Should replace range of messages in list")
    void testReplaceMessageRange() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        messages.add(createTextMessage("Message 2", MsgRole.ASSISTANT));
        messages.add(createTextMessage("Message 3", MsgRole.USER));
        messages.add(createTextMessage("Message 4", MsgRole.ASSISTANT));

        Msg newMsg = createTextMessage("Replaced Range", MsgRole.ASSISTANT);
        MsgUtils.replaceMsg(messages, 1, 2, newMsg);

        assertEquals(3, messages.size());
        assertEquals("Message 1", messages.get(0).getTextContent());
        assertEquals("Replaced Range", messages.get(1).getTextContent());
        assertEquals("Message 4", messages.get(2).getTextContent());
    }

    @Test
    @DisplayName("Should replace all messages in list")
    void testReplaceAllMessages() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        messages.add(createTextMessage("Message 2", MsgRole.ASSISTANT));
        messages.add(createTextMessage("Message 3", MsgRole.USER));

        Msg newMsg = createTextMessage("Replaced All", MsgRole.ASSISTANT);
        MsgUtils.replaceMsg(messages, 0, 2, newMsg);

        assertEquals(1, messages.size());
        assertEquals("Replaced All", messages.get(0).getTextContent());
    }

    @Test
    @DisplayName("Should handle replaceMsg with null list")
    void testReplaceMsgWithNullList() {
        Msg newMsg = createTextMessage("Test", MsgRole.USER);
        MsgUtils.replaceMsg(null, 0, 0, newMsg);
        // Should not throw exception
    }

    @Test
    @DisplayName("Should handle replaceMsg with null message")
    void testReplaceMsgWithNullMessage() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        MsgUtils.replaceMsg(messages, 0, 0, null);
        // Should not throw exception, list should remain unchanged
        assertEquals(1, messages.size());
    }

    @Test
    @DisplayName("Should handle replaceMsg with invalid start index")
    void testReplaceMsgWithInvalidStartIndex() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        Msg newMsg = createTextMessage("Test", MsgRole.USER);
        MsgUtils.replaceMsg(messages, -1, 0, newMsg);
        // Should not throw exception, list should remain unchanged
        assertEquals(1, messages.size());
    }

    @Test
    @DisplayName("Should handle replaceMsg with start index out of bounds")
    void testReplaceMsgWithStartIndexOutOfBounds() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        Msg newMsg = createTextMessage("Test", MsgRole.USER);
        MsgUtils.replaceMsg(messages, 5, 5, newMsg);
        // Should not throw exception, list should remain unchanged
        assertEquals(1, messages.size());
    }

    @Test
    @DisplayName("Should handle replaceMsg with end index less than start index")
    void testReplaceMsgWithEndIndexLessThanStart() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        messages.add(createTextMessage("Message 2", MsgRole.ASSISTANT));
        Msg newMsg = createTextMessage("Test", MsgRole.USER);
        MsgUtils.replaceMsg(messages, 1, 0, newMsg);
        // Should not throw exception, list should remain unchanged
        assertEquals(2, messages.size());
    }

    @Test
    @DisplayName("Should handle replaceMsg with end index exceeding list size")
    void testReplaceMsgWithEndIndexExceedingSize() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Message 1", MsgRole.USER));
        messages.add(createTextMessage("Message 2", MsgRole.ASSISTANT));
        Msg newMsg = createTextMessage("Replaced", MsgRole.USER);
        MsgUtils.replaceMsg(messages, 0, 10, newMsg);

        // Should replace up to the last valid index
        assertEquals(1, messages.size());
        assertEquals("Replaced", messages.get(0).getTextContent());
    }

    @Test
    @DisplayName("Should handle round-trip serialization with complex messages")
    void testRoundTripWithComplexMessages() {
        List<Msg> original = new ArrayList<>();
        original.add(createTextMessage("User query", MsgRole.USER));
        original.add(createToolUseMessage("calculator", "call-1"));
        original.add(createToolResultMessage("calculator", "call-1", "42"));
        original.add(createTextMessage("Assistant response", MsgRole.ASSISTANT));

        // Serialize and deserialize
        Object serialized = MsgUtils.serializeMsgList(original);
        Object deserialized = MsgUtils.deserializeToMsgList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) deserialized;

        // Verify all messages are preserved
        assertEquals(original.size(), result.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).getRole(), result.get(i).getRole());
        }
    }

    @Test
    @DisplayName("Should handle round-trip serialization with message list map")
    void testRoundTripWithMsgListMap() {
        Map<String, List<Msg>> original = new HashMap<>();
        List<Msg> list1 = new ArrayList<>();
        list1.add(createTextMessage("Text 1", MsgRole.USER));
        list1.add(createToolUseMessage("tool1", "call-1"));
        original.put("uuid-1", list1);

        List<Msg> list2 = new ArrayList<>();
        list2.add(createToolResultMessage("tool1", "call-1", "result"));
        original.put("uuid-2", list2);

        // Serialize and deserialize
        Object serialized = MsgUtils.serializeMsgListMap(original);
        Object deserialized = MsgUtils.deserializeToMsgListMap(serialized);

        assertTrue(deserialized instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, List<Msg>> result = (Map<String, List<Msg>>) deserialized;

        // Verify all entries are preserved
        assertEquals(original.size(), result.size());
        assertTrue(result.containsKey("uuid-1"));
        assertTrue(result.containsKey("uuid-2"));
        assertEquals(original.get("uuid-1").size(), result.get("uuid-1").size());
        assertEquals(original.get("uuid-2").size(), result.get("uuid-2").size());
    }

    @Test
    @DisplayName("Should preserve message order after serialization")
    void testPreserveMessageOrder() {
        List<Msg> original = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            original.add(createTextMessage("Message " + i, MsgRole.USER));
        }

        Object serialized = MsgUtils.serializeMsgList(original);
        Object deserialized = MsgUtils.deserializeToMsgList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<Msg> result = (List<Msg>) deserialized;

        assertEquals(original.size(), result.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).getTextContent(), result.get(i).getTextContent());
        }
    }

    // Helper methods

    private Msg createTextMessage(String text, MsgRole role) {
        return Msg.builder()
                .role(role)
                .name(role == MsgRole.USER ? "user" : "assistant")
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createToolUseMessage(String toolName, String callId) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        ToolUseBlock.builder()
                                .name(toolName)
                                .id(callId)
                                .input(new HashMap<>())
                                .build())
                .build();
    }

    private Msg createToolResultMessage(String toolName, String callId, String result) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .name(toolName)
                .content(
                        ToolResultBlock.builder()
                                .name(toolName)
                                .id(callId)
                                .output(List.of(TextBlock.builder().text(result).build()))
                                .build())
                .build();
    }
}
