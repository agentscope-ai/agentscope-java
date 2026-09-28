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

/**
 * Internal registry for managing tool registration and lookup.
 *
 * <p>This class maintains mappings between tool names and their implementations, along with
 * metadata about registered tool functions. It is used internally by {@link Toolkit} to organize
 * and retrieve tools.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe, using {@link ConcurrentHashMap} for internal
 * storage to support concurrent tool registration and lookup operations.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve {@link AgentTool} implementations by name</li>
 *   <li>Maintain {@link RegisteredToolFunction} metadata for schema generation</li>
 *   <li>Support dynamic tool removal for group-based activation</li>
 * </ul>
 */
class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();

    /** Maximum characters of a tool description quoted in a rejected-registration message. */
    private static final int MAX_DESCRIPTION_IN_MESSAGE = 120;

    /**
     * Register a tool with its metadata.
     *
     * <p>Registering a name that is already bound to a <em>different</em> tool fails fast with an
     * {@link IllegalStateException} unless {@code allowReplaceExisting} is explicitly set, or the
     * registration is an idempotent refresh (see {@link #isIdempotentRefresh}). This prevents
     * silent shadowing (e.g. an MCP tool quietly replacing a local tool of the same name).
     *
     * <p>The check-then-bind sequence runs atomically per name via {@link
     * ConcurrentHashMap#compute}, so concurrent registrations of different tools under the same
     * name always have exactly one winner; every loser observes the winner's binding and fails
     * fast instead of silently shadowing it.
     *
     * @param toolName Tool name
     * @param tool AgentTool implementation
     * @param registered RegisteredToolFunction wrapper with metadata
     * @param allowReplaceExisting when true, an existing different tool under the same name is replaced.
     *     Only pass true for deliberate replacements (e.g. {@code Toolkit.replaceAgentTool} or the
     *     framework meta-tool rebind in {@code Toolkit.copy()}); passing true elsewhere would
     *     reintroduce the silent-shadowing behavior this check exists to prevent.
     * @throws IllegalStateException if the name is taken by a different tool, the registration is
     *     not an idempotent refresh, and {@code allowReplaceExisting} is false
     */
    void registerTool(
            String toolName,
            AgentTool tool,
            RegisteredToolFunction registered,
            boolean allowReplaceExisting) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        tools.compute(
                toolName,
                (name, existing) -> {
                    if (existing != null
                            && existing != tool
                            && !allowReplaceExisting
                            && !isIdempotentRefresh(existing, tool)) {
                        // Atomic fail-fast: compute completes without an update, so the map is
                        // left untouched and the existing binding survives the rejection.
                        throw new IllegalStateException(
                                duplicateRegistrationMessage(toolName, existing, tool, registered));
                    }
                    return tool;
                });
        registeredTools.put(toolName, registered);
    }

    /**
     * Register a tool with its metadata, failing fast on a name already bound to a different tool.
     *
     * @see #registerTool(String, AgentTool, RegisteredToolFunction, boolean)
     */
    void registerTool(String toolName, AgentTool tool, RegisteredToolFunction registered) {
        registerTool(toolName, tool, registered, false);
    }

    /**
     * Builds the user-facing message for a rejected duplicate registration. Includes the
     * descriptions of both tools (and the incoming MCP client name when applicable) so the
     * conflict can be diagnosed without guessing, plus the escape hatches.
     */
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
                + " Note: MCP tools are registered after local tools, so with the prefix disabled"
                + " an MCP tool can otherwise shadow a same-named local tool.";
    }

    /**
     * Whether re-registering {@code incoming} under a name already bound to {@code existing} is an
     * idempotent refresh (a legitimate re-registration of the same logical tool) rather than a
     * shadowing attempt.
     *
     * <p>Two patterns qualify:
     *
     * <ul>
     *   <li>Both are {@link McpTool}s served by the same MCP client: an MCP client re-registering
     *       its own tools is what a reconnect or tool-list refresh looks like.</li>
     *   <li>Both are {@link SchemaOnlyTool}s declaring an identical schema (same description,
     *       parameters and strict mode): an unchanged external tool re-declaration.</li>
     * </ul>
     *
     * <p>Everything else stays a fail-fast conflict — including a local tool over an MCP tool, an
     * MCP tool over a local tool, two different MCP clients claiming the same tool name, and a
     * schema that changed under the same name.
     */
    private static boolean isIdempotentRefresh(AgentTool existing, AgentTool incoming) {
        if (existing instanceof McpTool existingMcp && incoming instanceof McpTool incomingMcp) {
            return existingMcp.getClientName().equals(incomingMcp.getClientName());
        }
        if (existing instanceof SchemaOnlyTool existingSchema
                && incoming instanceof SchemaOnlyTool incomingSchema) {
            return Objects.equals(existingSchema.getDescription(), incomingSchema.getDescription())
                    && Objects.equals(
                            existingSchema.getParameters(), incomingSchema.getParameters())
                    && Objects.equals(existingSchema.getStrict(), incomingSchema.getStrict());
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
        return tools.get(name);
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
        return new HashSet<>(tools.keySet());
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
     * Remove a tool by name.
     *
     * @param toolName Tool name to remove
     */
    void removeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        tools.remove(toolName);
        registeredTools.remove(toolName);
    }

    /**
     * Atomically remove a tool only if the current instance matches the expected one.
     * Uses {@link ConcurrentHashMap#remove(Object, Object)} to avoid TOCTOU races.
     *
     * @param toolName Tool name to remove
     * @param expected The expected AgentTool instance (identity comparison)
     * @return true if the tool was removed, false if it was already replaced or absent
     */
    boolean removeToolIfSame(String toolName, AgentTool expected) {
        boolean removed = tools.remove(toolName, expected);
        if (removed) {
            registeredTools.remove(toolName);
        }
        return removed;
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
        for (Map.Entry<String, AgentTool> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            AgentTool tool = entry.getValue();
            RegisteredToolFunction registered = registeredTools.get(toolName);
            target.registerTool(
                    toolName,
                    tool,
                    registered == null
                            ? null
                            : new RegisteredToolFunction(
                                    tool,
                                    registered.getExtendedModel(),
                                    registered.getMcpClientName(),
                                    registered.getPresetParameters()));
        }
    }
}
