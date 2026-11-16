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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public, Harness-neutral command and query values. Checkpoints are never part of read views. */
public final class DurableTypes {
    private DurableTypes() {}

    public record SessionKey(String namespace, String sessionId) {
        public SessionKey {
            DurableJson.text(namespace, "namespace");
            DurableJson.text(sessionId, "sessionId");
        }

        public String storageKey() {
            return "session:" + DurableJson.hash(namespace) + ":" + DurableJson.hash(sessionId);
        }
    }

    public record ThreadKey(SessionKey session, String threadId) {
        public ThreadKey {
            Objects.requireNonNull(session);
            DurableJson.text(threadId, "threadId");
        }

        public String storageKey() {
            return session.storageKey() + ":thread:" + DurableJson.hash(threadId);
        }
    }

    public enum Lifecycle {
        OPEN,
        CLOSED
    }

    public record ThreadBinding(String harness, String definitionVersion, String stateVersion) {
        public ThreadBinding {
            DurableJson.text(harness, "harness");
            DurableJson.text(definitionVersion, "definitionVersion");
            DurableJson.text(stateVersion, "stateVersion");
        }
    }

    public record SessionSpec(SessionKey key, ThreadBinding primaryBinding, JsonNode metadata) {
        public SessionSpec {
            Objects.requireNonNull(key);
            Objects.requireNonNull(primaryBinding);
            metadata = DurableJson.copy(metadata);
        }
    }

    public record SessionView(
            SessionKey key, String primaryThreadId, Lifecycle status, JsonNode metadata) {}

    public record ThreadSpec(ThreadKey key, ThreadBinding binding) {
        public ThreadSpec {
            Objects.requireNonNull(key);
            Objects.requireNonNull(binding);
        }

        public String harness() {
            return binding.harness();
        }

        public String definitionVersion() {
            return binding.definitionVersion();
        }

        public String stateVersion() {
            return binding.stateVersion();
        }
    }

    public record ThreadView(ThreadSpec definition, Lifecycle status) {}

    public record ThreadPage(List<ThreadView> items, String cursor) {}

    /** Ready-work selection: exact definition/state pair, or all definitions for a resolver. */
    public record HarnessSelector(String harness, String definitionVersion, String stateVersion) {
        public HarnessSelector {
            DurableJson.text(harness, "harness");
            if ((definitionVersion == null) != (stateVersion == null))
                throw new DurableException(
                        DurableException.Code.INVALID,
                        "Select both definition and state versions, or neither");
            if (definitionVersion != null) {
                DurableJson.text(definitionVersion, "definitionVersion");
                DurableJson.text(stateVersion, "stateVersion");
            }
        }

        public static HarnessSelector all(String harness) {
            return new HarnessSelector(harness, null, null);
        }

        public static HarnessSelector exact(ThreadSpec definition) {
            return new HarnessSelector(
                    definition.harness(),
                    definition.definitionVersion(),
                    definition.stateVersion());
        }
    }

    public enum TurnStatus {
        QUEUED,
        RUNNING,
        WAITING,
        PAUSING,
        PAUSED,
        CANCELLING,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    public record TurnReceipt(ThreadKey thread, String requestId, String turnId) {}

    /** Additional invocation retries; the first recorded failure freezes this policy for its Turn. */
    public record RecoveryPolicy(int maxRetries, long initialDelayMillis, long maxDelayMillis) {
        public RecoveryPolicy {
            if (maxRetries < 0
                    || maxRetries > 1000
                    || initialDelayMillis < 1
                    || maxDelayMillis < initialDelayMillis
                    || maxDelayMillis > 86_400_000)
                throw new IllegalArgumentException("Invalid bounded recovery policy");
        }

        public static RecoveryPolicy defaults() {
            return new RecoveryPolicy(3, 1000, 30_000);
        }

        long delay(int failures) {
            long delay = initialDelayMillis;
            for (int i = 1; i < failures && delay < maxDelayMillis; i++)
                delay = Math.min(maxDelayMillis, delay * 2);
            return delay;
        }
    }

    /** Host classification, not permission to repeat an unknown external operation. */
    public record Failure(boolean retryable, String message, RecoveryPolicy policy) {
        public Failure {
            Objects.requireNonNull(message);
            Objects.requireNonNull(policy);
        }
    }

    public record RecoveryView(
            int failures, RecoveryPolicy policy, boolean retryable, Long retryAt, String message) {
        public RecoveryView {
            Objects.requireNonNull(policy);
            Objects.requireNonNull(message);
            boolean allowed = retryable && failures <= policy.maxRetries();
            if (failures < 1
                    || failures > policy.maxRetries() + 1
                    || allowed != (retryAt != null)
                    || retryAt != null && retryAt < 0)
                throw new IllegalArgumentException("Invalid recovery state");
        }

        public boolean retryAllowed() {
            return retryable && failures <= policy.maxRetries();
        }
    }

    public record TurnView(
            ThreadKey thread,
            String turnId,
            TurnStatus status,
            long revision,
            boolean cancellationRequested,
            JsonNode output,
            String error,
            WaitCondition waiting,
            RecoveryView recovery,
            DelegationParent parent) {}

    public record SessionPage(List<SessionView> items, String cursor) {}

    public record TurnPage(List<TurnView> items, String cursor) {}

    /** One actual native invocation; it never owns the logical task queue. */
    public enum RunStatus {
        PREPARED,
        RUNNING,
        COMPLETED,
        STOPPED,
        FAILED,
        LOST;

        public boolean terminal() {
            return this != PREPARED && this != RUNNING;
        }
    }

    public record RunView(
            ThreadKey thread,
            String turnId,
            String runId,
            String requestId,
            String owner,
            long epoch,
            String previousRunId,
            RunStatus status,
            long preparedAt,
            Long startedAt,
            Long endedAt,
            String outcome,
            String reason) {}

    public record RunPage(List<RunView> items, String cursor) {}

    public enum StepStatus {
        RUNNING,
        COMPLETED,
        STOPPED,
        FAILED,
        LOST;

        public boolean terminal() {
            return this != RUNNING;
        }
    }

    public enum StepPhase {
        REASONING,
        ACTING,
        FINALIZATION
    }

    public record StepView(
            ThreadKey thread,
            String turnId,
            String runId,
            String stepId,
            int index,
            StepStatus status,
            StepPhase phase,
            String resumedFromStepId,
            long startedAt,
            Long endedAt,
            String reason) {}

    public record StepPage(List<StepView> items, String cursor) {}

    public enum ModelStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        LOST
    }

    /** MODEL_CALL cannot reveal retries hidden inside an uninstrumented model implementation. */
    public enum ModelObservation {
        MODEL_CALL,
        PROVIDER_ATTEMPTS
    }

    /** Known token counts. Missing values stay null; details are subsets, not additional tokens. */
    public record TokenUsage(Long inputTokens, Long outputTokens, Map<String, Long> details) {
        public TokenUsage {
            if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0)
                throw new IllegalArgumentException("Token counts must be nonnegative");
            details = Map.copyOf(details);
            if (details.values().stream().anyMatch(value -> value < 0))
                throw new IllegalArgumentException("Token details must be nonnegative");
        }

        public TokenUsage plus(TokenUsage other) {
            var combined = new LinkedHashMap<>(details);
            other.details.forEach((key, value) -> combined.merge(key, value, Math::addExact));
            return new TokenUsage(
                    sum(inputTokens, other.inputTokens),
                    sum(outputTokens, other.outputTokens),
                    combined);
        }

        private static Long sum(Long left, Long right) {
            return left == null ? right : right == null ? left : Math.addExact(left, right);
        }
    }

    public record ModelRequestView(
            String requestId,
            int attempt,
            String provider,
            ModelStatus status,
            long startedAt,
            Long endedAt,
            Long durationMillis,
            TokenUsage usage,
            boolean usageComplete,
            String errorType) {}

    /** A logical call with its observed requests. Replay refers to the original call without a new request. */
    public record ModelCallView(
            ThreadKey thread,
            String turnId,
            String runId,
            String stepId,
            String modelCallId,
            String model,
            String purpose,
            int iteration,
            long startedAt,
            Long endedAt,
            ModelStatus status,
            ModelObservation observation,
            TokenUsage usage,
            boolean usageComplete,
            List<ModelRequestView> requests) {
        public ModelCallView {
            requests = List.copyOf(requests);
        }
    }

    public record ModelCallPage(List<ModelCallView> items, String cursor) {}

    /** Invocation facts commit with the native checkpoint; only takeover may declare LOST. */
    public record RunChange(String runId, RunStatus status, String outcome, String reason) {
        public RunChange {
            DurableJson.text(runId, "runId");
            Objects.requireNonNull(status);
            if (status == RunStatus.PREPARED || status == RunStatus.LOST)
                throw new IllegalArgumentException("Run status is owned by the execution host");
            if (status.terminal()) DurableJson.text(outcome, "run outcome");
            else if (outcome != null || reason != null)
                throw new IllegalArgumentException("A running invocation has no final outcome");
        }
    }

    public record StepChange(
            String runId,
            String stepId,
            StepStatus status,
            StepPhase phase,
            String resumedFromStepId,
            String reason) {
        public StepChange {
            DurableJson.text(runId, "runId");
            DurableJson.text(stepId, "stepId");
            Objects.requireNonNull(status);
            Objects.requireNonNull(phase);
            if (status == StepStatus.LOST)
                throw new IllegalArgumentException("Only takeover may declare a lost Step");
            if (resumedFromStepId != null) DurableJson.text(resumedFromStepId, "resumedFromStepId");
        }
    }

    public record HistoryPage(List<JsonNode> items, String cursor) {}

    public record Interaction(
            String threadId,
            String runId,
            String stepId,
            String id,
            String requestId,
            String turnId,
            String key,
            String kind,
            String status,
            boolean automatic,
            JsonNode call) {}

    public enum ControlKind {
        STEER,
        INJECT,
        WITHDRAW,
        CANCEL,
        RESPOND,
        PAUSE,
        RESUME,
        SIGNAL
    }

    public record ControlCommand(
            String turnId,
            String requestId,
            ControlKind kind,
            String target,
            JsonNode value,
            long expectedRevision) {
        public ControlCommand {
            DurableJson.text(turnId, "turnId");
            DurableJson.text(requestId, "requestId");
            Objects.requireNonNull(kind);
            value = DurableJson.copy(value);
        }
    }

    /** A stable admission receipt; later application/settlement is observed through events/state. */
    public record CommandReceipt(
            String requestId, String turnId, String operation, String status, String cursor) {}

    public record CheckpointRestore(
            ThreadKey source,
            long position,
            ThreadSpec target,
            long expectedPosition,
            String requestId) {}

    public record Lease(ThreadKey thread, String turnId, String owner, long epoch, long expiresAt) {
        public SessionKey session() {
            return thread.session();
        }
    }

    public record WaitCondition(String key, long deadline) {
        public WaitCondition {
            DurableJson.text(key, "wait key");
            if (deadline < 0) throw new IllegalArgumentException("Negative deadline");
        }
    }

    public record Signal(String id, String key, JsonNode value) {
        public Signal {
            DurableJson.text(id, "signal id");
            DurableJson.text(key, "signal key");
            value = DurableJson.copy(value);
        }
    }

    /** A position within the enclosing Thread/Turn; auxiliary operations may have no Step. */
    public record RunStepRef(String runId, String stepId) {
        public RunStepRef {
            DurableJson.text(runId, "runId");
            if (stepId != null) DurableJson.text(stepId, "stepId");
        }
    }

    public record ToolCall(
            String callId,
            String executor,
            String name,
            JsonNode arguments,
            String binding,
            RunStepRef origin) {
        /** Direct executors may use an unbound call. Catalog-backed workers require a binding digest. */
        public ToolCall(String callId, String executor, String name, JsonNode arguments) {
            this(callId, executor, name, arguments, null, null);
        }

        /** Calls outside a recorded invocation have no Run/Step origin. */
        public ToolCall(
                String callId, String executor, String name, JsonNode arguments, String binding) {
            this(callId, executor, name, arguments, binding, null);
        }

        public ToolCall withOrigin(RunStepRef origin) {
            return new ToolCall(callId, executor, name, arguments, binding, origin);
        }

        public ToolCall {
            if (binding != null && !binding.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid tool binding digest");
            DurableJson.text(callId, "callId");
            DurableJson.text(executor, "executor");
            DurableJson.text(name, "tool name");
            arguments = DurableJson.copy(arguments);
        }
    }

    public record ExecutionRequest(
            String executionId, ThreadKey thread, String turnId, ToolCall call) {
        public ExecutionRequest {
            DurableJson.text(executionId, "executionId");
            Objects.requireNonNull(thread);
            DurableJson.text(turnId, "turnId");
            Objects.requireNonNull(call);
        }

        public SessionKey session() {
            return thread.session();
        }
    }

    public enum ExecutionState {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        NOT_FOUND,
        UNKNOWN;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    public record ExecutionResult(
            ExecutionState state, String handle, JsonNode result, String error) {
        public ExecutionResult {
            Objects.requireNonNull(state);
            result = DurableJson.copy(result);
        }

        public static ExecutionResult unknown(String error) {
            return new ExecutionResult(ExecutionState.UNKNOWN, null, null, error);
        }

        public static ExecutionResult missing() {
            return new ExecutionResult(ExecutionState.NOT_FOUND, null, null, null);
        }
    }

    public record ToolExecution(
            ExecutionRequest request,
            boolean dispatchAttempted,
            ExecutionResult result,
            boolean applied,
            RunStepRef appliedAt,
            DelegationLink delegation) {}

    public record DelegationLink(ThreadKey thread, String turnId) {
        public DelegationLink {
            Objects.requireNonNull(thread);
            DurableJson.text(turnId, "child turnId");
        }
    }

    public record DelegationParent(
            ThreadKey thread, String turnId, String executionId, RunStepRef origin) {
        public DelegationParent {
            Objects.requireNonNull(thread);
            DurableJson.text(turnId, "parent turnId");
            DurableJson.text(executionId, "parent executionId");
        }
    }

    /** Frozen child binding and input; naming an existing Thread explicitly reuses its context. */
    public record DelegationTarget(ThreadSpec child, JsonNode input) {
        public DelegationTarget {
            Objects.requireNonNull(child);
            input = DurableJson.copy(input);
        }
    }

    /** A cancellation before admission has no child; it never creates a runnable task. */
    public record DelegationView(
            ExecutionRequest parent, DelegationLink child, boolean cancelRequested) {}

    /** Result application and its checkpoint are committed together, possibly in a later Run. */
    public record AppliedResult(String executionId, RunStepRef appliedAt) {
        public AppliedResult {
            DurableJson.text(executionId, "executionId");
        }
    }

    /** Host-verified terminal fact for an UNKNOWN effect; evidence is an audit reference. */
    public record ExecutionReconciliation(
            String requestId, ExecutionResult result, String evidenceRef) {
        public ExecutionReconciliation {
            DurableJson.text(requestId, "reconciliation requestId");
            Objects.requireNonNull(result);
            if (!result.state().terminal())
                throw new DurableException(
                        DurableException.Code.INVALID,
                        "Reconciliation requires a verified terminal result");
            if (evidenceRef == null || evidenceRef.isBlank() || evidenceRef.length() > 2048)
                throw new DurableException(
                        DurableException.Code.INVALID,
                        "Reconciliation requires an evidence reference");
        }
    }

    /** Base64 output bytes; byte offsets never share the public event cursor namespace. */
    public record OutputPage(
            long offset, long nextOffset, String data, long size, boolean terminal, String gap) {}

    public record EventInput(
            String eventId, String type, JsonNode data, String runId, String stepId) {
        public EventInput(String eventId, String type, JsonNode data) {
            this(eventId, type, data, null, null);
        }

        public EventInput {
            DurableJson.text(eventId, "eventId");
            DurableJson.text(type, "event type");
            data = DurableJson.copy(data);
            if (runId != null) DurableJson.text(runId, "runId");
            if (stepId != null) {
                DurableJson.text(stepId, "stepId");
                if (runId == null) throw new IllegalArgumentException("Step requires a Run");
            }
        }
    }

    public record PublicEvent(
            String schemaVersion,
            String eventId,
            SessionKey session,
            String threadId,
            String turnId,
            String runId,
            String stepId,
            String sequence,
            String cursor,
            String type,
            JsonNode data) {}

    /**
     * Best-effort live presentation signal. Live events have no durable sequence or cursor and are
     * never returned by event history or included in snapshots.
     */
    public record LiveEvent(
            String schemaVersion,
            String eventId,
            SessionKey session,
            String threadId,
            String turnId,
            String runId,
            String stepId,
            long occurredAt,
            String type,
            JsonNode data) {
        public LiveEvent {
            DurableJson.text(schemaVersion, "schemaVersion");
            DurableJson.text(eventId, "eventId");
            Objects.requireNonNull(session);
            DurableJson.text(threadId, "threadId");
            DurableJson.text(turnId, "turnId");
            if (runId != null) DurableJson.text(runId, "runId");
            if (stepId != null) DurableJson.text(stepId, "stepId");
            if (occurredAt < 0) throw new IllegalArgumentException("Negative event timestamp");
            DurableJson.text(type, "event type");
            data = DurableJson.copy(data);
        }
    }

    /** Invocation-local input used to publish a live event after its native commit succeeds. */
    public record LiveEventInput(
            String eventId,
            long occurredAt,
            String type,
            JsonNode data,
            String runId,
            String stepId) {
        public LiveEventInput {
            DurableJson.text(eventId, "eventId");
            if (occurredAt < 0) throw new IllegalArgumentException("Negative event timestamp");
            DurableJson.text(type, "event type");
            data = DurableJson.copy(data);
            if (runId != null) DurableJson.text(runId, "runId");
            if (stepId != null) {
                DurableJson.text(stepId, "stepId");
                if (runId == null) throw new IllegalArgumentException("Step requires a Run");
            }
        }
    }

    public record EventPage(List<PublicEvent> events, String cursor, String highWatermark) {}

    /** A selection within one Session's committed stream, retaining its original cursor. */
    public record EventFilter(String threadId, String turnId, String runId) {
        public EventFilter {
            if (threadId != null) DurableJson.text(threadId, "threadId");
            if (turnId != null) DurableJson.text(turnId, "turnId");
            if (runId != null) DurableJson.text(runId, "runId");
            if (turnId != null && threadId == null || runId != null && turnId == null)
                throw new DurableException(
                        DurableException.Code.INVALID, "Event filter requires parent identities");
        }

        public static EventFilter all() {
            return new EventFilter(null, null, null);
        }

        public boolean matches(PublicEvent event) {
            return matches(event.threadId(), event.turnId(), event.runId());
        }

        public boolean matches(LiveEvent event) {
            return matches(event.threadId(), event.turnId(), event.runId());
        }

        private boolean matches(String thread, String turn, String run) {
            return (threadId == null || threadId.equals(thread))
                    && (turnId == null || turnId.equals(turn))
                    && (runId == null || runId.equals(run));
        }

        EventPage select(EventPage page) {
            return new EventPage(
                    page.events().stream().filter(this::matches).toList(),
                    page.cursor(),
                    page.highWatermark());
        }

        Snapshot select(Snapshot snapshot) {
            if (threadId == null) return snapshot;
            ObjectNode projection = EventProjection.empty();
            snapshot.projection()
                    .fields()
                    .forEachRemaining(
                            category -> {
                                if (category.getKey().equals("session")) return;
                                ObjectNode selected =
                                        projection.withObject("/" + category.getKey());
                                category.getValue()
                                        .fields()
                                        .forEachRemaining(
                                                entry -> {
                                                    var value = entry.getValue();
                                                    if (category.getKey().equals("threads")) {
                                                        if (turnId == null
                                                                && threadId.equals(entry.getKey()))
                                                            selected.set(
                                                                    entry.getKey(),
                                                                    value.deepCopy());
                                                    } else if (matches(
                                                            value.path("threadId").asText(null),
                                                            value.path("turnId").asText(null),
                                                            value.path("runId").asText(null)))
                                                        selected.set(
                                                                entry.getKey(), value.deepCopy());
                                                });
                            });
            return new Snapshot(
                    snapshot.session(),
                    snapshot.turns().stream()
                            .filter(
                                    turn ->
                                            threadId.equals(turn.thread().threadId())
                                                    && (turnId == null
                                                            || turnId.equals(turn.turnId())))
                            .toList(),
                    projection,
                    snapshot.sequence(),
                    snapshot.cursor());
        }
    }

    public record Snapshot(
            SessionKey session,
            List<TurnView> turns,
            JsonNode projection,
            String sequence,
            String cursor) {}

    /** Opaque native entries appended in the same transaction as checkpoint and effects. */
    public record JournalAppend(long expectedPosition, List<JsonNode> entries) {
        public JournalAppend {
            if (expectedPosition < 0)
                throw new IllegalArgumentException("Negative journal position");
            entries = entries.stream().map(DurableJson::copy).toList();
        }
    }

    public record Commit(
            String commitId,
            long expectedRevision,
            JsonNode checkpoint,
            List<ToolCall> tools,
            List<AppliedResult> appliedResults,
            List<String> consumedSignals,
            List<EventInput> events,
            TurnStatus status,
            WaitCondition waiting,
            JsonNode output,
            String error,
            JournalAppend journal,
            RunChange run,
            List<StepChange> steps) {
        public Commit(
                String commitId,
                long expectedRevision,
                JsonNode checkpoint,
                List<ToolCall> tools,
                List<AppliedResult> appliedResults,
                List<String> consumedSignals,
                List<EventInput> events,
                TurnStatus status,
                WaitCondition waiting,
                JsonNode output,
                String error,
                JournalAppend journal) {
            this(
                    commitId,
                    expectedRevision,
                    checkpoint,
                    tools,
                    appliedResults,
                    consumedSignals,
                    events,
                    status,
                    waiting,
                    output,
                    error,
                    journal,
                    null,
                    List.of());
        }

        public Commit(
                String commitId,
                long expectedRevision,
                JsonNode checkpoint,
                List<ToolCall> tools,
                List<AppliedResult> appliedResults,
                List<String> consumedSignals,
                List<EventInput> events,
                TurnStatus status,
                WaitCondition waiting,
                JsonNode output,
                String error) {
            this(
                    commitId,
                    expectedRevision,
                    checkpoint,
                    tools,
                    appliedResults,
                    consumedSignals,
                    events,
                    status,
                    waiting,
                    output,
                    error,
                    null);
        }

        public Commit {
            DurableJson.text(commitId, "commitId");
            checkpoint = DurableJson.copy(checkpoint);
            tools = List.copyOf(tools);
            appliedResults = List.copyOf(appliedResults);
            consumedSignals = List.copyOf(consumedSignals);
            events = List.copyOf(events);
            steps = List.copyOf(steps);
            Objects.requireNonNull(status);
            output = DurableJson.copy(output);
            if (expectedRevision < 0
                    || status == TurnStatus.QUEUED
                    || status == TurnStatus.PAUSING
                    || status == TurnStatus.CANCELLING)
                throw new IllegalArgumentException("Invalid commit revision/status");
        }
    }

    public record CommitReceipt(String commitId, String digest, long revision, String cursor) {}

    /** Opaque native state binding. Position is independent of turn revision and public cursors. */
    public record CheckpointMetadata(
            String schemaVersion,
            String harness,
            String definitionVersion,
            String stateVersion,
            String position,
            String digest) {
        public void verify(ThreadSpec definition, JsonNode checkpoint) {
            if (!"1".equals(schemaVersion)
                    || !definition.harness().equals(harness)
                    || !definition.definitionVersion().equals(definitionVersion)
                    || !definition.stateVersion().equals(stateVersion))
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE, "Checkpoint binding/version mismatch");
            if (position == null
                    || !position.matches("[1-9][0-9]*")
                    || !DurableJson.hash(checkpoint).equals(digest))
                throw new DurableException(
                        DurableException.Code.INVALID, "Checkpoint integrity check failed");
        }
    }

    public record CheckpointView(
            ThreadSpec definition,
            CheckpointMetadata metadata,
            JsonNode checkpoint,
            long journalPosition,
            boolean settled) {}

    /** A native adapter prepares a settled baseline; installation requires an idle target. */
    public record Restoration(
            String requestId,
            long expectedPosition,
            ThreadSpec target,
            JsonNode checkpoint,
            List<JsonNode> journal,
            List<SessionResources.Value> resources) {
        public Restoration(
                String requestId,
                long expectedPosition,
                ThreadSpec target,
                JsonNode checkpoint,
                List<JsonNode> journal) {
            this(requestId, expectedPosition, target, checkpoint, journal, List.of());
        }

        public Restoration {
            resources = List.copyOf(resources);
            DurableJson.text(requestId, "requestId");
            Objects.requireNonNull(target);
            checkpoint = DurableJson.copy(checkpoint);
            journal = journal.stream().map(DurableJson::copy).toList();
            if (expectedPosition < 0 || checkpoint.isNull())
                throw new IllegalArgumentException("Invalid restoration baseline");
        }
    }

    public record Frame(
            ThreadSpec definition,
            TurnView turn,
            JsonNode input,
            JsonNode checkpoint,
            CheckpointMetadata checkpointMetadata,
            List<ToolExecution> tools,
            List<Signal> signals,
            long now) {}
}
