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
import io.agentscope.durable.DurableTypes.CommandReceipt;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.ControlCommand;
import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.DelegationView;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.Failure;
import io.agentscope.durable.DurableTypes.Frame;
import io.agentscope.durable.DurableTypes.HarnessSelector;
import io.agentscope.durable.DurableTypes.HistoryPage;
import io.agentscope.durable.DurableTypes.Interaction;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.ModelCallPage;
import io.agentscope.durable.DurableTypes.ModelCallView;
import io.agentscope.durable.DurableTypes.RecoveryView;
import io.agentscope.durable.DurableTypes.Restoration;
import io.agentscope.durable.DurableTypes.RunPage;
import io.agentscope.durable.DurableTypes.RunView;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionPage;
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
import io.agentscope.durable.DurableTypes.TurnPage;
import io.agentscope.durable.DurableTypes.TurnReceipt;
import io.agentscope.durable.DurableTypes.TurnView;
import io.agentscope.durable.store.ObjectStore.Reference;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Publishes only after a backend acknowledges a mutation; hints never change its outcome. */
final class NotifyingSessionBackend implements SessionBackend {
    private final SessionBackend delegate;
    private final Wakeups wakeups;
    private final BiConsumer<SessionKey, Throwable> errors;

    NotifyingSessionBackend(
            SessionBackend delegate, Wakeups wakeups, BiConsumer<SessionKey, Throwable> errors) {
        this.delegate = Objects.requireNonNull(delegate);
        this.wakeups = Objects.requireNonNull(wakeups);
        this.errors = Objects.requireNonNull(errors);
    }

    static void logFailure(SessionKey session, Throwable error) {
        System.getLogger(NotifyingSessionBackend.class.getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Durable wakeup failed; polling remains active",
                        error);
    }

    private void changed(ThreadKey thread) {
        changed(thread.session());
    }

    private void changed(SessionKey session) {
        try {
            wakeups.publish(session);
        } catch (RuntimeException error) {
            try {
                errors.accept(session, error);
            } catch (RuntimeException reportingError) {
                logFailure(session, reportingError);
            }
        }
    }

    @Override
    public Wakeups wakeups() {
        return wakeups;
    }

    @Override
    public Capabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public SessionView create(SessionSpec definition) {
        var result = delegate.create(definition);
        changed(definition.key());
        return result;
    }

    @Override
    public SessionView session(SessionKey key) {
        return delegate.session(key);
    }

    @Override
    public ThreadView createThread(ThreadSpec specification) {
        var result = delegate.createThread(specification);
        changed(specification.key());
        return result;
    }

    @Override
    public ThreadView thread(ThreadKey key) {
        return delegate.thread(key);
    }

    @Override
    public ThreadPage threads(SessionKey key, String cursor, int limit) {
        return delegate.threads(key, cursor, limit);
    }

    @Override
    public SessionView closeSession(SessionKey key) {
        var result = delegate.closeSession(key);
        changed(key);
        return result;
    }

    @Override
    public ThreadView closeThread(ThreadKey key) {
        var result = delegate.closeThread(key);
        changed(key);
        return result;
    }

    @Override
    public SessionPage listSessions(String namespace, String cursor, int limit) {
        return delegate.listSessions(namespace, cursor, limit);
    }

    @Override
    public TurnPage turns(ThreadKey key, String cursor, int limit) {
        return delegate.turns(key, cursor, limit);
    }

    @Override
    public HistoryPage history(ThreadKey key, String cursor, int limit) {
        return delegate.history(key, cursor, limit);
    }

    @Override
    public List<Interaction> interactions(ThreadKey key, String turnId) {
        return delegate.interactions(key, turnId);
    }

    @Override
    public CommandReceipt control(ThreadKey key, ControlCommand command) {
        var result = delegate.control(key, command);
        changed(key);
        return result;
    }

    @Override
    public Reference putObject(SessionKey session, byte[] bytes) {
        return delegate.putObject(session, bytes);
    }

    @Override
    public byte[] readObject(SessionKey session, Reference reference) {
        return delegate.readObject(session, reference);
    }

    @Override
    public TurnReceipt submit(ThreadKey session, String requestId, JsonNode input) {
        var result = delegate.submit(session, requestId, input);
        changed(session);
        return result;
    }

    @Override
    public Optional<TurnReceipt> admission(SessionKey session, String requestId) {
        return delegate.admission(session, requestId);
    }

    @Override
    public List<SessionView> sessions(String after, int limit) {
        return delegate.sessions(after, limit);
    }

    @Override
    public List<ThreadSpec> ready(List<HarnessSelector> harnesses, int limit) {
        return delegate.ready(harnesses, limit);
    }

    @Override
    public TurnView turn(ThreadKey session, String turnId) {
        return delegate.turn(session, turnId);
    }

    @Override
    public RunView prepareRun(Lease lease, String requestId) {
        var view = delegate.prepareRun(lease, requestId);
        changed(lease.session());
        return view;
    }

    @Override
    public RunView run(ThreadKey session, String turnId, String runId) {
        return delegate.run(session, turnId, runId);
    }

    @Override
    public RunPage runs(ThreadKey session, String turnId, String cursor, int limit) {
        return delegate.runs(session, turnId, cursor, limit);
    }

    @Override
    public StepView step(ThreadKey session, String turnId, String runId, String stepId) {
        return delegate.step(session, turnId, runId, stepId);
    }

    @Override
    public StepPage steps(
            ThreadKey session, String turnId, String runId, String cursor, int limit) {
        return delegate.steps(session, turnId, runId, cursor, limit);
    }

    @Override
    public ModelCallView modelCall(
            ThreadKey thread, String turnId, String runId, String modelCallId) {
        return delegate.modelCall(thread, turnId, runId, modelCallId);
    }

    @Override
    public ModelCallPage modelCalls(
            ThreadKey thread,
            String turnId,
            String runId,
            String stepId,
            String cursor,
            int limit) {
        return delegate.modelCalls(thread, turnId, runId, stepId, cursor, limit);
    }

    @Override
    public Optional<Lease> claim(ThreadKey session, String owner, Duration duration) {
        var result = delegate.claim(session, owner, duration);
        if (result.isPresent()) changed(session);
        return result;
    }

    @Override
    public void renew(Lease lease, Duration duration) {
        delegate.renew(lease, duration);
    }

    @Override
    public void release(Lease lease, Duration delay) {
        delegate.release(lease, delay);
        changed(lease.session());
    }

    @Override
    public Optional<LocalStorage> localStorage() {
        return delegate.localStorage();
    }

    @Override
    public RecoveryView recordFailure(Lease lease, Failure failure) {
        var result = delegate.recordFailure(lease, failure);
        changed(lease.session());
        return result;
    }

    @Override
    public Frame frame(Lease lease) {
        return delegate.frame(lease);
    }

    @Override
    public CommitReceipt commit(Lease lease, Commit commit) {
        var result = delegate.commit(lease, commit);
        changed(lease.session());
        return result;
    }

    @Override
    public List<JsonNode> journal(Lease lease, long after, int limit) {
        return delegate.journal(lease, after, limit);
    }

    @Override
    public Optional<CommitReceipt> receipt(ThreadKey session, String turnId, String commitId) {
        return delegate.receipt(session, turnId, commitId);
    }

    @Override
    public ToolExecution execution(ThreadKey session, String executionId) {
        return delegate.execution(session, executionId);
    }

    @Override
    public DelegationView delegate(ExecutionRequest parent, DelegationTarget target) {
        var result = delegate.delegate(parent, target);
        changed(parent.session());
        return result;
    }

    @Override
    public Optional<DelegationView> delegation(ExecutionRequest parent) {
        return delegate.delegation(parent);
    }

    @Override
    public DelegationView cancelDelegation(ExecutionRequest parent, String childTurnId) {
        var result = delegate.cancelDelegation(parent, childTurnId);
        changed(parent.session());
        return result;
    }

    @Override
    public ToolExecution beginDispatch(Lease lease, String executionId) {
        var result = delegate.beginDispatch(lease, executionId);
        changed(lease.session());
        return result;
    }

    @Override
    public JsonNode resource(Lease lease, SessionResources.Request request) {
        return delegate.resource(lease, request);
    }

    @Override
    public List<SessionResources.Value> checkpointResources(ThreadKey session, long position) {
        return delegate.checkpointResources(session, position);
    }

    @Override
    public void acceptExecutionEvent(ExecutionMessages.Event event) {
        delegate.acceptExecutionEvent(event);
        changed(event.request().session());
    }

    @Override
    public void acceptResult(ThreadKey session, String executionId, ExecutionResult result) {
        delegate.acceptResult(session, executionId, result);
        changed(session);
    }

    @Override
    public void signal(ThreadKey session, String turnId, Signal signal) {
        delegate.signal(session, turnId, signal);
        changed(session);
    }

    @Override
    public void withdraw(ThreadKey session, String turnId, String signalId, String requestId) {
        delegate.withdraw(session, turnId, signalId, requestId);
        changed(session);
    }

    @Override
    public void cancel(ThreadKey session, String turnId, String requestId) {
        delegate.cancel(session, turnId, requestId);
        changed(session);
    }

    @Override
    public Snapshot snapshot(SessionKey session) {
        return delegate.snapshot(session);
    }

    @Override
    public EventPage events(SessionKey session, String cursor, int limit) {
        return delegate.events(session, cursor, limit);
    }

    @Override
    public List<CheckpointMetadata> checkpoints(ThreadKey session, long after, int limit) {
        return delegate.checkpoints(session, after, limit);
    }

    @Override
    public CheckpointView checkpoint(ThreadKey session, long position) {
        return delegate.checkpoint(session, position);
    }

    @Override
    public List<JsonNode> checkpointJournal(
            ThreadKey session, long position, long after, int limit) {
        return delegate.checkpointJournal(session, position, after, limit);
    }

    @Override
    public CommitReceipt restore(Restoration restoration) {
        var receipt = delegate.restore(restoration);
        changed(restoration.target().key());
        return receipt;
    }

    @Override
    public void pruneEvents(SessionKey session, String throughCursor) {
        delegate.pruneEvents(session, throughCursor);
        changed(session);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
