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
import java.util.List;
import java.util.Map;
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
                .thenReturn(new EventPage(List.of(), "0", "0"));

        try (var ignored =
                new SessionEventStream(handle, EventFilter.all(), EventStreamOptions.defaults())) {
            verify(liveEvents, never()).subscribe(any());
        }
    }

    private LiveEvent live(String type, Map<String, ?> data) {
        return new LiveEvent(
                "1",
                type + "-source",
                session,
                "main",
                "turn-1",
                "run-1",
                "step-1",
                1,
                type,
                DurableJson.value(data));
    }
}
