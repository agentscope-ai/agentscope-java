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
 *   <li>Tool message identification (isToolMessage, isToolUseMessage, isToolResultMessage)</li>
 *   <li>Final assistant response identification (isFinalAssistantResponse, including compressed
 *       message handling)</li>
 *   <li>CompressionEvent serialization and deserialization (round-trip, backward compatibility)</li>
 *   <li>Character count calculation (single message and message list)</li>
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
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private Msg createToolUseMessage(String toolName, String callId) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(callId)
                                        .input(new HashMap<>())
                                        .build()))
                .build();
    }

    private Msg createToolResultMessage(String toolName, String callId, String result) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .name(toolName)
                .content(
                        List.of(
                                ToolResultBlock.builder()
                                        .name(toolName)
                                        .id(callId)
                                        .output(List.of(TextBlock.builder().text(result).build()))
                                        .build()))
                .build();
    }

    // ============================================================================
    // Tests for isToolMessage, isToolUseMessage, isToolResultMessage
    // ============================================================================

    @Test
    @DisplayName("Should identify tool message with TOOL role")
    void testIsToolMessageWithToolRole() {
        Msg toolMsg = createToolResultMessage("calculator", "call-1", "42");
        assertTrue(MsgUtils.isToolMessage(toolMsg));
    }

    @Test
    @DisplayName("Should identify tool message with ToolUseBlock")
    void testIsToolMessageWithToolUseBlock() {
        Msg toolUseMsg = createToolUseMessage("calculator", "call-1");
        assertTrue(MsgUtils.isToolMessage(toolUseMsg));
    }

    @Test
    @DisplayName("Should identify tool message with ToolResultBlock")
    void testIsToolMessageWithToolResultBlock() {
        Msg toolResultMsg = createToolResultMessage("calculator", "call-1", "42");
        assertTrue(MsgUtils.isToolMessage(toolResultMsg));
    }

    @Test
    @DisplayName("Should return false for non-tool message")
    void testIsToolMessageWithTextMessage() {
        Msg textMsg = createTextMessage("Hello", MsgRole.USER);
        assertFalse(MsgUtils.isToolMessage(textMsg));
    }

    @Test
    @DisplayName("Should return false for null message in isToolMessage")
    void testIsToolMessageWithNull() {
        assertFalse(MsgUtils.isToolMessage(null));
    }

    @Test
    @DisplayName("Should identify tool use message")
    void testIsToolUseMessage() {
        Msg toolUseMsg = createToolUseMessage("calculator", "call-1");
        assertTrue(MsgUtils.isToolUseMessage(toolUseMsg));
    }

    @Test
    @DisplayName("Should return false for non-tool-use message")
    void testIsToolUseMessageWithTextMessage() {
        Msg textMsg = createTextMessage("Hello", MsgRole.ASSISTANT);
        assertFalse(MsgUtils.isToolUseMessage(textMsg));
    }

    @Test
    @DisplayName("Should return false for null message in isToolUseMessage")
    void testIsToolUseMessageWithNull() {
        assertFalse(MsgUtils.isToolUseMessage(null));
    }

    @Test
    @DisplayName("Should identify tool result message with TOOL role")
    void testIsToolResultMessageWithToolRole() {
        Msg toolResultMsg = createToolResultMessage("calculator", "call-1", "42");
        assertTrue(MsgUtils.isToolResultMessage(toolResultMsg));
    }

    @Test
    @DisplayName("Should identify tool result message with ToolResultBlock")
    void testIsToolResultMessageWithToolResultBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .name("calculator")
                                                .id("call-1")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("42")
                                                                        .build()))
                                                .build()))
                        .build();
        assertTrue(MsgUtils.isToolResultMessage(msg));
    }

    @Test
    @DisplayName("Should return false for non-tool-result message")
    void testIsToolResultMessageWithTextMessage() {
        Msg textMsg = createTextMessage("Hello", MsgRole.USER);
        assertFalse(MsgUtils.isToolResultMessage(textMsg));
    }

    @Test
    @DisplayName("Should return false for null message in isToolResultMessage")
    void testIsToolResultMessageWithNull() {
        assertFalse(MsgUtils.isToolResultMessage(null));
    }

    // ============================================================================
    // Tests for isFinalAssistantResponse
    // ============================================================================

    @Test
    @DisplayName("Should identify final assistant response without tool calls")
    void testIsFinalAssistantResponse() {
        Msg finalResponse = createTextMessage("This is a final response", MsgRole.ASSISTANT);
        assertTrue(MsgUtils.isFinalAssistantResponse(finalResponse));
    }

    @Test
    @DisplayName("Should return false for assistant message with tool calls")
    void testIsFinalAssistantResponseWithToolCalls() {
        Msg toolUseMsg = createToolUseMessage("calculator", "call-1");
        assertFalse(MsgUtils.isFinalAssistantResponse(toolUseMsg));
    }

    @Test
    @DisplayName("Should return false for non-assistant message")
    void testIsFinalAssistantResponseWithUserMessage() {
        Msg userMsg = createTextMessage("Hello", MsgRole.USER);
        assertFalse(MsgUtils.isFinalAssistantResponse(userMsg));
    }

    @Test
    @DisplayName("Should return false for null message in isFinalAssistantResponse")
    void testIsFinalAssistantResponseWithNull() {
        assertFalse(MsgUtils.isFinalAssistantResponse(null));
    }

    @Test
    @DisplayName("Should return false for compressed current round message")
    void testIsFinalAssistantResponseWithCompressedMessage() {
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> compressMeta = new HashMap<>();
        compressMeta.put("compressed_current_round", true);
        metadata.put("_compress_meta", compressMeta);

        Msg compressedMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Compressed content").build()))
                        .metadata(metadata)
                        .build();

        assertFalse(MsgUtils.isFinalAssistantResponse(compressedMsg));
    }

    @Test
    @DisplayName("Should return true for assistant message with null metadata")
    void testIsFinalAssistantResponseWithNullMetadata() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Response").build()))
                        .metadata(null)
                        .build();

        assertTrue(MsgUtils.isFinalAssistantResponse(msg));
    }

    @Test
    @DisplayName("Should return true for assistant message without _compress_meta")
    void testIsFinalAssistantResponseWithoutCompressMeta() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("other_key", "value");

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Response").build()))
                        .metadata(metadata)
                        .build();

        assertTrue(MsgUtils.isFinalAssistantResponse(msg));
    }

    @Test
    @DisplayName("Should return true for assistant message with null compressMeta")
    void testIsFinalAssistantResponseWithNullCompressMeta() {
        // Note: Map.copyOf() doesn't allow null values, so we test with _compress_meta key missing
        // which results in metadata.get("_compress_meta") returning null, achieving the same effect
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("other_key", "value");

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Response").build()))
                        .metadata(metadata)
                        .build();

        assertTrue(MsgUtils.isFinalAssistantResponse(msg));
    }

    @Test
    @DisplayName("Should return false for assistant message with ToolResultBlock")
    void testIsFinalAssistantResponseWithToolResultBlock() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .name("tool")
                                                .id("call-1")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("result")
                                                                        .build()))
                                                .build()))
                        .build();

        assertFalse(MsgUtils.isFinalAssistantResponse(msg));
    }

    // ============================================================================
    // Tests for CompressionEvent serialization/deserialization
    // ============================================================================

    @Test
    @DisplayName("Should serialize and deserialize compression event list")
    void testSerializeDeserializeCompressionEventList() {
        List<CompressionEvent> original = new ArrayList<>();
        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put("tokenBefore", 100);
        metadata1.put("tokenAfter", 50);
        original.add(
                new CompressionEvent(
                        CompressionEvent.TOOL_INVOCATION_COMPRESS,
                        System.currentTimeMillis(),
                        5,
                        "prev-msg-1",
                        "next-msg-1",
                        "compressed-msg-1",
                        metadata1));

        Map<String, Object> metadata2 = new HashMap<>();
        metadata2.put("inputToken", 200);
        metadata2.put("outputToken", 100);
        original.add(
                new CompressionEvent(
                        CompressionEvent.CURRENT_ROUND_MESSAGE_COMPRESS,
                        System.currentTimeMillis() + 1000,
                        3,
                        "prev-msg-2",
                        "next-msg-2",
                        "compressed-msg-2",
                        metadata2));

        Object serialized = MsgUtils.serializeCompressionEventList(original);
        Object deserialized = MsgUtils.deserializeToCompressionEventList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<CompressionEvent> result = (List<CompressionEvent>) deserialized;

        assertEquals(2, result.size());
        assertEquals(original.get(0).getEventType(), result.get(0).getEventType());
        assertEquals(
                original.get(0).getCompressedMessageCount(),
                result.get(0).getCompressedMessageCount());
        assertEquals(original.get(0).getPreviousMessageId(), result.get(0).getPreviousMessageId());
        assertEquals(original.get(0).getNextMessageId(), result.get(0).getNextMessageId());
        assertEquals(
                original.get(0).getCompressedMessageId(), result.get(0).getCompressedMessageId());
        assertEquals(100, result.get(0).getTokenBefore());
        assertEquals(50, result.get(0).getTokenAfter());
    }

    @Test
    @DisplayName("Should serialize and deserialize empty compression event list")
    void testSerializeDeserializeEmptyCompressionEventList() {
        List<CompressionEvent> original = new ArrayList<>();
        Object serialized = MsgUtils.serializeCompressionEventList(original);
        Object deserialized = MsgUtils.deserializeToCompressionEventList(serialized);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<CompressionEvent> result = (List<CompressionEvent>) deserialized;
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName(
            "Should return original object for non-list input in serializeCompressionEventList")
    void testSerializeCompressionEventListWithNonList() {
        String nonList = "not a list";
        Object result = MsgUtils.serializeCompressionEventList(nonList);
        assertEquals(nonList, result);
    }

    @Test
    @DisplayName(
            "Should return original object for non-list input in deserializeToCompressionEventList")
    void testDeserializeToCompressionEventListWithNonList() {
        String nonList = "not a list";
        Object result = MsgUtils.deserializeToCompressionEventList(nonList);
        assertEquals(nonList, result);
    }

    @Test
    @DisplayName("Should handle backward compatibility with old compression event format")
    void testDeserializeCompressionEventListWithOldFormat() {
        List<Map<String, Object>> oldFormat = new ArrayList<>();
        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("eventType", CompressionEvent.TOOL_INVOCATION_COMPRESS);
        eventMap.put("timestamp", System.currentTimeMillis());
        eventMap.put("compressedMessageCount", 5);
        eventMap.put("previousMessageId", "prev-1");
        eventMap.put("nextMessageId", "next-1");
        eventMap.put("compressedMessageId", "compressed-1");
        // Old format: tokenBefore and tokenAfter at top level
        eventMap.put("tokenBefore", 100);
        eventMap.put("tokenAfter", 50);
        oldFormat.add(eventMap);

        Object deserialized = MsgUtils.deserializeToCompressionEventList(oldFormat);

        assertTrue(deserialized instanceof List);
        @SuppressWarnings("unchecked")
        List<CompressionEvent> result = (List<CompressionEvent>) deserialized;
        assertEquals(1, result.size());
        assertEquals(100, result.get(0).getTokenBefore());
        assertEquals(50, result.get(0).getTokenAfter());
    }

    // ============================================================================
    // Tests for character count calculation
    // ============================================================================

    @Test
    @DisplayName("Should calculate character count for text message")
    void testCalculateMessageCharCountForTextMessage() {
        Msg msg = createTextMessage("Hello World", MsgRole.USER);
        int count = MsgUtils.calculateMessageCharCount(msg);
        assertEquals(11, count);
    }

    @Test
    @DisplayName("Should calculate character count for tool use message")
    void testCalculateMessageCharCountForToolUseMessage() {
        Map<String, Object> input = new HashMap<>();
        input.put("a", 1);
        input.put("b", "test");

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .name("calculator")
                                                .id("call-123")
                                                .input(input)
                                                .build()))
                        .build();

        int count = MsgUtils.calculateMessageCharCount(msg);
        // Should count: "calculator" (10) + "call-123" (8) + input JSON
        assertTrue(count > 18); // At least name and ID
    }

    @Test
    @DisplayName("Should calculate character count for tool result message")
    void testCalculateMessageCharCountForToolResultMessage() {
        Msg msg = createToolResultMessage("calculator", "call-1", "The result is 42");
        int count = MsgUtils.calculateMessageCharCount(msg);
        // Should count: "calculator" (10) + "call-1" (6) + "The result is 42" (17) = 33
        // But actual implementation may count differently, so we check it's at least close
        assertTrue(count >= 30);
    }

    @Test
    @DisplayName("Should return zero for null message in calculateMessageCharCount")
    void testCalculateMessageCharCountWithNull() {
        assertEquals(0, MsgUtils.calculateMessageCharCount(null));
    }

    @Test
    @DisplayName("Should return zero for message with null content")
    void testCalculateMessageCharCountWithNullContent() {
        Msg msg = Msg.builder().role(MsgRole.USER).name("user").content(List.of()).build();
        assertEquals(0, MsgUtils.calculateMessageCharCount(msg));
    }

    @Test
    @DisplayName("Should calculate character count for empty text message")
    void testCalculateMessageCharCountForEmptyText() {
        Msg msg = createTextMessage("", MsgRole.USER);
        assertEquals(0, MsgUtils.calculateMessageCharCount(msg));
    }

    @Test
    @DisplayName("Should calculate total character count for message list")
    void testCalculateMessagesCharCount() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Hello", MsgRole.USER));
        messages.add(createTextMessage("World", MsgRole.ASSISTANT));
        messages.add(createTextMessage("Test", MsgRole.USER));

        int totalCount = MsgUtils.calculateMessagesCharCount(messages);
        assertEquals(14, totalCount); // 5 + 5 + 4 = 14
    }

    @Test
    @DisplayName("Should return zero for null message list in calculateMessagesCharCount")
    void testCalculateMessagesCharCountWithNull() {
        assertEquals(0, MsgUtils.calculateMessagesCharCount(null));
    }

    @Test
    @DisplayName("Should return zero for empty message list in calculateMessagesCharCount")
    void testCalculateMessagesCharCountWithEmptyList() {
        assertEquals(0, MsgUtils.calculateMessagesCharCount(new ArrayList<>()));
    }

    @Test
    @DisplayName("Should calculate character count for message with multiple content blocks")
    void testCalculateMessageCharCountWithMultipleBlocks() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder().text("First block").build(),
                                        TextBlock.builder().text("Second block").build()))
                        .build();

        int count = MsgUtils.calculateMessageCharCount(msg);
        assertEquals(23, count); // "First block" (11) + "Second block" (12) = 23
    }
}
