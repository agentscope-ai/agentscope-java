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

import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Explicit operator reconciliation, never automatic re-execution of an uncertain side effect. */
public final class ThreadRecovery {
    private ThreadRecovery() {}

    public static void reconcile(
            ThreadJournal log, Map<String, ToolResultBlock> outcomes, String reason) {
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reconciliation reason is required");
        var writer = log.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            var projection = JournalProjection.read(log);
            if (!projection.uncertainToolCalls().equals(outcomes.keySet()))
                throw new IllegalArgumentException("Supply exactly the unresolved tool outcomes");
            var state = projection.restore();
            if (state == null) throw new JournalException("No recoverable state baseline");
            var verifiedMessages = new ArrayList<ToolResultMessage>();
            for (var entry : outcomes.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().getId())
                        || entry.getValue().isSuspended()
                        || entry.getValue().hasUnknownOutcome())
                    throw new IllegalArgumentException(
                            "Reconciliation requires a verified result for the original call");
                verifiedMessages.add(ToolResultMessage.builder().result(entry.getValue()).build());
            }
            var context = state.contextMutable();
            for (int i = context.size() - 1; i >= 0; i--) {
                var message = context.get(i);
                if (message.getRole() == MsgRole.ASSISTANT) break;
                var retained =
                        message.getContent().stream()
                                .filter(
                                        block ->
                                                !(block instanceof ToolResultBlock result)
                                                        || !outcomes.containsKey(result.getId()))
                                .toList();
                if (retained.size() == message.getContent().size()) continue;
                if (retained.isEmpty()) context.remove(i);
                else context.set(i, message.withContent(retained));
            }
            context.addAll(verifiedMessages);
            long seq = projection.asOfSeq();
            String run = projection.positionRunId();
            String turn = projection.positionTurnId();
            var repairs = new ArrayList<JournalEvent>();
            for (var message : verifiedMessages)
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
                                        .toJson(Map.of("message", message, "reconciled", true))));
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
                                                    projection.activeRuns())));
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
}
