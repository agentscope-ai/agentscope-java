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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Execution-side inbox consumer. Validation runs under the native execution writer lease. */
public final class SessionExecution {
    public static final String CONTEXT_KEY = "agentscope.session.execution";
    private final SessionLog log;
    private final SessionInbox inbox;
    private final SessionInbox.Command command;
    private List<Msg> inputs;
    private boolean opened;

    public SessionExecution(SessionLog log, SessionInbox.Command command) {
        this.log = log;
        this.inbox = new SessionInbox(log);
        this.command = command;
    }

    public static SessionExecution from(RuntimeContext rc) {
        return rc != null && rc.get(CONTEXT_KEY) instanceof SessionExecution value ? value : null;
    }

    public List<Msg> inputs() {
        return inputs;
    }

    public void prepare(SessionRecorder recorder) {
        var view = SessionTurns.read(log);
        if (view.started().contains(command.id()))
            throw new IllegalStateException("Command already executed");
        var latest = view.latest();
        var pending = SessionInteractions.pending(log);
        if (command.kind().equals("submit")) {
            if (latest != null && !latest.ended())
                throw new IllegalStateException(
                        "Continue the unfinished task before starting another task");
            if (!pending.isEmpty())
                throw new IllegalStateException("Answer pending interactions first");
            inputs = command.messages();
        } else {
            if (latest == null || !latest.turnId().equals(command.turnId()) || latest.ended())
                throw new IllegalStateException("Only the latest unfinished task can continue");
            if (command.kind().equals("resume")) {
                if (!pending.isEmpty())
                    throw new IllegalStateException("Answer pending interactions first");
                // Confirmations change tool/permission state, rather than becoming chat messages.
                // Resolved answers must not be replayed as a blank user message on continuation.
                inputs =
                        view.inputs().getOrDefault(command.turnId(), List.of()).stream()
                                .filter(
                                        message ->
                                                !message.getMetadata()
                                                        .containsKey(Msg.METADATA_CONFIRM_RESULTS))
                                .toList();
            } else {
                for (String id : command.requestIds()) {
                    var request = pending.get(id);
                    if (request == null || !command.turnId().equals(request.turnId()))
                        throw new IllegalStateException("Interaction is no longer pending: " + id);
                }
                inputs = command.messages();
            }
        }
        recorder.append("inbox/started", Map.of("commandId", command.id(), "kind", command.kind()));
        inbox.locked(
                tx -> {
                    tx.open(command.turnId(), recorder.runId());
                    return null;
                });
        opened = true;
    }

    /** Checkpoint messages before marking their commands consumed; retries deduplicate message IDs. */
    public void beforeStep(SessionRecorder recorder, AgentState state) {
        inbox.locked(
                tx -> {
                    var applied = SessionTurns.read(log).applied();
                    var accepted = new ArrayList<String>();
                    var known = new HashSet<>(recorder.acceptedMessageIds());
                    for (var message : state.getContext()) known.add(message.getId());
                    var snapshot = tx.snapshot();
                    for (var input : snapshot.commands()) {
                        if (snapshot.rejected().containsKey(input.id())
                                || applied.contains(input.id())
                                || !(input.kind().equals("inject") || input.kind().equals("steer")))
                            continue;
                        if (input.kind().equals("steer")
                                && !Objects.equals(input.turnId(), command.turnId())) continue;
                        for (var message : input.messages())
                            if (known.add(message.getId())) state.contextMutable().add(message);
                        accepted.add(input.id());
                    }
                    if (!accepted.isEmpty()) {
                        recorder.checkpointNow(state, "session_input");
                        recorder.recordNow("inbox/applied", Map.of("commandIds", accepted));
                    }
                    return null;
                });
    }

    /** Serializes terminal admission with steering acceptance, so accepted steering is not lost. */
    public boolean continueOrClose(SessionRecorder recorder) {
        return inbox.locked(
                tx -> {
                    var applied = SessionTurns.read(log).applied();
                    boolean pending =
                            tx.snapshot().commands().stream()
                                    .anyMatch(
                                            input ->
                                                    input.kind().equals("steer")
                                                            && Objects.equals(
                                                                    input.turnId(),
                                                                    command.turnId())
                                                            && !applied.contains(input.id()));
                    if (!pending) close(tx, recorder);
                    return pending;
                });
    }

    public void close(SessionRecorder recorder) {
        if (opened)
            inbox.locked(
                    tx -> {
                        close(tx, recorder);
                        return null;
                    });
    }

    private void close(SessionInbox.Transaction tx, SessionRecorder recorder) {
        if (opened) {
            tx.close(command.turnId(), recorder.runId());
            opened = false;
        }
    }
}
