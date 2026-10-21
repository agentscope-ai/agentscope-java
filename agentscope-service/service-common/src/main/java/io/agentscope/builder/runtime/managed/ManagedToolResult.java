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

import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Original tool executions may finish after Brain ownership changes. Their identity remains stable. */
public record ManagedToolResult(
        ManagedScope scope, String taskId, String executionId, String status, String result) {
    public ManagedToolResult {
        if (scope == null) throw new IllegalArgumentException("Missing tool result scope");
        ManagedScope.requireText(taskId, "task id");
        ManagedScope.requireText(executionId, "tool execution id");
        if (!Set.of("succeeded", "failed", "cancelled").contains(status) || result == null)
            throw new IllegalArgumentException("Tool result needs a confirmed terminal outcome");
    }

    public String batchId() {
        return "result_" + ManagedScope.fingerprint(taskId, executionId);
    }

    public static String cancelledPayload(String operation, String arguments) {
        Object payload;
        if (ManagedMcpBatch.OPERATION.equals(operation)) {
            payload =
                    new ManagedMcpBatch.Result(
                            ManagedMcpBatch.manifest(arguments).calls().stream()
                                    .map(
                                            call ->
                                                    new ManagedMcpBatch.CallResult(
                                                            call.callId(),
                                                            "cancelled",
                                                            cancelledPayload(
                                                                    "mcp.call",
                                                                    JsonUtils.getJsonCodec()
                                                                            .toJson(
                                                                                    call
                                                                                            .parameters()))))
                                    .toList());
        } else if ("mcp.describe".equals(operation)) {
            payload = Map.of("tools", List.of(), "unavailable", List.of());
        } else if ("mcp.call".equals(operation)) {
            payload =
                    Map.of(
                            "content",
                            List.of(
                                    Map.of(
                                            "type",
                                            "text",
                                            "text",
                                            "Cancelled before MCP invocation")),
                            "isError",
                            true);
        } else if (ManagedSubagents.OPERATION.equals(operation)) {
            payload =
                    new ManagedSubagents.Reply(
                            "cancelled", null, "cancelled_before_child_admission");
        } else if (ManagedArtifactTool.OPERATION.equals(operation)) {
            payload = Map.of("error", "cancelled", "message", "Cancelled before export");
        } else if (operation.startsWith("fs.")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = JsonUtils.getJsonCodec().fromJson(arguments, Map.class);
            payload = Map.of("path", String.valueOf(input.get("path")), "error", "cancelled");
        } else
            payload =
                    Map.of(
                            "output",
                            "Cancelled before execution",
                            "exitCode",
                            -1,
                            "truncated",
                            false);
        return JsonUtils.getJsonCodec().toJson(payload);
    }
}
