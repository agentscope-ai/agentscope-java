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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormattedMessageTest {

    @Test
    void testFormattedMessageCreationAndAccess() {
        Map<String, Object> data = new HashMap<>();
        data.put("role", "user");
        data.put("content", "Hello, world!");
        data.put("name", "TestUser");

        FormattedMessage message = new FormattedMessage(data);

        assertEquals("user", message.getRole());
        assertEquals("Hello, world!", message.getContentAsString());
        assertEquals("TestUser", message.getName());
        assertFalse(message.hasToolCalls());
    }

    @Test
    void testFormattedMessageWithToolCalls() {
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_123");
        toolCall.put("type", "function");
        toolCall.put("function", Map.of("name", "test_function", "arguments", "{}"));

        Map<String, Object> data = new HashMap<>();
        data.put("role", "assistant");
        data.put("content", null);
        data.put("tool_calls", List.of(toolCall));

        FormattedMessage message = new FormattedMessage(data);

        assertEquals("assistant", message.getRole());
        assertTrue(message.hasToolCalls());
        assertEquals(1, message.getToolCalls().size());
        assertEquals("call_123", message.getToolCalls().get(0).get("id"));
    }

    @Test
    void testFormattedMessageBuilder() {
        FormattedMessage message =
                FormattedMessage.builder()
                        .role("system")
                        .content("System message")
                        .name("System")
                        .build();

        assertEquals("system", message.getRole());
        assertEquals("System message", message.getContentAsString());
        assertEquals("System", message.getName());
    }

    @Test
    void testFormattedMessageListCreation() {
        Map<String, Object> msg1 = Map.of("role", "user", "content", "Hello");
        Map<String, Object> msg2 = Map.of("role", "assistant", "content", "Hi there!");

        List<Map<String, Object>> rawMessages = List.of(msg1, msg2);
        FormattedMessageList messageList = new FormattedMessageList(rawMessages);

        assertEquals(2, messageList.size());
        assertEquals("user", messageList.get(0).getRole());
        assertEquals("assistant", messageList.get(1).getRole());
    }

    @Test
    void testFormattedMessageListFiltering() {
        Map<String, Object> msg1 = Map.of("role", "user", "content", "Hello");
        Map<String, Object> msg2 = Map.of("role", "assistant", "content", "Hi there!");
        Map<String, Object> msg3 = Map.of("role", "system", "content", "System message");

        List<Map<String, Object>> rawMessages = List.of(msg1, msg2, msg3);
        FormattedMessageList messageList = new FormattedMessageList(rawMessages);

        FormattedMessageList userMessages = messageList.filterByRole("user");
        assertEquals(1, userMessages.size());
        assertEquals("user", userMessages.get(0).getRole());

        FormattedMessageList systemMessages = messageList.filterByRole("system");
        assertEquals(1, systemMessages.size());
        assertEquals("system", systemMessages.get(0).getRole());
    }
}
