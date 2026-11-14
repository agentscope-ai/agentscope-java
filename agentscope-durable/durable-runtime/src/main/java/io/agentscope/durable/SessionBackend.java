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
import io.agentscope.durable.DurableTypes.CheckpointMetadata;
import io.agentscope.durable.DurableTypes.CheckpointView;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.DelegationView;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.Failure;
import io.agentscope.durable.DurableTypes.Frame;
import io.agentscope.durable.DurableTypes.HarnessSelector;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.ModelCallPage;
import io.agentscope.durable.DurableTypes.ModelCallView;
import io.agentscope.durable.DurableTypes.RecoveryView;
import io.agentscope.durable.DurableTypes.Restoration;
import io.agentscope.durable.DurableTypes.RunPage;
import io.agentscope.durable.DurableTypes.RunView;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.SessionView;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.StepPage;
import io.agentscope.durable.DurableTypes.StepView;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadPage;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.ThreadView;
import io.agentscope.durable.DurableTypes.ToolExecution;
import io.agentscope.durable.DurableTypes.TurnReceipt;
import io.agentscope.durable.DurableTypes.TurnView;
import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.ObjectStore.Reference;
import io.agentscope.durable.store.RecordStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Blocking atomic session commands. Hosts execute these calls on worker threads. */
public interface SessionBackend extends AutoCloseable {
    /**
     * Local storage used to assemble colocated durable execution transports. Remote backends do not
     * expose it. Application code should configure the backend once and let DurableRuntime pass this
     * capability to managed executors.
     */
    record LocalStorage(RecordStore records, ObjectStore objects) {
        public LocalStorage {
            Objects.requireNonNull(records);
            Objects.requireNonNull(objects);
        }
    }

    default Optional<LocalStorage> localStorage() {
        return Optional.empty();
    }

    /**
     * Optional local access to change hints. This is wiring, not a remote protocol operation.
     * Readers and runtimes always retain polling as their source of truth.
     */
    default Wakeups wakeups() {
        return Wakeups.none();
    }

    /**
     * Use the returned backend for every writer, runtime, HTTP handler and observer.
     * Publishes after successful mutations, outside the transaction. Duplicate hints are allowed.
     * Closing it closes this backend, but not the caller-owned wakeup transport.
     */
    default SessionBackend withWakeups(Wakeups wakeups) {
        return withWakeups(wakeups, NotifyingSessionBackend::logFailure);
    }

    /** Notification and reporting failures cannot turn an acknowledged commit into a failure. */
    default SessionBackend withWakeups(Wakeups wakeups, BiConsumer<SessionKey, Throwable> errors) {
        return new NotifyingSessionBackend(this, wakeups, errors);
    }

    /** Capabilities describe the actual store, including when accessed over HTTP. */
    record Capabilities(
            String protocolVersion,
            String storage,
            boolean durable,
            boolean sharedAcrossHosts,
            boolean atomicCheckpointAndIntent,
            boolean fencedOwnership,
            boolean durableWaits,
            boolean eventReplay) {}

    Capabilities capabilities();

    SessionView create(SessionSpec specification);

    ThreadView createThread(ThreadSpec specification);

    ThreadView thread(ThreadKey key);

    ThreadPage threads(SessionKey session, String cursor, int limit);

    SessionView closeSession(SessionKey session);

    ThreadView closeThread(ThreadKey thread);

    SessionView session(SessionKey key);

    DurableTypes.SessionPage listSessions(String namespace, String cursor, int limit);

    DurableTypes.TurnPage turns(ThreadKey session, String cursor, int limit);

    DurableTypes.HistoryPage history(ThreadKey session, String cursor, int limit);

    List<DurableTypes.Interaction> interactions(ThreadKey session, String turnId);

    DurableTypes.CommandReceipt control(ThreadKey session, DurableTypes.ControlCommand command);

    /** Stages private bytes; the reference is scoped and verified on read. */
    Reference putObject(SessionKey session, byte[] bytes);

    byte[] readObject(SessionKey session, Reference reference);

    TurnReceipt submit(ThreadKey session, String requestId, JsonNode input);

    Optional<TurnReceipt> admission(SessionKey session, String requestId);

    List<SessionView> sessions(String after, int limit);

    /** Ready candidates are ordered by storage time, not Session enumeration order. */
    List<ThreadSpec> ready(List<HarnessSelector> harnesses, int limit);

    TurnView turn(ThreadKey session, String turnId);

    /** Prepare exactly one invocation; retry the same request ID after an uncertain response. */
    RunView prepareRun(Lease lease, String requestId);

    RunView run(ThreadKey session, String turnId, String runId);

    RunPage runs(ThreadKey session, String turnId, String cursor, int limit);

    StepView step(ThreadKey session, String turnId, String runId, String stepId);

    StepPage steps(ThreadKey session, String turnId, String runId, String cursor, int limit);

    ModelCallView modelCall(ThreadKey thread, String turnId, String runId, String modelCallId);

    /** Null stepId selects all calls in the Run, including auxiliary calls outside a Step. */
    ModelCallPage modelCalls(
            ThreadKey thread, String turnId, String runId, String stepId, String cursor, int limit);

    Optional<Lease> claim(ThreadKey session, String owner, Duration duration);

    void renew(Lease lease, Duration duration);

    void release(Lease lease, Duration delay);

    /** Record at most one failure per lease epoch; replaying the same report returns its receipt. */
    RecoveryView recordFailure(Lease lease, Failure failure);

    Frame frame(Lease lease);

    CommitReceipt commit(Lease lease, Commit commit);

    /** Native journal entries after an exclusive position; reading requires current ownership. */
    List<JsonNode> journal(Lease lease, long after, int limit);

    Optional<CommitReceipt> receipt(ThreadKey session, String turnId, String commitId);

    /** Private persisted intent for host authorization; never creates or dispatches an operation. */
    ToolExecution execution(ThreadKey session, String executionId);

    ToolExecution beginDispatch(Lease lease, String executionId);

    /** Atomically freeze the parent/child mapping and admit one child Turn. */
    DelegationView delegate(ExecutionRequest parent, DelegationTarget target);

    Optional<DelegationView> delegation(ExecutionRequest parent);

    /** A missing delegation becomes a cancellation tombstone, without creating a child. */
    DelegationView cancelDelegation(ExecutionRequest parent, String childTurnId);

    JsonNode resource(Lease lease, SessionResources.Request request);

    List<SessionResources.Value> checkpointResources(ThreadKey session, long position);

    void acceptExecutionEvent(ExecutionMessages.Event event);

    void acceptResult(ThreadKey session, String executionId, ExecutionResult result);

    void signal(ThreadKey session, String turnId, Signal signal);

    /** Withdrawal wins only before the input is atomically applied to native state. */
    void withdraw(ThreadKey session, String turnId, String signalId, String requestId);

    void cancel(ThreadKey session, String turnId, String requestId);

    Snapshot snapshot(SessionKey session);

    EventPage events(SessionKey session, String cursor, int limit);

    List<CheckpointMetadata> checkpoints(ThreadKey session, long after, int limit);

    CheckpointView checkpoint(ThreadKey session, long position);

    List<JsonNode> checkpointJournal(ThreadKey session, long position, long after, int limit);

    CommitReceipt restore(Restoration restoration);

    /** Retain the projection while removing an acknowledged event prefix. */
    void pruneEvents(SessionKey session, String throughCursor);

    @Override
    default void close() {}
}
