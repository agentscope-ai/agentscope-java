/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FormattedMessageList Additional Coverage Tests")
class FormattedMessageListAdditionalTest {

    @Test
    @DisplayName("isEmpty should return true for empty list")
    void testIsEmptyTrue() {
        FormattedMessageList emptyList = FormattedMessageList.empty();
        assertTrue(emptyList.isEmpty());
    }

    @Test
    @DisplayName("isEmpty should return false for non-empty list")
    void testIsEmptyFalse() {
        FormattedMessage msg = FormattedMessage.builder().role("user").content("Hello").build();
        FormattedMessageList list = FormattedMessageList.of(msg);
        assertFalse(list.isEmpty());
    }

    @Test
    @DisplayName("empty should create an empty FormattedMessageList")
    void testEmpty() {
        FormattedMessageList emptyList = FormattedMessageList.empty();
        assertNotNull(emptyList);
        assertEquals(0, emptyList.size());
        assertTrue(emptyList.isEmpty());
    }

    @Test
    @DisplayName("add should add a message and return new list")
    void testAdd() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();
        FormattedMessage msg2 =
                FormattedMessage.builder().role("assistant").content("Hi there").build();

        FormattedMessageList list1 = FormattedMessageList.of(msg1);
        FormattedMessageList list2 = list1.add(msg2);

        // Original list should be unchanged
        assertEquals(1, list1.size());

        // New list should have both messages
        assertEquals(2, list2.size());
        assertEquals("user", list2.get(0).getRole());
        assertEquals("assistant", list2.get(1).getRole());
    }

    @Test
    @DisplayName("addAll should merge two lists and return new list")
    void testAddAll() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();
        FormattedMessage msg2 = FormattedMessage.builder().role("assistant").content("Hi").build();
        FormattedMessage msg3 =
                FormattedMessage.builder().role("user").content("How are you?").build();

        FormattedMessageList list1 = FormattedMessageList.of(msg1, msg2);
        FormattedMessageList list2 = FormattedMessageList.of(msg3);
        FormattedMessageList merged = list1.addAll(list2);

        // Original lists should be unchanged
        assertEquals(2, list1.size());
        assertEquals(1, list2.size());

        // Merged list should have all messages
        assertEquals(3, merged.size());
        assertEquals("user", merged.get(0).getRole());
        assertEquals("assistant", merged.get(1).getRole());
        assertEquals("user", merged.get(2).getRole());
    }

    @Test
    @DisplayName("getMessages should return defensive copy of messages")
    void testGetMessages() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();
        FormattedMessage msg2 = FormattedMessage.builder().role("assistant").content("Hi").build();

        FormattedMessageList list = FormattedMessageList.of(msg1, msg2);
        List<FormattedMessage> messages = list.getMessages();

        assertNotNull(messages);
        assertEquals(2, messages.size());

        // Should be a defensive copy
        messages.clear();
        assertEquals(2, list.size()); // Original list should be unchanged
    }

    @Test
    @DisplayName("getMessagesWithToolCalls should filter messages with tool calls")
    void testGetMessagesWithToolCalls() {
        // Message without tool calls
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();

        // Message with tool calls
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_123");
        toolCall.put("type", "function");
        toolCall.put("function", Map.of("name", "search", "arguments", "{}"));
        toolCalls.add(toolCall);

        FormattedMessage msg2 =
                FormattedMessage.builder()
                        .role("assistant")
                        .content("Using tool")
                        .toolCalls(toolCalls)
                        .build();

        // Another message without tool calls
        FormattedMessage msg3 =
                FormattedMessage.builder().role("assistant").content("Done").build();

        FormattedMessageList list = FormattedMessageList.of(msg1, msg2, msg3);
        FormattedMessageList filtered = list.getMessagesWithToolCalls();

        // Should only have the message with tool calls
        assertEquals(1, filtered.size());
        assertEquals("assistant", filtered.get(0).getRole());
        assertTrue(filtered.get(0).hasToolCalls());
    }

    @Test
    @DisplayName("getMessagesWithToolCalls should return empty list when no tool calls")
    void testGetMessagesWithToolCallsEmpty() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();
        FormattedMessage msg2 = FormattedMessage.builder().role("assistant").content("Hi").build();

        FormattedMessageList list = FormattedMessageList.of(msg1, msg2);
        FormattedMessageList filtered = list.getMessagesWithToolCalls();

        assertEquals(0, filtered.size());
        assertTrue(filtered.isEmpty());
    }

    @Test
    @DisplayName("toString should return string representation")
    void testToString() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();
        FormattedMessage msg2 = FormattedMessage.builder().role("assistant").content("Hi").build();

        FormattedMessageList list = FormattedMessageList.of(msg1, msg2);
        String str = list.toString();

        assertNotNull(str);
        assertTrue(str.contains("FormattedMessageList"));
        assertTrue(str.contains("size=2"));
    }
}
