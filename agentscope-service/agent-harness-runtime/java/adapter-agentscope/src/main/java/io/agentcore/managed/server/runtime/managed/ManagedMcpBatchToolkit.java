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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.ToolGroupScope;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/** Adds durable MCP waves at the post-permission Toolkit boundary; the SDK owns tool logging and execution. */
public final class ManagedMcpBatchToolkit extends Toolkit {
    private final Toolkit delegate;
    private boolean batchEligible = true;
    private boolean opaqueRegistrations;

    public ManagedMcpBatchToolkit() {
        this(new Toolkit());
    }

    ManagedMcpBatchToolkit(Toolkit delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolRegistration registration() {
        // The SDK also uses this builder for skill tools. Check metadata per MCP tool.
        opaqueRegistrations = true;
        return delegate.registration();
    }

    @Override
    public void registerTool(Object toolObject) {
        delegate.registerTool(toolObject);
    }

    @Override
    public void registerAgentTool(AgentTool tool) {
        delegate.registerAgentTool(tool);
    }

    @Override
    public AgentTool getTool(String name) {
        return delegate.getTool(name);
    }

    @Override
    public AgentTool getTool(String name, ToolRequestConfig requestConfig) {
        return delegate.getTool(name, requestConfig);
    }

    @Override
    public Set<String> getToolNames() {
        return delegate.getToolNames();
    }

    @Override
    public void registerSchema(ToolSchema schema) {
        delegate.registerSchema(schema);
    }

    @Override
    public void registerSchemas(List<ToolSchema> schemas) {
        delegate.registerSchemas(schemas);
    }

    @Override
    public boolean isExternalTool(String toolName) {
        return delegate.isExternalTool(toolName);
    }

    @Override
    public boolean isExternalTool(String toolName, ToolRequestConfig requestConfig) {
        return delegate.isExternalTool(toolName, requestConfig);
    }

    @Override
    public List<ToolSchema> getToolSchemas() {
        return delegate.getToolSchemas();
    }

    @Override
    public List<ToolSchema> getToolSchemas(Collection<String> activeGroups) {
        return delegate.getToolSchemas(activeGroups);
    }

    @Override
    public List<ToolSchema> getToolSchemas(
            Collection<String> activeGroups, ToolRequestConfig requestConfig) {
        return delegate.getToolSchemas(activeGroups, requestConfig);
    }

    @Override
    public void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        delegate.setChunkCallback(callback);
    }

    @Override
    public Mono<ToolResultBlock> callTool(ToolCallParam param) {
        return delegate.callTool(param);
    }

    @Override
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext) {
        return callTools(
                toolCalls,
                agentExecutionConfig,
                agent,
                agentRuntimeContext,
                ToolRequestConfig.NONE,
                null);
    }

    @Override
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        return callTools(
                toolCalls,
                agentExecutionConfig,
                agent,
                agentRuntimeContext,
                ToolRequestConfig.NONE,
                internalChunkCallback);
    }

    @Override
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            ToolRequestConfig requestConfig,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        return Mono.defer(
                () -> {
                    RuntimeContext context = agentRuntimeContext;
                    if (context != null
                            && context.get(ManagedTurnExecution.class) != null
                            && batchEligible
                            && !toolCalls.isEmpty()
                            && toolCalls.size() <= 128) {
                        Collection<String> active =
                                context.getAgentState() == null
                                        ? null
                                        : context.getAgentState()
                                                .getToolContext()
                                                .getActivatedGroups();
                        var available =
                                delegate.getToolSchemas(active, requestConfig).stream()
                                        .map(ToolSchema::getName)
                                        .collect(Collectors.toSet());
                        var calls = new ArrayList<ManagedMcpBatch.Call>();
                        for (var call : toolCalls) {
                            if (!available.contains(call.getName())
                                    || !(delegate.getTool(call.getName(), requestConfig)
                                            instanceof ManagedMcpTool tool)
                                    || opaqueRegistrations
                                            && !ManagedToolkitMetadata.hasNoCustomArguments(
                                                    delegate, call.getName())
                                    || ToolValidator.validateInput(
                                                    call.getContent(), tool.getParameters())
                                            != null) {
                                calls.clear();
                                break;
                            }
                            calls.add(
                                    new ManagedMcpBatch.Call(
                                            call.getId(),
                                            call.getName(),
                                            tool.arguments(
                                                    call.getInput(), context, call.getId())));
                        }
                        if (!calls.isEmpty())
                            context =
                                    RuntimeContext.builder(context)
                                            .put(
                                                    AgentScopeMcpBatch.class,
                                                    new AgentScopeMcpBatch(
                                                            new ManagedMcpBatch.Manifest(calls)))
                                            .build();
                    }
                    return delegate.callTools(
                            toolCalls,
                            agentExecutionConfig,
                            agent,
                            context,
                            requestConfig,
                            internalChunkCallback);
                });
    }

    @Override
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return delegate.registerMcpClient(mcpClientWrapper);
    }

    @Override
    public Mono<Void> removeMcpClient(String mcpClientName) {
        return delegate.removeMcpClient(mcpClientName);
    }

    @Override
    public void closeMcpClients() {
        delegate.closeMcpClients();
    }

    @Override
    public void createToolGroup(String groupName, String description, boolean active) {
        delegate.createToolGroup(groupName, description, active);
    }

    @Override
    public void createToolGroup(
            String groupName, String description, boolean active, ToolGroupScope scope) {
        delegate.createToolGroup(groupName, description, active, scope);
    }

    @Override
    public void createToolGroup(String groupName, String description) {
        delegate.createToolGroup(groupName, description);
    }

    @Override
    public void createSkillToolGroup(
            String groupName, String description, boolean active, String activateOnSkill) {
        delegate.createSkillToolGroup(groupName, description, active, activateOnSkill);
    }

    @Override
    public List<String> findSkillToolGroupsByActivateOnSkill(String skillName) {
        return delegate.findSkillToolGroupsByActivateOnSkill(skillName);
    }

    @Override
    public void registerToolGroup(ToolGroup group) {
        delegate.registerToolGroup(group);
    }

    @Override
    public void addToolToGroup(String groupName, String toolName) {
        delegate.addToolToGroup(groupName, toolName);
    }

    @Override
    public void updateToolGroups(List<String> groupNames, boolean active) {
        delegate.updateToolGroups(groupNames, active);
    }

    @Override
    public void removeTool(String toolName) {
        delegate.removeTool(toolName);
    }

    @Override
    public boolean removeToolIfSame(String toolName, AgentTool expected) {
        return delegate.removeToolIfSame(toolName, expected);
    }

    @Override
    public void removeToolGroups(List<String> groupNames) {
        delegate.removeToolGroups(groupNames);
    }

    @Override
    public List<String> getActiveGroups() {
        return delegate.getActiveGroups();
    }

    @Override
    public void setActiveGroups(List<String> groups) {
        delegate.setActiveGroups(groups);
    }

    @Override
    public ToolGroup getToolGroup(String groupName) {
        return delegate.getToolGroup(groupName);
    }

    @Override
    public void registerMetaTool() {
        delegate.registerMetaTool();
    }

    @Override
    public void updateToolPresetParameters(
            String toolName, Map<String, Object> newPresetParameters) {
        if (delegate.getTool(toolName) instanceof ManagedMcpTool) batchEligible = false;
        delegate.updateToolPresetParameters(toolName, newPresetParameters);
    }

    @Override
    public Toolkit copy() {
        var copy = new ManagedMcpBatchToolkit(delegate.copy());
        copy.batchEligible = batchEligible;
        copy.opaqueRegistrations = opaqueRegistrations;
        return copy;
    }
}
