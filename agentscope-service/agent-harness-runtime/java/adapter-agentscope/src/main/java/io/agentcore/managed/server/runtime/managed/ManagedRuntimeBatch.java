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

import io.agentscope.core.session.SessionEvent;
import java.util.List;
import java.util.Set;

/** An immutable, epoch-fenced native recovery boundary committed by HarnessRuntime. */
public record ManagedRuntimeBatch(
        ManagedScope scope,
        String turnId,
        long epoch,
        String executionToken,
        String writerOwner,
        String batchId,
        long expectedSequence,
        List<SessionEvent> events,
        List<ToolIntent> toolIntents,
        List<InputReceipt> inputReceipts,
        String terminalStatus,
        Suspension suspension) {
    public ManagedRuntimeBatch(
            ManagedScope scope,
            String turnId,
            long epoch,
            String executionToken,
            String writerOwner,
            String batchId,
            long expectedSequence,
            List<SessionEvent> events,
            List<ToolIntent> toolIntents,
            List<InputReceipt> inputReceipts,
            String terminalStatus) {
        this(
                scope,
                turnId,
                epoch,
                executionToken,
                writerOwner,
                batchId,
                expectedSequence,
                events,
                toolIntents,
                inputReceipts,
                terminalStatus,
                null);
    }

    public ManagedRuntimeBatch withEvents(List<SessionEvent> value) {
        return new ManagedRuntimeBatch(
                scope,
                turnId,
                epoch,
                executionToken,
                writerOwner,
                batchId,
                expectedSequence,
                value,
                toolIntents,
                inputReceipts,
                terminalStatus,
                suspension);
    }

    public ManagedRuntimeBatch {
        if (scope == null || epoch < 1 || expectedSequence < 0)
            throw new IllegalArgumentException("Invalid runtime boundary");
        ManagedScope.requireText(turnId, "Turn id");
        ManagedScope.requireText(executionToken, "execution token");
        ManagedScope.requireText(writerOwner, "writer owner");
        ManagedScope.requireText(batchId, "batch id");
        events = List.copyOf(events);
        toolIntents = List.copyOf(toolIntents);
        inputReceipts = List.copyOf(inputReceipts);
        if (suspension != null && terminalStatus != null)
            throw new IllegalArgumentException("A boundary cannot suspend and finish a Turn");
        if (events.size() > 4096 || toolIntents.size() > 256 || inputReceipts.size() > 256)
            throw new IllegalArgumentException("Runtime boundary exceeds item limit");
        if (terminalStatus != null
                && !Set.of("succeeded", "failed", "cancelled").contains(terminalStatus))
            throw new IllegalArgumentException("Invalid terminal status");
        long sequence = expectedSequence;
        for (var event : events) {
            if (Set.of("model/chunk", "tool/chunk").contains(event.type()))
                throw new IllegalArgumentException(
                        "Diagnostic chunks cannot enter an execution boundary");
            if (event.seq() != ++sequence || !turnId.equals(event.turnId()))
                throw new IllegalArgumentException("Native event sequence or Turn differs");
        }
    }

    /** Immutable continuation identity; executable objects and callbacks are never persisted. */
    public record Suspension(
            String id, List<String> taskIds, List<String> actionIds, String resumePhase) {
        public static Suspension tools(String id, List<String> tasks) {
            return new Suspension(id, tasks, List.of(), "APPLY_TOOL_RESULTS");
        }

        public static Suspension inputs(String id, List<String> actions) {
            return new Suspension(id, List.of(), actions, "APPLY_INPUTS");
        }

        public boolean waitingForInput() {
            return "APPLY_INPUTS".equals(resumePhase);
        }

        public Suspension {
            ManagedScope.requireText(id, "suspension id");
            taskIds = List.copyOf(taskIds);
            actionIds = List.copyOf(actionIds);
            if (!Set.of("APPLY_TOOL_RESULTS", "APPLY_INPUTS").contains(resumePhase))
                throw new IllegalArgumentException("Unsupported continuation phase");
            var pending = "APPLY_INPUTS".equals(resumePhase) ? actionIds : taskIds;
            var other = "APPLY_INPUTS".equals(resumePhase) ? taskIds : actionIds;
            if (pending.isEmpty()
                    || pending.size() > 128
                    || pending.stream().distinct().count() != pending.size()
                    || !other.isEmpty())
                throw new IllegalArgumentException("Suspension needs unique pending identities");
            taskIds.forEach(idValue -> ManagedScope.requireText(idValue, "pending task id"));
            actionIds.forEach(idValue -> ManagedScope.requireText(idValue, "pending action id"));
        }
    }

    public record ToolIntent(
            String toolCallId, String toolName, String arguments, String configuration) {
        public ToolIntent(String toolCallId, String toolName, String arguments) {
            this(toolCallId, toolName, arguments, null);
        }

        public ToolIntent {
            ManagedScope.requireText(toolCallId, "tool call id");
            ManagedScope.requireText(toolName, "tool name");
            if (arguments == null) throw new IllegalArgumentException("Missing tool arguments");
        }
    }

    public record InputReceipt(String inputId, String status, String reason) {
        public InputReceipt {
            ManagedScope.requireText(inputId, "input id");
            if (!Set.of("applied", "rejected").contains(status))
                throw new IllegalArgumentException("Invalid input receipt status");
            if ("rejected".equals(status))
                ManagedScope.requireText(reason, "input rejection reason");
        }
    }
}
