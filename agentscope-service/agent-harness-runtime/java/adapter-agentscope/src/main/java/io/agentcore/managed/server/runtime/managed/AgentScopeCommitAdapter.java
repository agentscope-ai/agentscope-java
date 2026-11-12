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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.web.managed.CommittedSessionEventProjector;
import io.agentscope.core.session.SessionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Interprets AgentScope records and prepares explicit changes before the platform transaction.
 * This adapter does not write SQL metadata.
 */
final class AgentScopeCommitAdapter {
    private AgentScopeCommitAdapter() {}

    static ManagedCommitPlan prepare(
            ManagedRuntimeBatch batch,
            String hash,
            ObjectMapper mapper,
            ManagedJournalStore journal) {
        var contexts =
                ManagedBoundaryMetrics.measure(
                        ManagedBoundaryMetrics.Phase.CHECKPOINT_VERIFY,
                        () ->
                                journal.objects()
                                        .verify(batch.scope(), batch.epoch(), batch.events()));
        var models = new AgentScopeModelResults(mapper, journal);
        var outcomes =
                ManagedBoundaryMetrics.measure(
                        ManagedBoundaryMetrics.Phase.MODEL_PREPARE, () -> models.prepare(batch));
        var stored = models.references(batch, outcomes);
        var segment =
                ManagedBoundaryMetrics.measure(
                        ManagedBoundaryMetrics.Phase.RECOVERY_STAGE,
                        () -> journal.stageNative(batch.scope(), stored.events()));
        var changes = new ArrayList<ManagedCommitPlan.Change>();
        var requests = new ArrayList<ManagedCommitPlan.ModelRequest>();
        var finalizations = new ArrayList<ManagedCommitPlan.Finalization>();
        Long entryCheckpoint = null;
        long suspensionCheckpoint =
                stored.events().stream()
                        .filter(
                                event ->
                                        List.of(
                                                        "state/checkpoint",
                                                        "migration/baseline",
                                                        "state/restored")
                                                .contains(event.type()))
                        .mapToLong(SessionEvent::seq)
                        .max()
                        .orElse(-1);
        for (var event : stored.events()) {
            changes.add(
                    change(
                            batch.scope(),
                            event,
                            contexts.get(event.seq()),
                            batch.suspension() != null && event.seq() == suspensionCheckpoint,
                            mapper,
                            journal.objects()));
            // The model-entry checkpoint rules deliberately differ from the latest recovery head.
            if ("state/checkpoint".equals(event.type())) entryCheckpoint = event.seq();
            if ("request/prepared".equals(event.type()))
                requests.add(
                        new ManagedCommitPlan.ModelRequest(
                                String.valueOf(event.data().get("modelCallId")),
                                event.seq(),
                                entryCheckpoint,
                                String.valueOf(event.data().get("purpose"))
                                        .toLowerCase(Locale.ROOT)));
            var data = event.data();
            if (ManagedFinalization.START.equals(event.type()))
                finalizations.add(
                        new ManagedCommitPlan.Finalization(
                                ManagedCommitPlan.FinalizationKind.OPEN,
                                String.valueOf(data.get("id")),
                                event.seq(),
                                journal.objects()
                                        .encodeReference(
                                                journal.objects().reference(batch.scope(), event)),
                                0,
                                null,
                                null));
            else if (ManagedFinalization.END.equals(event.type()))
                finalizations.add(
                        new ManagedCommitPlan.Finalization(
                                ManagedCommitPlan.FinalizationKind.CLOSE,
                                String.valueOf(data.get("id")),
                                event.seq(),
                                null,
                                0,
                                null,
                                null));
            else if (ManagedFinalization.MODEL.equals(event.type()))
                finalizations.add(
                        new ManagedCommitPlan.Finalization(
                                ManagedCommitPlan.FinalizationKind.MODEL,
                                (String) data.get("frame"),
                                event.seq(),
                                null,
                                ((Number) data.get("slot")).intValue(),
                                data.get("modelCallId"),
                                data.get("requestHash")));
        }
        var bindings = new ArrayList<ManagedCommitPlan.ToolBinding>();
        for (var intent : batch.toolIntents()) {
            var children = new ArrayList<ManagedCommitPlan.BatchCall>();
            String task =
                    "tool_"
                            + ManagedScope.fingerprint(
                                    batch.scope().messageGroup(),
                                    batch.turnId(),
                                    intent.toolCallId());
            if (ManagedMcpBatch.OPERATION.equals(intent.toolName())) {
                int ordinal = 0;
                for (var call : ManagedMcpBatch.manifest(intent.arguments()).calls())
                    children.add(
                            new ManagedCommitPlan.BatchCall(
                                    ManagedMcpBatch.childTaskId(task, call.callId()),
                                    call.callId(),
                                    ordinal++));
            }
            try {
                var arguments = mapper.readValue(intent.arguments(), Map.class);
                bindings.add(
                        new ManagedCommitPlan.ToolBinding(
                                arguments.get("native_tool_call_id"), children));
            } catch (JsonProcessingException invalid) {
                throw new IllegalArgumentException("Cannot decode runtime boundary", invalid);
            }
        }
        return new ManagedCommitPlan(
                ManagedSessionCommitAdapter.encode(stored),
                hash,
                changes,
                bindings,
                segment,
                requests,
                outcomes.stream()
                        .map(
                                value ->
                                        new ManagedCommitPlan.ModelOutcome(
                                                value.result(),
                                                value.reference(),
                                                ((Map<?, ?>)
                                                                value.end()
                                                                        .data()
                                                                        .get(
                                                                                ManagedModelCapture
                                                                                        .OUTCOME))
                                                        .get("replayHash")))
                        .toList(),
                finalizations,
                metricKind(batch));
    }

    private static ManagedCommitPlan.Change change(
            ManagedScope scope,
            SessionEvent event,
            Map<String, Object> context,
            boolean capturePending,
            ObjectMapper mapper,
            ManagedCheckpointObjects objects) {
        var data = event.data();
        var calls = new ArrayList<ManagedCommitPlan.ToolCall>();
        switch (event.type()) {
            case "tool/dispatch" ->
                    calls.add(
                            new ManagedCommitPlan.ToolCall(
                                    String.valueOf(data.get("toolCallId")), true));
            case "action/end" -> {
                if (data.get("result") != null)
                    calls.add(
                            new ManagedCommitPlan.ToolCall(
                                    String.valueOf(data.get("toolCallId")), false));
            }
            case "recovery/applied" -> {
                if (data.get("resolvedToolCallIds") instanceof List<?> ids)
                    for (var id : ids)
                        calls.add(new ManagedCommitPlan.ToolCall(String.valueOf(id), false));
            }
            default -> {}
        }
        String anchor = null;
        if ("model/dispatch".equals(event.type())) {
            anchor = String.valueOf(data.get("modelCallId"));
            ManagedScope.requireText(anchor, "model call id");
        }
        Object iteration = null;
        Boolean reasoning = null;
        if ("step/start".equals(event.type())) {
            iteration = data.get("iteration");
            if (iteration == null)
                throw new ManagedStateException(
                        "native_event_invalid", "Step start requires an iteration");
            reasoning = false;
        } else if ("message/assistant".equals(event.type())) reasoning = true;
        ManagedCommitPlan.Checkpoint checkpoint = null;
        if (List.of("state/checkpoint", "migration/baseline", "state/restored")
                .contains(event.type()))
            checkpoint =
                    new ManagedCommitPlan.Checkpoint(
                            objects.reference(scope, event),
                            event.occurredAt(),
                            capturePending ? pending(context) : null);
        ManagedCommitPlan.Interaction interaction = null;
        if ("interaction/requested".equals(event.type()))
            interaction =
                    new ManagedCommitPlan.Interaction(
                            String.valueOf(data.get("requestId")),
                            String.valueOf(data.get("kind")),
                            event.payloadJson(),
                            true);
        else if ("interaction/resolved".equals(event.type()))
            interaction =
                    new ManagedCommitPlan.Interaction(
                            String.valueOf(data.get("requestId")), null, null, false);
        var facts = new ArrayList<ManagedCommitPlan.PublicFact>();
        for (var fact : CommittedSessionEventProjector.projectAll(event))
            facts.add(
                    new ManagedCommitPlan.PublicFact(
                            CommittedSessionEventProjector.projectionId(event, fact),
                            fact.type(),
                            fact.payload()));
        String status = null;
        if (List.of("turn/start", "turn/resumed", "turn/suspended").contains(event.type())) {
            status = "turn/suspended".equals(event.type()) ? "waiting" : "running";
            // Kept after ordinary projections, preserving source IDs and public event order.
            facts.add(
                    new ManagedCommitPlan.PublicFact(
                            event.eventId() + ":status",
                            "waiting".equals(status) ? "turn.requires_action" : "turn.running",
                            Map.of("turn_id", event.turnId(), "status", status)));
        }
        return new ManagedCommitPlan.Change(
                event.seq(),
                calls,
                anchor,
                iteration,
                reasoning,
                usage(event, mapper),
                checkpoint,
                interaction,
                facts,
                status);
    }

    static ManagedCommitPlan.ModelUsage usage(SessionEvent event, ObjectMapper mapper) {
        if (!"model/end".equals(event.type())
                || !(event.data().get("modelCallId") instanceof String id)) return null;
        var data = event.data();
        Map<String, Object> usage =
                data.get("usage") instanceof Map<?, ?> value
                        ? mapper.convertValue(value, new TypeReference<Map<String, Object>>() {})
                        : Map.of();
        return new ManagedCommitPlan.ModelUsage(
                id, String.valueOf(data.get("status")), usage, data.get("usage") != null);
    }

    private static ManagedCommitPlan.PendingState pending(Map<String, Object> context) {
        List<ManagedCommitPlan.CapturedOperation> operations = null;
        if (context.get("toolOperations") instanceof List<?> values) {
            operations = new ArrayList<>();
            var seen = new HashSet<String>();
            for (var value : values) {
                if (!(value instanceof Map<?, ?> operation)
                        || !(operation.get("taskId") instanceof String task)
                        || !seen.add(task))
                    throw new ManagedStateException(
                            "checkpoint_tool_conflict", "Invalid checkpoint operation identity");
                operations.add(
                        new ManagedCommitPlan.CapturedOperation(
                                task,
                                operation.get("executionId"),
                                operation.get("operationToolCallId"),
                                operation.get("name"),
                                operation.get("arguments")));
            }
        }
        return new ManagedCommitPlan.PendingState(
                context.get("pendingTaskIds") instanceof List<?> tasks ? tasks : null,
                context.get("pendingActionIds") instanceof List<?> actions ? actions : null,
                operations);
    }

    static String metricKind(ManagedRuntimeBatch batch) {
        String kind =
                batch.terminalStatus() != null
                        ? "terminal"
                        : batch.suspension() != null
                                ? "suspend"
                                : !batch.toolIntents().isEmpty() ? "tool_handoff" : "state";
        if (kind.equals("state")) {
            var types = batch.events().stream().map(SessionEvent::type).collect(Collectors.toSet());
            if (types.contains("model/end")) kind = "model_result";
            else if (types.contains("model/dispatch")) kind = "model_dispatch";
            else if (types.contains("run/end")) kind = "run_end";
            else if (types.contains("run/start")) kind = "run_start";
            else if (types.contains("inbox/applied")) kind = "input";
        }
        return kind;
    }
}
