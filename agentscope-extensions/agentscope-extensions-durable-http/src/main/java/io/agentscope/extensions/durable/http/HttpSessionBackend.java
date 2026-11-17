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

package io.agentscope.extensions.durable.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol.Endpoint;
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
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionResources;
import io.agentscope.durable.store.ObjectStore.Reference;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

public final class HttpSessionBackend implements SessionBackend {
    private final DurableHttp transport;

    public HttpSessionBackend(URI endpoint) {
        this(new DurableHttp(endpoint));
    }

    public HttpSessionBackend(DurableHttp transport) {
        this.transport = transport;
    }

    private JsonNode call(String operation, Object... fields) {
        var payload = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) payload.put((String) fields[i], fields[i + 1]);
        return transport.call(Endpoint.BACKEND, operation, payload);
    }

    @Override
    public Capabilities capabilities() {
        return as(call("capabilities"), Capabilities.class);
    }

    private static <T> T as(JsonNode result, Class<T> type) {
        return DurableJson.convert(result, type);
    }

    @Override
    public SessionView create(SessionSpec definition) {
        return as(call("create", "definition", definition), SessionView.class);
    }

    @Override
    public SessionView session(SessionKey key) {
        return as(call("session", "session", key), SessionView.class);
    }

    @Override
    public ThreadView createThread(ThreadSpec specification) {
        return as(call("createThread", "definition", specification), ThreadView.class);
    }

    @Override
    public ThreadView thread(ThreadKey key) {
        return as(call("thread", "thread", key), ThreadView.class);
    }

    @Override
    public ThreadPage threads(SessionKey key, String cursor, int limit) {
        return as(
                call("threads", "session", key, "cursor", cursor, "limit", limit),
                ThreadPage.class);
    }

    @Override
    public SessionView closeSession(SessionKey key) {
        return as(call("closeSession", "session", key), SessionView.class);
    }

    @Override
    public ThreadView closeThread(ThreadKey key) {
        return as(call("closeThread", "thread", key), ThreadView.class);
    }

    @Override
    public SessionPage listSessions(String namespace, String cursor, int limit) {
        return as(
                call("listSessions", "namespace", namespace, "cursor", cursor, "limit", limit),
                SessionPage.class);
    }

    @Override
    public TurnPage turns(ThreadKey key, String cursor, int limit) {
        return as(call("turns", "thread", key, "cursor", cursor, "limit", limit), TurnPage.class);
    }

    @Override
    public HistoryPage history(ThreadKey key, String cursor, int limit) {
        return as(
                call("history", "thread", key, "cursor", cursor, "limit", limit),
                HistoryPage.class);
    }

    @Override
    public List<Interaction> interactions(ThreadKey key, String turnId) {
        return List.of(
                as(call("interactions", "thread", key, "turnId", turnId), Interaction[].class));
    }

    @Override
    public CommandReceipt control(ThreadKey key, ControlCommand command) {
        return as(call("control", "thread", key, "command", command), CommandReceipt.class);
    }

    @Override
    public Reference putObject(SessionKey session, byte[] bytes) {
        return transport.putObject(session, bytes);
    }

    @Override
    public byte[] readObject(SessionKey session, Reference reference) {
        return transport.readObject(session, reference);
    }

    @Override
    public TurnReceipt submit(ThreadKey session, String requestId, JsonNode input) {
        return as(
                call("submit", "thread", session, "requestId", requestId, "input", input),
                TurnReceipt.class);
    }

    @Override
    public Optional<TurnReceipt> admission(SessionKey session, String requestId) {
        return Optional.ofNullable(
                as(
                        call("admission", "session", session, "requestId", requestId),
                        TurnReceipt.class));
    }

    @Override
    public List<SessionView> sessions(String after, int limit) {
        return List.copyOf(
                Arrays.asList(
                        as(call("sessions", "after", after, "limit", limit), SessionView[].class)));
    }

    @Override
    public List<ThreadSpec> ready(List<HarnessSelector> harnesses, int limit) {
        return List.of(
                as(call("ready", "harnesses", harnesses, "limit", limit), ThreadSpec[].class));
    }

    @Override
    public TurnView turn(ThreadKey session, String turnId) {
        return as(call("turn", "thread", session, "turnId", turnId), TurnView.class);
    }

    @Override
    public RunView prepareRun(Lease lease, String requestId) {
        return as(call("prepareRun", "lease", lease, "requestId", requestId), RunView.class);
    }

    @Override
    public RunView run(ThreadKey session, String turnId, String runId) {
        return as(call("run", "thread", session, "turnId", turnId, "runId", runId), RunView.class);
    }

    @Override
    public RunPage runs(ThreadKey session, String turnId, String cursor, int limit) {
        return as(
                call("runs", "thread", session, "turnId", turnId, "cursor", cursor, "limit", limit),
                RunPage.class);
    }

    @Override
    public StepView step(ThreadKey session, String turnId, String runId, String stepId) {
        return as(
                call("step", "thread", session, "turnId", turnId, "runId", runId, "stepId", stepId),
                StepView.class);
    }

    @Override
    public StepPage steps(
            ThreadKey session, String turnId, String runId, String cursor, int limit) {
        return as(
                call(
                        "steps", "thread", session, "turnId", turnId, "runId", runId, "cursor",
                        cursor, "limit", limit),
                StepPage.class);
    }

    @Override
    public ModelCallView modelCall(
            ThreadKey thread, String turnId, String runId, String modelCallId) {
        return as(
                call(
                        "modelCall",
                        "thread",
                        thread,
                        "turnId",
                        turnId,
                        "runId",
                        runId,
                        "modelCallId",
                        modelCallId),
                ModelCallView.class);
    }

    @Override
    public ModelCallPage modelCalls(
            ThreadKey thread,
            String turnId,
            String runId,
            String stepId,
            String cursor,
            int limit) {
        return as(
                call(
                        "modelCalls",
                        "thread",
                        thread,
                        "turnId",
                        turnId,
                        "runId",
                        runId,
                        "stepId",
                        stepId,
                        "cursor",
                        cursor,
                        "limit",
                        limit),
                ModelCallPage.class);
    }

    @Override
    public Optional<Lease> claim(ThreadKey session, String owner, Duration duration) {
        return Optional.ofNullable(
                as(
                        call(
                                "claim",
                                "thread",
                                session,
                                "owner",
                                owner,
                                "duration",
                                duration.toMillis()),
                        Lease.class));
    }

    @Override
    public void renew(Lease lease, Duration duration) {
        call("renew", "lease", lease, "duration", duration.toMillis());
    }

    @Override
    public void release(Lease lease, Duration delay) {
        call("release", "lease", lease, "delay", delay.toMillis());
    }

    @Override
    public RecoveryView recordFailure(Lease lease, Failure failure) {
        return as(call("recordFailure", "lease", lease, "failure", failure), RecoveryView.class);
    }

    @Override
    public Frame frame(Lease lease) {
        return as(call("frame", "lease", lease), Frame.class);
    }

    @Override
    public CommitReceipt commit(Lease lease, Commit commit) {
        return as(call("commit", "lease", lease, "commit", commit), CommitReceipt.class);
    }

    @Override
    public List<JsonNode> journal(Lease lease, long after, int limit) {
        return List.of(
                as(
                        call("journal", "lease", lease, "after", after, "limit", limit),
                        JsonNode[].class));
    }

    @Override
    public Optional<CommitReceipt> receipt(ThreadKey session, String turnId, String commitId) {
        return Optional.ofNullable(
                as(
                        call("receipt", "thread", session, "turnId", turnId, "commitId", commitId),
                        CommitReceipt.class));
    }

    @Override
    public ToolExecution execution(ThreadKey session, String executionId) {
        return as(
                call("execution", "thread", session, "executionId", executionId),
                ToolExecution.class);
    }

    @Override
    public DelegationView delegate(ExecutionRequest parent, DelegationTarget target) {
        return as(
                call("delegate", "thread", parent.thread(), "request", parent, "target", target),
                DelegationView.class);
    }

    @Override
    public Optional<DelegationView> delegation(ExecutionRequest parent) {
        return Optional.ofNullable(
                as(
                        call("delegation", "thread", parent.thread(), "request", parent),
                        DelegationView.class));
    }

    @Override
    public DelegationView cancelDelegation(ExecutionRequest parent, String childTurnId) {
        return as(
                call(
                        "cancelDelegation",
                        "thread",
                        parent.thread(),
                        "request",
                        parent,
                        "childTurnId",
                        childTurnId),
                DelegationView.class);
    }

    @Override
    public ToolExecution beginDispatch(Lease lease, String executionId) {
        return as(
                call("beginDispatch", "lease", lease, "executionId", executionId),
                ToolExecution.class);
    }

    @Override
    public JsonNode resource(Lease lease, SessionResources.Request request) {
        return call("resource", "lease", lease, "request", request);
    }

    @Override
    public List<SessionResources.Value> checkpointResources(ThreadKey session, long position) {
        return Arrays.asList(
                as(
                        call("checkpointResources", "thread", session, "position", position),
                        SessionResources.Value[].class));
    }

    @Override
    public void acceptExecutionEvent(ExecutionMessages.Event event) {
        call("acceptExecutionEvent", "thread", event.request().thread(), "event", event);
    }

    @Override
    public void acceptResult(ThreadKey session, String executionId, ExecutionResult result) {
        call("acceptResult", "thread", session, "executionId", executionId, "result", result);
    }

    @Override
    public void signal(ThreadKey session, String turnId, Signal signal) {
        call("signal", "thread", session, "turnId", turnId, "signal", signal);
    }

    @Override
    public void withdraw(ThreadKey session, String turnId, String signalId, String requestId) {
        call(
                "withdraw",
                "thread",
                session,
                "turnId",
                turnId,
                "signalId",
                signalId,
                "requestId",
                requestId);
    }

    @Override
    public void cancel(ThreadKey session, String turnId, String requestId) {
        call("cancel", "thread", session, "turnId", turnId, "requestId", requestId);
    }

    @Override
    public Snapshot snapshot(SessionKey session) {
        return as(call("snapshot", "session", session), Snapshot.class);
    }

    @Override
    public EventPage events(SessionKey session, String cursor, int limit) {
        return as(
                call("events", "session", session, "cursor", cursor, "limit", limit),
                EventPage.class);
    }

    @Override
    public List<CheckpointMetadata> checkpoints(ThreadKey session, long after, int limit) {
        return List.of(
                as(
                        call("checkpoints", "thread", session, "after", after, "limit", limit),
                        CheckpointMetadata[].class));
    }

    @Override
    public CheckpointView checkpoint(ThreadKey session, long position) {
        return as(
                call("checkpoint", "thread", session, "position", position), CheckpointView.class);
    }

    @Override
    public List<JsonNode> checkpointJournal(
            ThreadKey session, long position, long after, int limit) {
        return List.of(
                as(
                        call(
                                "checkpointJournal",
                                "thread",
                                session,
                                "position",
                                position,
                                "after",
                                after,
                                "limit",
                                limit),
                        JsonNode[].class));
    }

    @Override
    public CommitReceipt restore(Restoration restore) {
        return as(
                call("restore", "thread", restore.target().key(), "restoration", restore),
                CommitReceipt.class);
    }

    @Override
    public void pruneEvents(SessionKey session, String throughCursor) {
        call("pruneEvents", "session", session, "throughCursor", throughCursor);
    }
}
