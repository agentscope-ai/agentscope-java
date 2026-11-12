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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.agentscope.AgentScopeEventSchemas;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.core.agent.AgentExecutionPosition;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.util.JsonUtils;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Call-scoped continuation and cached head of the durably committed native log. */
final class ManagedLocalJournal {
    private static final Logger log = LoggerFactory.getLogger(ManagedLocalJournal.class);
    private final ManagedScope scope;
    private final ManagedJournalStore journal;
    private final ManagedJournalReads reads;
    private final long startupSequence;

    private record Prepared(String sourceHash, ManagedRuntimeBatch wire) {}

    private final Map<String, String> batches = new LinkedHashMap<>();
    private final Map<String, Prepared> prepared = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> taskFacts = new LinkedHashMap<>();
    private final Map<String, Long> inputPositions = new LinkedHashMap<>();
    private final Map<String, Object> resources;
    private long appliedInputSequence;
    private final Set<String> settledInputs = new LinkedHashSet<>();
    private final Set<String> pendingTools = new LinkedHashSet<>();
    private final Map<String, Map<String, ToolTask>> frameworkTasks = new LinkedHashMap<>();
    private final String turn;
    private String modelAnchor;
    private long firstSequence;
    private String frameworkScope;
    private SessionRecoveryHead head;
    private final LinkedHashMap<Long, SessionRecoveryHead> views = new LinkedHashMap<>();
    private boolean handedOff;

    private record IterationPosition(int iteration, boolean reasoningCompleted) {
        AgentExecutionPosition entry() {
            return new AgentExecutionPosition(
                    reasoningCompleted ? Math.addExact(iteration, 1) : iteration, iteration);
        }
    }

    private IterationPosition iterationPosition = new IterationPosition(0, false);

    ManagedLocalJournal(ManagedScope scope, String turn, ManagedJournalStore journal) {
        AgentScopeEventSchemas.ensureRegistered();
        this.scope = scope;
        this.journal = journal;
        this.reads = new ManagedJournalReads(scope, journal);
        this.turn = turn;
        var snapshot = journal.executionSnapshot(scope, turn);
        this.head = snapshot.head();
        views.put(head.sequence(), head);
        this.startupSequence = head.sequence();
        this.modelAnchor = snapshot.modelAnchor();
        resources = snapshot.context();
        appliedInputSequence = ((Number) resources.get("appliedInputSeq")).longValue();
        restoreContinuation();
        var position = snapshot.position();
        iterationPosition =
                new IterationPosition(position.iteration(), position.reasoningCompleted());
        firstSequence = position.firstSequence();
        for (var task : journal.continuationTools(scope, turn)) {
            var fresh = taskFact(task);
            var captured = taskFacts.get(task.id());
            if (captured != null) {
                for (String key :
                        List.of("executionId", "operationToolCallId", "name", "arguments"))
                    if (!Objects.equals(captured.get(key), fresh.get(key)))
                        throw new ManagedStateException(
                                "checkpoint_tool_conflict",
                                "Captured operation differs from its durable ToolTask");
                if (fresh.get("resultJson") != null)
                    captured.put("resultJson", fresh.get("resultJson"));
                if (fresh.get("processHandle") != null)
                    captured.put("processHandle", fresh.get("processHandle"));
            } else taskFacts.put(task.id(), fresh);
            if (task.result() == null) pendingTools.add(task.id());
        }
    }

    private void restoreContinuation() {
        var reference = head.checkpoint();
        if (reference == null) return;
        if (reference.identity().nativeSequence() != head.checkpointSequence())
            throw new ManagedStateException(
                    "checkpoint_position_conflict",
                    "Checkpoint pointer does not match its native position");
        if (!turn.equals(reference.identity().turnId())) return;
        var image = journal.objects().read(reference);
        if (image.continuation().get("iterationPosition") instanceof Map<?, ?> position) {
            iterationPosition =
                    new IterationPosition(
                            ((Number) position.get("iteration")).intValue(),
                            Boolean.TRUE.equals(position.get("reasoningCompleted")));
            iterationPosition.entry();
        }
        if (image.continuation().get("toolOperations") instanceof List<?> operations)
            for (var value : operations) {
                if (!(value instanceof Map<?, ?> operation)
                        || !(operation.get("taskId") instanceof String id))
                    throw new ManagedStateException(
                            "checkpoint_tool_conflict",
                            "Captured operation has no stable identity");
                var restored = new LinkedHashMap<String, Object>();
                operation.forEach((key, entry) -> restored.put((String) key, entry));
                taskFacts.put(id, restored);
            }
        if (image.continuation().get("settledInputIds") instanceof List<?> ids)
            for (var id : ids) settledInputs.add((String) id);
        ManagedRuntimeLog.checkpoint(
                log,
                "harness_runtime.checkpoint.loaded",
                scope,
                turn,
                reference,
                "epoch",
                head.epoch(),
                "resume_phase",
                image.continuation().get("resumePhase"));
    }

    private final Map<String, ManagedModelCapture> modelCaptures = new LinkedHashMap<>();

    synchronized void modelStarted(String call, ManagedModelCapture capture) {
        modelCaptures.put(call, capture);
    }

    synchronized void modelChunk(String call, ChatResponse chunk) {
        var capture = modelCaptures.get(call);
        if (capture == null)
            throw new ManagedStateException("model_outcome_missing", "Model capture is not active");
        capture.accept(chunk);
    }

    private String toolConfiguration;

    synchronized void toolConfiguration(String value) {
        toolConfiguration = value;
    }

    synchronized String toolConfiguration() {
        return toolConfiguration;
    }

    synchronized SessionRecoveryHead head() {
        return head;
    }

    /** Durable commit and local advancement are serialized across Recorder, Inbox and tool boundaries. */
    synchronized SessionCommitReceipt publish(
            ManagedRuntimeBatch batch, ManagedRuntimeWriter writer, ManagedExecutionGuard guard) {
        batch =
                batch.withEvents(
                        batch.events().stream()
                                .map(
                                        event -> {
                                            if (!"model/end".equals(event.type())) return event;
                                            String call =
                                                    String.valueOf(event.data().get("modelCallId"));
                                            var capture = modelCaptures.get(call);
                                            if (capture == null) return event;
                                            var data =
                                                    new LinkedHashMap<String, Object>(event.data());
                                            data.put(
                                                    ManagedModelCapture.OUTCOME,
                                                    capture.freeze(
                                                            String.valueOf(data.get("status"))));
                                            return new SessionEvent(
                                                    event.schemaVersion(),
                                                    event.eventId(),
                                                    event.seq(),
                                                    event.occurredAt(),
                                                    event.type(),
                                                    event.executionRunId(),
                                                    event.turnId(),
                                                    event.required(),
                                                    JsonUtils.getJsonCodec().toJson(data));
                                        })
                                .toList());
        var nativeEvents = batch.events();
        String sourceHash = AgentScopePreparation.hash(new ObjectMapper(), batch);
        var prior = prepared.get(batch.batchId());
        if (prior != null && !prior.sourceHash().equals(sourceHash))
            throw new ManagedStateException(
                    "batch_content_conflict", "Accepted batch identity was reused");
        if (prior != null) batch = prior.wire();
        else {
            if (handedOff)
                throw new ManagedStateException("writer_sealed", "Execution already handed off");
            guard.check();
            try {
                var unprepared = batch;
                batch =
                        ManagedBoundaryMetrics.measure(
                                ManagedBoundaryMetrics.Phase.CHECKPOINT_PREPARE,
                                () ->
                                        AgentScopeCheckpoints.prepare(
                                                journal.objects(),
                                                unprepared,
                                                continuation(unprepared)));
            } catch (RuntimeException unavailable) {
                guard.invalidate("Checkpoint object was not durably captured", unavailable);
                throw unavailable;
            }
            prepared.put(batch.batchId(), new Prepared(sourceHash, batch));
        }
        String hash = AgentScopePreparation.hash(new ObjectMapper(), batch);
        String previous = batches.get(batch.batchId());
        if (previous != null) {
            if (!previous.equals(hash))
                throw new ManagedStateException(
                        "batch_content_conflict", "Accepted batch identity was reused");
            return new SessionCommitReceipt(
                    batch.batchId(),
                    hash,
                    "committed",
                    null,
                    batch.expectedSequence() + batch.events().size(),
                    batch.epoch());
        }
        if (handedOff)
            throw new ManagedStateException("writer_sealed", "Execution already handed off");
        if (batch.expectedSequence() != head.sequence())
            throw new ManagedStateException(
                    "native_sequence_conflict", "Accepted local prefix differs");
        SessionCommitReceipt receipt;
        try {
            receipt = writer.commit(batch, guard);
        } catch (RuntimeException unavailable) {
            guard.invalidate("Runtime commit outcome is not confirmed", unavailable);
            throw unavailable;
        }
        if (firstSequence == 0 && !nativeEvents.isEmpty())
            firstSequence = nativeEvents.get(0).seq();
        iterationPosition = positionAfter(iterationPosition, nativeEvents);
        markApplied(taskFacts, nativeEvents);
        ManagedCheckpointObjects.Reference checkpoint = head.checkpoint();
        long checkpointSequence = head.checkpointSequence();
        for (var event : batch.events()) {
            if ("model/dispatch".equals(event.type()))
                modelAnchor = String.valueOf(event.data().get("modelCallId"));
            if (List.of("state/checkpoint", "migration/baseline", "state/restored")
                    .contains(event.type())) {
                checkpoint = journal.objects().reference(scope, event);
                checkpointSequence = event.seq();
            }
        }
        for (var input : batch.inputReceipts()) {
            settledInputs.add(input.inputId());
            appliedInputSequence =
                    Math.max(
                            appliedInputSequence, inputPositions.getOrDefault(input.inputId(), 0L));
        }
        for (var tool : batch.toolIntents()) {
            var fact = intentFact(tool);
            String id = (String) fact.get("taskId");
            pendingTools.add(id);
            taskFacts.put(id, fact);
        }
        batches.put(batch.batchId(), hash);
        head =
                new SessionRecoveryHead(
                        receipt.sequence(),
                        batch.epoch(),
                        batch.executionToken(),
                        batch.batchId(),
                        checkpoint,
                        checkpointSequence);
        views.put(head.sequence(), head);
        while (views.size() > 16) views.remove(views.keySet().iterator().next());
        reads.committed(batch.events());
        for (var event : batch.events())
            if ("model/end".equals(event.type()))
                modelCaptures.remove(String.valueOf(event.data().get("modelCallId")));
        handedOff = batch.terminalStatus() != null || batch.suspension() != null;
        return receipt;
    }

    private Map<String, Object> continuation(ManagedRuntimeBatch batch) {
        var value = new LinkedHashMap<String, Object>(resources);
        var position = positionAfter(iterationPosition, batch.events());
        value.put(
                "iterationPosition",
                Map.of(
                        "iteration",
                        position.iteration(),
                        "reasoningCompleted",
                        position.reasoningCompleted()));
        var settled = new LinkedHashSet<>(settledInputs);
        long through = appliedInputSequence;
        for (var receipt : batch.inputReceipts()) {
            settled.add(receipt.inputId());
            through = Math.max(through, inputPositions.getOrDefault(receipt.inputId(), 0L));
        }
        var operations = new LinkedHashMap<String, Map<String, Object>>();
        for (var entry : taskFacts.entrySet())
            operations.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        markApplied(operations, batch.events());
        for (var tool : batch.toolIntents()) {
            var fact = intentFact(tool);
            operations.put((String) fact.get("taskId"), fact);
        }
        value.put("toolOperations", List.copyOf(operations.values()));
        var pending = new LinkedHashSet<>(pendingTools);
        for (var tool : batch.toolIntents()) pending.add((String) intentFact(tool).get("taskId"));
        value.put("pendingTaskIds", List.copyOf(pending));
        value.put("settledInputIds", List.copyOf(settled));
        value.put("appliedInputSeq", through);
        value.put("modelAnchor", modelAnchor);
        if (head.checkpoint() != null) value.put("previousCheckpoint", head.checkpoint());
        return value;
    }

    private IterationPosition positionAfter(IterationPosition initial, List<SessionEvent> events) {
        var position = initial;
        for (var event : events) {
            if (!turn.equals(event.turnId())) continue;
            if ("step/start".equals(event.type())) {
                position =
                        new IterationPosition(
                                ((Number) event.data().get("iteration")).intValue(), false);
                position.entry();
            } else if ("message/assistant".equals(event.type())) {
                position = new IterationPosition(position.iteration(), true);
            }
        }
        return position;
    }

    synchronized AgentExecutionPosition executionPosition() {
        return iterationPosition.entry();
    }

    private void markApplied(
            Map<String, Map<String, Object>> operations, List<SessionEvent> events) {
        var calls = new LinkedHashSet<String>();
        for (var event : events) {
            if (!"tool/result".equals(event.type())
                    || !(event.data().get("message") instanceof Map<?, ?> message)
                    || !(message.get("content") instanceof List<?> blocks)) continue;
            for (var block : blocks)
                if (block instanceof Map<?, ?> result
                        && "tool_result".equals(result.get("type"))
                        && result.get("id") instanceof String id) calls.add(id);
        }
        for (var operation : operations.values())
            if (calls.contains(operation.get("nativeToolCallId"))) operation.put("applied", true);
    }

    private Map<String, Object> intentFact(ManagedRuntimeBatch.ToolIntent intent) {
        String id =
                "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, intent.toolCallId());
        var fact = new LinkedHashMap<String, Object>();
        fact.put("taskId", id);
        fact.put("executionId", id);
        fact.put("operationToolCallId", intent.toolCallId());
        fact.put("name", intent.toolName());
        fact.put("arguments", intent.arguments());
        fact.put(
                "nativeToolCallId",
                JsonUtils.getJsonCodec()
                        .fromJson(intent.arguments(), Map.class)
                        .get("native_tool_call_id"));
        fact.put("applied", false);
        return fact;
    }

    private Map<String, Object> taskFact(ToolTask task) {
        var fact = new LinkedHashMap<String, Object>();
        fact.put("taskId", task.id());
        fact.put("executionId", task.executionId());
        fact.put("operationToolCallId", task.toolCallId());
        fact.put("name", task.name());
        fact.put("arguments", task.arguments());
        fact.put(
                "nativeToolCallId",
                JsonUtils.getJsonCodec()
                        .fromJson(task.arguments(), Map.class)
                        .get("native_tool_call_id"));
        fact.put("processHandle", task.processHandle());
        fact.put("resultJson", task.result());
        fact.put("applied", false);
        return fact;
    }

    synchronized void inputPosition(String id, long sequence) {
        inputPositions.put(id, sequence);
    }

    synchronized List<SessionEvent> readAfter(long after, int limit) {
        return readMetadataAfter(after, limit).stream().map(reads::materialize).toList();
    }

    synchronized List<SessionEvent> readMetadataAfter(long after, int limit) {
        return reads.readAfter(after, limit, head.sequence());
    }

    synchronized Iterable<SessionEvent> scanRecovery(long after, long through) {
        var view = views.get(through);
        if (view == null || after < 0 || through < after)
            throw new SessionLogException("Recovery view is no longer available");
        long first = firstSequence > through ? 0 : firstSequence;
        return () ->
                new Iterator<SessionEvent>() {
                    long cursor = after;
                    Iterator<SessionEvent> page = List.<SessionEvent>of().iterator();

                    @Override
                    public boolean hasNext() {
                        if (cursor >= through) return false;
                        if (!page.hasNext()) {
                            synchronized (ManagedLocalJournal.this) {
                                page =
                                        reads.readExecution(
                                                        cursor,
                                                        (int) Math.min(256, through - cursor),
                                                        view,
                                                        turn,
                                                        first)
                                                .iterator();
                            }
                        }
                        if (!page.hasNext())
                            throw new SessionLogException("Missing committed execution facts");
                        return true;
                    }

                    @Override
                    public SessionEvent next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        var event = page.next();
                        if (event.seq() <= cursor || event.seq() > through)
                            throw new SessionLogException("Recovery view position differs");
                        cursor = event.seq();
                        return event;
                    }
                };
    }

    synchronized SessionEvent materialize(SessionEvent event) {
        return reads.materialize(event);
    }

    synchronized boolean inputSettled(String id) {
        return settledInputs.contains(id);
    }

    synchronized String frameworkCall() {
        if (frameworkScope != null) return frameworkScope;
        return "framework_"
                + ManagedScope.fingerprint(
                        turn, modelAnchor == null ? "initialization" : modelAnchor);
    }

    synchronized void frameworkScope(String value) {
        frameworkScope = value;
    }

    /** One recovery read per logical framework group, never a per-step commit receipt wait. */
    synchronized ToolTask frameworkTask(String call, String taskId) {
        return frameworkTasks
                .computeIfAbsent(
                        call,
                        key -> {
                            var tasks = new LinkedHashMap<String, ToolTask>();
                            for (var task : journal.toolsForNativeCall(scope, turn, key)) {
                                tasks.put(task.id(), task);
                                taskFacts.putIfAbsent(task.id(), taskFact(task));
                            }
                            return tasks;
                        })
                .get(taskId);
    }

    synchronized List<String> pendingTools() {
        return List.copyOf(pendingTools);
    }

    synchronized void toolCompleted(String id, String result) {
        pendingTools.remove(id);
        var fact = taskFacts.get(id);
        if (fact != null) {
            fact.put("resultRead", true);
            if (result != null) fact.put("resultJson", result);
        }
    }

    synchronized boolean handedOff() {
        return handedOff;
    }
}
