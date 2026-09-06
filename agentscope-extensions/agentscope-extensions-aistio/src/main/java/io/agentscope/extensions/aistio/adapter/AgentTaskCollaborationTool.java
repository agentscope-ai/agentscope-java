/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Runtime-context-bound proxy for one canonical control-plane collaboration MCP tool. */
final class AgentTaskCollaborationTool implements AgentTool {

    private static final Set<String> READ_ONLY =
            Set.of(
                    "issue.get",
                    "issue.comment.list",
                    "artifact.download",
                    "task.get",
                    "team.get",
                    "run.get",
                    "run.graph",
                    "run.artifacts");

    private final CollaborationClient collaboration;
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;

    AgentTaskCollaborationTool(CollaborationClient collaboration, JsonNode definition) {
        this.collaboration = collaboration;
        this.name = definition.path("name").asText();
        this.description = definition.path("description").asText();
        this.parameters =
                ControlPlaneHttpClient.mapper()
                        .convertValue(
                                definition.path("inputSchema"),
                                new TypeReference<Map<String, Object>>() {});
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description + " This tool is available only while processing an aistio AgentTask.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public boolean isReadOnly() {
        return READ_ONLY.contains(name);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext runtimeContext = param.getRuntimeContext();
                            AgentTaskToolContext taskContext =
                                    runtimeContext == null
                                            ? null
                                            : runtimeContext.get(AgentTaskToolContext.class);
                            if (taskContext == null) {
                                return ToolResultBlock.text(
                                        "Error: "
                                                + name
                                                + " requires an active aistio AgentTask context.");
                            }
                            JsonNode result =
                                    collaboration.callTool(
                                            taskContext.taskId(),
                                            taskContext.taskToken(),
                                            name,
                                            param.getInput(),
                                            param.getToolUseBlock() == null
                                                    ? null
                                                    : param.getToolUseBlock().getId());
                            return ToolResultBlock.text(result.toString());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
