/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for StreamContext.
 */
class StreamContextTest {

    private static final String THREAD_ID = "thread-123";
    private static final String RUN_ID = "run-456";

    private AguiAdapterConfig mockConfig;
    private StreamContext context;

    @BeforeEach
    void setUp() {
        mockConfig = mock(AguiAdapterConfig.class);
        context = new StreamContext(THREAD_ID, RUN_ID, mockConfig);
    }

    @Nested
    @DisplayName("1. Initialization & Basic Getters")
    class InitializationTests {
        @Test
        @DisplayName("Should initialize properly and return correct context variables")
        void testInitialization() {
            assertEquals(THREAD_ID, context.getThreadId());
            assertEquals(RUN_ID, context.getRunId());
            assertEquals(mockConfig, context.getConfig());
            assertTrue(context.getAndClearEmittedEvents().isEmpty());
        }
    }

    @Nested
    @DisplayName("2. Event Emission & Deferred Queue Logic")
    class EmissionAndDeferredTests {
        @Test
        @DisplayName("Should emit events and clear the buffer correctly")
        void testEmitAndClear() {
            AguiEvent event1 =
                    new AguiEvent.TextMessageStart(THREAD_ID, RUN_ID, "msg-1", "assistant");
            AguiEvent event2 =
                    new AguiEvent.TextMessageContent(THREAD_ID, RUN_ID, "msg-1", "hello");

            context.emit(event1);
            context.emit(event2);

            List<AguiEvent> emitted = context.getAndClearEmittedEvents();
            assertEquals(2, emitted.size());
            assertSame(event1, emitted.get(0));
            assertSame(event2, emitted.get(1));

            // Verify buffer is cleared
            assertTrue(context.getAndClearEmittedEvents().isEmpty());
        }

        @Test
        @DisplayName("Should defer an end event and flush it to the emission queue")
        void testDeferAndFlushSpecificEvent() {
            AguiEvent endEvent = new AguiEvent.TextMessageEnd(THREAD_ID, RUN_ID, "msg-1");
            String id = StreamContext.PREFIX_TEXT + "msg-1";

            context.deferEndEvent(id, endEvent);
            context.flushEndEvent(id);

            List<AguiEvent> emitted = context.getAndClearEmittedEvents();
            assertEquals(1, emitted.size());
            assertSame(endEvent, emitted.get(0));

            // Verify it was removed from deferred queue
            assertTrue(context.flushAllRemainingDeferred().isEmpty());
        }

        @Test
        @DisplayName("Edge Case: Flushing a non-existent deferred event should be safe")
        void testFlushNonExistentDeferredEvent() {
            assertDoesNotThrow(() -> context.flushEndEvent("non-existent-id"));
            assertTrue(context.getAndClearEmittedEvents().isEmpty());
        }

        @Test
        @DisplayName("Should flush all remaining deferred events safely")
        void testFlushAllRemainingDeferred() {
            AguiEvent event1 = new AguiEvent.TextMessageEnd(THREAD_ID, RUN_ID, "msg-1");
            AguiEvent event2 = new AguiEvent.TextMessageEnd(THREAD_ID, RUN_ID, "msg-2");

            context.deferEndEvent("id1", event1);
            context.deferEndEvent("id2", event2);

            List<AguiEvent> remaining = context.flushAllRemainingDeferred();
            assertEquals(2, remaining.size());
            assertTrue(remaining.contains(event1));
            assertTrue(remaining.contains(event2));

            // Verify the deferred map is now empty
            assertTrue(context.flushAllRemainingDeferred().isEmpty());
        }
    }

    @Nested
    @DisplayName("3. State Machine Tracking (Text, Reasoning, Tool)")
    class StateMachineTests {

        @Test
        @DisplayName("Text lifecycle: add, remove, and track finished state")
        void testTextLifecycle() {
            String msgId = "text-msg-1";

            assertFalse(context.isTextActive(msgId));

            context.addActiveText(msgId);
            assertTrue(context.isTextActive(msgId));
            assertFalse(context.isTextFinished(msgId));

            context.removeActiveText(msgId);
            assertFalse(context.isTextActive(msgId));
            assertTrue(context.isTextFinished(msgId));
        }

        @Test
        @DisplayName(
                "Edge Case: Double-remove of an active ID should not throw and keep it finished")
        void testDoubleRemoveActiveId() {
            String msgId = "reasoning-msg-1";
            context.addActiveReasoning(msgId);

            assertDoesNotThrow(
                    () -> {
                        context.removeActiveReasoning(msgId);
                        context.removeActiveReasoning(msgId); // Double remove
                    });

            assertFalse(context.isReasoningActive(msgId));
            assertTrue(context.isReasoningFinished(msgId));
        }

        @Test
        @DisplayName("Tool lifecycle: tools only have active state, no finished state tracked")
        void testToolLifecycle() {
            String callId = "call-123";

            context.addActiveTool(callId);
            assertTrue(context.isToolActive(callId));

            context.removeActiveTool(callId);
            assertFalse(context.isToolActive(callId));
        }
    }

    @Nested
    @DisplayName("4. Interleaved Flush Semantics (Integration logic)")
    class InterleavedFlushTests {

        @Test
        @DisplayName("flushAllActiveTexts should flush queued events and update state to finished")
        void testFlushAllActiveTexts() {
            String msgId1 = "msg-1";
            String msgId2 = "msg-2";

            AguiEvent endEvent1 = new AguiEvent.TextMessageEnd(THREAD_ID, RUN_ID, msgId1);
            AguiEvent endEvent2 = new AguiEvent.TextMessageEnd(THREAD_ID, RUN_ID, msgId2);

            // Setup state and deferred events
            context.addActiveText(msgId1);
            context.addActiveText(msgId2);
            context.deferEndEvent(StreamContext.PREFIX_TEXT + msgId1, endEvent1);
            context.deferEndEvent(StreamContext.PREFIX_TEXT + msgId2, endEvent2);

            // Execute
            context.flushAllActiveTexts();

            // Assert state changes
            assertFalse(context.isTextActive(msgId1));
            assertFalse(context.isTextActive(msgId2));
            assertTrue(context.isTextFinished(msgId1));
            assertTrue(context.isTextFinished(msgId2));

            // Assert events were pushed to the emission queue
            List<AguiEvent> emitted = context.getAndClearEmittedEvents();
            assertEquals(2, emitted.size());
            assertTrue(emitted.contains(endEvent1));
            assertTrue(emitted.contains(endEvent2));
        }

        @Test
        @DisplayName(
                "flushAllActiveReasonings should behave correctly when no active reasonings exist")
        void testFlushEmptyReasonings() {
            // Setup active text but NO active reasoning
            context.addActiveText("msg-1");

            assertDoesNotThrow(() -> context.flushAllActiveReasonings());

            // Text should remain unaffected
            assertTrue(context.isTextActive("msg-1"));
            assertTrue(context.getAndClearEmittedEvents().isEmpty());
        }
    }
}
