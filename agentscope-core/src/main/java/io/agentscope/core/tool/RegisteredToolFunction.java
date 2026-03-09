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
package io.agentscope.core.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper for AgentTool with metadata for schema extension and execution.
 *
 * <p>This class wraps an AgentTool and adds additional metadata such as extended schema
 * information, MCP client association, and preset parameters.
 */
class RegisteredToolFunction {

    private final AgentTool tool;
    private final ExtendedModel extendedModel; // null if no extensions
    private final String mcpClientName; // null for non-MCP tools
    private volatile Map<String, Object>
            presetParameters; // preset parameters for context injection

    /**
     * Normalizes preset parameters map to ensure non-null return.
     *
     * @param params the preset parameters (may be null)
     * @return a new HashMap copy of params, or empty map if null
     */
    private static Map<String, Object> normalizePresetParameters(Map<String, Object> params) {
        return params != null ? new HashMap<>(params) : Collections.emptyMap();
    }

    /**
     * Creates a new registered tool function with metadata.
     *
     * @param tool The underlying agent tool
     * @param extendedModel Extended model for schema extension (null if no extensions)
     * @param mcpClientName MCP client name for MCP tools (null for non-MCP tools)
     */
    public RegisteredToolFunction(
            AgentTool tool, ExtendedModel extendedModel, String mcpClientName) {
        this(tool, extendedModel, mcpClientName, null);
    }

    /**
     * Creates a new registered tool function with metadata and preset parameters.
     *
     * @param tool The underlying agent tool
     * @param extendedModel Extended model for schema extension (null if no extensions)
     * @param mcpClientName MCP client name for MCP tools (null for non-MCP tools)
     * @param presetParameters Preset parameters that will be automatically injected during tool
     *     execution (null for no preset parameters)
     */
    public RegisteredToolFunction(
            AgentTool tool,
            ExtendedModel extendedModel,
            String mcpClientName,
            Map<String, Object> presetParameters) {
        this.tool = tool;
        this.extendedModel = extendedModel;
        this.mcpClientName = mcpClientName;
        this.presetParameters = normalizePresetParameters(presetParameters);
    }

    /**
     * Gets the underlying agent tool.
     *
     * @return The agent tool instance
     */
    public AgentTool getTool() {
        return tool;
    }

    /**
     * Gets the extended model for schema extension.
     *
     * @return The extended model, or null if no extensions
     */
    public ExtendedModel getExtendedModel() {
        return extendedModel;
    }

    /**
     * Gets the MCP client name for MCP tools.
     *
     * @return The MCP client name, or null for non-MCP tools
     */
    public String getMcpClientName() {
        return mcpClientName;
    }

    /**
     * Gets the preset parameters for this tool.
     *
     * <p>Preset parameters are automatically injected during tool execution and are not exposed in
     * the JSON schema. This is useful for passing context like API keys, user IDs, or session
     * information.
     *
     * @return A copy of the preset parameters map (never null, may be empty)
     */
    public Map<String, Object> getPresetParameters() {
        return new HashMap<>(presetParameters);
    }

    /**
     * Updates the preset parameters for this tool at runtime.
     *
     * <p>This method allows dynamic modification of preset parameters, useful for updating
     * session-specific context or credentials without re-registering the tool.
     *
     * @param newPresetParameters The new preset parameters (null will be treated as empty map)
     */
    public void updatePresetParameters(Map<String, Object> newPresetParameters) {
        this.presetParameters = normalizePresetParameters(newPresetParameters);
    }

    /**
     * Get the extended JSON schema by merging base parameters with extended model.
     *
     * @return Merged parameter schema
     * @throws IllegalStateException if there are conflicting properties
     */
    public Map<String, Object> getExtendedParameters() {
        if (extendedModel == null) {
            return tool.getParameters();
        }
        return extendedModel.mergeWithBaseSchema(tool.getParameters());
    }
}
