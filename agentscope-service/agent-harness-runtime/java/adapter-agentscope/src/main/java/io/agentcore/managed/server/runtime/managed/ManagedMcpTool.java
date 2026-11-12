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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.agentscope.core.tool.mcp.McpMeta;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Model-facing MCP descriptor with the normal permission chain and a durable Tool Worker execution. */
public final class ManagedMcpTool extends ToolBase {
    private final ManagedMcpCatalog.Tool tool;
    private final ManagedToolRequests requests;
    private final ObjectMapper mapper;

    public ManagedMcpTool(
            ManagedMcpCatalog.Tool tool, ManagedToolRequests requests, ObjectMapper mapper) {
        super(
                ToolBase.builder()
                        .name(tool.name())
                        .description(tool.description() == null ? "" : tool.description())
                        .inputSchema(tool.inputSchema())
                        .readOnly(tool.readOnly())
                        .concurrencySafe(false)
                        .mcp(tool.server()));
        this.tool = tool;
        this.requests = requests;
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return tool.outputSchema();
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input, PermissionContextState context) {
        return Mono.just(
                isReadOnly()
                        ? PermissionDecision.allow("Read-only MCP tool allowed")
                        : PermissionDecision.ask("MCP tool requires authorization"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(
                        () -> {
                            var batch =
                                    param.getRuntimeContext() == null
                                            ? null
                                            : param.getRuntimeContext()
                                                    .get(AgentScopeMcpBatch.class);
                            if (batch != null) return decode(batch.request(param, requests));
                            var arguments =
                                    arguments(
                                            param.getInput(),
                                            param.getRuntimeContext(),
                                            param.getToolUseBlock() == null
                                                    ? null
                                                    : param.getToolUseBlock().getId());
                            return decode(
                                    mapper.writeValueAsString(
                                            requests.request(
                                                    param.getRuntimeContext(),
                                                    "mcp.call",
                                                    arguments,
                                                    Map.class)));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    Map<String, Object> arguments(
            Map<String, Object> input, RuntimeContext context, String callId) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("descriptor_hash", ManagedMcpCatalog.descriptorHash(tool, mapper));
        arguments.put("server", tool.server());
        arguments.put("tool", tool.remoteName());
        arguments.put("arguments", input);
        if (tool.recovery() != null) arguments.put("recovery", tool.recovery());
        var meta = new LinkedHashMap<String, Object>();
        if (context != null) {
            var configured = context.get(McpMeta.class);
            if (configured != null) meta.putAll(configured.entries());
        }
        if (callId != null) meta.put("io.agentscope/toolCallId", callId);
        arguments.put("meta", meta);
        return arguments;
    }

    public ToolResultBlock decode(String value) {
        try {
            if ("text-json-v1".equals(System.getenv("MANAGED_AGENTS_EVALUATION_TOOL_ENVELOPE"))) {
                return evaluationResult(value);
            }
            return McpContentConverter.convertCallToolResult(
                    mapper.readValue(value, McpSchema.CallToolResult.class));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot decode original MCP result", invalid);
        }
    }

    ToolResultBlock evaluationResult(String value) throws Exception {
        var content = mapper.readTree(value);
        boolean error = content.path("isError").asBoolean(false);
        // Keep the full MCP envelope, including structuredContent and empty results.
        // This explicit evaluation mode is for text benchmarks; native multimodal
        // conversion remains the default outside the evaluation deployments.
        return ToolResultBlock.of(
                        TextBlock.builder().text(content.toString()).build(),
                        Map.of("error", error))
                .withState(error ? ToolResultState.ERROR : ToolResultState.SUCCESS);
    }
}
