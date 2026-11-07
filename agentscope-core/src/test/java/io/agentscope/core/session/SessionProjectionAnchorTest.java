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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Recovery may start from the latest checkpoint/run-start anchor without changing semantics. */
class SessionProjectionAnchorTest {

    private static SessionEvent event(long seq, String type, String runId, Map<String, ?> data) {
        return new SessionEvent(
                1,
                "e" + seq,
                seq,
                1,
                type,
                runId,
                "t",
                true,
                JsonUtils.getJsonCodec().toJson(data));
    }

    private static SessionEvent checkpoint(long seq, String summary, String type) {
        return event(
                seq,
                type,
                null,
                Map.of("stateJson", AgentState.builder().summary(summary).build().toJson()));
    }

    private static SessionEvent runStart(long seq, String runId) {
        return event(seq, "run/start", runId, Map.of("executionRunId", runId));
    }

    private static SessionEvent runEnd(long seq, String runId) {
        return event(seq, "run/end", runId, Map.of("executionRunId", runId));
    }

    private static UserMessage message(String id, String text) {
        return UserMessage.builder().id(id).textContent(text).build();
    }

    private static SessionEvent messageEvent(long seq, Msg message) {
        return event(seq, "message/user", null, Map.of("message", message));
    }

    private static SessionEvent stateEvent(long seq, String type, List<Msg> context) {
        return event(
                seq,
                type,
                null,
                Map.of(
                        "stateJson",
                        AgentState.builder().context(new ArrayList<>(context)).build().toJson()));
    }

    /** A journal whose commits are written and counted one event per segment. */
    private static final class CountingStorage implements AtomicSessionStorage {
        private final Map<String, Value> values = new ConcurrentHashMap<>();
        int commitReads;
        int anchorReads;
        Runnable beforeAnchorsCas;

        public Value read(String path) {
            if (path.contains("/commits/")) commitReads++;
            if (path.endsWith("/anchors.json")) anchorReads++;
            return values.get(path);
        }

        public synchronized boolean compareAndSet(String path, long version, byte[] bytes) {
            if (path.endsWith("/anchors.json") && beforeAnchorsCas != null) {
                Runnable hook = beforeAnchorsCas;
                beforeAnchorsCas = null;
                hook.run();
            }
            var old = values.get(path);
            if ((old == null ? 0 : old.version()) != version) return false;
            values.put(path, new Value(version + 1, bytes));
            return true;
        }

        void remove(String path) {
            values.remove(path);
        }

        void write(String path, String json) {
            var old = values.get(path);
            values.put(
                    path,
                    new Value(
                            (old == null ? 0 : old.version()) + 1,
                            json.getBytes(StandardCharsets.UTF_8)));
        }

        String text(String path) {
            var value = values.get(path);
            return value == null ? null : new String(value.bytes(), StandardCharsets.UTF_8);
        }
    }

    private static JournalSessionLog prepare(CountingStorage storage, List<SessionEvent> events) {
        var log = new JournalSessionLog(storage, "test");
        var writer = log.acquire("w", Duration.ofMinutes(2));
        long expected = 0;
        for (var event : events) {
            log.commit(writer, "b" + event.seq(), expected, List.of(event));
            expected = event.seq();
        }
        log.release(writer);
        return log;
    }

    /** Delegates every core operation; anchor lookups keep their default (unknown) behavior. */
    private static class DelegatingSessionLog implements SessionLog {
        final SessionLog delegate;

        DelegatingSessionLog(SessionLog delegate) {
            this.delegate = delegate;
        }

        public Head head() {
            return delegate.head();
        }

        public Writer acquire(String owner, Duration lease) {
            return delegate.acquire(owner, lease);
        }

        public void renew(Writer writer, Duration lease) {
            delegate.renew(writer, lease);
        }

        public void release(Writer writer) {
            delegate.release(writer);
        }

        public Head commit(
                Writer writer, String batchId, long expectedSeq, List<SessionEvent> events) {
            return delegate.commit(writer, batchId, expectedSeq, events);
        }

        public List<SessionEvent> readAfter(long after, int limit) {
            return delegate.readAfter(after, limit);
        }

        public Iterable<SessionEvent> scan(long after, long through) {
            return delegate.scan(after, through);
        }
    }

    /** Replays the whole journal, matching the old pre-anchor behavior. */
    private static SessionProjection fullScan(SessionLog delegate) {
        return SessionProjection.read(new DelegatingSessionLog(delegate));
    }

    private static void assertSameProjection(SessionProjection expected, SessionProjection actual) {
        assertEquals(expected.asOfSeq(), actual.asOfSeq());
        assertEquals(expected.stateJson(), actual.stateJson());
        assertEquals(expected.activeRuns(), actual.activeRuns());
        assertEquals(expected.uncertainToolCalls(), actual.uncertainToolCalls());
    }

    @Test
    void multiTurnSessionRestoresIdenticallyFromAnchorAndFromZero() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                runEnd(4, "r1"),
                                runStart(5, "r2"),
                                checkpoint(6, "cp2", "state/checkpoint"),
                                runEnd(7, "r2"),
                                runStart(8, "r3"),
                                checkpoint(9, "cp3", "state/checkpoint")));

        var anchored = SessionProjection.read(log);
        var full = fullScan(log);

        assertSameProjection(full, anchored);
        assertEquals(Set.of("r3"), anchored.activeRuns());
        assertEquals(9, anchored.asOfSeq());
        assertEquals(9, log.lastCheckpointSeq());
        assertEquals(8, log.lastRunStartSeq());
    }

    @Test
    void anchorAlsoCoversARunStartedAfterTheLatestCheckpoint() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                runEnd(4, "r1"),
                                runStart(5, "r2"),
                                checkpoint(6, "cp2", "state/checkpoint"),
                                runEnd(7, "r2"),
                                runStart(8, "r3"),
                                checkpoint(9, "cp3", "state/checkpoint"),
                                runEnd(10, "r3"),
                                runStart(11, "r4")));

        var anchored = SessionProjection.read(log);
        var full = fullScan(log);

        assertSameProjection(full, anchored);
        assertEquals(Set.of("r4"), anchored.activeRuns());
        assertEquals(9, log.lastCheckpointSeq());
        assertEquals(11, log.lastRunStartSeq());
    }

    @Test
    void interruptedRunWithoutRunEndRemainsActive() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                runEnd(4, "r1"),
                                runStart(5, "r2"),
                                checkpoint(6, "cp2", "state/checkpoint")));

        var anchored = SessionProjection.read(log);
        var full = fullScan(log);

        assertSameProjection(full, anchored);
        assertEquals(Set.of("r2"), anchored.activeRuns());
        assertEquals(5, log.lastRunStartSeq());
    }

    @Test
    void checkpointCommittedWhileAnchorsAreResolvedStillReachesTheProjection() {
        var storage = new CountingStorage();
        var log = new JournalSessionLog(storage, "test");
        var writer = log.acquire("w", Duration.ofMinutes(2));
        long expected = 0;
        for (var event :
                List.of(
                        checkpoint(1, "baseline", "migration/baseline"),
                        runStart(2, "r1"),
                        checkpoint(3, "cp1", "state/checkpoint"))) {
            log.commit(writer, "b" + event.seq(), expected, List.of(event));
            expected = event.seq();
        }
        var committed = new boolean[1];
        // Simulate a writer that commits a new run and checkpoint between the anchor lookup and
        // the upper-bound read. The old order read upper first, then let the anchor jump past it.
        var racing =
                new DelegatingSessionLog(log) {
                    @Override
                    public long lastCheckpointSeq() {
                        if (!committed[0]) {
                            committed[0] = true;
                            log.commit(
                                    writer,
                                    "late",
                                    3,
                                    List.of(
                                            runStart(4, "r2"),
                                            checkpoint(5, "cp2", "state/checkpoint")));
                        }
                        return log.lastCheckpointSeq();
                    }

                    @Override
                    public long lastRunStartSeq() {
                        return log.lastRunStartSeq();
                    }
                };
        try {
            var projection = SessionProjection.read(racing);
            assertEquals(5, projection.asOfSeq());
            assertTrue(projection.stateJson().contains("\"summary\" : \"cp2\""));
            assertEquals(Set.of("r2"), projection.activeRuns());
        } finally {
            log.release(writer);
        }
    }

    @Test
    void baselineWithoutRunStartRetreatsToTheStartOfTheJournal() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                event(2, "turn/start", null, Map.of("turnId", "t"))));

        var anchored = SessionProjection.read(log);
        var full = fullScan(log);

        assertSameProjection(full, anchored);
        assertEquals(1, log.lastCheckpointSeq());
        assertEquals(0, log.lastRunStartSeq());
        assertTrue(anchored.stateJson().contains("\"summary\" : \"baseline\""));
    }

    @Test
    void uncertainToolCallSurvivesTheAnchoredReplay() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                event(4, "tool/dispatch", "r1", Map.of("toolCallId", "call-1")),
                                checkpoint(5, "cp2", "state/checkpoint"),
                                runEnd(6, "r1")));

        var anchored = SessionProjection.read(log);
        var full = fullScan(log);

        assertSameProjection(full, anchored);
        assertEquals(Set.of("call-1"), anchored.uncertainToolCalls());
        assertEquals(Set.of(), anchored.activeRuns());
    }

    @Test
    void anchoredRecoveryReadsFewerCommitSegmentsThanAFullReplay() {
        var events = new ArrayList<SessionEvent>();
        long seq = 1;
        events.add(checkpoint(seq++, "baseline", "migration/baseline"));
        for (int run = 1; run <= 50; run++) {
            events.add(runStart(seq++, "r" + run));
            events.add(checkpoint(seq++, "cp" + run, "state/checkpoint"));
            events.add(runEnd(seq++, "r" + run));
        }
        events.add(runStart(seq++, "current"));
        events.add(checkpoint(seq, "latest", "state/checkpoint"));

        var storage = new CountingStorage();
        var log = prepare(storage, events);

        storage.commitReads = 0;
        storage.anchorReads = 0;
        var anchored = SessionProjection.read(log);
        int anchoredReads = storage.commitReads;
        int anchoredAnchorReads = storage.anchorReads;

        storage.commitReads = 0;
        var full = fullScan(log);
        int fullReads = storage.commitReads;

        assertSameProjection(full, anchored);
        assertEquals(Set.of("current"), anchored.activeRuns());
        assertEquals(2, anchoredAnchorReads);
        assertTrue(
                anchoredReads < fullReads,
                "expected anchored reads " + anchoredReads + " to beat full " + fullReads);
    }

    @Test
    void anchorsTrackTheLatestCheckpointAndRunStart() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                runEnd(4, "r1"),
                                runStart(5, "r2"),
                                checkpoint(6, "cp2", "state/checkpoint")));

        assertEquals(6, log.lastCheckpointSeq());
        assertEquals(5, log.lastRunStartSeq());
        var json = storage.text("test/anchors.json");
        var anchors = JsonUtils.getJsonCodec().fromJson(json, Map.class);
        assertEquals(6, ((Number) anchors.get("lastCheckpointSeq")).intValue());
        assertEquals(5, ((Number) anchors.get("lastRunStartSeq")).intValue());
    }

    @Test
    void missingAnchorsFallBackToTheFullJournal() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                event(4, "tool/dispatch", "r1", Map.of("toolCallId", "call-1")),
                                runEnd(5, "r1")));
        storage.remove("test/anchors.json");

        assertEquals(0, log.lastCheckpointSeq());
        assertEquals(0, log.lastRunStartSeq());
        var anchored = SessionProjection.read(log);
        assertSameProjection(fullScan(log), anchored);
        assertEquals(Set.of("call-1"), anchored.uncertainToolCalls());
    }

    @Test
    void laggingAnchorsReplayMoreButKeepTheSameResult() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                runEnd(4, "r1"),
                                event(5, "tool/dispatch", "r1", Map.of("toolCallId", "call-1")),
                                runStart(6, "r2"),
                                checkpoint(7, "cp2", "state/checkpoint"),
                                runEnd(8, "r2")));
        storage.write("test/anchors.json", "{\"lastCheckpointSeq\":3,\"lastRunStartSeq\":2}");

        assertEquals(3, log.lastCheckpointSeq());
        assertEquals(2, log.lastRunStartSeq());
        var anchored = SessionProjection.read(log);
        assertSameProjection(fullScan(log), anchored);
        assertEquals(Set.of("call-1"), anchored.uncertainToolCalls());
        assertEquals(Set.of(), anchored.activeRuns());
    }

    @Test
    void anchorsNeverDecreaseWhenACasConflictWritesNewerValues() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint")));
        storage.beforeAnchorsCas =
                () ->
                        storage.write(
                                "test/anchors.json",
                                "{\"lastCheckpointSeq\":100,\"lastRunStartSeq\":200}");
        var writer = log.acquire("w2", Duration.ofMinutes(2));
        try {
            log.commit(
                    writer,
                    "late",
                    3,
                    List.of(runStart(4, "r2"), checkpoint(5, "cp2", "state/checkpoint")));
        } finally {
            log.release(writer);
        }

        assertEquals(100, log.lastCheckpointSeq());
        assertEquals(200, log.lastRunStartSeq());
    }

    @Test
    void missingAnchorsAreRebuiltOnTheNextBatchInsteadOfBeingOverwritten() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                runEnd(4, "r1")));
        storage.remove("test/anchors.json");
        var writer = log.acquire("w2", Duration.ofMinutes(2));
        try {
            log.commit(writer, "next", 4, List.of(runStart(5, "r2")));
        } finally {
            log.release(writer);
        }

        assertEquals(3, log.lastCheckpointSeq());
        assertEquals(5, log.lastRunStartSeq());
        var projection = SessionProjection.read(log);
        assertTrue(projection.stateJson().contains("\"summary\" : \"cp1\""));
    }

    @Test
    void corruptAnchorsAreRebuiltFromTheCommittedChain() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint"),
                                runEnd(4, "r1")));
        storage.write("test/anchors.json", "{not-json");
        var writer = log.acquire("w2", Duration.ofMinutes(2));
        try {
            log.commit(writer, "next", 4, List.of(runStart(5, "r2")));
        } finally {
            log.release(writer);
        }

        assertEquals(3, log.lastCheckpointSeq());
        assertEquals(5, log.lastRunStartSeq());
    }

    @Test
    void transcriptKeepsHistoryAcrossANonEmptyReplacedContext() {
        var m1 = message("m1", "one");
        var m2 = message("m2", "two");
        var m3 = message("m3", "three");
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                stateEvent(1, "migration/baseline", List.of(m1)),
                                messageEvent(2, m2),
                                stateEvent(3, "state/checkpoint", List.of(m2)),
                                messageEvent(4, m3)));

        var transcript = SessionViews.transcript(log);

        assertEquals(
                List.of("one", "two", "three"),
                transcript.messages().stream().map(Msg::getTextContent).toList());
    }

    @Test
    void transcriptMatchesTheFullJournalAfterMergingCheckpointStates() {
        var m1 = message("m1", "one");
        var m2 = message("m2", "two");
        var m3 = message("m3", "three");
        var m4 = message("m4", "four");
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                stateEvent(1, "migration/baseline", List.of(m1)),
                                messageEvent(2, m2),
                                stateEvent(3, "state/checkpoint", List.of(m1, m2)),
                                messageEvent(4, m3),
                                stateEvent(5, "state/checkpoint", List.of(m1, m2, m3)),
                                messageEvent(6, m4)));

        var anchored = SessionViews.transcript(log);
        var full = SessionViews.transcript(new DelegatingSessionLog(log));

        assertEquals(full.asOfSeq(), anchored.asOfSeq());
        assertEquals(
                full.messages().stream().map(Msg::getTextContent).toList(),
                anchored.messages().stream().map(Msg::getTextContent).toList());
        assertEquals(
                List.of("one", "two", "three", "four"),
                anchored.messages().stream().map(Msg::getTextContent).toList());
    }

    @Test
    void runStartWithoutCheckpointReplaysFromTheJournalStart() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                event(1, "tool/dispatch", "r1", Map.of("toolCallId", "call-1")),
                                runStart(2, "r1")));

        assertEquals(0, log.lastCheckpointSeq());
        assertEquals(2, log.lastRunStartSeq());
        var projection = SessionProjection.read(log);
        assertSameProjection(fullScan(log), projection);
        assertEquals(Set.of("call-1"), projection.uncertainToolCalls());
    }

    @Test
    void outOfRangeAnchorsFallBackToTheFullJournal() {
        var storage = new CountingStorage();
        var log =
                prepare(
                        storage,
                        List.of(
                                checkpoint(1, "baseline", "migration/baseline"),
                                runStart(2, "r1"),
                                checkpoint(3, "cp1", "state/checkpoint")));
        storage.write("test/anchors.json", "{\"lastCheckpointSeq\":999,\"lastRunStartSeq\":999}");

        var projection = SessionProjection.read(log);

        assertSameProjection(fullScan(log), projection);
        assertTrue(projection.stateJson().contains("\"summary\" : \"cp1\""));
    }

    @Test
    void checkpointLookupReturnsZeroForAnEmptyJournal() {
        var log = new JournalSessionLog(new CountingStorage(), "empty");
        assertEquals(0, log.lastCheckpointSeq());
        assertEquals(0, log.lastRunStartSeq());
        assertEquals(0, SessionProjection.read(log).asOfSeq());
    }
}
