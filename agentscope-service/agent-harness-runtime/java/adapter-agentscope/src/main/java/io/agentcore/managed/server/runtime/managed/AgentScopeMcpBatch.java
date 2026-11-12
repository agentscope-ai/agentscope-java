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

import static io.agentcore.managed.server.runtime.managed.ManagedMcpBatch.OPERATION;
import static io.agentcore.managed.server.runtime.managed.ManagedMcpBatch.manifest;
import static io.agentcore.managed.server.runtime.managed.ManagedMcpBatch.result;

import io.agentcore.managed.server.runtime.managed.ManagedMcpBatch.Call;
import io.agentcore.managed.server.runtime.managed.ManagedMcpBatch.Manifest;
import io.agentcore.managed.server.runtime.managed.ManagedMcpBatch.Result;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;

/** AgentScope dispatch for a platform MCP batch manifest. */
public final class AgentScopeMcpBatch {
    private final Manifest manifest;
    private volatile Result completed;

    public AgentScopeMcpBatch(Manifest manifest) {
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
}
