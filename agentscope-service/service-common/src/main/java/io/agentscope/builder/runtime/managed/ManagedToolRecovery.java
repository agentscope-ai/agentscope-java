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

import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconstructs built-in tool outputs using original Hands operations; never sends a new operation. */
public final class ManagedToolRecovery {
    private static final Set<String> BUILTINS =
            Set.of(
                    "execute",
                    "read_file",
                    "write_file",
                    "edit_file",
                    "list_files",
                    "grep_files",
                    "glob_files");

    private ManagedToolRecovery() {}

    public static void reconcile(
            ManagedTurnExecution execution, HarnessAgent agent, RuntimeContext base) {
        var pending =
                execution.journal().transport().pending(execution.scope(), execution.turnId());
        if (!pending.isEmpty()) {
            // A new epoch may admit the same approved wave, but must obtain its own Broker ACK
            // first.
            execution.boundary(
                    "recover_tool_proposals_" + UUID.randomUUID(),
                    List.of(),
                    pending.stream()
                            .map(
                                    command ->
                                            new ToolIntent(
                                                    command.task().toolCallId(),
                                                    command.task().name(),
                                                    command.task().arguments(),
                                                    command.configuration()))
                            .toList(),
                    List.of(),
                    null);
        }
        var log = execution.log();
        var dispatch = new LinkedHashMap<String, Map<String, Object>>();
        var projection =
                SessionProjection.read(
                        log,
                        event -> {
                            if (execution.turnId().equals(event.turnId())
                                    && "tool/dispatch".equals(event.type()))
                                dispatch.put(
                                        String.valueOf(event.data().get("toolCallId")),
                                        event.data());
                        });
        if (projection.uncertainToolCalls().isEmpty()) {
            reconcileBatchRemainder(execution, agent);
            return;
        }
        var results = new LinkedHashMap<String, ToolResultBlock>();
        for (String id : projection.uncertainToolCalls()) {
            var original = dispatch.get(id);
            if (original != null
                    && Set.of("agent_spawn", "agent_send").contains(original.get("name"))) {
                var candidates =
                        execution
                                .journal()
                                .toolsForNativeCall(execution.scope(), execution.turnId(), id)
                                .stream()
                                .filter(task -> ManagedSubagents.OPERATION.equals(task.name()))
                                .toList();
                if (candidates.size() != 1)
                    throw new ManagedStateException(
                            "tool_reconciliation_required",
                            "Child recovery requires exactly one original durable call: " + id);
                var task = candidates.get(0);
                if (task.result() == null) throw new ManagedToolWaiting(task.id());
                @SuppressWarnings("unchecked")
                var arguments = JsonUtils.getJsonCodec().fromJson(task.arguments(), Map.class);
                var specification =
                        JsonUtils.getJsonCodec()
                                .convertValue(
                                        arguments.get("subagent"),
                                        ManagedSubagents.Specification.class);
                var reply =
                        JsonUtils.getJsonCodec()
                                .fromJson(task.result(), ManagedSubagents.Reply.class);
                String text =
                        "succeeded".equals(reply.status())
                                ? specification.resultHeader()
                                        + "\nstatus: ok\nreply:\n"
                                        + JsonUtils.getJsonCodec()
                                                .fromJson(reply.messageJson(), Msg.class)
                                                .getTextContent()
                                : specification.resultHeader()
                                        + "\nstatus: error\nerror: "
                                        + reply.reason();
                execution.toolCompleted(task.id(), task.result());
                results.put(
                        id,
                        ToolResultBlock.text(text)
                                .withIdAndName(id, (String) original.get("name")));
                continue;
            }
            if (original != null
                    && agent.getToolkit().getTool((String) original.get("name"))
                            instanceof ManagedMcpTool proxy) {
                var batch =
                        execution
                                .journal()
                                .mcpBatchForCall(execution.scope(), execution.turnId(), id);
                if (batch.isPresent()) {
                    var task = batch.get();
                    if (task.result() == null) throw new ManagedToolWaiting(task.id());
                    execution.toolCompleted(task.id(), task.result());
                    results.put(
                            id,
                            proxy.decode(ManagedMcpBatch.result(task.result()).require(id).result())
                                    .withIdAndName(id, (String) original.get("name")));
                    continue;
                }
                var operations =
                        execution
                                .journal()
                                .toolsForNativeCall(execution.scope(), execution.turnId(), id)
                                .stream()
                                .filter(task -> "mcp.call".equals(task.name()))
                                .toList();
                if (operations.size() > 1)
                    throw new ManagedStateException(
                            "tool_reconciliation_required",
                            "MCP recovery requires its original operation");
                if (!operations.isEmpty()) {
                    var task = operations.get(0);
                    if (task.result() == null) throw new ManagedToolWaiting(task.id());
                    execution.toolCompleted(task.id(), task.result());
                    results.put(
                            id,
                            proxy.decode(task.result())
                                    .withIdAndName(id, (String) original.get("name")));
                    continue;
                }
                // No committed authorization means this managed MCP call could not have executed.
                // The new durable epoch fences any old proposal before retrying the approved call.

            }
            boolean unadmittedMcp =
                    original != null
                            && agent.getToolkit().getTool((String) original.get("name"))
                                    instanceof ManagedMcpTool;
            if (original == null || !BUILTINS.contains(original.get("name")) && !unadmittedMcp)
                throw new ManagedStateException(
                        "tool_reconciliation_required",
                        "Unknown external tool outcome requires explicit reconciliation: " + id);
            String name = (String) original.get("name");
            var tool = agent.getToolkit().getTool(name);
            if (tool == null)
                throw new ManagedStateException(
                        "tool_definition_missing", "Original tool definition is unavailable");
            @SuppressWarnings("unchecked")
            var arguments = (Map<String, Object>) original.get("arguments");
            var call =
                    ToolUseBlock.builder()
                            .id(id)
                            .name(name)
                            .input(arguments)
                            .content(JsonUtils.getJsonCodec().toJson(arguments))
                            .build();
            var context =
                    RuntimeContext.builder(base)
                            .runId("recover_" + UUID.randomUUID())
                            .put(ToolUseBlock.class, call)
                            .put(
                                    ManagedToolRequests.Operations.class,
                                    new ManagedToolRequests.Operations(!unadmittedMcp))
                            .build();
            context.setAgentState(projection.restore());
            execution.check();
            var result =
                    tool.callAsync(
                                    ToolCallParam.builder()
                                            .toolUseBlock(call)
                                            .input(arguments)
                                            .agent(agent)
                                            .runtimeContext(context)
                                            .build())
                            .block();
            if (result == null || result.isSuspended())
                throw new ManagedStateException(
                        "tool_reconciliation_required",
                        "Recovered wrapper has no confirmed result");
            execution.check();
            results.put(id, result.withIdAndName(id, name));
        }
        execution.check();
        SessionRecovery.reconcile(
                log, results, "Confirmed original Hands operations under the new execution epoch");
        reconcileBatchRemainder(execution, agent);
    }

    /** Worker admission covers the entire approved wave, including calls not yet entered by SDK's
     * serial dispatcher. Apply their confirmed results before cancelling or starting another Turn. */
    private static void reconcileBatchRemainder(
            ManagedTurnExecution execution, HarnessAgent agent) {
        var log = execution.log();
        var projection = SessionProjection.read(log);
        var state = projection.restore();
        if (state == null) return;
        var resolved = new HashSet<String>();
        var pending = new LinkedHashMap<String, ToolUseBlock>();
        for (var message : state.contextMutable()) {
            for (var block : message.getContent()) {
                if (block instanceof ToolResultBlock result) resolved.add(result.getId());
                else if (block instanceof ToolUseBlock call) pending.put(call.getId(), call);
            }
        }
        var originals = new LinkedHashMap<String, ManagedJournalStore.ToolTask>();
        var aggregates = new LinkedHashMap<String, ManagedMcpBatch.Result>();
        var admittedCalls = new LinkedHashMap<String, ManagedMcpBatch.Call>();
        for (var task : execution.journal().mcpBatches(execution.scope(), execution.turnId())) {
            for (var call : ManagedMcpBatch.manifest(task.arguments()).calls()) {
                if (!pending.containsKey(call.callId()) || resolved.contains(call.callId()))
                    continue;
                admittedCalls.put(call.callId(), call);
                if (originals.putIfAbsent(call.callId(), task) != null)
                    throw new ManagedStateException(
                            "tool_content_conflict", "Native MCP call belongs to multiple batches");
            }
        }
        var outcomes = new LinkedHashMap<String, ToolResultBlock>();
        for (var call : pending.values()) {
            if (resolved.contains(call.getId())) continue;
            var task = originals.get(call.getId());
            if (task == null) continue;
            if (task.result() == null) throw new ManagedToolWaiting(task.id());
            if (!(agent.getToolkit().getTool(call.getName()) instanceof ManagedMcpTool proxy))
                throw new ManagedStateException(
                        "tool_reconciliation_required", "Batch MCP descriptor is unavailable");
            var admitted = admittedCalls.get(call.getId());
            if (!call.getName().equals(admitted.name()))
                throw new ManagedStateException(
                        "tool_content_conflict", "Batch call name differs from its model call");
            var outcome =
                    aggregates
                            .computeIfAbsent(
                                    task.id(), ignored -> ManagedMcpBatch.result(task.result()))
                            .require(call.getId());
            var result = proxy.decode(outcome.result()).withIdAndName(call.getId(), call.getName());
            if ("cancelled".equals(outcome.status()))
                result = result.withState(ToolResultState.INTERRUPTED);
            outcomes.put(call.getId(), result);
            execution.toolCompleted(task.id(), task.result());
        }
        if (outcomes.isEmpty()) return;
        execution.check();
        var writer = log.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
        try {
            long seq = projection.asOfSeq();
            var facts = new ArrayList<SessionEvent>();
            for (var result : outcomes.values()) {
                var message = ToolResultMessage.builder().result(result).build();
                state.contextMutable().add(message);
                facts.add(
                        new SessionEvent(
                                1,
                                UUID.randomUUID().toString(),
                                ++seq,
                                System.currentTimeMillis(),
                                "tool/result",
                                writer.owner(),
                                execution.turnId(),
                                true,
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                Map.of(
                                                        "message",
                                                        message,
                                                        "reconciled",
                                                        true,
                                                        "source",
                                                        "managed_mcp_batch"))));
            }
            resolved.addAll(outcomes.keySet());
            var context = state.contextMutable();
            for (int i = 0; i < context.size(); i++) {
                var message = context.get(i);
                var content = new ArrayList<ContentBlock>();
                for (var block : message.getContent())
                    content.add(
                            block instanceof ToolUseBlock call && resolved.contains(call.getId())
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
                            "recovery/applied",
                            writer.owner(),
                            execution.turnId(),
                            true,
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "resolvedToolCallIds",
                                                    outcomes.keySet(),
                                                    "reason",
                                                    "Confirmed remaining MCP batch results",
                                                    "interruptedRuns",
                                                    projection.activeRuns()))));
            facts.add(
                    new SessionEvent(
                            1,
                            UUID.randomUUID().toString(),
                            ++seq,
                            System.currentTimeMillis(),
                            "state/checkpoint",
                            writer.owner(),
                            execution.turnId(),
                            true,
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "stateJson",
                                                    state.toJson(),
                                                    "projectorVersion",
                                                    1,
                                                    "reason",
                                                    "managed_mcp_batch_results"))));
            log.commit(writer, UUID.randomUUID().toString(), projection.asOfSeq(), facts);
        } finally {
            log.release(writer);
        }
    }
}
