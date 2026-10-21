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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One permission-approved MCP wave, with stable identities for every constituent invocation. */
public final class ManagedMcpBatch {
    public static final String OPERATION = "mcp.batch";

    public record Call(String callId, String name, Map<String, Object> parameters) {
        public Call {
            ManagedScope.requireText(callId, "native call id");
            ManagedScope.requireText(name, "tool name");
            parameters = Map.copyOf(parameters);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(List<Call> calls) {
        public Manifest {
            calls = List.copyOf(calls);
            if (calls.isEmpty() || calls.size() > 128)
                throw new IllegalArgumentException("MCP batch requires 1..128 calls");
            var ids = new HashSet<String>();
            for (var call : calls)
                if (!ids.add(call.callId()))
                    throw new IllegalArgumentException("Duplicate MCP call id");
        }
    }

    public record CallResult(String callId, String status, String result) {
        public CallResult {
            ManagedScope.requireText(callId, "native call id");
            if (!Set.of("succeeded", "failed", "cancelled").contains(status) || result == null)
                throw new IllegalArgumentException("MCP call has no confirmed outcome");
        }
    }

    public record Result(List<CallResult> calls) {
        public Result {
            calls = List.copyOf(calls);
            var ids = new HashSet<String>();
            for (var call : calls)
                if (!ids.add(call.callId()))
                    throw new IllegalArgumentException("Duplicate MCP result id");
        }

        public CallResult require(String id) {
            var matches = calls.stream().filter(call -> call.callId().equals(id)).toList();
            if (matches.size() != 1)
                throw new ManagedStateException(
                        "mcp_batch_result_mismatch", "MCP batch result does not match its call");
            return matches.get(0);
        }
    }

    private final Manifest manifest;
    private volatile Result completed;

    public ManagedMcpBatch(Manifest manifest) {
        this.manifest = manifest;
    }

    public String request(ToolCallParam param, ManagedToolRequests requests) {
        var context = param.getRuntimeContext();
        var execution = context.get(ManagedTurnExecution.class);
        String id = param.getToolUseBlock().getId();
        var ready = completed;
        if (ready != null) return ready.require(id).result();
        var original =
                execution.journal().mcpBatchForCall(execution.scope(), execution.turnId(), id);
        if (original.isPresent()) {
            var task = original.get();
            var call =
                    manifest(task.arguments()).calls().stream()
                            .filter(item -> item.callId().equals(id))
                            .findFirst()
                            .orElseThrow();
            var current =
                    manifest.calls().stream()
                            .filter(item -> item.callId().equals(id))
                            .findFirst()
                            .orElseThrow();
            if (!call.equals(current))
                throw new ManagedStateException(
                        "tool_content_conflict",
                        "Recovered MCP call differs from its admitted batch");
            if (task.result() == null) throw new ManagedToolWaiting(task.id());
            execution.toolCompleted(task.id(), task.result());
            completed = result(task.result());
            return completed.require(id).result();
        }
        // Parent identity is independent of which constituent call the SDK dispatches first.
        String batchId =
                "mcp_batch_"
                        + ManagedScope.fingerprint(
                                execution.turnId(),
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                manifest.calls().stream()
                                                        .map(Call::callId)
                                                        .toList()));
        var parent =
                RuntimeContext.builder(context)
                        .put(ToolUseBlock.class, new ToolUseBlock(batchId, OPERATION, Map.of()))
                        .build();
        var value =
                requests.request(
                        parent, OPERATION, Map.of("calls", manifest.calls()), Result.class);
        completed = value;
        return value.require(id).result();
    }

    public static String childTaskId(String parent, String callId) {
        return "mcp_child_" + ManagedScope.fingerprint(parent, callId);
    }

    public static Manifest manifest(String value) {
        return JsonUtils.getJsonCodec().fromJson(value, Manifest.class);
    }

    public static Result result(String value) {
        return JsonUtils.getJsonCodec().fromJson(value, Result.class);
    }
}
