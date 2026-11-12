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

import java.util.List;

/** Versioned data-only commit envelope. Kernel SDK types stay behind the Brain adapter. */
public record SessionCommit(
        int schemaVersion,
        ManagedScope scope,
        String turnId,
        long epoch,
        String executionToken,
        String writerOwner,
        String commitId,
        long expectedSequence,
        List<RecoveryFact> recoveryFacts,
        List<ToolIntent> toolIntents,
        List<InputReceipt> inputReceipts,
        String terminalStatus,
        Suspension suspension) {
    public SessionCommit {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported commit protocol");
        if (scope == null || epoch < 1 || expectedSequence < 0)
            throw new IllegalArgumentException("Invalid commit identity");
        ManagedScope.requireText(turnId, "Turn id");
        ManagedScope.requireText(commitId, "commit id");
        ManagedScope.requireText(executionToken, "execution token");
        ManagedScope.requireText(writerOwner, "writer owner");
        if (recoveryFacts.size() > 4096 || toolIntents.size() > 256 || inputReceipts.size() > 256)
            throw new IllegalArgumentException("Commit exceeds item limit");
        recoveryFacts = List.copyOf(recoveryFacts);
        toolIntents = List.copyOf(toolIntents);
        inputReceipts = List.copyOf(inputReceipts);
    }

    /** Opaque kernel payload; the adapter, not the transport, interprets its format. */
    public record RecoveryFact(
            int schemaVersion,
            String eventId,
            long sequence,
            long occurredAt,
            String type,
            String executionRunId,
            String turnId,
            boolean required,
            String payloadJson) {}

    public record ToolIntent(
            String toolCallId, String toolName, String arguments, String configuration) {}

    public record InputReceipt(String inputId, String status, String reason) {}

    public record Suspension(
            String id, List<String> taskIds, List<String> actionIds, String resumePhase) {
        public boolean waitingForInput() {
            return "APPLY_INPUTS".equals(resumePhase);
        }

        public Suspension {
            taskIds = List.copyOf(taskIds);
            actionIds = List.copyOf(actionIds);
        }
    }
}
