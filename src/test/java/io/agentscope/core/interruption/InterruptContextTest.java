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
package io.agentscope.core.interruption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.exception.InterruptSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for InterruptContext.
 */
@DisplayName("InterruptContext Tests")
class InterruptContextTest {

    @Test
    @DisplayName("Should create context with builder")
    void testBuilderBasic() {
        Instant timestamp = Instant.now();
        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.USER)
                        .timestamp(timestamp)
                        .build();

        assertNotNull(context);
        assertEquals(InterruptSource.USER, context.getSource());
        assertEquals(timestamp, context.getTimestamp());
        assertNull(context.getUserMessage());
        assertNotNull(context.getPendingToolCalls());
        assertTrue(context.getPendingToolCalls().isEmpty());
    }

    @Test
    @DisplayName("Should create context with user message")
    void testBuilderWithUserMessage() {
        Msg userMessage =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Cancel execution").build())
                        .build();

        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.USER)
                        .userMessage(userMessage)
                        .timestamp(Instant.now())
                        .build();

        assertNotNull(context);
        assertEquals(userMessage, context.getUserMessage());
    }

    @Test
    @DisplayName("Should create context with pending tool calls")
    void testBuilderWithPendingToolCalls() {
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather_tool")
                        .input(new HashMap<>())
                        .build());
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("search_tool")
                        .input(new HashMap<>())
                        .build());

        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.TOOL)
                        .pendingToolCalls(toolCalls)
                        .timestamp(Instant.now())
                        .build();

        assertNotNull(context);
        assertEquals(2, context.getPendingToolCalls().size());
        assertEquals("call_1", context.getPendingToolCalls().get(0).getId());
        assertEquals("call_2", context.getPendingToolCalls().get(1).getId());
    }

    @Test
    @DisplayName("Should handle all interrupt sources")
    void testAllInterruptSources() {
        for (InterruptSource source : InterruptSource.values()) {
            InterruptContext context =
                    InterruptContext.builder().source(source).timestamp(Instant.now()).build();

            assertNotNull(context);
            assertEquals(source, context.getSource());
        }
    }

    @Test
    @DisplayName("Should create context with all fields")
    void testBuilderWithAllFields() {
        Msg userMessage =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Stop").build())
                        .build();

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("test_tool")
                        .input(new HashMap<>())
                        .build());

        Instant timestamp = Instant.now();

        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.USER)
                        .userMessage(userMessage)
                        .pendingToolCalls(toolCalls)
                        .timestamp(timestamp)
                        .build();

        assertNotNull(context);
        assertEquals(InterruptSource.USER, context.getSource());
        assertEquals(userMessage, context.getUserMessage());
        assertEquals(1, context.getPendingToolCalls().size());
        assertEquals(timestamp, context.getTimestamp());
    }

    @Test
    @DisplayName("Should handle null user message")
    void testNullUserMessage() {
        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.SYSTEM)
                        .userMessage(null)
                        .timestamp(Instant.now())
                        .build();

        assertNotNull(context);
        assertNull(context.getUserMessage());
    }

    @Test
    @DisplayName("Should handle null pending tool calls")
    void testNullPendingToolCalls() {
        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.TOOL)
                        .pendingToolCalls(null)
                        .timestamp(Instant.now())
                        .build();

        assertNotNull(context);
        assertNotNull(context.getPendingToolCalls());
        assertTrue(context.getPendingToolCalls().isEmpty());
    }

    @Test
    @DisplayName("Should handle empty pending tool calls")
    void testEmptyPendingToolCalls() {
        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.USER)
                        .pendingToolCalls(new ArrayList<>())
                        .timestamp(Instant.now())
                        .build();

        assertNotNull(context);
        assertNotNull(context.getPendingToolCalls());
        assertTrue(context.getPendingToolCalls().isEmpty());
    }

    @Test
    @DisplayName("Should get correct timestamp")
    void testTimestamp() {
        Instant before = Instant.now();
        InterruptContext context =
                InterruptContext.builder().source(InterruptSource.USER).timestamp(before).build();
        Instant after = Instant.now();

        assertNotNull(context.getTimestamp());
        assertFalse(context.getTimestamp().isBefore(before));
        assertFalse(context.getTimestamp().isAfter(after));
    }

    @Test
    @DisplayName("Should create builder from builder")
    void testBuilderChaining() {
        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.USER)
                        .timestamp(Instant.now())
                        .userMessage(null)
                        .pendingToolCalls(null)
                        .build();

        assertNotNull(context);
    }

    @Test
    @DisplayName("Should generate toString representation")
    void testToString() {
        Msg userMessage =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test message").build())
                        .build();

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("test_tool")
                        .input(new HashMap<>())
                        .build());

        Instant timestamp = Instant.now();

        InterruptContext context =
                InterruptContext.builder()
                        .source(InterruptSource.TOOL)
                        .userMessage(userMessage)
                        .pendingToolCalls(toolCalls)
                        .timestamp(timestamp)
                        .build();

        String result = context.toString();
        assertNotNull(result);
        assertTrue(result.contains("InterruptContext"));
        assertTrue(result.contains("TOOL"));
    }
}
