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
package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MessageUtils Tests")
class MessageUtilsTest {

    @Test
    @DisplayName("Should skip compressed assistant messages when extracting recent tool calls")
    void testExtractRecentToolCallsSkipsCompressedAssistantMessages() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createAssistantToolUseMessage("real-tool", "real-call"));
        messages.add(createCompressedAssistantToolUseMessage("compressed-tool", "compressed-call"));

        List<ToolUseBlock> toolCalls = MessageUtils.extractRecentToolCalls(messages, "assistant");

        assertEquals(1, toolCalls.size());
        assertEquals("real-tool", toolCalls.get(0).getName());
        assertEquals("real-call", toolCalls.get(0).getId());
        assertFalse(toolCalls.stream().anyMatch(block -> "compressed-call".equals(block.getId())));
    }

    @Test
    @DisplayName("Should return empty list when messages are null or empty")
    void testExtractRecentToolCallsWithEmptyInput() {
        assertTrue(MessageUtils.extractRecentToolCalls(null, "assistant").isEmpty());
        assertTrue(MessageUtils.extractRecentToolCalls(List.of(), "assistant").isEmpty());
    }

    @Test
    @DisplayName("Should not skip assistant messages when _compress_meta is not a map")
    void testExtractRecentToolCallsDoesNotSkipAssistantMessagesWithInvalidCompressMetadata() {
        Msg message =
                createAssistantToolUseMessage(
                        "real-tool", "real-call", Map.of("_compress_meta", "not-a-map"));

        List<ToolUseBlock> toolCalls =
                MessageUtils.extractRecentToolCalls(List.of(message), "assistant");

        assertEquals(1, toolCalls.size());
        assertEquals("real-call", toolCalls.get(0).getId());
    }

    private Msg createAssistantToolUseMessage(String toolName, String callId) {
        return createAssistantToolUseMessage(toolName, callId, Map.of());
    }

    private Msg createAssistantToolUseMessage(
            String toolName, String callId, Map<String, Object> metadata) {
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
                .metadata(metadata)
                .build();
    }

    private Msg createCompressedAssistantToolUseMessage(String toolName, String callId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_compress_meta", Map.of("offloaduuid", "uuid-123"));

        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(callId)
                                        .input(new HashMap<>())
                                        .build(),
                                TextBlock.builder().text("Compressed summary").build()))
                .metadata(metadata)
                .build();
    }
}
