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

import io.agentscope.core.tool.mcp.McpTool;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores each tool and its metadata in one registration. Per-name atomic updates prevent
 * conflicting registrations and keep binding metadata consistent during replacement and removal.
 */
class ToolRegistry {

    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();

    /** Maximum characters of a tool description quoted in a rejected-registration message. */
    private static final int MAX_DESCRIPTION_IN_MESSAGE = 120;

    /**
     * Atomically binds a tool and its metadata, rejecting conflicts unless replacement is explicit
     * or the registration refreshes the same source.
     *
     * @param toolName registration name
     * @param tool tool implementation
     * @param registered metadata wrapping the same tool instance
     * @param allowReplaceExisting whether an existing binding may be deliberately replaced
     * @throws IllegalStateException when the name belongs to a different tool
     * @throws IllegalArgumentException when the name, tool or metadata is invalid
     */
    void registerTool(
            String toolName,
            AgentTool tool,
            RegisteredToolFunction registered,
            boolean allowReplaceExisting,
            Runnable updateGroups) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        if (tool == null || registered == null) {
            throw new IllegalArgumentException("Tool and registration metadata cannot be null");
        }
        if (registered.getTool() != tool) {
            throw new IllegalArgumentException(
                    "Registration metadata must wrap the tool being registered");
        }
        registeredTools.compute(
                toolName,
                (name, existingRegistration) -> {
                    AgentTool existing =
                            existingRegistration == null ? null : existingRegistration.getTool();
                    if (existing != null
                            && existing != tool
                            && !allowReplaceExisting
                            && !isIdempotentRefresh(existing, tool)) {
                        // Throwing from compute preserves the existing binding and metadata.
                        throw new IllegalStateException(
                                duplicateRegistrationMessage(toolName, existing, tool, registered));
                    }
                    updateGroups.run();
                    return registered;
                });
    }

    /**
     * Register a tool with its metadata, failing fast on a name already bound to a different tool.
     *
     * @see #registerTool(String, AgentTool, RegisteredToolFunction, boolean, Runnable)
     */
    void registerTool(
            String toolName,
            AgentTool tool,
            RegisteredToolFunction registered,
            boolean allowReplaceExisting) {
        registerTool(toolName, tool, registered, allowReplaceExisting, () -> {});
    }

    /**
     * Register a tool with its metadata, failing fast on a name already bound to a different tool.
     *
     * @see #registerTool(String, AgentTool, RegisteredToolFunction, boolean, Runnable)
     */
    void registerTool(String toolName, AgentTool tool, RegisteredToolFunction registered) {
        registerTool(toolName, tool, registered, false, () -> {});
    }

    /** Identifies both tools and the supported conflict-resolution options. */
    private static String duplicateRegistrationMessage(
            String toolName,
            AgentTool existing,
            AgentTool incoming,
            RegisteredToolFunction registered) {
        String incomingSource =
                registered.getMcpClientName() == null
                        ? "local tool"
                        : "MCP tool from client '" + registered.getMcpClientName() + "'";
        return "Tool '"
                + toolName
                + "' is already registered and will not be silently replaced."
                + "  existing: ["
                + summarize(existing.getDescription())
                + "]  incoming: ["
                + incomingSource
                + ", description: "
                + summarize(incoming.getDescription())
                + "]. To resolve the name conflict: use Toolkit.replaceAgentTool(...) to replace"
                + " intentionally, remove the existing tool first, or register the incoming tool"
                + " (e.g. an MCP client) under a distinct name prefix such as toolNamePrefix."
                + " Note: MCP tools are registered after local tools, so a same-named local tool"
                + " is the most likely existing tool an MCP registration collides with.";
    }

    /** Accepts the same annotated source, MCP target, or unchanged external schema as a refresh. */
    private static boolean isIdempotentRefresh(AgentTool existing, AgentTool incoming) {
        if (existing instanceof ReflectiveFunctionTool existingReflective
                && incoming instanceof ReflectiveFunctionTool incomingReflective) {
            return existingReflective.getToolObject() == incomingReflective.getToolObject()
                    && existingReflective.getMethod().equals(incomingReflective.getMethod());
        }
        if (existing instanceof McpTool existingMcp && incoming instanceof McpTool incomingMcp) {
            return existingMcp.isFromSameSource(incomingMcp);
        }
        if (existing instanceof SchemaOnlyTool existingSchema
                && incoming instanceof SchemaOnlyTool incomingSchema) {
            return Objects.equals(existingSchema.getDescription(), incomingSchema.getDescription())
                    && Objects.equals(
                            existingSchema.getParameters(), incomingSchema.getParameters())
                    && Objects.equals(existingSchema.getStrict(), incomingSchema.getStrict())
                    && Objects.equals(
                            existingSchema.getDeferLoading(), incomingSchema.getDeferLoading());
        }
        return false;
    }

    /**
     * Renders a tool description for a diagnostic message: collapses whitespace (descriptions can
     * be multi-line) and truncates long values, so a rejected-registration message stays a single
     * readable log line.
     */
    private static String summarize(String description) {
        if (description == null) {
            return "null";
        }
        String collapsed = description.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= MAX_DESCRIPTION_IN_MESSAGE) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_DESCRIPTION_IN_MESSAGE - 3) + "...";
    }

    /**
     * Get tool by name.
     *
     * @param name Tool name
     * @return AgentTool or null if not found
     */
    AgentTool getTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        RegisteredToolFunction registration = registeredTools.get(name);
        return registration == null ? null : registration.getTool();
    }

    /**
     * Get registered tool function by name.
     *
     * @param name Tool name
     * @return RegisteredToolFunction or null if not found
     */
    RegisteredToolFunction getRegisteredTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return registeredTools.get(name);
    }

    /**
     * Get all tool names.
     *
     * @return Set of tool names
     */
    Set<String> getToolNames() {
        return new HashSet<>(registeredTools.keySet());
    }

    /**
     * Get all registered tool functions.
     *
     * @return Map of tool name to RegisteredToolFunction
     */
    Map<String, RegisteredToolFunction> getAllRegisteredTools() {
        return new ConcurrentHashMap<>(registeredTools);
    }

    /**
     * Remove a tool by name. Removes the tool binding and its registration metadata as one atomic
     * map operation.
     *
     * @param toolName Tool name to remove
     */
    void removeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        registeredTools.remove(toolName);
    }

    /**
     * Atomically remove a tool only if the current instance matches the expected one.
     *
     * <p>Uses a single {@link ConcurrentHashMap#computeIfPresent} pass over the unified map, so the
     * tool binding and its metadata are removed together and cannot be observed in a
     * half-removed state (tool gone but metadata still advertising it, or vice versa).
     *
     * @param toolName Tool name to remove
     * @param expected The expected AgentTool instance (identity comparison)
     * @return true if the tool was removed, false if it was already replaced or absent
     */
    boolean removeToolIfSame(String toolName, AgentTool expected) {
        return removeToolIfSame(toolName, expected, () -> {});
    }

    /** Runs binding cleanup before another registration can reuse the name. */
    boolean removeToolIfSame(String toolName, AgentTool expected, Runnable cleanup) {
        AtomicBoolean removed = new AtomicBoolean(false);
        registeredTools.computeIfPresent(
                toolName,
                (name, registration) -> {
                    if (registration.getTool() != expected) {
                        return registration;
                    }
                    cleanup.run();
                    removed.set(true);
                    return null;
                });
        return removed.get();
    }

    /**
     * Remove multiple tools by names.
     *
     * @param toolNames Set of tool names to remove
     */
    void removeTools(Set<String> toolNames) {
        toolNames.forEach(this::removeTool);
    }

    /**
     * Copy all tools (and their registration metadata) from this registry to another registry.
     *
     * <p>Tools are shared by reference (they are stateless and thread-safe); only the registry
     * entries (including {@link RegisteredToolFunction} metadata) are copied, so the target is an
     * isolated registry for build-time agent isolation.
     *
     * @param target The target registry to copy tools to
     */
    void copyTo(ToolRegistry target) {
        registeredTools.forEach(
                (toolName, registration) ->
                        target.registerTool(
                                toolName,
                                registration.getTool(),
                                new RegisteredToolFunction(
                                        registration.getTool(),
                                        registration.getExtendedModel(),
                                        registration.getMcpClientName(),
                                        registration.getPresetParameters())));
    }
}
