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

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Manages MCP (Model Context Protocol) client registration and lifecycle.
 * Handles MCP client initialization, tool registration, and cleanup.
 */
class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);
    private static final int MAX_MODEL_TOOL_NAME_LENGTH = 64;
    private static final int TOOL_NAME_HASH_LENGTH = 12;

    private final Map<String, McpClientWrapper> mcpClients = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolRegistrationCallback registrationCallback;

    /**
     * Callback interface for tool registration.
     */
    @FunctionalInterface
    interface ToolRegistrationCallback {
        void registerAgentToolWithMcpClient(
                AgentTool tool,
                String groupName,
                String mcpClientName,
                Map<String, Object> presetParameters);
    }

    McpClientManager(
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolRegistrationCallback registrationCallback) {
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.registrationCallback = registrationCallback;
    }

    /**
     * Registers an MCP client and all its tools.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return registerMcpClient(mcpClientWrapper, null, null, null);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper, List<String> enableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, null, null);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, null);
    }

    /**
     * Registers an MCP client with tool filtering and group assignment.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName) {
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, groupName, null);
    }

    /**
     * Registers an MCP client with tool filtering, group assignment, and preset parameters.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @param presetParametersMapping map from tool name to preset parameters for that tool
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                "");
    }

    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                toolNamePrefix,
                null);
    }

    /**
     * Registers an MCP client with full control over tool filtering, grouping, preset parameters,
     * naming and metadata propagation.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @param presetParametersMapping map from tool name to preset parameters for that tool
     * @param toolNamePrefix optional namespace prefix for model-facing tool names
     * @param propagateMetaOverride registration-level default for metadata propagation on the
     *     registered tools; {@code null} means {@code true} (the connection-level switch on the
     *     wrapper is still applied live at call time)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix,
            Boolean propagateMetaOverride) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                toolNamePrefix,
                propagateMetaOverride,
                null);
    }

    /**
     * Registers an MCP client with full control over tool filtering, grouping, preset parameters,
     * naming and per-tool metadata propagation.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @param presetParametersMapping map from tool name to preset parameters for that tool
     * @param toolNamePrefix optional namespace prefix for model-facing tool names
     * @param propagateMetaOverride registration-level default for metadata propagation on the
     *     registered tools; {@code null} means {@code true}
     * @param toolPropagateMetaOverrides per-tool metadata propagation overrides keyed by the
     *     remote MCP tool name (before any {@code toolNamePrefix}); an entry wins over
     *     {@code propagateMetaOverride}. Keys that do not match a tool that is actually
     *     registered from this client (unknown name, remote rename, or filtered out by
     *     {@code enableTools}/{@code disableTools}) fail the registration with an
     *     {@link IllegalArgumentException} so a silencing override is never lost silently
     *
     * <p>On any registration failure (including the unknown-tool check above) the client is
     * closed best-effort: it was already initialized but is not tracked by this manager, so
     * nobody else could release the underlying connection (for stdio transports, a spawned
     * subprocess). The wrapper must not be reused after a failed registration.
     *
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix,
            Boolean propagateMetaOverride,
            Map<String, Boolean> toolPropagateMetaOverrides) {
        if (mcpClientWrapper == null) {
            return Mono.error(new IllegalArgumentException("MCP client wrapper cannot be null"));
        }

        // Validate group exists if specified
        if (groupName != null) {
            try {
                groupManager.validateGroupExists(groupName);
            } catch (IllegalArgumentException e) {
                return Mono.error(e);
            }
        }

        logger.info("Registering MCP client: {}", mcpClientWrapper.getName());

        return mcpClientWrapper
                .initialize()
                .then(Mono.defer(mcpClientWrapper::listTools))
                .flatMap(
                        tools -> {
                            // Keep validation and registration under one registry lock
                            // so concurrent MCP clients cannot both pass the name check.
                            synchronized (toolRegistry) {
                                List<McpSchema.Tool> selectedTools =
                                        tools.stream()
                                                .filter(
                                                        tool ->
                                                                shouldRegisterTool(
                                                                        tool.name(),
                                                                        enableTools,
                                                                        disableTools))
                                                .toList();
                                Map<String, McpSchema.Tool> modelTools =
                                        prepareModelTools(
                                                selectedTools,
                                                toolNamePrefix,
                                                mcpClientWrapper.getName());

                                // Reject an override that does not match a selected remote tool.
                                // Otherwise a metadata restriction could be silently ignored
                                // for an unknown, renamed, or filtered-out tool.
                                if (toolPropagateMetaOverrides != null
                                        && !toolPropagateMetaOverrides.isEmpty()) {
                                    Set<String> registeredNames =
                                            selectedTools.stream()
                                                    .map(tool -> tool.name())
                                                    .collect(Collectors.toSet());
                                    // TreeSet so the rejected names in the user-facing error
                                    // message are deterministically ordered and pasteable.
                                    Set<String> unknown =
                                            new TreeSet<>(toolPropagateMetaOverrides.keySet());
                                    unknown.removeAll(registeredNames);
                                    if (!unknown.isEmpty()) {
                                        return Mono.<Void>error(
                                                new IllegalArgumentException(
                                                        "Unknown MCP tool(s) in propagateMeta"
                                                                + " override for client '"
                                                                + mcpClientWrapper.getName()
                                                                + "': "
                                                                + unknown));
                                    }
                                }
                                for (var entry : modelTools.entrySet()) {
                                    McpSchema.Tool mcpTool = entry.getValue();
                                    logger.debug(
                                            "Registering MCP tool: {} from client {} into group {}",
                                            mcpTool.name(),
                                            mcpClientWrapper.getName(),
                                            groupName);

                                    // Get preset parameters for this specific tool
                                    Map<String, Object> toolPresetParams =
                                            presetParametersMapping != null
                                                    ? presetParametersMapping.get(mcpTool.name())
                                                    : null;

                                    boolean readOnly =
                                            mcpTool.annotations() != null
                                                    && Boolean.TRUE.equals(
                                                            mcpTool.annotations().readOnlyHint());

                                    // The callback wraps this tool in RegisteredToolFunction,
                                    // where preset arguments are applied.
                                    McpTool agentTool =
                                            new McpTool(
                                                    entry.getKey(),
                                                    mcpTool.name(),
                                                    mcpTool.description() != null
                                                            ? mcpTool.description()
                                                            : "",
                                                    McpTool.convertMcpSchemaToParameters(
                                                            mcpTool.inputSchema(),
                                                            toolPresetParams != null
                                                                    ? toolPresetParams.keySet()
                                                                    : Collections.emptySet()),
                                                    mcpTool.outputSchema() != null
                                                            ? new ConcurrentHashMap<>(
                                                                    mcpTool.outputSchema())
                                                            : null,
                                                    mcpClientWrapper,
                                                    null,
                                                    mcpClientWrapper.getName(),
                                                    readOnly,
                                                    McpTool.resolveDisplayTitle(mcpTool));

                                    // Resolve metadata propagation at registration time:
                                    // per-tool override > registration default > true.
                                    // McpTool still reads the wrapper's connection-level
                                    // switch on every call, so disabling the connection
                                    // later suppresses metadata.
                                    Boolean perToolOverride =
                                            toolPropagateMetaOverrides != null
                                                    ? toolPropagateMetaOverrides.get(mcpTool.name())
                                                    : null;
                                    boolean propagateMeta =
                                            perToolOverride != null
                                                    ? perToolOverride
                                                    : propagateMetaOverride != null
                                                            ? propagateMetaOverride
                                                            : true;
                                    agentTool.setPropagateMeta(propagateMeta);

                                    // Register the tool with its group, client association,
                                    // and preset parameters.
                                    registrationCallback.registerAgentToolWithMcpClient(
                                            agentTool,
                                            groupName,
                                            mcpClientWrapper.getName(),
                                            toolPresetParams);
                                }
                                mcpClients.put(mcpClientWrapper.getName(), mcpClientWrapper);
                            }
                            return Mono.<Void>empty();
                        })
                .doOnSuccess(
                        v -> {
                            logger.info(
                                    "MCP client '{}' registered successfully",
                                    mcpClientWrapper.getName());
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to register MCP client: {}",
                                        mcpClientWrapper.getName(),
                                        e))
                .doOnError(
                        e -> {
                            // The client was initialized above but is only added to mcpClients
                            // on success, so on any failure nobody else can reach or close it
                            // (for stdio transports it owns a spawned subprocess). Best-effort
                            // cleanup must not mask the original error.
                            try {
                                mcpClientWrapper.close();
                            } catch (RuntimeException cleanupEx) {
                                logger.debug(
                                        "Failed to close MCP client '{}' after a failed"
                                                + " registration",
                                        mcpClientWrapper.getName(),
                                        cleanupEx);
                            }
                        });
    }

    /**
     * Derives the model-facing name for a remote MCP tool.
     *
     * <p>Without a prefix the remote name is returned unchanged. With a prefix, characters
     * outside {@code [A-Za-z0-9_-]} are replaced and the name is capped at {@value
     * #MAX_MODEL_TOOL_NAME_LENGTH} characters; whenever either step changes the name, a stable
     * hash of {@code serverName + remoteToolName} is appended so the identity survives
     * re-registrations.
     */
    private static String modelToolName(
            String toolNamePrefix, String serverName, String remoteToolName) {
        String prefix = toolNamePrefix != null ? toolNamePrefix : "";
        String name = prefix + remoteToolName;
        if (prefix.isEmpty()) {
            return name;
        }

        String normalized = name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalized.equals(name) && normalized.length() <= MAX_MODEL_TOOL_NAME_LENGTH) {
            return normalized;
        }

        String identity = serverName + '\0' + remoteToolName;
        byte[] digest;
        try {
            digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        String hash = HexFormat.of().formatHex(digest, 0, TOOL_NAME_HASH_LENGTH / 2);
        int namePrefixLength = MAX_MODEL_TOOL_NAME_LENGTH - TOOL_NAME_HASH_LENGTH - 1;
        return normalized.substring(0, Math.min(normalized.length(), namePrefixLength))
                + "_"
                + hash;
    }

    /**
     * Maps every selected remote tool to its model-facing name and rejects the whole batch when
     * two tools collide or a name is already registered, so a failing client registers no tools.
     *
     * @param tools tools selected by the server-level enable/disable filters
     * @return model-facing name to remote tool, in server listing order
     */
    private Map<String, McpSchema.Tool> prepareModelTools(
            List<McpSchema.Tool> tools, String toolNamePrefix, String serverName) {
        Map<String, McpSchema.Tool> modelTools = new LinkedHashMap<>();
        for (McpSchema.Tool tool : tools) {
            String modelName = modelToolName(toolNamePrefix, serverName, tool.name());
            if (modelTools.putIfAbsent(modelName, tool) != null
                    || toolRegistry.getRegisteredTool(modelName) != null) {
                throw new IllegalArgumentException(
                        "MCP tool name conflict for client '" + serverName + "': " + modelName);
            }
        }
        return modelTools;
    }

    /**
     * Removes an MCP client and all its tools.
     *
     * @param mcpClientName the name of the MCP client to remove
     * @return Mono that completes when removal is finished
     */
    Mono<Void> removeMcpClient(String mcpClientName) {
        McpClientWrapper wrapper = mcpClients.remove(mcpClientName);
        if (wrapper == null) {
            logger.warn("MCP client not found: {}", mcpClientName);
            return Mono.empty();
        }

        logger.info("Removing MCP client: {}", mcpClientName);

        // Remove all tools from this MCP client
        List<String> toolsToRemove =
                toolRegistry.getAllRegisteredTools().values().stream()
                        .filter(reg -> mcpClientName.equals(reg.getMcpClientName()))
                        .map(reg -> reg.getTool().getName())
                        .collect(Collectors.toList());

        toolsToRemove.forEach(
                toolName -> {
                    toolRegistry.removeTool(toolName);
                    logger.debug("Removed MCP tool: {}", toolName);
                });

        return Mono.fromRunnable(wrapper::close)
                .then()
                .doOnSuccess(
                        v -> logger.info("MCP client '{}' removed successfully", mcpClientName));
    }

    /**
     * Gets all registered MCP client names.
     *
     * @return set of MCP client names, never null but may be empty
     */
    Set<String> getMcpClientNames() {
        return new HashSet<>(mcpClients.keySet());
    }

    /**
     * Gets an MCP client wrapper by name.
     *
     * @param name the MCP client name
     * @return the MCP client wrapper, or null if not found
     */
    McpClientWrapper getMcpClient(String name) {
        return mcpClients.get(name);
    }

    /**
     * Determines if a tool should be registered based on enable/disable lists.
     *
     * @param toolName the tool name
     * @param enableTools list of tools to enable (null means all), takes precedence over disableTools
     * @param disableTools list of tools to disable (null means none)
     * @return true if the tool should be registered
     */
    private boolean shouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) {
        // Default: register all tools
        boolean result = true;

        if (disableTools != null && !disableTools.isEmpty()) {
            result = !disableTools.contains(toolName);
        }

        if (enableTools != null && !enableTools.isEmpty()) {
            result = enableTools.contains(toolName);
        }

        return result;
    }
}
