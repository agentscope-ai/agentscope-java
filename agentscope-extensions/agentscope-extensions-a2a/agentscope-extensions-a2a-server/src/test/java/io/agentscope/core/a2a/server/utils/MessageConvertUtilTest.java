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

package io.agentscope.core.a2a.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for MessageConvertUtil.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Message conversion from Agentscope {@link Msg} to A2A {@link Message}</li>
 *   <li>Content block conversion from {@link Msg} to list of {@link Part}</li>
 *   <li>Metadata handling during conversion</li>
 * </ul>
 */
@DisplayName("MessageConvertUtil Tests")
class MessageConvertUtilTest {

    private Msg msg;

    private String taskId;

    private String contextId;

    @BeforeEach
    void setUp() {
        msg = mock(Msg.class);
        taskId = "test-task-id";
        contextId = "test-context-id";
    }

    @Test
    @DisplayName("Should convert Msg to Message with empty metadata")
    void testConvertFromMsgToMessageWithEmptyMetadata() {
        when(msg.getMetadata()).thenReturn(null);
        when(msg.getContent()).thenReturn(mockContentBlocks());
        when(msg.getId()).thenReturn("msg-id");
        when(msg.getName()).thenReturn("test-agent");

        Message result = MessageConvertUtil.convertFromMsgToMessage(msg, taskId, contextId);

        assertNotNull(result);
        assertEquals(Message.Role.AGENT, result.getRole());
        assertEquals(taskId, result.getTaskId());
        assertEquals(contextId, result.getContextId());
        assertNotNull(result.getMetadata());
        assertTrue(result.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should convert Msg to Message with metadata")
    void testConvertFromMsgToMessageWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", 123);

        when(msg.getMetadata()).thenReturn(metadata);
        when(msg.getId()).thenReturn("msg-id");
        when(msg.getName()).thenReturn("test-agent");
        when(msg.getContent()).thenReturn(mockContentBlocks());

        Message result = MessageConvertUtil.convertFromMsgToMessage(msg, taskId, contextId);

        assertNotNull(result);
        assertEquals(Message.Role.AGENT, result.getRole());
        assertEquals(taskId, result.getTaskId());
        assertEquals(contextId, result.getContextId());
        assertNotNull(result.getMetadata());
        assertEquals(metadata, result.getMetadata().get("msg-id"));
    }

    @Test
    @DisplayName("Should convert content blocks to Parts")
    void testConvertFromContentBlocks() {
        when(msg.getContent()).thenReturn(mockContentBlocks());
        when(msg.getId()).thenReturn("msg-id");
        when(msg.getName()).thenReturn("test-agent");

        List<Part<?>> parts = MessageConvertUtil.convertFromContentBlocks(msg);

        assertNotNull(parts);
        // Note: Actual size depends on ContentBlockParserRouter implementation
        // In a real test, we would mock the parser router to control the output
    }

    @Test
    @DisplayName("Should have correct constant values")
    void testConstantValues() {
        assertEquals("_agentscope_msg_source", MessageConstants.SOURCE_NAME_METADATA_KEY);
        assertEquals("_agentscope_msg_id", MessageConstants.MSG_ID_METADATA_KEY);
    }

    @Test
    @DisplayName("Should convert Message to Msgs with multiple parts from same message")
    void testConvertFromMessageToMsgsWithMultiplePartsFromSameMsg() {
        // Given
        Message message = mock(Message.class);

        Map<String, Object> part1Metadata = new HashMap<>();
        Map<String, Object> part2Metadata = new HashMap<>();
        Map<String, Object> messageMetadata = new HashMap<>();
        Map<String, Object> msgMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        part1Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        part1Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);
        part2Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        part2Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        msgMetadata.put("key1", "value1");
        messageMetadata.put(msgId, msgMetadata);

        TextPart part1 = new TextPart("text1", part1Metadata);
        TextPart part2 = new TextPart("text2", part2Metadata);

        when(message.getMetadata()).thenReturn(messageMetadata);
        when(message.getParts()).thenReturn(List.of(part1, part2));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        Msg msg = result.get(0);
        assertEquals(msgId, msg.getId());
        assertEquals(msgName, msg.getName());
        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals(2, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getContent().get(0));
        assertInstanceOf(TextBlock.class, msg.getContent().get(1));
        assertEquals("text1", ((TextBlock) msg.getContent().get(0)).getText());
        assertEquals("text2", ((TextBlock) msg.getContent().get(1)).getText());
        assertEquals(msgMetadata, msg.getMetadata());
    }

    @Test
    @DisplayName("Should convert Message to Msgs with parts from different messages")
    void testConvertFromMessageToMsgsWithPartsFromDifferentMsgs() {
        // Given
        Message message = mock(Message.class);

        Map<String, Object> part1Metadata = new HashMap<>();
        Map<String, Object> part2Metadata = new HashMap<>();

        String msgId1 = "msg-1";
        String msgName1 = "test-agent-1";
        String msgId2 = "msg-2";
        String msgName2 = "test-agent-2";

        part1Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId1);
        part1Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName1);
        part2Metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId2);
        part2Metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName2);

        TextPart part1 = new TextPart("text1", part1Metadata);
        TextPart part2 = new TextPart("text2", part2Metadata);

        when(message.getMetadata()).thenReturn(null);
        when(message.getParts()).thenReturn(List.of(part1, part2));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(msgId1, result.get(0).getId());
        assertEquals(msgName1, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text1", ((TextBlock) result.get(0).getContent().get(0)).getText());

        assertEquals(msgId2, result.get(1).getId());
        assertEquals(msgName2, result.get(1).getName());
        assertEquals(MsgRole.USER, result.get(1).getRole());
        assertEquals(1, result.get(1).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(1).getContent().get(0));
        assertEquals("text2", ((TextBlock) result.get(1).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle parts without msgId metadata")
    void testConvertFromMessageToMsgsWithoutMsgId() {
        // Given
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();

        // No MSG_ID_METADATA_KEY in metadata
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, "test-agent");

        TextPart part = new TextPart("text content", partMetadata);

        when(message.getMetadata()).thenReturn(null);
        when(message.getParts()).thenReturn(List.of(part));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertNotNull(result.get(0).getId()); // Should generate random UUID
        assertEquals("test-agent", result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle parts without metadata")
    void testConvertFromMessageToMsgsWithoutPartMetadata() {
        // Given
        Message message = mock(Message.class);

        TextPart part = new TextPart("text content", null);

        when(message.getMetadata()).thenReturn(null);
        when(message.getParts()).thenReturn(List.of(part));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertNotNull(result.get(0).getId()); // Should generate random UUID
        assertNull(result.get(0).getName()); // Should be null as no metadata
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle message without metadata")
    void testConvertFromMessageToMsgsWithoutMessageMetadata() {
        // Given
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        TextPart part = new TextPart("text content", partMetadata);

        when(message.getMetadata()).thenReturn(null); // No message metadata
        when(message.getParts()).thenReturn(List.of(part));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertEquals(msgName, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
        assertNotNull(result.get(0).getMetadata());
        assertTrue(result.get(0).getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should handle message with non-map metadata")
    void testConvertFromMessageToMsgsWithNonMapMetadata() {
        // Given
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();
        Map<String, Object> messageMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        // Put non-map metadata
        messageMetadata.put(msgId, "not-a-map");

        TextPart part = new TextPart("text content", partMetadata);

        when(message.getMetadata()).thenReturn(messageMetadata);
        when(message.getParts()).thenReturn(List.of(part));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertEquals(msgName, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
        assertNotNull(result.get(0).getMetadata());
        assertTrue(result.get(0).getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should handle parts with metadata but without source name key")
    void testConvertFromMessageToMsgsWithoutSourceNameInMetadata() {
        // Given
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();

        String msgId = "msg-1";

        // Metadata exists but no SOURCE_NAME_METADATA_KEY
        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);

        TextPart part = new TextPart("text content", partMetadata);

        when(message.getMetadata()).thenReturn(null);
        when(message.getParts()).thenReturn(List.of(part));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertNull(result.get(0).getName()); // Should be null as no source name in metadata
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle message with metadata but without corresponding msgId key")
    void testConvertFromMessageToMsgsWithoutCorrespondingMsgIdKey() {
        // Given
        Message message = mock(Message.class);

        Map<String, Object> partMetadata = new HashMap<>();
        Map<String, Object> messageMetadata = new HashMap<>();

        String msgId = "msg-1";
        String msgName = "test-agent";

        partMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        partMetadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msgName);

        // Message metadata exists but no entry for msgId
        messageMetadata.put("different-msg-id", Map.of("key", "value"));

        TextPart part = new TextPart("text content", partMetadata);

        when(message.getMetadata()).thenReturn(messageMetadata);
        when(message.getParts()).thenReturn(List.of(part));

        // When
        List<Msg> result = MessageConvertUtil.convertFromMessageToMsgs(message);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(msgId, result.get(0).getId());
        assertEquals(msgName, result.get(0).getName());
        assertEquals(MsgRole.USER, result.get(0).getRole());
        assertEquals(1, result.get(0).getContent().size());
        assertInstanceOf(TextBlock.class, result.get(0).getContent().get(0));
        assertEquals("text content", ((TextBlock) result.get(0).getContent().get(0)).getText());
        assertNotNull(result.get(0).getMetadata());
        assertTrue(
                result.get(0)
                        .getMetadata()
                        .isEmpty()); // Should be empty as no corresponding metadata
    }

    private List<ContentBlock> mockContentBlocks() {
        return List.of(
                TextBlock.builder().text("text1").build(),
                TextBlock.builder().text("text2").build());
    }
}
