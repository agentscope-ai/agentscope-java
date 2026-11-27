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
package io.agentscope.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SessionStreamEventTest {
    private final SessionKey session = new SessionKey("tenant", "session");

    @Test
    void deltasAreOptInPerConnectionAndMapToTypedEventsWithoutACursor() {
        var message =
                live(
                        "message.delta",
                        Map.of("messageId", "message-1", "blockId", "text-1", "text", "hi"));
        var defaults = EventStreamOptions.defaults();
        var enabled =
                EventStreamOptions.builder()
                        .after("cursor-1")
                        .deltas(EventDeltaType.MESSAGE)
                        .build();

        assertFalse(defaults.accepts(message));
        assertTrue(enabled.accepts(message));
        assertEquals("cursor-1", enabled.cursor());
        var delta =
                assertInstanceOf(
                        SessionStreamEvent.EventDelta.class, SessionStreamEvent.from(message));
        assertEquals("message-1", delta.eventId());
        assertEquals(EventDeltaType.MESSAGE, delta.deltaType());
        assertEquals("text-1", delta.blockId());
        assertEquals("hi", delta.data().path("text").asText());
    }

    @Test
    void toolArgumentDeltasUseTheToolCallAsTheirStablePreviewId() {
        var event =
                live(
                        "tool.arguments.delta",
                        Map.of("toolCallId", "call-1", "name", "search", "text", "{\"q\""));

        var delta =
                assertInstanceOf(
                        SessionStreamEvent.EventDelta.class, SessionStreamEvent.from(event));

        assertEquals("call-1", delta.eventId());
        assertEquals(EventDeltaType.TOOL_ARGUMENTS, delta.deltaType());
    }

    @Test
    void defaultDurableStreamDoesNotDependOnTheLiveTransport() {
        var handle = mock(DurableSession.class);
        var liveEvents = mock(LiveEvents.class);
        when(handle.liveEvents()).thenReturn(liveEvents);
        when(handle.listEvents(eq(EventFilter.all()), eq(null), eq(100)))
                .thenReturn(new EventPage(List.of(), "0", "0", "0", "0"));

        try (var ignored =
                new SessionEventStream(handle, EventFilter.all(), EventStreamOptions.defaults())) {
            verify(liveEvents, never()).subscribe(any());
        }
    }

    @Test
    void streamsAndSubscriptionsRejectOtherSessionsOnTheSameLiveTransport() throws Exception {
        var handle = mock(DurableSession.class);
        var transport = LiveEvents.local();
        when(handle.liveEvents()).thenReturn(transport);
        when(handle.key()).thenReturn(session);
        when(handle.wakeups()).thenReturn(Wakeups.none());
        var filter = EventFilter.all();
        when(handle.listEvents(eq(filter), any(), eq(100)))
                .thenReturn(new EventPage(List.of(), "0", "0", "0", "0"));
        var executor = Executors.newSingleThreadScheduledExecutor();
        var push = new LinkedBlockingQueue<LiveEvent>();
        var pulled = new ArrayList<LiveEvent>();
        var options = EventStreamOptions.builder().deltas(EventDeltaType.MESSAGE).build();
        try (var stream = new SessionEventStream(handle, filter, options);
                var subscription =
                        new SessionEventSubscription(
                                handle,
                                filter,
                                null,
                                false,
                                snapshot -> {},
                                event -> {},
                                push::add,
                                error -> {},
                                executor,
                                Duration.ofMillis(10),
                                Duration.ofMillis(10),
                                closed -> {})) {
            subscription.start();
            var data = Map.of("messageId", "m1", "blockId", "b1", "text", "example");
            transport.publish(live(new SessionKey("other", "session"), "message.delta", data));
            transport.publish(live(new SessionKey("tenant", "different"), "message.delta", data));
            var local = live("message.delta", data);
            transport.publish(local);
            stream.transferTo(
                    new SessionEventStream.Sink() {
                        @Override
                        public void accept(DurableTypes.PublicEvent event) {}

                        @Override
                        public void live(LiveEvent event) {
                            pulled.add(event);
                        }
                    },
                    Duration.ofMillis(20));
            assertEquals(List.of(local), pulled);
            assertEquals(local, push.poll(3, TimeUnit.SECONDS));
            assertTrue(push.isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void slowStreamDropsOnlyExcessPreviews() {
        var handle = mock(DurableSession.class);
        var transport = LiveEvents.local();
        when(handle.liveEvents()).thenReturn(transport);
        when(handle.key()).thenReturn(session);
        when(handle.wakeups()).thenReturn(Wakeups.none());
        when(handle.listEvents(eq(EventFilter.all()), any(), eq(100)))
                .thenReturn(new EventPage(List.of(), "0", "0", "0", "0"));
        var options = EventStreamOptions.builder().deltas(EventDeltaType.MESSAGE).build();
        var delivered = new ArrayList<SessionStreamEvent>();
        try (var stream = new SessionEventStream(handle, EventFilter.all(), options)) {
            for (int index = 0; index < EventStreamOptions.LIVE_BUFFER_CAPACITY + 10; index++) {
                transport.publish(
                        live(
                                "message.delta",
                                Map.of("messageId", "m1", "blockId", "b1", "text", "" + index)));
            }
            stream.forEach(delivered::add, Duration.ofMillis(20));
        }
        assertEquals(EventStreamOptions.LIVE_BUFFER_CAPACITY, delivered.size());
    }

    private LiveEvent live(String type, Map<String, ?> data) {
        return live(session, type, data);
    }

    private LiveEvent live(SessionKey scope, String type, Map<String, ?> data) {
        return new LiveEvent(
                "1",
                type + "-source",
                scope,
                "main",
                "turn-1",
                "run-1",
                "step-1",
                1,
                type,
                DurableJson.value(data));
    }
}
