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
package io.agentscope.harness.agent.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceMessageBusTest {

    @TempDir Path tempDir;
    private WorkspaceMessageBus bus;

    @BeforeEach
    void setUp() {
        LocalFilesystem fs = new LocalFilesystem(tempDir, true, 10);
        bus = new WorkspaceMessageBus(fs, "/bus");
    }

    // ---- Mode A: drain queue ----

    @Test
    void pushAndDrainInOrder() {
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

        bus.queueDrain("q1", 10).block();
        List<BusEntry> second = bus.queueDrain("q1", 10).block();
        assertTrue(second.isEmpty());
    }

    @Test
    void drainRespectsMaxCount() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queuePush("q1", Map.of("v", 2)).block();
        bus.queuePush("q1", Map.of("v", 3)).block();

        List<BusEntry> first = bus.queueDrain("q1", 2).block();
        assertEquals(2, first.size());

        List<BusEntry> remaining = bus.queueDrain("q1", 10).block();
        assertEquals(1, remaining.size());
        assertEquals(3, remaining.get(0).payload().get("v"));
    }

    @Test
    void drainEmptyQueueReturnsEmpty() {
        List<BusEntry> drained = bus.queueDrain("nonexistent", 10).block();
        assertNotNull(drained);
        assertTrue(drained.isEmpty());
    }

    @Test
    void queueDeleteRemovesAll() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queueDelete("q1").block();

        List<BusEntry> drained = bus.queueDrain("q1", 10).block();
        assertTrue(drained.isEmpty());
    }

    @Test
    void pushReturnsUniqueIds() {
        String id1 = bus.queuePush("q1", Map.of("v", 1)).block();
        String id2 = bus.queuePush("q1", Map.of("v", 2)).block();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    @Test
    void queuesIsolatedByKey() {
        bus.queuePush("q1", Map.of("from", "q1")).block();
        bus.queuePush("q2", Map.of("from", "q2")).block();

        List<BusEntry> fromQ1 = bus.queueDrain("q1", 10).block();
        assertEquals(1, fromQ1.size());
        assertEquals("q1", fromQ1.get(0).payload().get("from"));
    }

    // ---- Mode C: replay log ----

    @Test
    void logAppendAndReadInOrder() {
        bus.logAppend("log1", Map.of("seq", 1), 0).block();
        bus.logAppend("log1", Map.of("seq", 2), 0).block();

        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).payload().get("seq"));
        assertEquals(2, all.get(1).payload().get("seq"));
    }

    @Test
    void logReadWithCursor() {
        bus.logAppend("log1", Map.of("v", "a"), 0).block();
        String id2 = bus.logAppend("log1", Map.of("v", "b"), 0).block();
        bus.logAppend("log1", Map.of("v", "c"), 0).block();

        List<BusEntry> afterId2 = bus.logRead("log1", id2, 100).block();
        assertEquals(1, afterId2.size());
        assertEquals("c", afterId2.get(0).payload().get("v"));
    }

    @Test
    void logReadIsNonDestructive() {
        bus.logAppend("log1", Map.of("v", 1), 0).block();
        List<BusEntry> first = bus.logRead("log1", null, 100).block();
        List<BusEntry> second = bus.logRead("log1", null, 100).block();
        assertEquals(first.size(), second.size());
    }

    @Test
    void logAppendRespectsMaxLen() {
        for (int i = 0; i < 10; i++) {
            bus.logAppend("log1", Map.of("i", i), 5).block();
        }
        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertEquals(5, all.size());
        assertEquals(5, all.get(0).payload().get("i"));
        assertEquals(9, all.get(4).payload().get("i"));
    }

    @Test
    void logTrimDeletesAll() {
        bus.logAppend("log1", Map.of("v", 1), 0).block();
        bus.logTrim("log1").block();

        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertTrue(all.isEmpty());
    }

    // ---- Inbox domain helpers ----

    @Test
    void inboxPushAndDrain() {
        bus.inboxPush("s1", Map.of("hint", "hello")).block();
        bus.inboxPush("s1", Map.of("hint", "world")).block();

        List<BusEntry> entries = bus.inboxDrain("s1", 100).block();
        assertEquals(2, entries.size());
        assertEquals("hello", entries.get(0).payload().get("hint"));

        assertTrue(bus.inboxDrain("s1", 100).block().isEmpty());
    }

    // ---- Cross-instance sharing ----

    @Test
    void twoInstancesShareSameDirectory() {
        LocalFilesystem fs2 = new LocalFilesystem(tempDir, true, 10);
        WorkspaceMessageBus bus2 = new WorkspaceMessageBus(fs2, "/bus");

        bus.queuePush("shared", Map.of("from", "bus1")).block();
        bus2.queuePush("shared", Map.of("from", "bus2")).block();

        List<BusEntry> all = bus.queueDrain("shared", 10).block();
        assertEquals(2, all.size());

        assertTrue(bus2.queueDrain("shared", 10).block().isEmpty());
    }

    // ---- Session events ----

    @Test
    void sessionPublishAndReadEvents() {
        bus.sessionPublishEvent("s1", Map.of("type", "TEXT_DELTA")).block();
        bus.sessionPublishEvent("s1", Map.of("type", "TEXT_END")).block();

        List<BusEntry> events = bus.sessionReadEvents("s1", null, 100).block();
        assertEquals(2, events.size());
        assertEquals("TEXT_DELTA", events.get(0).payload().get("type"));
        assertEquals("TEXT_END", events.get(1).payload().get("type"));
    }

    @Test
    void sessionTrimClearsEvents() {
        bus.sessionPublishEvent("s1", Map.of("v", 1)).block();
        bus.sessionTrimEvents("s1").block();

        assertTrue(bus.sessionReadEvents("s1", null, 100).block().isEmpty());
    }

    // ---- File structure verification ----

    @Test
    void filesCreatedUnderBusRoot() throws IOException {
        bus.queuePush("myqueue", Map.of("v", 1)).block();

        Path busDir = tempDir.resolve("bus");
        assertTrue(Files.exists(busDir), "Bus root directory should exist");
        assertTrue(Files.exists(busDir.resolve("queues")), "queues/ should exist");

        long jsonFiles =
                Files.walk(busDir.resolve("queues"))
                        .filter(p -> p.toString().endsWith(".json"))
                        .count();
        assertTrue(jsonFiles >= 1, "Should have at least one .json queue entry");
    }
}
