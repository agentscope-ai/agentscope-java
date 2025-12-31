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
package io.agentscope.core.interruption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for InterruptContext class.
 *
 * Tests cover:
 * - Builder pattern with various field combinations
 * - All getter methods
 * - Default values
 * - Immutability of pendingToolCalls list
 * - toString method
 * - Boundary cases (null parameters)
 */
@DisplayName("InterruptContext Tests")
class InterruptContextTest {

    @Test
    @DisplayName("Should create context with all fields using builder")
    void testBuilderWithAllFields() {
        // Prepare test data
        Instant timestamp = Instant.now();
        Msg userMessage =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Stop processing").build())
                        .build();
        List<ToolUseBlock> toolCalls = createSampleToolCalls();

        // Build context
        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.USER)
                        .timestamp(timestamp)
                        .userMessage(userMessage)
                        .pendingToolCalls(toolCalls)
                        .build();

        // Verify all fields
        assertNotNull(context, "Context should not be null");
        assertEquals(InterruptSource.USER, context.getSource(), "Source should match");
        assertEquals(timestamp, context.getTimestamp(), "Timestamp should match");
        assertEquals(userMessage, context.getUserMessage(), "User message should match");
        assertEquals(2, context.getPendingToolCalls().size(), "Should have 2 pending tool calls");
    }

    @Test
    @DisplayName("Should create context with minimal fields using builder")
    void testBuilderWithMinimalFields() {
        // Build context with only defaults
        InterruptContext context = InterruptContext.builder().build();

        // Verify defaults
        assertNotNull(context, "Context should not be null");
        assertEquals(
                InterruptSource.USER,
                context.getSource(),
                "Default source should be USER"); // Default
        assertNotNull(context.getTimestamp(), "Timestamp should be auto-generated");
        assertNull(context.getUserMessage(), "User message should be null by default");
        assertTrue(
                context.getPendingToolCalls().isEmpty(),
                "Pending tool calls should be empty by default");
    }

    @Test
    @DisplayName("Should use different interrupt sources")
    void testDifferentInterruptSources() {
        // Test USER source
        InterruptContext userContext =
                InterruptContext.builder().source(InterruptSource.USER).build();
        assertEquals(InterruptSource.USER, userContext.getSource());

        // Test TOOL source
        InterruptContext toolContext =
                InterruptContext.builder().source(InterruptSource.TOOL).build();
        assertEquals(InterruptSource.TOOL, toolContext.getSource());

        // Test SYSTEM source
        InterruptContext systemContext =
                InterruptContext.builder().source(InterruptSource.SYSTEM).build();
        assertEquals(InterruptSource.SYSTEM, systemContext.getSource());
    }

    @Test
    @DisplayName("Should handle null user message")
    void testNullUserMessage() {
        InterruptContext context = InterruptContext.builder().userMessage(null).build();

        assertNotNull(context, "Context should not be null");
        assertNull(context.getUserMessage(), "User message should be null");
    }

    @Test
    @DisplayName("Should handle empty pending tool calls")
    void testEmptyPendingToolCalls() {
        InterruptContext context =
                InterruptContext.builder().pendingToolCalls(new ArrayList<>()).build();

        assertNotNull(context, "Context should not be null");
        assertTrue(context.getPendingToolCalls().isEmpty(), "Pending tool calls should be empty");
    }

    @Test
    @DisplayName("Should handle null pending tool calls")
    void testNullPendingToolCalls() {
        InterruptContext context = InterruptContext.builder().pendingToolCalls(null).build();

        assertNotNull(context, "Context should not be null");
        assertNotNull(
                context.getPendingToolCalls(),
                "Pending tool calls should not be null"); // Converted to empty list
        assertTrue(context.getPendingToolCalls().isEmpty(), "Pending tool calls should be empty");
    }

    @Test
    @DisplayName("Should create immutable pending tool calls list")
    void testPendingToolCallsImmutability() {
        // Create mutable list
        List<ToolUseBlock> originalList = new ArrayList<>(createSampleToolCalls());

        // Build context
        InterruptContext context =
                InterruptContext.builder().pendingToolCalls(originalList).build();

        // Get the list from context
        List<ToolUseBlock> contextList = context.getPendingToolCalls();

        // Verify list is immutable
        assertThrows(
                UnsupportedOperationException.class,
                () -> contextList.add(createToolCall("new_tool", "call-3")),
                "Should not be able to modify the list");

        // Verify modifying original list doesn't affect context
        int originalSize = contextList.size();
        originalList.add(createToolCall("another_tool", "call-4"));
        assertEquals(
                originalSize,
                contextList.size(),
                "Context list should not be affected by original list modifications");
    }

    @Test
    @DisplayName("Should set custom timestamp")
    void testCustomTimestamp() {
        Instant customTime = Instant.parse("2025-10-22T10:30:00Z");

        InterruptContext context = InterruptContext.builder().timestamp(customTime).build();

        assertEquals(customTime, context.getTimestamp(), "Timestamp should match custom value");
    }

    @Test
    @DisplayName("Should auto-generate timestamp if not set")
    void testAutoGeneratedTimestamp() {
        Instant before = Instant.now();
        InterruptContext context = InterruptContext.builder().build();
        Instant after = Instant.now();

        Instant timestamp = context.getTimestamp();
        assertNotNull(timestamp, "Timestamp should be auto-generated");
        assertFalse(timestamp.isBefore(before), "Timestamp should not be before context creation");
        assertFalse(timestamp.isAfter(after), "Timestamp should not be after context creation");
    }

    @Test
    @DisplayName("Should format toString correctly with all fields")
    void testToStringWithAllFields() {
        Msg userMessage =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test").build())
                        .build();
        List<ToolUseBlock> toolCalls = createSampleToolCalls();

        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.TOOL)
                        .userMessage(userMessage)
                        .pendingToolCalls(toolCalls)
                        .build();

        String toString = context.toString();

        // Verify toString contains key information
        assertNotNull(toString, "toString should not be null");
        assertTrue(toString.contains("InterruptContext"), "Should contain class name");
        assertTrue(toString.contains("TOOL"), "Should contain source");
        assertTrue(toString.contains("present"), "Should indicate user message is present");
        assertTrue(toString.contains("2"), "Should contain pending tool calls count");
    }

    @Test
    @DisplayName("Should format toString correctly with minimal fields")
    void testToStringWithMinimalFields() {
        InterruptContext context = InterruptContext.builder().build();

        String toString = context.toString();

        assertNotNull(toString, "toString should not be null");
        assertTrue(toString.contains("InterruptContext"), "Should contain class name");
        assertTrue(toString.contains("USER"), "Should contain default source");
        assertTrue(toString.contains("null"), "Should indicate user message is null");
        assertTrue(toString.contains("0"), "Should contain zero pending tool calls");
    }

    @Test
    @DisplayName("Should return correct pending tool calls count")
    void testGetPendingToolCallsCount() {
        // No tool calls
        InterruptContext context1 = InterruptContext.builder().build();
        assertEquals(0, context1.getPendingToolCalls().size());

        // One tool call
        InterruptContext context2 =
                InterruptContext.builder()
                        .pendingToolCalls(List.of(createToolCall("tool1", "call-1")))
                        .build();
        assertEquals(1, context2.getPendingToolCalls().size());

        // Multiple tool calls
        InterruptContext context3 =
                InterruptContext.builder().pendingToolCalls(createSampleToolCalls()).build();
        assertEquals(2, context3.getPendingToolCalls().size());
    }

    @Test
    @DisplayName("Should chain builder methods")
    void testBuilderChaining() {
        // Test builder method chaining
        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.SYSTEM)
                        .timestamp(Instant.now())
                        .userMessage(
                                Msg.builder()
                                        .name("user")
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text("Chained").build())
                                        .build())
                        .pendingToolCalls(createSampleToolCalls())
                        .build();

        assertNotNull(context, "Context should be built successfully");
        assertEquals(InterruptSource.SYSTEM, context.getSource());
        assertNotNull(context.getTimestamp());
        assertNotNull(context.getUserMessage());
        assertEquals(2, context.getPendingToolCalls().size());
    }

    // Helper methods

    private List<ToolUseBlock> createSampleToolCalls() {
        return List.of(
                createToolCall("search_tool", "call-1"),
                createToolCall("calculate_tool", "call-2"));
    }

    private ToolUseBlock createToolCall(String toolName, String callId) {
        return ToolUseBlock.builder()
                .name(toolName)
                .id(callId)
                .input(Map.of("param", "value"))
                .build();
    }
}
