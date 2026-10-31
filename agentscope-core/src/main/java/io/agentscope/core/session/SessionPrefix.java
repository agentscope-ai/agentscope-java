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

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One replay of a committed prefix feeding every read-only view taken at that prefix.
 *
 * <p>A turn start needs the turn view, pending interactions, the recovery projection and the
 * transcript. Each is a pure function of the same committed event prefix, so replaying the prefix
 * once and feeding all of them from that single pass replaces four independent full replays.
 */
public final class SessionPrefix {
    private final long asOfSeq;
    private final SessionTurns turns;
    private final SessionProjection projection;
    private final Map<String, SessionEvent> pending;
    private final SessionViews.Transcript transcript;
    private final Set<String> runIds;
    private final Set<String> turnIds;
    private final Set<String> completedTurnIds;

    private SessionPrefix(
            long asOfSeq,
            SessionTurns turns,
            SessionProjection projection,
            Map<String, SessionEvent> pending,
            SessionViews.Transcript transcript,
            Set<String> runIds,
            Set<String> turnIds,
            Set<String> completedTurnIds) {
        this.asOfSeq = asOfSeq;
        this.turns = turns;
        this.projection = projection;
        this.pending = pending;
        this.transcript = transcript;
        this.runIds = runIds;
        this.turnIds = turnIds;
        this.completedTurnIds = completedTurnIds;
    }

    static SessionPrefix read(SessionLog log, long upper) {
        var turns = new SessionTurns.Builder();
        var projection = new SessionProjection.Builder();
        var interactions = new SessionInteractions.Builder();
        var transcript = new SessionViews.Builder();
        var runIds = new LinkedHashSet<String>();
        var turnIds = new LinkedHashSet<String>();
        var completedTurnIds = new LinkedHashSet<String>();
        for (var event : log.scan(0, upper)) {
            turns.accept(event);
            projection.accept(event);
            interactions.accept(event);
            transcript.accept(event);
            if (event.executionRunId() != null) runIds.add(event.executionRunId());
            if (event.turnId() != null) {
                turnIds.add(event.turnId());
                if (event.type().equals("turn/completed")
                        || (event.type().equals("turn/end")
                                && "completed".equals(event.data().get("status"))))
                    completedTurnIds.add(event.turnId());
            }
        }
        return new SessionPrefix(
                upper,
                turns.build(),
                projection.build(),
                interactions.build(),
                transcript.build(upper),
                Set.copyOf(runIds),
                Set.copyOf(turnIds),
                Set.copyOf(completedTurnIds));
    }

    long asOfSeq() {
        return asOfSeq;
    }

    SessionTurns turns() {
        return turns;
    }

    SessionProjection projection() {
        return projection;
    }

    Map<String, SessionEvent> pending() {
        return pending;
    }

    SessionViews.Transcript transcript() {
        return transcript;
    }

    Set<String> runIds() {
        return runIds;
    }

    Set<String> turnIds() {
        return turnIds;
    }

    Set<String> completedTurnIds() {
        return completedTurnIds;
    }
}
