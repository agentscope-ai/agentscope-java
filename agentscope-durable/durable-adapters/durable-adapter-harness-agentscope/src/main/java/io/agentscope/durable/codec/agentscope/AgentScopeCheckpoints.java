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

package io.agentscope.durable.codec.agentscope;

import io.agentscope.core.journal.JournalEvent;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.CheckpointRestore;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.Restoration;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.codec.NativeCheckpointCodec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Backend-only restore/fork of a settled native baseline, without constructing an Agent.
 * Private session resources are restored with the baseline; SessionBackend installs it atomically.
 */
public final class AgentScopeCheckpoints {
    private final SessionBackend backend;
    private final AgentScopeCheckpointCodec codec = new AgentScopeCheckpointCodec();

    public AgentScopeCheckpoints(SessionBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    public static CommitReceipt restore(SessionBackend backend, CheckpointRestore request) {
        return new AgentScopeCheckpoints(backend)
                .restore(
                        request.source(),
                        request.position(),
                        request.target(),
                        request.expectedPosition(),
                        request.requestId());
    }

    public CommitReceipt restore(
            ThreadKey source,
            long position,
            ThreadSpec target,
            long expectedTargetPosition,
            String requestId) {
        var snapshot = backend.checkpoint(source, position);
        snapshot.metadata().verify(snapshot.definition(), snapshot.checkpoint());
        if (!snapshot.settled())
            throw new DurableException(
                    DurableException.Code.CONFLICT, "Checkpoint contains unfinished execution");
        if (!source.session().namespace().equals(target.key().session().namespace())
                || !snapshot.definition().harness().equals(target.harness())
                || !snapshot.definition().definitionVersion().equals(target.definitionVersion())
                || !snapshot.definition().stateVersion().equals(target.stateVersion())
                || !AgentScopeCheckpointCodec.STATE_VERSION.equals(target.stateVersion()))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE,
                    "Checkpoint scope or frozen definition differs");
        String statePayload = null;
        long after = 0;
        while (after < snapshot.journalPosition()) {
            var page = backend.checkpointJournal(source, position, after, 1000);
            if (page.isEmpty())
                throw new DurableException(
                        DurableException.Code.INVALID, "Incomplete checkpoint journal");
            for (var entry : page) {
                var event = DurableJson.convert(entry, JournalEvent.class);
                if (event.seq() != ++after)
                    throw new DurableException(
                            DurableException.Code.INVALID, "Discontinuous checkpoint journal");
                var inspection =
                        codec.inspect(
                                new NativeCheckpointCodec.Event(
                                        event.type(),
                                        event.executionRunId(),
                                        event.required(),
                                        event.payloadJson()));
                if (inspection.checkpoint()) {
                    statePayload = inspection.settled() ? event.payloadJson() : null;
                }
            }
        }
        if (statePayload == null)
            throw new DurableException(
                    DurableException.Code.CONFLICT, "No settled native state at checkpoint");
        String identity =
                "restore:" + DurableJson.hash(List.of(source, position, target, requestId));
        var baseline =
                codec.baseline(
                        statePayload,
                        target.key().session().sessionId(),
                        target.key().threadId(),
                        target.key().session().namespace(),
                        !source.equals(target.key()),
                        Map.of("sourceCheckpoint", position, "requestId", requestId));
        var event =
                new JournalEvent(
                        JournalEvent.SCHEMA_VERSION,
                        identity,
                        1,
                        1,
                        baseline.type(),
                        identity,
                        null,
                        true,
                        baseline.payloadJson());
        var checkpoint =
                DurableJson.value(
                        Map.of(
                                "format",
                                AgentScopeCheckpointCodec.STATE_VERSION,
                                "position",
                                1,
                                "lastCommit",
                                identity));
        return backend.restore(
                new Restoration(
                        requestId,
                        expectedTargetPosition,
                        target,
                        checkpoint,
                        List.of(DurableJson.value(event)),
                        backend.checkpointResources(source, position)));
    }
}
