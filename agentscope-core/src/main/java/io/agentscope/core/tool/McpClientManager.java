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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Tracks attempts and rollback bindings for one wrapper. */
    private static final class ClientClaim {
        private final McpClientWrapper wrapper;
        private final List<AgentTool> registeredTools = new ArrayList<>();
        private boolean completed;
        private int inFlight;

        private ClientClaim(McpClientWrapper wrapper) {
            this.wrapper = wrapper;
        }

        private synchronized void enter() {
            inFlight++;
        }

        private synchronized void complete() {
            completed = true;
        }

        private synchronized boolean leave(ToolRegistry registry) {
            inFlight--;
            discardSupersededTools(registry);
            return !completed && inFlight == 0;
        }

        private synchronized void record(AgentTool tool, ToolRegistry registry) {
            registeredTools.add(tool);
            discardSupersededTools(registry);
        }

        private synchronized List<AgentTool> drainTools() {
            List<AgentTool> tools = new ArrayList<>(registeredTools);
            registeredTools.clear();
            return tools;
        }

        private void discardSupersededTools(ToolRegistry registry) {
            // Active attempts may still need older bindings for rollback.
            if (inFlight == 0) {
                registeredTools.removeIf(tool -> registry.getTool(tool.getName()) != tool);
            }
        }
    }

    private record RegistrationOptions(
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix,
            Boolean propagateMetaOverride,
            Map<String, Boolean> toolPropagateMetaOverrides) {

        private Map<String, Object> presetParameters(String toolName) {
            return presetParametersMapping == null ? null : presetParametersMapping.get(toolName);
        }

        private boolean propagateMeta(String toolName) {
            Boolean perTool =
                    toolPropagateMetaOverrides == null
                            ? null
                            : toolPropagateMetaOverrides.get(toolName);
            return perTool != null
                    ? perTool
                    : propagateMetaOverride == null || propagateMetaOverride;
        }
    }

    private final Map<String, ClientClaim> mcpClients = new ConcurrentHashMap<>();
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
     * <p>Client names are reserved on subscription. A distinct wrapper cannot reuse a tracked
     * name; the same wrapper may refresh its tools. The last unsuccessful attempt releases a
     * claim only if no attempt has succeeded. Released or removed claims roll back their own
     * bindings by identity, including writes that finish after cancellation.
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

        RegistrationOptions options =
                new RegistrationOptions(
                        enableTools,
                        disableTools,
                        groupName,
                        presetParametersMapping,
                        toolNamePrefix,
                        propagateMetaOverride,
                        toolPropagateMetaOverrides);
        // Release failed claims before downstream recovery reuses the name.
        return Mono.using(
                () -> admitClientName(mcpClientWrapper),
                claim -> registerClient(claim, options),
                this::finishAttempt,
                true);
    }

    private Mono<Void> registerClient(ClientClaim claim, RegistrationOptions options) {
        McpClientWrapper wrapper = claim.wrapper;
        logger.info("Registering MCP client: {}", wrapper.getName());
        return Mono.defer(wrapper::initialize)
                .then(Mono.defer(wrapper::listTools))
                .flatMap(
                        tools -> {
                            synchronized (toolRegistry) {
                                validateMetadataOverrides(tools, options, wrapper.getName());
                                List<McpSchema.Tool> selectedTools =
                                        tools.stream()
                                                .filter(
                                                        tool ->
                                                                shouldRegisterTool(
                                                                        tool.name(),
                                                                        options.enableTools(),
                                                                        options.disableTools()))
                                                .toList();
                                Map<String, McpSchema.Tool> modelTools =
                                        prepareModelTools(selectedTools, options, wrapper);
                                for (McpSchema.Tool tool : modelTools.values()) {
                                    registerTool(claim, tool, options);
                                }
                            }
                            return Mono.<Void>empty();
                        })
                .doOnSuccess(unused -> completeRegistration(claim))
                .doOnError(
                        error ->
                                logger.error(
                                        "Failed to register MCP client: {}",
                                        wrapper.getName(),
                                        error));
    }

    private void validateMetadataOverrides(
            List<McpSchema.Tool> tools, RegistrationOptions options, String clientName) {
        if (options.toolPropagateMetaOverrides() == null
                || options.toolPropagateMetaOverrides().isEmpty()) {
            return;
        }
        Set<String> selectedNames =
                tools.stream()
                        .map(McpSchema.Tool::name)
                        .filter(
                                name ->
                                        shouldRegisterTool(
                                                name,
                                                options.enableTools(),
                                                options.disableTools()))
                        .collect(Collectors.toSet());
        Set<String> unknown = new TreeSet<>(options.toolPropagateMetaOverrides().keySet());
        unknown.removeAll(selectedNames);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown MCP tool(s) in propagateMeta override for client '"
                            + clientName
                            + "': "
                            + unknown);
        }
    }

    private void registerTool(
            ClientClaim claim, McpSchema.Tool remoteTool, RegistrationOptions options) {
        String clientName = claim.wrapper.getName();
        if (mcpClients.get(clientName) != claim) {
            throw new IllegalStateException(
                    "MCP client '"
                            + clientName
                            + "' was removed or replaced while its registration was in flight");
        }
        Map<String, Object> presetParameters = options.presetParameters(remoteTool.name());
        McpTool tool = createTool(claim.wrapper, remoteTool, options, presetParameters);
        logger.debug(
                "Registering MCP tool: {} from client {} into group {}",
                remoteTool.name(),
                clientName,
                options.groupName());
        try {
            registrationCallback.registerAgentToolWithMcpClient(
                    tool, options.groupName(), clientName, presetParameters);
        } finally {
            // A callback may publish before throwing or finish after cancellation cleanup.
            claim.record(tool, toolRegistry);
            if (mcpClients.get(clientName) != claim) {
                removeToolBinding(tool);
            }
        }
    }

    private McpTool createTool(
            McpClientWrapper wrapper,
            McpSchema.Tool remoteTool,
            RegistrationOptions options,
            Map<String, Object> presetParameters) {
        boolean readOnly =
                remoteTool.annotations() != null
                        && Boolean.TRUE.equals(remoteTool.annotations().readOnlyHint());
        McpTool tool =
                new McpTool(
                        modelToolName(
                                options.toolNamePrefix(), wrapper.getName(), remoteTool.name()),
                        remoteTool.name(),
                        remoteTool.description() == null ? "" : remoteTool.description(),
                        McpTool.convertMcpSchemaToParameters(
                                remoteTool.inputSchema(),
                                presetParameters == null
                                        ? Collections.emptySet()
                                        : presetParameters.keySet()),
                        remoteTool.outputSchema() == null
                                ? null
                                : new ConcurrentHashMap<>(remoteTool.outputSchema()),
                        wrapper,
                        null, // Preset arguments are applied by RegisteredToolFunction.
                        wrapper.getName(),
                        readOnly,
                        McpTool.resolveDisplayTitle(remoteTool));
        // The wrapper's connection-wide switch remains effective on every invocation.
        tool.setPropagateMeta(options.propagateMeta(remoteTool.name()));
        return tool;
    }

    private ClientClaim admitClientName(McpClientWrapper wrapper) {
        return mcpClients.compute(
                wrapper.getName(),
                (name, existing) -> {
                    if (existing != null && existing.wrapper != wrapper) {
                        throw new IllegalStateException(
                                "MCP client name '"
                                        + name
                                        + "' is already registered by a different MCP client"
                                        + " wrapper. Call removeMcpClient(\""
                                        + name
                                        + "\") first, or register the new client under a distinct"
                                        + " name.");
                    }
                    ClientClaim claim = existing == null ? new ClientClaim(wrapper) : existing;
                    claim.enter();
                    return claim;
                });
    }

    private void completeRegistration(ClientClaim claim) {
        ClientClaim tracked =
                mcpClients.computeIfPresent(
                        claim.wrapper.getName(),
                        (name, current) -> {
                            if (current == claim) {
                                claim.complete();
                            }
                            return current;
                        });
        if (tracked == claim) {
            logger.info("MCP client '{}' registered successfully", claim.wrapper.getName());
        }
    }

    private void finishAttempt(ClientClaim claim) {
        AtomicBoolean released = new AtomicBoolean();
        ClientClaim tracked =
                mcpClients.compute(
                        claim.wrapper.getName(),
                        (name, current) -> {
                            boolean shouldRelease = claim.leave(toolRegistry);
                            if (current == claim && shouldRelease) {
                                released.set(true);
                                return null;
                            }
                            return current;
                        });
        if (tracked != claim) {
            claim.drainTools().forEach(this::removeToolBinding);
        }
        if (released.get()) {
            try {
                claim.wrapper.close();
            } catch (RuntimeException cleanupError) {
                logger.debug(
                        "Failed to close MCP client '{}' after a failed registration",
                        claim.wrapper.getName(),
                        cleanupError);
            }
        }
    }

    private void removeToolBinding(AgentTool tool) {
        if (toolRegistry.removeToolIfSame(
                tool.getName(), tool, () -> groupManager.removeToolFromAllGroups(tool.getName()))) {
            logger.debug("Removed MCP tool: {}", tool.getName());
        }
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
            List<McpSchema.Tool> tools, RegistrationOptions options, McpClientWrapper wrapper) {
        Map<String, McpSchema.Tool> modelTools = new LinkedHashMap<>();
        for (McpSchema.Tool tool : tools) {
            String serverName = wrapper.getName();
            String modelName = modelToolName(options.toolNamePrefix(), serverName, tool.name());
            if (modelTools.putIfAbsent(modelName, tool) != null) {
                throw new IllegalArgumentException(
                        "MCP tool name conflict for client '" + serverName + "': " + modelName);
            }
            McpTool incoming =
                    createTool(wrapper, tool, options, options.presetParameters(tool.name()));
            toolRegistry.validateRegistration(
                    modelName, incoming, new RegisteredToolFunction(incoming, null, serverName));
        }
        return modelTools;
    }

    /**
     * Removes an MCP client and all its tools.
     *
     * <p>Remove and re-register operations for one name must be sequenced by the caller.
     * Removing an in-flight registration is supported; any late writes are rolled back.
     *
     * @param mcpClientName the name of the MCP client to remove
     * @return Mono that completes when removal is finished
     */
    Mono<Void> removeMcpClient(String mcpClientName) {
        ClientClaim claim = mcpClients.remove(mcpClientName);
        McpClientWrapper wrapper = claim == null ? null : claim.wrapper;
        if (wrapper == null) {
            logger.warn("MCP client not found: {}", mcpClientName);
            return Mono.empty();
        }

        logger.info("Removing MCP client: {}", mcpClientName);

        removeToolsOfClient(mcpClientName);

        return Mono.fromRunnable(wrapper::close)
                .then()
                .doOnSuccess(
                        v -> logger.info("MCP client '{}' removed successfully", mcpClientName));
    }

    /** Removes current client bindings and their group memberships. */
    private void removeToolsOfClient(String mcpClientName) {
        List<AgentTool> toolsToRemove =
                toolRegistry.getAllRegisteredTools().values().stream()
                        .filter(reg -> mcpClientName.equals(reg.getMcpClientName()))
                        .map(RegisteredToolFunction::getTool)
                        .collect(Collectors.toList());
        toolsToRemove.forEach(this::removeToolBinding);
    }

    /**
     * Gets all registered MCP client names.
     *
     * <p>Includes registrations in flight. Await registration completion before using the tools.
     *
     * @return set of MCP client names, never null but may be empty
     */
    Set<String> getMcpClientNames() {
        return new HashSet<>(mcpClients.keySet());
    }

    /**
     * Gets an MCP client wrapper by name.
     *
     * <p>The returned wrapper may belong to an in-flight registration (see
     * {@link #getMcpClientNames()}) — it is not guaranteed initialized or fully registered.
     *
     * @param name the MCP client name
     * @return the MCP client wrapper, or null if not found
     */
    McpClientWrapper getMcpClient(String name) {
        ClientClaim claim = mcpClients.get(name);
        return claim == null ? null : claim.wrapper;
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
