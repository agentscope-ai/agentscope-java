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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A turn start needs turns, pending interactions, the recovery projection and the transcript at one
 * committed prefix. They must share a single replay instead of replaying the prefix once each.
 */
class SessionPrefixTest {

    private static final int COMMITS = 200;
    private static final int EVENTS_PER_COMMIT = 10;

    @Test
    void oneReplayFeedsEveryViewAtThePrefix() {
        var storage = new CountingStorage();
        var log = seed(storage);
        long total = log.head().seq();

        storage.reset();
        var turns = SessionTurns.read(log);
        int turnsReads = storage.reads;

        storage.reset();
        var projection = SessionProjection.read(log);
        int projectionReads = storage.reads;

        storage.reset();
        var pending = SessionInteractions.pending(log);
        int pendingReads = storage.reads;

        storage.reset();
        var transcript = SessionViews.transcript(log);
        int transcriptReads = storage.reads;

        storage.reset();
        var prefix = SessionPrefix.read(log, total);
        int sharedReads = storage.reads;

        int separateReads = turnsReads + projectionReads + pendingReads + transcriptReads;

        // The shared prefix costs one replay, i.e. no more than any single view, which also reads
        // the head it resolves the prefix at.
        assertTrue(
                sharedReads <= turnsReads,
                "shared replay should cost one prefix, not more: "
                        + sharedReads
                        + " vs "
                        + turnsReads);
        assertTrue(
                sharedReads * 3 < separateReads,
                "shared replay should cost far less than four replays: "
                        + sharedReads
                        + " vs "
                        + separateReads);

        // Sharing the replay must not change what the views report.
        assertEquals(turns.turns(), prefix.turns().turns());
        assertEquals(turns.inputs(), prefix.turns().inputs());
        assertEquals(turns.started(), prefix.turns().started());
        assertEquals(turns.applied(), prefix.turns().applied());
        assertEquals(projection, prefix.projection());
        assertEquals(pending, prefix.pending());
        // Messages carry no value equality; the transcript contract is the ordered message ids.
        assertEquals(
                transcript.messages().stream().map(Msg::getId).toList(),
                prefix.transcript().messages().stream().map(Msg::getId).toList());
        assertEquals(transcript.asOfSeq(), prefix.transcript().asOfSeq());
        assertEquals(total, prefix.asOfSeq());
    }

    private static SessionLog seed(CountingStorage storage) {
        var log = new JournalSessionLog(storage, "prefix");
        var writer = log.acquire("seed", Duration.ofMinutes(5));
        long seq = 0;
        for (int commit = 0; commit < COMMITS; commit++) {
            var batch = new ArrayList<SessionEvent>();
            for (int index = 0; index < EVENTS_PER_COMMIT; index++) {
                seq++;
                var event = eventFor(seq, commit, index);
                batch.add(event);
            }
            log.commit(writer, "batch-" + commit, seq - EVENTS_PER_COMMIT, batch);
        }
        return log;
    }

    private static SessionEvent eventFor(long seq, int commit, int index) {
        String type = "model/chunk";
        String payload = "{\"chunk\":\"x\"}";
        if (index == 0) {
            type = "input/received";
            payload = "{\"messages\":[]}";
        } else if (index == 1) {
            type = "run/start";
            payload = "{}";
        } else if (index == 2) {
            type = "message/user";
            payload =
                    "{\"message\":{\"id\":\"m"
                            + seq
                            + "\",\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
        }
        return new SessionEvent(
                1, "event-" + seq, seq, 1, type, "run-" + commit, "turn-" + commit, true, payload);
    }

    private static final class CountingStorage implements AtomicSessionStorage {
        private final Map<String, Value> values = new LinkedHashMap<>();
        private int reads;

        void reset() {
            reads = 0;
        }

        @Override
        public Value read(String key) {
            reads++;
            return values.get(key);
        }

        @Override
        public boolean compareAndSet(String key, long version, byte[] bytes) {
            var existing = values.get(key);
            if ((existing == null ? 0 : existing.version()) != version) return false;
            values.put(key, new Value(version + 1, bytes));
            return true;
        }
    }
}
