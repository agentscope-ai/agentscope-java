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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents.Call;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents.Reply;
import java.util.List;
import java.util.Locale;

/** Tool Worker delegates child work through the ordinary Turn ledger, without occupying a tool slot. */
public final class ManagedSubagentExecutor implements ManagedToolExecutor {
    private final ManagedSubagents children;
    private final ManagedAdmissionStore admission;
    private final ManagedJournalStore journal;
    private final ObjectMapper mapper;

    public ManagedSubagentExecutor(
            ManagedSubagents children,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ObjectMapper mapper) {
        this.children = children;
        this.admission = admission;
        this.journal = journal;
        this.mapper = mapper;
    }

    @Override
    public boolean idempotentStart() {
        return true;
    }

    @Override
    public boolean waitForCompletion(ToolTask task) {
        return false;
    }

    @Override
    public String start(ManagedScope scope, ToolTask task) {
        var call = children.start(scope, task.id());
        return call.child() == null ? "cancelled:" + task.id() : call.childTurnId();
    }

    @Override
    public Outcome query(ManagedScope scope, ToolTask task) {
        var call = children.find(scope, task.id()).orElse(null);
        if (call == null) return new Outcome(State.NOT_FOUND, null);
        if (call.child() == null)
            return reply(State.CANCELLED, null, "cancelled_before_child_admission");
        if (journal.cancelRequested(call.root(), call.rootTurnId())
                || journal.cancelRequested(scope, call.parentTurnId())) requestCancellation(call);
        return completed(call);
    }

    @Override
    public Outcome cancel(ManagedScope scope, ToolTask task) {
        var call = children.find(scope, task.id()).orElse(null);
        // If the previous worker died before admission, start rechecks cancellation atomically.
        if (call == null) call = children.start(scope, task.id());
        if (call.child() == null)
            return reply(State.CANCELLED, null, "cancelled_before_child_admission");
        requestCancellation(call);
        return completed(call);
    }

    private void requestCancellation(Call call) {
        var turn = admission.turn(call.child(), call.childTurnId());
        if (terminal(turn.status())) return;
        // An ancestor's cancellation is visible to workers, but a suspended child still needs its
        // own durable Input to trigger Resume. Admission deduplicates this original parent intent.
        try {
            admission.acceptInput(
                    call.child(),
                    call.childTurnId(),
                    turn.actor(),
                    "parent-cancel:" + call.taskId(),
                    ManagedInputInbox.Type.CANCEL,
                    null,
                    "{}");
        } catch (ManagedStateException race) {
            // A closed input gate means execution is settling, never that it has stopped.
            if (!List.of("cancel_already_requested", "input_closed", "session_inactive")
                    .contains(race.code())) throw race;
        }
    }

    private Outcome completed(Call call) {
        var turn = admission.turn(call.child(), call.childTurnId());
        if (!terminal(turn.status())) return new Outcome(State.RUNNING, null);
        if (journal.toolsUnsettled(call.child(), call.childTurnId())
                || journal.head(call.child()).checkpoint() == null)
            throw new ManagedStateException(
                    "subagent_terminal_unproven",
                    "Child terminal lacks committed stop/checkpoint facts");
        String output = null;
        long through = journal.head(call.child()).sequence();
        long after = 0;
        while (after < through) {
            int count = (int) Math.min(1000, through - after);
            var events = journal.recoveryManifests(call.child(), after, count);
            if (events.size() != count)
                throw new ManagedStateException(
                        "native_sequence_conflict", "Missing committed child prefix");
            for (var event : events) {
                if (event.seq() != ++after)
                    throw new ManagedStateException(
                            "native_sequence_conflict", "Missing committed child prefix");
                if (call.childTurnId().equals(event.turnId()) && "turn/output".equals(event.type()))
                    output =
                            event.data().get("message") == null
                                    ? null
                                    : mapper.valueToTree(event.data().get("message")).toString();
            }
        }
        return switch (turn.status()) {
            case "succeeded" -> {
                if (output == null)
                    throw new ManagedStateException(
                            "subagent_output_missing", "Succeeded child has no committed output");
                yield reply(State.SUCCEEDED, output, null);
            }
            case "failed" -> reply(State.FAILED, output, "child_turn_failed");
            default -> reply(State.CANCELLED, output, "child_turn_cancelled");
        };
    }

    private Outcome reply(State state, String messageJson, String reason) {
        try {
            return new Outcome(
                    state,
                    mapper.writeValueAsString(
                            new Reply(state.name().toLowerCase(Locale.ROOT), messageJson, reason)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode committed child reply", error);
        }
    }

    private static boolean terminal(String status) {
        return List.of("succeeded", "failed", "cancelled").contains(status);
    }
}
