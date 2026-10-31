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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionInteractionsTest {

    @Test
    void pendingScansTheStablePrefixOnceInsteadOfPagingFromHead() {
        var events = new ArrayList<SessionEvent>();
        for (long seq = 1; seq <= 600; seq++) {
            if (seq == 1) events.add(event(seq, "interaction/requested", "resolved"));
            else if (seq == 300) events.add(event(seq, "interaction/resolved", "resolved"));
            else if (seq == 599) events.add(event(seq, "interaction/requested", "pending"));
            else events.add(event(seq, "ignored", null));
        }
        var log = new CountingSessionLog(events);

        var pending = SessionInteractions.pending(log);

        assertEquals(Set.of("pending"), pending.keySet());
        assertSame(events.get(598), pending.get("pending"));
        assertEquals(1, log.headCalls);
        assertEquals(1, log.scanCalls);
        assertEquals(0, log.readAfterCalls);
    }

    private static SessionEvent event(long seq, String type, String requestId) {
        String payload = requestId == null ? "{}" : "{\"requestId\":\"" + requestId + "\"}";
        return new SessionEvent(1, "event-" + seq, seq, 1, type, "run", "turn", true, payload);
    }

    private static final class CountingSessionLog implements SessionLog {
        private final List<SessionEvent> events;
        private int headCalls;
        private int scanCalls;
        private int readAfterCalls;

        private CountingSessionLog(List<SessionEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public Head head() {
            headCalls++;
            return new Head(events.size(), 0, null, 0, null);
        }

        @Override
        public Iterable<SessionEvent> scan(long after, long through) {
            scanCalls++;
            return events.subList((int) after, (int) through);
        }

        @Override
        public List<SessionEvent> readAfter(long after, int limit) {
            readAfterCalls++;
            int start = (int) after;
            int end = Math.min(events.size(), start + limit);
            return events.subList(start, end);
        }

        @Override
        public Writer acquire(String owner, Duration lease) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void renew(Writer writer, Duration lease) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(Writer writer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Head commit(
                Writer writer, String batchId, long expectedSeq, List<SessionEvent> events) {
            throw new UnsupportedOperationException();
        }
    }
}
