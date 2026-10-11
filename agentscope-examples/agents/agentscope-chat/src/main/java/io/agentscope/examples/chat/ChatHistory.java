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
package io.agentscope.examples.chat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionViews;
import io.agentscope.harness.agent.session.AgentSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A UI snapshot and its cursor are built from exactly the same committed prefix. */
final class ChatHistory {
    record Turn(String turnId, String runId, String status) {}

    record Frame(
            long seq,
            String eventId,
            String type,
            String turnId,
            String runId,
            Map<String, Object> data) {}

    record Snapshot(
            String sessionId,
            String cursor,
            List<Msg> messages,
            List<ChatItems.Item> items,
            List<Turn> turns,
            List<Frame> pending,
            List<Frame> trace,
            long checkpointSeq,
            int workingMessages,
            Set<String> uncertainTools,
            String executionError,
            List<AgentSession.Task> tasks) {}

    static Snapshot snapshot(String sessionId, SessionLog log, String activeRun, String error) {
        return snapshot(sessionId, log, activeRun, error, List.of());
    }

    static Snapshot snapshot(
            String sessionId,
            SessionLog log,
            String activeRun,
            String error,
            List<AgentSession.Task> tasks) {
        var head = log.head();
        var facts = new ArrayList<SessionEvent>();
        log.scan(0, head.seq()).forEach(facts::add);
        var prefix = new Prefix(head, List.copyOf(facts));
        var projection = SessionProjection.read(prefix);
        var turns = new LinkedHashMap<String, Turn>();
        long checkpoint = 0;
        for (var fact : facts) {
            if (fact.type().equals("state/checkpoint")) checkpoint = fact.seq();
            if (fact.turnId() == null) continue;
            if (fact.type().equals("run/start"))
                turns.put(
                        fact.turnId(),
                        new Turn(
                                fact.turnId(),
                                fact.executionRunId(),
                                fact.executionRunId().equals(activeRun)
                                        ? "running"
                                        : "interrupted"));
            if (fact.type().equals("run/end"))
                turns.put(
                        fact.turnId(),
                        new Turn(
                                fact.turnId(),
                                fact.executionRunId(),
                                String.valueOf(fact.data().get("status"))));
        }
        var state = projection.restore();
        return new Snapshot(
                sessionId,
                cursor(sessionId, head.seq()),
                SessionViews.transcript(prefix).messages(),
                ChatItems.read(facts),
                List.copyOf(turns.values()),
                SessionInteractions.pending(prefix).values().stream()
                        .map(ChatHistory::frame)
                        .toList(),
                facts.stream()
                        .skip(Math.max(0, facts.size() - 80))
                        .map(ChatHistory::frame)
                        .toList(),
                checkpoint,
                state == null ? 0 : state.getContext().size(),
                projection.uncertainToolCalls(),
                error,
                tasks);
    }

    static String cursor(String session, long seq) {
        return session + ":" + seq;
    }

    static long position(String session, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        if (!cursor.startsWith(session + ":"))
            throw new IllegalArgumentException("Cursor belongs to another session");
        long seq = Long.parseLong(cursor.substring(session.length() + 1));
        if (seq < 0) throw new IllegalArgumentException("Invalid cursor");
        return seq;
    }

    static Frame frame(SessionEvent fact) {
        var data = new LinkedHashMap<String, Object>();
        var payload = fact.data();
        // The live trace shows identity/boundaries; large requests and checkpoints are read on
        // demand.
        for (String key :
                List.of(
                        "message",
                        "requestId",
                        "kind",
                        "call",
                        "status",
                        "reason",
                        "modelCallId",
                        "toolCallId"))
            if (payload.get(key) != null) data.put(key, payload.get(key));
        return new Frame(
                fact.seq(),
                fact.eventId(),
                fact.type(),
                fact.turnId(),
                fact.executionRunId(),
                data);
    }

    /** Read-only view: SessionViews/SessionProjection cannot advance beyond this snapshot's head. */
    private record Prefix(SessionLog.Head head, List<SessionEvent> facts) implements SessionLog {
        public List<SessionEvent> readAfter(long seq, int limit) {
            return facts.stream().filter(e -> e.seq() > seq).limit(limit).toList();
        }

        public Iterable<SessionEvent> scan(long after, long through) {
            return facts.stream().filter(e -> e.seq() > after && e.seq() <= through).toList();
        }

        public Writer acquire(String owner, Duration lease) {
            throw new UnsupportedOperationException();
        }

        public void renew(Writer writer, Duration lease) {
            throw new UnsupportedOperationException();
        }

        public void release(Writer writer) {
            throw new UnsupportedOperationException();
        }

        public Head commit(Writer writer, String batch, long seq, List<SessionEvent> events) {
            throw new UnsupportedOperationException();
        }
    }
}
