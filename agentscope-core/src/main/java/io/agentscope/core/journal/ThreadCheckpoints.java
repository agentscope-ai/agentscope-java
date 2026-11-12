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
package io.agentscope.core.journal;

import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Current-format Thread checkpoint initialization and fork; never invents invocation history. */
public final class ThreadCheckpoints {
    private ThreadCheckpoints() {}

    public static void initialize(
            ThreadJournal target, AgentState state, String source, long sourceSequence) {
        String json = state.toJson(),
                hash = AtomicThreadJournal.hash(json.getBytes(StandardCharsets.UTF_8));
        String batch = "baseline-" + hash;
        var writer = target.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            if (target.head().seq() > 0) {
                var first = target.readAfter(0, 1).get(0);
                if (first.type().equals("state/initialized")
                        && hash.equals(first.data().get("sourceHash"))) return;
                throw new JournalException("Thread checkpoint destination is not empty");
            }
            var payload =
                    Map.of(
                            "stateJson",
                            json,
                            "coverage",
                            "baseline_only",
                            "source",
                            source,
                            "sourceSequence",
                            sourceSequence,
                            "sourceHash",
                            hash);
            target.commit(
                    writer,
                    batch,
                    0,
                    List.of(
                            new JournalEvent(
                                    JournalEvent.SCHEMA_VERSION,
                                    UUID.randomUUID().toString(),
                                    1,
                                    System.currentTimeMillis(),
                                    "state/initialized",
                                    null,
                                    null,
                                    true,
                                    JsonUtils.getJsonCodec().toJson(payload))));
        } finally {
            target.release(writer);
        }
    }

    public record ExportedState(AgentState state, long asOfSeq) {}

    public static AgentState exportState(ThreadJournal source) {
        return exportSnapshot(source).state();
    }

    public static ExportedState exportSnapshot(ThreadJournal source) {
        if (source.head().owner() != null)
            throw new JournalException("Stop the active invocation before exporting the Thread");
        var writer = source.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            var projection = JournalProjection.read(source);
            if (!projection.uncertainToolCalls().isEmpty() || !projection.activeRuns().isEmpty())
                throw new JournalException("Reconcile interrupted execution before exporting");
            if (!ThreadInteractions.pending(source).isEmpty())
                throw new JournalException(
                        "Resolve pending interactions before exporting or forking");
            if (projection.stateJson() == null)
                throw new JournalException("No recoverable baseline");
            return new ExportedState(projection.restore(), projection.asOfSeq());
        } finally {
            source.release(writer);
        }
    }

    public static void fork(
            ThreadJournal source,
            ThreadJournal destination,
            ThreadKey destinationKey,
            String sourceReference) {
        var snapshot = exportSnapshot(source);
        var codec = JsonUtils.getJsonCodec();
        var state =
                new LinkedHashMap<String, Object>(
                        codec.fromJson(snapshot.state().toJson(), Map.class));
        state.put("session_id", destinationKey.sessionId());
        state.put("thread_id", destinationKey.threadId());
        state.put("user_id", destinationKey.userId());
        initialize(
                destination,
                AgentState.fromJsonString(codec.toJson(state)),
                "fork:" + sourceReference,
                snapshot.asOfSeq());
    }
}
