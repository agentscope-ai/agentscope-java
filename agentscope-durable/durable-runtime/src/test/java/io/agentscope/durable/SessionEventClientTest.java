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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SessionEventClientTest {
    private final SessionKey session = new SessionKey("application", "session");

    @Test
    void localHintsFanOutCoalesceAndUnsubscribeIndependently() throws Exception {
        var hints = Wakeups.local();
        var backend = mock(SessionBackend.class);
        when(backend.wakeups()).thenReturn(hints);
        when(backend.events(eq(session), any(), eq(100)))
                .thenReturn(new EventPage(List.of(), "cursor", "0"));
        try (var first = new SessionEventClient(backend, session, null, s -> {}, e -> {});
                var second = new SessionEventClient(backend, session, null, s -> {}, e -> {})) {
            first.poll();
            second.poll();
            hints.publish(new SessionKey("application", "other"));
            assertFalse(first.awaitChanges(Duration.ofMillis(1)));
            for (int i = 0; i < 100; i++) hints.publish(session);
            assertTrue(first.awaitChanges(Duration.ofMillis(1)));
            assertFalse(first.awaitChanges(Duration.ofMillis(1)));
            assertTrue(second.awaitChanges(Duration.ofMillis(1)));
            first.close();
            hints.publish(session);
            assertTrue(second.awaitChanges(Duration.ofMillis(1)));
            assertFalse(first.awaitChanges(Duration.ofMillis(1)));
        }
    }

    @Test
    void failingListenerDoesNotStarveOthersAndRepeatedCallbackHasSeparateSubscriptions()
            throws Exception {
        var hints = Wakeups.local();
        var calls = new AtomicInteger();
        Consumer<SessionKey> callback = key -> calls.incrementAndGet();
        try (var failed =
                        hints.subscribe(
                                key -> {
                                    throw new IllegalStateException("failed");
                                });
                var first = hints.subscribe(callback);
                var second = hints.subscribe(callback)) {
            assertThrows(IllegalStateException.class, () -> hints.publish(session));
            assertEquals(2, calls.get());
            first.close();
            first.close();
            assertThrows(IllegalStateException.class, () -> hints.publish(session));
            assertEquals(3, calls.get());
        }
        hints.publish(session);
        assertEquals(3, calls.get());
    }

    @Test
    void failedSubscriptionFallsBackToPollingAndCloseReleasesWait() throws Exception {
        var backend = mock(SessionBackend.class);
        var hints = mock(Wakeups.class);
        when(backend.wakeups()).thenReturn(hints);
        when(hints.subscribe(any())).thenThrow(new IllegalStateException("MQ unavailable"));
        when(backend.events(eq(session), any(), eq(100)))
                .thenReturn(new EventPage(List.of(), "cursor", "0"));
        var worker = Executors.newSingleThreadExecutor();
        try (var reader = new SessionEventClient(backend, session, null, s -> {}, e -> {})) {
            assertEquals(0, reader.poll());
            assertFalse(reader.awaitChanges(Duration.ofMillis(1)));
            var waiting = worker.submit(() -> reader.awaitChanges(Duration.ofSeconds(30)));
            reader.close();
            assertFalse(waiting.get(1, TimeUnit.SECONDS));
            verify(backend).events(session, null, 100);
            verify(backend, never()).cancel(any(), any(), any());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void replayBoundaryIsReachedEvenWhenTheNextPageAlsoContainsNewerEvents() {
        var backend = mock(SessionBackend.class);
        when(backend.wakeups()).thenReturn(Wakeups.none());
        when(backend.events(eq(session), any(), eq(100)))
                .thenReturn(
                        new EventPage(List.of(event("1")), "1", "2"),
                        new EventPage(List.of(event("2"), event("3")), "3", "3"));
        try (var reader = new SessionEventClient(backend, session, null, s -> {}, e -> {})) {
            assertEquals(1, reader.poll());
            assertFalse(reader.replayCaughtUp());
            assertEquals(2, reader.poll());
            assertTrue(reader.replayCaughtUp());
        }
    }

    private PublicEvent event(String cursor) {
        return new PublicEvent(
                "1",
                "event-" + cursor,
                session,
                "main",
                "turn",
                null,
                null,
                cursor,
                cursor,
                "x.test.event",
                DurableJson.value(Map.of()));
    }
}
