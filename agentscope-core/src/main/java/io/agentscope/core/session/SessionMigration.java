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

import io.agentscope.core.state.AgentState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Offline migration/fork primitives. Source archives are retained; checkpoints never invent past facts. */
public final class SessionMigration {
    private SessionMigration() {}

    public static void importBaseline(
            SessionLog target, AgentState state, String source, long sourceVersion) {
        String json = state.toJson(),
                hash = JournalSessionLog.hash(json.getBytes(StandardCharsets.UTF_8));
        String batch = "baseline-" + hash;
        var writer = target.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            if (target.head().seq() > 0) {
                var first = target.readAfter(0, 1).get(0);
                if (first.type().equals("migration/baseline")
                        && hash.equals(first.data().get("sourceHash"))) return;
                throw new SessionLogException("Migration destination is not empty");
            }
            var payload =
                    Map.of(
                            "stateJson",
                            json,
                            "coverage",
                            "baseline_only",
                            "source",
                            source,
                            "sourceVersion",
                            sourceVersion,
                            "sourceHash",
                            hash);
            target.commit(
                    writer,
                    batch,
                    0,
                    List.of(
                            new SessionEvent(
                                    1,
                                    UUID.randomUUID().toString(),
                                    1,
                                    System.currentTimeMillis(),
                                    "migration/baseline",
                                    writer.owner(),
                                    null,
                                    true,
                                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                                            .toJson(payload))));
        } finally {
            target.release(writer);
        }
    }

    public record ExportedState(AgentState state, long asOfSeq) {}

    public static AgentState exportState(SessionLog source) {
        return exportSnapshot(source).state();
    }

    public static ExportedState exportSnapshot(SessionLog source) {
        if (source.head().owner() != null)
            throw new SessionLogException("Pause the source session before migration");
        var writer = source.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            var projection = SessionProjection.read(source);
            if (!projection.uncertainToolCalls().isEmpty() || !projection.activeRuns().isEmpty())
                throw new SessionLogException("Reconcile interrupted execution before exporting");
            if (!SessionInteractions.pending(source).isEmpty())
                throw new SessionLogException(
                        "Resolve pending interactions before exporting or forking");
            if (projection.stateJson() == null)
                throw new SessionLogException("No recoverable baseline");
            return new ExportedState(projection.restore(), projection.asOfSeq());
        } finally {
            source.release(writer);
        }
    }

    public static void fork(
            SessionLog source,
            SessionLog destination,
            SessionKey destinationKey,
            String sourceReference) {
        var snapshot = exportSnapshot(source);
        var codec = io.agentscope.core.util.JsonUtils.getJsonCodec();
        var state =
                new java.util.LinkedHashMap<String, Object>(
                        codec.fromJson(snapshot.state().toJson(), Map.class));
        state.put("session_id", destinationKey.sessionId());
        state.put("user_id", destinationKey.userId());
        importBaseline(
                destination,
                AgentState.fromJsonString(codec.toJson(state)),
                "fork:" + sourceReference,
                snapshot.asOfSeq());
    }
}
