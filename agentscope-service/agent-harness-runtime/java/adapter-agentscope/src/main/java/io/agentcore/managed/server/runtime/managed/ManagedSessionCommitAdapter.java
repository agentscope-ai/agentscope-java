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

/** The AgentScope/native boundary mapping, kept out of the Session Store port. */
public final class ManagedSessionCommitAdapter {
    private ManagedSessionCommitAdapter() {}

    public static SessionCommit encode(ManagedRuntimeBatch batch) {
        var suspension = batch.suspension();
        return new SessionCommit(
                1,
                batch.scope(),
                batch.turnId(),
                batch.epoch(),
                batch.executionToken(),
                batch.writerOwner(),
                batch.batchId(),
                batch.expectedSequence(),
                batch.events().stream()
                        .map(
                                event ->
                                        new SessionCommit.RecoveryFact(
                                                event.schemaVersion(),
                                                event.eventId(),
                                                event.seq(),
                                                event.occurredAt(),
                                                event.type(),
                                                event.executionRunId(),
                                                event.turnId(),
                                                event.required(),
                                                event.payloadJson()))
                        .toList(),
                batch.toolIntents().stream()
                        .map(
                                tool ->
                                        new SessionCommit.ToolIntent(
                                                tool.toolCallId(),
                                                tool.toolName(),
                                                tool.arguments(),
                                                tool.configuration()))
                        .toList(),
                batch.inputReceipts().stream()
                        .map(
                                input ->
                                        new SessionCommit.InputReceipt(
                                                input.inputId(), input.status(), input.reason()))
                        .toList(),
                batch.terminalStatus(),
                suspension == null
                        ? null
                        : new SessionCommit.Suspension(
                                suspension.id(),
                                suspension.taskIds(),
                                suspension.actionIds(),
                                suspension.resumePhase()));
    }

    static ManagedRuntimeBatch decode(SessionCommit commit) {
        var suspension = commit.suspension();
        return new ManagedRuntimeBatch(
                commit.scope(),
                commit.turnId(),
                commit.epoch(),
                commit.executionToken(),
                commit.writerOwner(),
                commit.commitId(),
                commit.expectedSequence(),
                commit.recoveryFacts().stream()
                        .map(
                                event ->
                                        new SessionEvent(
                                                event.schemaVersion(),
                                                event.eventId(),
                                                event.sequence(),
                                                event.occurredAt(),
                                                event.type(),
                                                event.executionRunId(),
                                                event.turnId(),
                                                event.required(),
                                                event.payloadJson()))
                        .toList(),
                commit.toolIntents().stream()
                        .map(
                                tool ->
                                        new ManagedRuntimeBatch.ToolIntent(
                                                tool.toolCallId(),
                                                tool.toolName(),
                                                tool.arguments(),
                                                tool.configuration()))
                        .toList(),
                commit.inputReceipts().stream()
                        .map(
                                input ->
                                        new ManagedRuntimeBatch.InputReceipt(
                                                input.inputId(), input.status(), input.reason()))
                        .toList(),
                commit.terminalStatus(),
                suspension == null
                        ? null
                        : new ManagedRuntimeBatch.Suspension(
                                suspension.id(),
                                suspension.taskIds(),
                                suspension.actionIds(),
                                suspension.resumePhase()));
    }
}
