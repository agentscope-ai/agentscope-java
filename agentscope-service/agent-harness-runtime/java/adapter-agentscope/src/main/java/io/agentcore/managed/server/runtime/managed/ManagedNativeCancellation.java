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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Clears pending native interactions on cancellation so the next Turn never inherits ASKING. */
public final class ManagedNativeCancellation {
    private ManagedNativeCancellation() {}

    /** A native dispatch is not external admission. Only proven unadmitted managed MCP calls
     * can be cancelled without discovering tools or invoking their implementation. */
    public static boolean cancelUnadmittedMcp(
            ManagedTurnExecution execution, Set<String> pinnedMcpTools) {
        if (pinnedMcpTools.isEmpty()) return false;
        execution.check();
        if (!execution.journal().cancelRequested(execution.scope(), execution.turnId())
                || !execution.local().pendingTools().isEmpty()
                || execution.journal().toolsUnsettled(execution.scope(), execution.turnId())
                || !execution
                        .journal()
                        .transport()
                        .pending(execution.scope(), execution.turnId())
                        .isEmpty()) return false;
        var dispatches = new LinkedHashMap<String, String>();
        var log = execution.log();
        var projection =
                SessionProjection.read(
                        log,
                        event -> {
                            if (execution.turnId().equals(event.turnId())
                                    && "tool/dispatch".equals(event.type()))
                                dispatches.put(
                                        String.valueOf(event.data().get("toolCallId")),
                                        String.valueOf(event.data().get("name")));
                        });
        var cancelled = new LinkedHashMap<String, ToolResultBlock>();
        for (String call : projection.uncertainToolCalls()) {
            String name = dispatches.get(call);
            if (name == null
                    || !pinnedMcpTools.contains(name)
                    || !execution
                            .journal()
                            .toolsForNativeCall(execution.scope(), execution.turnId(), call)
                            .isEmpty()
                    || execution
                            .journal()
                            .mcpBatchForCall(execution.scope(), execution.turnId(), call)
                            .isPresent()) return false;
            cancelled.put(
                    call,
                    ToolResultBlock.text(
                                    "Cancelled before managed tool admission; no external operation"
                                            + " was executed.")
                            .withIdAndName(call, name)
                            .withState(ToolResultState.INTERRUPTED));
        }
        if (cancelled.isEmpty()) return true;
        // The current execution epoch fences old proposals; checks above never issue a command.
        execution.check();
        SessionRecovery.reconcile(
                log,
                cancelled,
                "Caller cancelled managed MCP calls before durable external admission");
        return true;
    }

    public static void settle(ManagedTurnExecution execution) {
        var projection = SessionProjection.read(execution.log());
        if (!projection.uncertainToolCalls().isEmpty())
            throw new ManagedStateException(
                    "tool_reconciliation_required", "Cancel cannot invent an unknown tool outcome");
        var state = projection.restore();
        if (state == null) state = AgentState.builder().build();
        var facts = new ArrayList<SessionEvent>();
        var calls = new HashSet<String>();
        String batch = "cancel_" + UUID.randomUUID();
        long sequence = projection.asOfSeq();
        for (var request : SessionInteractions.pending(execution.log()).values()) {
            if (!execution.turnId().equals(request.turnId())) continue;
            var call =
                    JsonUtils.getJsonCodec()
                            .convertValue(request.data().get("call"), ToolUseBlock.class);
            calls.add(call.getId());
            var result =
                    ToolResultBlock.text(
                                    "Caller cancelled this pending interaction; caller-owned"
                                            + " external effects were not verified.")
                            .withIdAndName(call.getId(), call.getName())
                            .withState(ToolResultState.INTERRUPTED);
            var message =
                    ToolResultMessage.builder()
                            .id("cancelled_" + call.getId())
                            .result(result)
                            .build();
            state.contextMutable().add(message);
            facts.add(
                    event(
                            execution,
                            batch,
                            ++sequence,
                            "interaction/resolved",
                            Map.of(
                                    "requestId",
                                    call.getId(),
                                    "kind",
                                    request.data().get("kind"),
                                    "status",
                                    "cancelled")));
            facts.add(
                    event(
                            execution,
                            batch,
                            ++sequence,
                            "tool/result",
                            Map.of("message", message, "synthetic", true)));
        }
        var context = state.contextMutable();
        for (int i = 0; i < context.size(); i++) {
            var message = context.get(i);
            var content = new ArrayList<ContentBlock>();
            for (var block : message.getContent())
                content.add(
                        block instanceof ToolUseBlock call && calls.contains(call.getId())
                                ? call.withState(ToolCallState.FINISHED)
                                : block);
            context.set(i, message.withContent(content));
        }
        facts.add(
                event(
                        execution,
                        batch,
                        ++sequence,
                        "state/checkpoint",
                        Map.of(
                                "stateJson",
                                state.toJson(),
                                "projectorVersion",
                                1,
                                "reason",
                                "managed_cancelled")));
        if (!projection.activeRuns().isEmpty())
            facts.add(
                    event(
                            execution,
                            batch,
                            ++sequence,
                            "recovery/applied",
                            Map.of(
                                    "interruptedRuns",
                                    projection.activeRuns(),
                                    "reason",
                                    "caller_cancelled")));
        facts.add(
                event(
                        execution,
                        batch,
                        ++sequence,
                        "turn/cancelled",
                        Map.of("status", "cancelled", "reason", "caller_cancelled")));
        execution.boundary(batch, List.copyOf(facts), List.of(), List.of(), null);
    }

    private static SessionEvent event(
            ManagedTurnExecution execution,
            String batch,
            long sequence,
            String type,
            Object payload) {
        return new SessionEvent(
                1,
                UUID.randomUUID().toString(),
                sequence,
                System.currentTimeMillis(),
                type,
                batch,
                execution.turnId(),
                true,
                JsonUtils.getJsonCodec().toJson(payload));
    }
}
