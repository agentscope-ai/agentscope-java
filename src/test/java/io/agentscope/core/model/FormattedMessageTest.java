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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("FormattedMessage should correctly implement equals for same object")
    void testEqualsSameObject() {
        FormattedMessage msg = FormattedMessage.builder().role("user").content("Hello").build();

        // Same object reference should be equal
        assertEquals(msg, msg, "Same object should equal itself");
    }

    @Test
    @DisplayName("FormattedMessage should correctly implement equals for equal objects")
    void testEqualsEqualObjects() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();

        FormattedMessage msg2 = FormattedMessage.builder().role("user").content("Hello").build();

        // Objects with same data should be equal
        assertEquals(msg1, msg2, "Messages with same data should be equal");
        assertEquals(msg2, msg1, "Equality should be symmetric");
    }

    @Test
    @DisplayName("FormattedMessage should correctly implement equals for different objects")
    void testEqualsDifferentObjects() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();

        FormattedMessage msg2 = FormattedMessage.builder().role("assistant").content("Hi").build();

        // Objects with different data should not be equal
        assertNotEquals(msg1, msg2, "Messages with different data should not be equal");
    }

    @Test
    @DisplayName("FormattedMessage equals should handle null")
    void testEqualsWithNull() {
        FormattedMessage msg = FormattedMessage.builder().role("user").content("Hello").build();

        // Null should not equal message
        assertNotEquals(msg, null, "Message should not equal null");
    }

    @Test
    @DisplayName("FormattedMessage equals should handle different class")
    void testEqualsWithDifferentClass() {
        FormattedMessage msg = FormattedMessage.builder().role("user").content("Hello").build();

        // Different class should not be equal
        assertNotEquals(msg, "Not a FormattedMessage", "Message should not equal string");
    }

    @Test
    @DisplayName("FormattedMessage should correctly implement hashCode")
    void testHashCode() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();

        FormattedMessage msg2 = FormattedMessage.builder().role("user").content("Hello").build();

        // Equal objects should have same hash code
        assertEquals(msg1.hashCode(), msg2.hashCode(), "Equal messages should have same hash code");
    }

    @Test
    @DisplayName("FormattedMessage hashCode should differ for different objects")
    void testHashCodeDifferent() {
        FormattedMessage msg1 = FormattedMessage.builder().role("user").content("Hello").build();

        FormattedMessage msg2 = FormattedMessage.builder().role("assistant").content("Hi").build();

        // Different objects likely have different hash codes (not guaranteed but likely)
        assertNotEquals(
                msg1.hashCode(),
                msg2.hashCode(),
                "Different messages likely have different hash codes");
    }

    @Test
    @DisplayName("FormattedMessage should implement toString correctly")
    void testToString() {
        FormattedMessage msg =
                FormattedMessage.builder().role("user").content("Hello world").build();

        String str = msg.toString();

        assertAll(
                "toString should contain message details",
                () -> assertNotNull(str, "toString should not return null"),
                () -> assertTrue(str.contains("FormattedMessage"), "Should contain class name"),
                () -> assertTrue(str.contains("role"), "Should contain role field"),
                () -> assertTrue(str.contains("user"), "Should contain role value"),
                () -> assertTrue(str.contains("content"), "Should contain content field"),
                () -> assertTrue(str.contains("Hello world"), "Should contain content value"),
                () ->
                        assertTrue(
                                str.contains("hasToolCalls"), "Should contain hasToolCalls field"));
    }

    @Test
    @DisplayName("FormattedMessage toString should indicate tool calls presence")
    void testToStringWithToolCalls() {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("name", "search");
        toolCalls.add(toolCall);

        FormattedMessage msg =
                FormattedMessage.builder()
                        .role("assistant")
                        .content("Using search tool")
                        .toolCalls(toolCalls)
                        .build();

        String str = msg.toString();
        assertTrue(str.contains("hasToolCalls=true"), "toString should show hasToolCalls=true");
    }

    @Test
    @DisplayName("FormattedMessage withProperty should add new property")
    void testWithPropertyAddNew() {
        FormattedMessage original =
                FormattedMessage.builder().role("user").content("Hello").build();

        FormattedMessage modified = original.withProperty("custom_field", "custom_value");

        // Original should be unchanged
        assertNull(
                original.asMap().get("custom_field"),
                "Original message should not have new property");

        // Modified should have new property
        assertEquals(
                "custom_value",
                modified.asMap().get("custom_field"),
                "Modified message should have new property");

        // Both should still have original properties
        assertEquals("user", original.getRole(), "Original should keep role");
        assertEquals("user", modified.getRole(), "Modified should keep role");
    }

    @Test
    @DisplayName("FormattedMessage withProperty should override existing property")
    void testWithPropertyOverride() {
        FormattedMessage original =
                FormattedMessage.builder().role("user").content("Hello").build();

        FormattedMessage modified = original.withProperty("role", "assistant");

        // Original should be unchanged
        assertEquals("user", original.getRole(), "Original role should not change");

        // Modified should have new role
        assertEquals("assistant", modified.getRole(), "Modified should have new role");
    }

    @Test
    @DisplayName("FormattedMessage withoutProperty should remove property")
    void testWithoutPropertyRemove() {
        FormattedMessage original =
                FormattedMessage.builder().role("user").content("Hello").name("TestUser").build();

        FormattedMessage modified = original.withoutProperty("name");

        // Original should be unchanged
        assertEquals("TestUser", original.getName(), "Original should still have name");

        // Modified should not have name
        assertNull(modified.getName(), "Modified should not have name");

        // Both should still have other properties
        assertEquals("user", original.getRole(), "Original should keep role");
        assertEquals("user", modified.getRole(), "Modified should keep role");
    }

    @Test
    @DisplayName("FormattedMessage withoutProperty should handle non-existent property")
    void testWithoutPropertyNonExistent() {
        FormattedMessage original =
                FormattedMessage.builder().role("user").content("Hello").build();

        // Removing non-existent property should not cause error
        FormattedMessage modified = original.withoutProperty("non_existent_field");

        // Should still have original properties
        assertEquals("user", modified.getRole(), "Should keep original role");
        assertEquals("Hello", modified.getContentAsString(), "Should keep original content");
    }

    @Test
    @DisplayName("FormattedMessage getContentAsList should return list when content is list")
    void testGetContentAsListWithList() {
        List<Map<String, Object>> contentList = new ArrayList<>();
        Map<String, Object> item1 = new HashMap<>();
        item1.put("type", "text");
        item1.put("text", "Hello");
        contentList.add(item1);

        Map<String, Object> item2 = new HashMap<>();
        item2.put("type", "text");
        item2.put("text", "World");
        contentList.add(item2);

        FormattedMessage msg = FormattedMessage.builder().role("user").content(contentList).build();

        List<Map<String, Object>> result = msg.getContentAsList();

        assertAll(
                "getContentAsList should return proper list",
                () -> assertNotNull(result, "Result should not be null"),
                () -> assertEquals(2, result.size(), "Should have 2 items"),
                () ->
                        assertEquals(
                                "text", result.get(0).get("type"), "First item type should match"),
                () ->
                        assertEquals(
                                "Hello", result.get(0).get("text"), "First item text should match"),
                () ->
                        assertEquals(
                                "World",
                                result.get(1).get("text"),
                                "Second item text should match"));
    }

    @Test
    @DisplayName(
            "FormattedMessage getContentAsList should return empty list when content is string")
    void testGetContentAsListWithString() {
        FormattedMessage msg =
                FormattedMessage.builder().role("user").content("Simple string").build();

        List<Map<String, Object>> result = msg.getContentAsList();

        assertAll(
                "getContentAsList should return empty list for string content",
                () -> assertNotNull(result, "Result should not be null"),
                () -> assertTrue(result.isEmpty(), "Result should be empty list"));
    }

    @Test
    @DisplayName("FormattedMessage getContentAsList should return empty list when content is null")
    void testGetContentAsListWithNull() {
        FormattedMessage msg = FormattedMessage.builder().role("user").build();

        List<Map<String, Object>> result = msg.getContentAsList();

        assertAll(
                "getContentAsList should return empty list for null content",
                () -> assertNotNull(result, "Result should not be null"),
                () -> assertTrue(result.isEmpty(), "Result should be empty list"));
    }

    @Test
    @DisplayName("FormattedMessage should handle null data in constructor")
    void testConstructorWithNullData() {
        FormattedMessage msg = new FormattedMessage(null);

        assertAll(
                "Message with null data should have empty properties",
                () -> assertNotNull(msg, "Message should be created"),
                () -> assertNull(msg.getRole(), "Role should be null"),
                () -> assertNull(msg.getContent(), "Content should be null"),
                () -> assertNotNull(msg.asMap(), "asMap should not be null"),
                () -> assertTrue(msg.asMap().isEmpty(), "asMap should be empty"));
    }

    @Test
    @DisplayName("FormattedMessage constructor should make defensive copy of data")
    void testConstructorDefensiveCopy() {
        Map<String, Object> originalData = new HashMap<>();
        originalData.put("role", "user");
        originalData.put("content", "Hello");

        FormattedMessage msg = new FormattedMessage(originalData);

        // Modify original data
        originalData.put("role", "assistant");
        originalData.put("content", "Modified");

        // Message should retain original values
        assertEquals("user", msg.getRole(), "Message should not be affected by external changes");
        assertEquals(
                "Hello",
                msg.getContentAsString(),
                "Content should not be affected by external changes");
    }
}
