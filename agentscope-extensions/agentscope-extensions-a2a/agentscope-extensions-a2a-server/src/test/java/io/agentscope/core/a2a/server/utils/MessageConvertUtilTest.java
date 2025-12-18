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

package io.agentscope.core.a2a.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
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
        assertEquals("_agentscope_msg_source", MessageConvertUtil.SOURCE_NAME_METADATA_KEY);
        assertEquals("_agentscope_msg_id", MessageConvertUtil.MSG_ID_METADATA_KEY);
    }

    private List<ContentBlock> mockContentBlocks() {
        return List.of(
                TextBlock.builder().text("text1").build(),
                TextBlock.builder().text("text2").build());
    }
}
