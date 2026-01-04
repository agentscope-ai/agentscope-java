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
package io.agentscope.core.formatter.dashscope.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DashScopeMessage}
 *
 * <p>Tests getContentAsString, getContentAsList
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
class DashScopeMessageTest {

    public static final String SIMPLE_TEXT = "This is a test message.";

    @Test
    void testBuilder() {
        List<DashScopeToolCall> toolCalls =
                List.of(DashScopeToolCall.builder().id("test_tool_1").build());
        DashScopeMessage message =
                DashScopeMessage.builder()
                        .role("user")
                        .content(SIMPLE_TEXT)
                        .name("test_tool")
                        .toolCallId("test_tool_1")
                        .toolCalls(toolCalls)
                        .reasoningContent("Test reasoning content.")
                        .build();
        assertEquals("user", message.getRole());
        assertEquals(SIMPLE_TEXT, message.getContent());
        assertEquals("test_tool", message.getName());
        assertEquals("test_tool_1", message.getToolCallId());
        assertEquals(toolCalls, message.getToolCalls());
        assertEquals("Test reasoning content.", message.getReasoningContent());
    }

    @Test
    void testGetContentAsStringWithSimpleText() {
        DashScopeMessage message = DashScopeMessage.builder().content(SIMPLE_TEXT).build();
        assertEquals(SIMPLE_TEXT, message.getContentAsString());
    }

    @Test
    void testGetTextContentAsStringWithList() {
        List<DashScopeContentPart> content =
                List.of(
                        DashScopeContentPart.text(SIMPLE_TEXT),
                        DashScopeContentPart.image("https://example.com/image.jpg"));
        DashScopeMessage message = DashScopeMessage.builder().content(content).build();
        assertEquals(SIMPLE_TEXT, message.getContentAsString());
    }

    @Test
    void testGetTextContentAsStringWithNull() {
        assertNull(DashScopeMessage.builder().build().getContentAsString());
        assertNull(DashScopeMessage.builder().content(List.of()).build().getContentAsString());
    }

    @Test
    void testGetContentAsList() {
        List<DashScopeContentPart> content =
                List.of(
                        DashScopeContentPart.text(SIMPLE_TEXT),
                        DashScopeContentPart.image("https://example.com/image.jpg"));
        DashScopeMessage message = DashScopeMessage.builder().content(content).build();
        assertEquals(content, message.getContentAsList());
    }

    @Test
    void testGetContentAsListWithNull() {
        assertNull(DashScopeMessage.builder().build().getContentAsList());
    }

    @Test
    void testIsMultimodal() {
        List<DashScopeContentPart> content =
                List.of(
                        DashScopeContentPart.text(SIMPLE_TEXT),
                        DashScopeContentPart.image("https://example.com/image.jpg"));
        DashScopeMessage multimodalMessage = DashScopeMessage.builder().content(content).build();
        assertTrue(multimodalMessage.isMultimodal());
        assertFalse(DashScopeMessage.builder().content(SIMPLE_TEXT).build().isMultimodal());
        assertFalse(DashScopeMessage.builder().build().isMultimodal());
    }
}
