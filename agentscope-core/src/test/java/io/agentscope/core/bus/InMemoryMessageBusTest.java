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
package io.agentscope.core.bus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemoryMessageBusTest {

    private InMemoryMessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new InMemoryMessageBus();
    }

    // ---- Queue push/drain ----

    @Test
    void pushAndDrainReturnsEntriesInOrder() {
        bus.queuePush("q1", Map.of("seq", 1)).block();
        bus.queuePush("q1", Map.of("seq", 2)).block();
        bus.queuePush("q1", Map.of("seq", 3)).block();

        List<BusEntry> drained = bus.queueDrain("q1", 10).block();
        assertNotNull(drained);
        assertEquals(3, drained.size());
        assertEquals(1, drained.get(0).payload().get("seq"));
        assertEquals(2, drained.get(1).payload().get("seq"));
        assertEquals(3, drained.get(2).payload().get("seq"));
    }

    @Test
    void drainRemovesEntries() {
        bus.queuePush("q1", Map.of("v", "a")).block();
        bus.queuePush("q1", Map.of("v", "b")).block();

        List<BusEntry> first = bus.queueDrain("q1", 10).block();
        assertEquals(2, first.size());

        List<BusEntry> second = bus.queueDrain("q1", 10).block();
        assertTrue(second.isEmpty());
    }

    @Test
    void drainRespectsMaxCount() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queuePush("q1", Map.of("v", 2)).block();
        bus.queuePush("q1", Map.of("v", 3)).block();

        List<BusEntry> drained = bus.queueDrain("q1", 2).block();
        assertEquals(2, drained.size());

        List<BusEntry> remaining = bus.queueDrain("q1", 10).block();
        assertEquals(1, remaining.size());
        assertEquals(3, remaining.get(0).payload().get("v"));
    }

    @Test
    void drainEmptyQueueReturnsEmptyList() {
        List<BusEntry> drained = bus.queueDrain("nonexistent", 10).block();
        assertNotNull(drained);
        assertTrue(drained.isEmpty());
    }

    @Test
    void queueDeleteRemovesQueue() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queueDelete("q1").block();

        List<BusEntry> drained = bus.queueDrain("q1", 10).block();
        assertTrue(drained.isEmpty());
    }

    @Test
    void pushReturnsUniqueEntryIds() {
        String id1 = bus.queuePush("q1", Map.of("v", 1)).block();
        String id2 = bus.queuePush("q1", Map.of("v", 2)).block();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    @Test
    void queuesAreIsolatedByKey() {
        bus.queuePush("q1", Map.of("from", "q1")).block();
        bus.queuePush("q2", Map.of("from", "q2")).block();

        List<BusEntry> fromQ1 = bus.queueDrain("q1", 10).block();
        assertEquals(1, fromQ1.size());
        assertEquals("q1", fromQ1.get(0).payload().get("from"));

        List<BusEntry> fromQ2 = bus.queueDrain("q2", 10).block();
        assertEquals(1, fromQ2.size());
        assertEquals("q2", fromQ2.get(0).payload().get("from"));
    }

    // ---- Pub/sub ----

    @Test
    void subscribeReceivesPublishedMessages() {
        StepVerifier.create(bus.subscribe("ch1").take(1).timeout(Duration.ofSeconds(2)))
                .then(() -> bus.publish("ch1", Map.of("msg", "hello")).block())
                .assertNext(payload -> assertEquals("hello", payload.get("msg")))
                .verifyComplete();
    }

    @Test
    void publishWithNoSubscribersDoesNotError() {
        assertDoesNotThrow(() -> bus.publish("nobody", Map.of("msg", "void")).block());
    }

    // ---- Domain helpers: inbox ----

    @Test
    void inboxPushAndDrain() {
        bus.inboxPush("session-1", Map.of("type", "hint", "hint", "hello")).block();
        bus.inboxPush("session-1", Map.of("type", "hint", "hint", "world")).block();

        List<BusEntry> entries = bus.inboxDrain("session-1", 100).block();
        assertEquals(2, entries.size());
        assertEquals("hello", entries.get(0).payload().get("hint"));
        assertEquals("world", entries.get(1).payload().get("hint"));

        List<BusEntry> empty = bus.inboxDrain("session-1", 100).block();
        assertTrue(empty.isEmpty());
    }

    @Test
    void inboxesAreIsolatedBySession() {
        bus.inboxPush("s1", Map.of("for", "s1")).block();
        bus.inboxPush("s2", Map.of("for", "s2")).block();

        List<BusEntry> s1 = bus.inboxDrain("s1", 100).block();
        assertEquals(1, s1.size());
        assertEquals("s1", s1.get(0).payload().get("for"));

        List<BusEntry> s2 = bus.inboxDrain("s2", 100).block();
        assertEquals(1, s2.size());
        assertEquals("s2", s2.get(0).payload().get("for"));
    }

    // ---- Domain helpers: wakeup ----

    @Test
    void enqueueWakeupPushesEntryAndSignal() {
        StepVerifier.create(bus.subscribeWakeup().take(1).timeout(Duration.ofSeconds(2)))
                .then(() -> bus.enqueueWakeup("session-1", "agent-1").block())
                .assertNext(signal -> assertNotNull(signal))
                .verifyComplete();

        List<BusEntry> wakeups = bus.queueDrain("agentscope:wakeups", 10).block();
        assertEquals(1, wakeups.size());
        assertEquals("session-1", wakeups.get(0).payload().get("sessionId"));
        assertEquals("agent-1", wakeups.get(0).payload().get("agentId"));
    }
}
