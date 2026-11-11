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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Explicit operator reconciliation, never automatic re-execution of an uncertain side effect. */
public final class SessionRecovery {
    private SessionRecovery() {}

    /** Cancel an idle pending interaction without inventing success or replaying external work. */
    public static void cancelPending(SessionLog log, String turnId) {
        var writer = log.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            var projection = SessionProjection.read(log);
            if (!projection.uncertainToolCalls().isEmpty())
                throw new SessionLogException(
                        "Reconcile uncertain tool outcomes before cancelling this interrupted"
                                + " turn");
            var state = projection.restore();
            if (state == null) return;
            for (var event : log.scan(0, log.head().seq()))
                if (event.type().equals("turn/cancelled") && turnId.equals(event.turnId())) {
                    rejectSteering(log, turnId);
                    return;
                }
            var pending = SessionInteractions.pending(log);
            var ids = new HashSet<String>();
            var facts = new ArrayList<SessionEvent>();
            long seq = projection.asOfSeq();
            String run = writer.owner();
            for (var request : pending.values()) {
                if (!turnId.equals(request.turnId())) continue;
                var call =
                        JsonUtils.getJsonCodec()
                                .convertValue(request.data().get("call"), ToolUseBlock.class);
                ids.add(call.getId());
                var result =
                        ToolResultBlock.of(
                                        call.getId(),
                                        call.getName(),
                                        TextBlock.builder()
                                                .text(
                                                        "Caller cancelled this pending interaction;"
                                                            + " external side effects, if any, were"
                                                            + " not verified.")
                                                .build())
                                .withState(ToolResultState.INTERRUPTED);
                var message = ToolResultMessage.builder().result(result).build();
                state.contextMutable().add(message);
                facts.add(
                        new SessionEvent(
                                1,
                                UUID.randomUUID().toString(),
                                ++seq,
                                System.currentTimeMillis(),
                                "interaction/resolved",
                                run,
                                turnId,
                                true,
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                Map.of(
                                                        "requestId",
                                                        call.getId(),
                                                        "kind",
                                                        request.data().get("kind"),
                                                        "status",
                                                        "cancelled"))));
                facts.add(
                        new SessionEvent(
                                1,
                                UUID.randomUUID().toString(),
                                ++seq,
                                System.currentTimeMillis(),
                                "tool/result",
                                run,
                                turnId,
                                true,
                                JsonUtils.getJsonCodec()
                                        .toJson(Map.of("message", message, "synthetic", true))));
            }
            var context = state.contextMutable();
            for (int i = 0; i < context.size(); i++) {
                var message = context.get(i);
                var content = new ArrayList<ContentBlock>();
                for (var block : message.getContent())
                    content.add(
                            block instanceof ToolUseBlock call && ids.contains(call.getId())
                                    ? call.withState(ToolCallState.FINISHED)
                                    : block);
                context.set(i, message.withContent(content));
            }
            facts.add(
                    new SessionEvent(
                            1,
                            UUID.randomUUID().toString(),
                            ++seq,
                            System.currentTimeMillis(),
                            "state/checkpoint",
                            run,
                            turnId,
                            true,
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "stateJson",
                                                    state.toJson(),
                                                    "projectorVersion",
                                                    1,
                                                    "reason",
                                                    "pending_cancelled"))));
            if (!projection.activeRuns().isEmpty())
                facts.add(
                        new SessionEvent(
                                1,
                                UUID.randomUUID().toString(),
                                ++seq,
                                System.currentTimeMillis(),
                                "recovery/applied",
                                run,
                                turnId,
                                true,
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                Map.of(
                                                        "interruptedRuns",
                                                        projection.activeRuns(),
                                                        "reason",
                                                        "caller_cancelled"))));
            facts.add(
                    new SessionEvent(
                            1,
                            UUID.randomUUID().toString(),
                            ++seq,
                            System.currentTimeMillis(),
                            "turn/cancelled",
                            run,
                            turnId,
                            true,
                            JsonUtils.getJsonCodec().toJson(Map.of("status", "cancelled"))));
            log.commit(writer, UUID.randomUUID().toString(), projection.asOfSeq(), facts);
            rejectSteering(log, turnId);
        } finally {
            log.release(writer);
        }
    }

    private static void rejectSteering(SessionLog log, String turnId) {
        var applied = SessionTurns.read(log).applied();
        new SessionInbox(log)
                .locked(
                        tx -> {
                            var snapshot = tx.snapshot();
                            for (var command : snapshot.commands())
                                if ("steer".equals(command.kind())
                                        && turnId.equals(command.turnId())
                                        && !applied.contains(command.id())
                                        && !snapshot.rejected().containsKey(command.id()))
                                    tx.reject(command, "turn_cancelled");
                            return null;
                        });
    }

    public static void reconcile(
            SessionLog log, Map<String, ToolResultBlock> outcomes, String reason) {
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reconciliation reason is required");
        var writer = log.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            var projection = SessionProjection.read(log);
            if (!projection.uncertainToolCalls().equals(outcomes.keySet()))
                throw new IllegalArgumentException("Supply exactly the unresolved tool outcomes");
            var state = projection.restore();
            if (state == null) throw new SessionLogException("No recoverable state baseline");
            for (var entry : outcomes.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().getId())
                        || entry.getValue().isSuspended())
                    throw new IllegalArgumentException(
                            "Mismatched or suspended reconciliation result");
                state.contextMutable()
                        .add(ToolResultMessage.builder().result(entry.getValue()).build());
            }
            long seq = projection.asOfSeq();
            String run = UUID.randomUUID().toString();
            var repairs = new ArrayList<SessionEvent>();
            for (var outcome : outcomes.values())
                repairs.add(
                        new SessionEvent(
                                1,
                                UUID.randomUUID().toString(),
                                ++seq,
                                System.currentTimeMillis(),
                                "tool/result",
                                run,
                                null,
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
                    new SessionEvent(
                            1,
                            UUID.randomUUID().toString(),
                            seq + 1,
                            System.currentTimeMillis(),
                            "recovery/applied",
                            run,
                            null,
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
            var checkpoint =
                    new SessionEvent(
                            1,
                            UUID.randomUUID().toString(),
                            seq + 2,
                            System.currentTimeMillis(),
                            "state/checkpoint",
                            run,
                            null,
                            true,
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "stateJson",
                                                    state.toJson(),
                                                    "projectorVersion",
                                                    1)));
            repairs.add(repair);
            repairs.add(checkpoint);
            log.commit(writer, UUID.randomUUID().toString(), projection.asOfSeq(), repairs);
        } finally {
            log.release(writer);
        }
    }
}
