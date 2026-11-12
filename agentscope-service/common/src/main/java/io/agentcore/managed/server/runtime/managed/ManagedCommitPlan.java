/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal, prepared platform changes. No native event names or Context keys are interpreted by
 * the metadata transaction. Objects have already been staged before entering the metadata transaction.
 * Only a trusted adapter may construct a plan.
 */
public record ManagedCommitPlan(
        SessionCommit boundary,
        String contentHash,
        List<Change> changes,
        List<ToolBinding> toolBindings,
        RuntimeObjectStore.Reference recoverySegment,
        List<ModelRequest> modelRequests,
        List<ModelOutcome> modelOutcomes,
        List<Finalization> finalizations,
        String metricKind) {
    public ManagedCommitPlan {
        changes = List.copyOf(changes);
        toolBindings = List.copyOf(toolBindings);
        modelRequests = List.copyOf(modelRequests);
        modelOutcomes = List.copyOf(modelOutcomes);
        finalizations = List.copyOf(finalizations);
        if (changes.size() != boundary.recoveryFacts().size()
                || toolBindings.size() != boundary.toolIntents().size())
            throw new IllegalArgumentException(
                    "Prepared changes differ from the recovery boundary");
        for (int i = 0; i < changes.size(); i++) {
            var change = changes.get(i);
            if (change.sequence() != boundary.recoveryFacts().get(i).sequence())
                throw new IllegalArgumentException("Prepared change position differs");
            if (change.checkpoint() != null) {
                var identity = change.checkpoint().reference().identity();
                if (!boundary.scope().equals(identity.scope())
                        || !boundary.turnId().equals(identity.turnId())
                        || boundary.epoch() != identity.epoch()
                        || change.sequence() != identity.nativeSequence())
                    throw new IllegalArgumentException("Prepared checkpoint identity differs");
            }
        }
    }

    public record Change(
            long sequence,
            List<ToolCall> toolCalls,
            String modelAnchor,
            Object iterationPosition,
            Boolean reasoningCompleted,
            ModelUsage modelUsage,
            Checkpoint checkpoint,
            Interaction interaction,
            List<PublicFact> publicFacts,
            String executionStatus) {
        public Change {
            toolCalls = List.copyOf(toolCalls);
            publicFacts = List.copyOf(publicFacts);
        }
    }

    public record ToolCall(String callId, boolean dispatched) {}

    public record ModelUsage(
            String callId, String status, Map<String, Object> usage, boolean reported) {
        public ModelUsage {
            usage = Collections.unmodifiableMap(new LinkedHashMap<>(usage));
        }
    }

    public record Checkpoint(
            ManagedCheckpointObjects.Reference reference, long occurredAt, PendingState pending) {}

    /** Null lists mean that the native capture did not contain the corresponding evidence. */
    public record PendingState(
            List<?> taskIds, List<?> actionIds, List<CapturedOperation> operations) {
        public PendingState {
            taskIds =
                    taskIds == null ? null : Collections.unmodifiableList(new ArrayList<>(taskIds));
            actionIds =
                    actionIds == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(actionIds));
            operations = operations == null ? null : List.copyOf(operations);
        }
    }

    public record CapturedOperation(
            String taskId, Object executionId, Object operationId, Object name, Object arguments) {}

    public record Interaction(String id, String kind, String payload, boolean requested) {}

    public record PublicFact(String sourceId, String type, Map<String, Object> payload) {}

    public record ToolBinding(Object nativeCallId, List<BatchCall> batchCalls) {
        public ToolBinding {
            batchCalls = List.copyOf(batchCalls);
        }
    }

    public record BatchCall(String taskId, String nativeCallId, int ordinal) {}

    public record ModelRequest(
            String callId,
            long sequence,
            Long checkpointSequence,
            @JsonInclude(JsonInclude.Include.NON_NULL) String purpose) {
        public ModelRequest(String callId, long sequence, Long checkpointSequence) {
            this(callId, sequence, checkpointSequence, null);
        }
    }

    public record ModelOutcome(
            ModelCallResult result, RuntimeObjectStore.Reference reference, Object replayHash) {}

    public enum FinalizationKind {
        OPEN,
        CLOSE,
        MODEL
    }

    public record Finalization(
            FinalizationKind kind,
            String frameId,
            long sequence,
            String captureReference,
            int slot,
            Object modelCallId,
            Object requestHash) {}
}
