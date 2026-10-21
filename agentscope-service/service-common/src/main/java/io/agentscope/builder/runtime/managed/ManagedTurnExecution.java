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
package io.agentscope.builder.runtime.managed;

import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.Suspension;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Call-scoped service context; no mutable Session execution state is kept on an Agent instance. */
public record ManagedTurnExecution(
        ManagedScope scope,
        String turnId,
        long epoch,
        ManagedExecutionGuard guard,
        ManagedAdmissionStore admission,
        ManagedJournalStore journal,
        ManagedRuntimeWriter commits,
        ManagedLocalJournal local) {
    public ManagedTurnExecution(
            ManagedScope scope,
            String turnId,
            long epoch,
            ManagedExecutionGuard guard,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits) {
        this(
                scope,
                turnId,
                epoch,
                guard,
                admission,
                journal,
                commits,
                new ManagedLocalJournal(scope, turnId, journal));
    }

    public ManagedMqSessionLog log() {
        return new ManagedMqSessionLog(scope, turnId, epoch, guard, journal, commits, local);
    }

    public ManagedJournalStore.Head head() {
        return local.head();
    }

    public boolean inputSettled(String id) {
        return local.inputSettled(id);
    }

    public void toolCompleted(String id) {
        local.toolCompleted(id, null);
    }

    public void toolCompleted(String id, String result) {
        local.toolCompleted(id, result);
    }

    public boolean handedOff() {
        return local.handedOff();
    }

    public void check() {
        guard.check();
        admission.requireEpoch(scope, turnId, epoch);
    }

    public long closeInputs() {
        // Admission validates epoch under the Session lock in the same transaction as closing.
        guard.check();
        return admission.closeInputs(scope, turnId, epoch);
    }

    public void toolConfiguration(String sealed) {
        local.toolConfiguration(sealed);
    }

    public void boundary(
            String batchId,
            List<SessionEvent> events,
            List<ToolIntent> tools,
            List<InputReceipt> inputs,
            String terminal) {
        synchronized (local) {
            long expected = local.head().sequence();
            var batch =
                    new ManagedRuntimeBatch(
                            scope,
                            turnId,
                            epoch,
                            guard.token(),
                            guard.token(),
                            batchId,
                            expected,
                            events,
                            tools.stream()
                                    .map(
                                            intent ->
                                                    new ToolIntent(
                                                            intent.toolCallId(),
                                                            intent.toolName(),
                                                            intent.arguments(),
                                                            intent.configuration() == null
                                                                    ? local.toolConfiguration()
                                                                    : intent.configuration()))
                                    .toList(),
                            inputs,
                            terminal);
            local.publish(batch, commits, guard);
        }
    }

    public void finish(String status) {
        var captured = local.head();
        if (captured.checkpoint() == null)
            throw new ManagedStateException(
                    "checkpoint_required", "Terminal has no complete captured state");
        String id = "terminal_" + UUID.randomUUID();
        var event =
                new SessionEvent(
                        1,
                        id,
                        captured.sequence() + 1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        captured.checkpoint().identity().runId(),
                        turnId,
                        true,
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "stateJson",
                                                journal.objects().state(captured.checkpoint()),
                                                "reason",
                                                "managed_terminal",
                                                "resumePhase",
                                                "TERMINAL",
                                                "capturedFrom",
                                                captured.checkpoint())));
        boundary(id, List.of(event), List.of(), List.of(), status);
    }

    public void suspend(String waitingTask) {
        var pending = new ArrayList<>(local.pendingTools());
        // A result can race the timeout. Retain the triggering task so the handoff creates its
        // wakeup.
        if (!pending.contains(waitingTask)) pending.add(waitingTask);
        suspend(Suspension.tools("suspension_" + UUID.randomUUID(), pending));
    }

    public void suspendInputs(List<String> actions) {
        var tools = local.pendingTools();
        if (!tools.isEmpty()) {
            suspend(tools.get(0));
            return;
        }
        suspend(Suspension.inputs("suspension_" + UUID.randomUUID(), actions));
    }

    private void suspend(Suspension suspension) {
        var captured = local.head();
        if (captured.checkpoint() == null)
            throw new ManagedStateException("checkpoint_required", "Wait has no recoverable state");
        String id = suspension.id();
        var event =
                new SessionEvent(
                        1,
                        id,
                        captured.sequence() + 1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        captured.checkpoint().identity().runId(),
                        turnId,
                        true,
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "stateJson",
                                                journal.objects().state(captured.checkpoint()),
                                                "reason",
                                                suspension.waitingForInput()
                                                        ? "managed_waiting_input"
                                                        : "managed_waiting_tool",
                                                "resumePhase",
                                                suspension.resumePhase(),
                                                "capturedFrom",
                                                captured.checkpoint())));
        local.publish(
                new ManagedRuntimeBatch(
                        scope,
                        turnId,
                        epoch,
                        guard.token(),
                        guard.token(),
                        id,
                        captured.sequence(),
                        List.of(event),
                        List.of(),
                        List.of(),
                        null,
                        suspension),
                commits,
                guard);
    }
}
