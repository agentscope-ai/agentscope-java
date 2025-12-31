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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Event} and {@link EventType}.
 */
class EventTest {

    @Test
    void testEventTypeValues() {
        // Verify all expected event types exist
        assertEquals(6, EventType.values().length);
        assertNotNull(EventType.valueOf("REASONING"));
        assertNotNull(EventType.valueOf("TOOL_RESULT"));
        assertNotNull(EventType.valueOf("HINT"));
        assertNotNull(EventType.valueOf("AGENT_RESULT"));
        assertNotNull(EventType.valueOf("SUMMARY"));
        assertNotNull(EventType.valueOf("ALL"));
    }

    @Test
    void testEventCreation() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Test reasoning").build()))
                        .build();

        Event event = new Event(EventType.REASONING, msg, true);

        assertEquals(EventType.REASONING, event.getType());
        assertEquals(msg, event.getMessage());
        assertTrue(event.isLast());
        assertEquals(msg.getId(), event.getMessageId());
    }

    @Test
    void testEventIsLast() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Chunk").build()))
                        .build();

        // Test intermediate chunk (isLast=false)
        Event intermediateEvent = new Event(EventType.REASONING, msg, false);
        assertFalse(intermediateEvent.isLast());

        // Test final chunk (isLast=true)
        Event finalEvent = new Event(EventType.REASONING, msg, true);
        assertTrue(finalEvent.isLast());
    }

    @Test
    void testEventGetMessageId() {
        Msg msg =
                Msg.builder()
                        .id("test-msg-id-123")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Content").build()))
                        .build();

        Event event = new Event(EventType.TOOL_RESULT, msg, true);

        assertEquals("test-msg-id-123", event.getMessageId());
        assertEquals(msg.getId(), event.getMessageId());
    }

    @Test
    void testEventToString() {
        Msg msg =
                Msg.builder()
                        .id("msg-123")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Content 1").build(),
                                        TextBlock.builder().text("Content 2").build()))
                        .build();

        Event event = new Event(EventType.REASONING, msg, true);
        String toString = event.toString();

        // Verify toString contains key information
        assertTrue(toString.contains("Event"));
        assertTrue(toString.contains("type=REASONING"));
        assertTrue(toString.contains("isLast=true"));
        assertTrue(toString.contains("msgId=msg-123"));
        assertTrue(toString.contains("contentBlocks=2"));
    }

    @Test
    void testEventWithEmptyContent() {
        Msg msg =
                Msg.builder()
                        .id("empty-msg")
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(List.of())
                        .build();

        Event event = new Event(EventType.HINT, msg, true);

        assertEquals(EventType.HINT, event.getType());
        assertEquals(0, event.getMessage().getContent().size());
        assertTrue(event.toString().contains("contentBlocks=0"));
    }

    @Test
    void testEventWithMultipleContentBlocks() {
        List<ContentBlock> content =
                List.of(
                        TextBlock.builder().text("Block 1").build(),
                        TextBlock.builder().text("Block 2").build(),
                        TextBlock.builder().text("Block 3").build());

        Msg msg = Msg.builder().name("assistant").role(MsgRole.ASSISTANT).content(content).build();

        Event event = new Event(EventType.SUMMARY, msg, false);

        assertEquals(3, event.getMessage().getContent().size());
        assertEquals(content, event.getMessage().getContent());
    }

    @Test
    void testEventDifferentTypes() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        // Test each event type
        Event reasoningEvent = new Event(EventType.REASONING, msg, true);
        assertEquals(EventType.REASONING, reasoningEvent.getType());

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, msg, true);
        assertEquals(EventType.TOOL_RESULT, toolResultEvent.getType());

        Event hintEvent = new Event(EventType.HINT, msg, true);
        assertEquals(EventType.HINT, hintEvent.getType());

        Event agentResultEvent = new Event(EventType.AGENT_RESULT, msg, true);
        assertEquals(EventType.AGENT_RESULT, agentResultEvent.getType());

        Event summaryEvent = new Event(EventType.SUMMARY, msg, true);
        assertEquals(EventType.SUMMARY, summaryEvent.getType());
    }

    @Test
    void testStreamingScenario() {
        // Simulate a streaming scenario with the same message ID
        String messageId = "streaming-msg-123";

        // First chunk
        Msg chunk1 =
                Msg.builder()
                        .id(messageId)
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("First ").build()))
                        .build();
        Event event1 = new Event(EventType.REASONING, chunk1, false);

        assertFalse(event1.isLast());
        assertEquals(messageId, event1.getMessageId());

        // Second chunk
        Msg chunk2 =
                Msg.builder()
                        .id(messageId)
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First ").build(),
                                        TextBlock.builder().text("second ").build()))
                        .build();
        Event event2 = new Event(EventType.REASONING, chunk2, false);

        assertFalse(event2.isLast());
        assertEquals(messageId, event2.getMessageId());

        // Final chunk
        Msg chunk3 =
                Msg.builder()
                        .id(messageId)
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("First ").build(),
                                        TextBlock.builder().text("second ").build(),
                                        TextBlock.builder().text("complete").build()))
                        .build();
        Event event3 = new Event(EventType.REASONING, chunk3, true);

        assertTrue(event3.isLast());
        assertEquals(messageId, event3.getMessageId());

        // All events should have the same message ID
        assertEquals(event1.getMessageId(), event2.getMessageId());
        assertEquals(event2.getMessageId(), event3.getMessageId());
    }
}
