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
package io.agentscope.core.journal;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit operator reconciliation, never automatic re-execution of an uncertain side effect. */
public final class ThreadRecovery {
    private ThreadRecovery() {}

    public static void reconcile(
            ThreadJournal log, Map<String, ToolResultBlock> outcomes, String reason) {
        reconcile(log, outcomes, reason, false);
    }

    /** Persist one independently verified outcome while other dispatches may still need evidence. */
    public static void reconcileOne(ThreadJournal log, ToolResultBlock outcome, String reason) {
        reconcile(log, Map.of(outcome.getId(), outcome), reason, true);
    }

    private static void reconcile(
            ThreadJournal log,
            Map<String, ToolResultBlock> outcomes,
            String reason,
            boolean partial) {
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reconciliation reason is required");
        var writer = log.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            var projection = JournalProjection.read(log);
            boolean complete = projection.uncertainToolCalls().equals(outcomes.keySet());
            if (!complete
                    && !(partial && projection.uncertainToolCalls().containsAll(outcomes.keySet())))
                throw new IllegalArgumentException("Supply exactly the unresolved tool outcomes");
            var state = projection.restore();
            if (state == null) throw new JournalException("No recoverable state baseline");
            for (var entry : outcomes.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().getId())
                        || entry.getValue().isSuspended())
                    throw new IllegalArgumentException(
                            "Mismatched or suspended reconciliation result");
                state.contextMutable()
                        .add(ToolResultMessage.builder().result(entry.getValue()).build());
            }
            if (complete && !outcomes.isEmpty() && returnsDirectly(log, projection, state)) {
                state.setReturnDirectTurn(projection.positionTurnId());
            }
            long seq = projection.asOfSeq();
            String run = projection.positionRunId();
            String turn = projection.positionTurnId();
            var repairs = new ArrayList<JournalEvent>();
            for (var outcome : outcomes.values())
                repairs.add(
                        new JournalEvent(
                                JournalEvent.SCHEMA_VERSION,
                                UUID.randomUUID().toString(),
                                ++seq,
                                System.currentTimeMillis(),
                                "tool/result",
                                run,
                                turn,
                                true,
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                Map.of(
                                                        "message",
                                                        ToolResultMessage.builder()
                                                                .result(outcome)
                                                                .build(),
                                                        "reconciled",
                                                        true))));
            var repair =
                    new JournalEvent(
                            JournalEvent.SCHEMA_VERSION,
                            UUID.randomUUID().toString(),
                            seq + 1,
                            System.currentTimeMillis(),
                            "recovery/applied",
                            run,
                            turn,
                            true,
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "resolvedToolCallIds",
                                                    outcomes.keySet(),
                                                    "reason",
                                                    reason,
                                                    "outcomes",
                                                    outcomes,
                                                    "interruptedRuns",
                                                    complete
                                                            ? projection.activeRuns()
                                                            : java.util.List.of())));
            var checkpointData = new LinkedHashMap<String, Object>();
            checkpointData.put("stateJson", state.toJson());
            checkpointData.put("projectorVersion", 1);
            if (projection.executionPosition() != null)
                checkpointData.put("executionPosition", projection.executionPosition());
            var checkpoint =
                    new JournalEvent(
                            JournalEvent.SCHEMA_VERSION,
                            UUID.randomUUID().toString(),
                            seq + 2,
                            System.currentTimeMillis(),
                            "state/checkpoint",
                            run,
                            turn,
                            true,
                            JsonUtils.getJsonCodec().toJson(checkpointData));
            repairs.add(repair);
            repairs.add(checkpoint);
            log.commit(writer, UUID.randomUUID().toString(), projection.asOfSeq(), repairs);
        } finally {
            log.release(writer);
        }
    }

    private static boolean returnsDirectly(
            ThreadJournal log, JournalProjection projection, AgentState state) {
        var messages = state.contextMutable();
        int index = messages.size() - 1;
        var successful = new HashSet<String>();
        while (index >= 0 && messages.get(index) instanceof ToolResultMessage message) {
            for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                if (result.getState() != ToolResultState.SUCCESS) return false;
                successful.add(result.getId());
            }
            index--;
        }
        if (index < 0) return false;
        var calls = messages.get(index).getContentBlocks(ToolUseBlock.class);
        if (calls.isEmpty() || calls.size() != successful.size()) return false;
        var direct = new HashSet<String>();
        for (var event : log.scanMetadata(log.recoveryCursor(), projection.asOfSeq())) {
            if ("tool/dispatch".equals(event.type())
                    && Objects.equals(projection.positionTurnId(), event.turnId())) {
                String id = (String) event.data().get("toolCallId");
                if (Boolean.TRUE.equals(event.data().get("returnDirect"))) direct.add(id);
                else direct.remove(id);
            }
        }
        return calls.stream()
                .allMatch(
                        call -> successful.contains(call.getId()) && direct.contains(call.getId()));
    }
}
