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
import java.util.function.Predicate;

/**
 * Internal registry for managing tool registration and lookup.
 *
 * <p>Every tool name maps to exactly one {@link RegisteredToolFunction}, which holds both the
 * {@link AgentTool} implementation and its registration metadata (extended schema, MCP client
 * name, preset parameters). Keeping the tool and its metadata in a <em>single</em> map makes the
 * tool binding and its schema view impossible to diverge: registration, replacement and removal
 * are one atomic map operation each.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe, using {@link ConcurrentHashMap} with
 * per-name atomic {@code compute}/{@code computeIfPresent} operations to support concurrent
 * registration, replacement, removal and lookup.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve {@link AgentTool} implementations by name</li>
 *   <li>Maintain {@link RegisteredToolFunction} metadata for schema generation</li>
 *   <li>Support dynamic tool removal for group-based activation</li>
 * </ul>
 */
class ToolRegistry {

    /**
     * Single source of truth: tool name to its registration (tool instance + metadata). The tool
     * binding and the metadata read by {@code ToolSchemaProvider} are written and removed together
     * in one atomic operation, so a concurrent removal can never leave metadata describing an
     * absent tool (or vice versa).
     */
    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();

    /** Maximum characters of a tool description quoted in a rejected-registration message. */
    private static final int MAX_DESCRIPTION_IN_MESSAGE = 120;

    /**
     * Register a tool with its metadata.
     *
     * <p>Registering a name that is already bound to a <em>different</em> tool fails fast with an
     * {@link IllegalStateException} unless {@code allowReplaceExisting} is explicitly set, or the
     * registration is an idempotent refresh (see {@link #isIdempotentRefresh}) or {@code
     * sameSourceRefresh} accepts the currently bound tool. This prevents silent shadowing (e.g. an
     * MCP tool quietly replacing a local tool of the same name).
     *
     * <p>The check-then-bind sequence runs atomically per name via {@link
     * ConcurrentHashMap#compute}: the conflict classification and the write of the new
     * registration (tool instance <em>and</em> metadata together) happen in one map operation, so
     * concurrent registrations of different tools under the same name always have exactly one
     * winner, and no concurrent removal can interleave between the check and the metadata write.
     * Every loser observes the winner's binding and fails fast instead of silently shadowing it.
     *
     * @param toolName Tool name
     * @param tool AgentTool implementation
     * @param registered RegisteredToolFunction wrapper with metadata
     * @param allowReplaceExisting when true, an existing different tool under the same name is replaced.
     *     Only pass true for deliberate replacements (e.g. {@code Toolkit.replaceAgentTool} or the
     *     framework meta-tool rebind in {@code Toolkit.copy()}); passing true elsewhere would
     *     reintroduce the silent-shadowing behavior this check exists to prevent.
     * @param sameSourceRefresh optional, {@code null} for the normal case. Predicate tested
     *     against the currently bound tool <em>inside</em> the atomic operation: returning true
     *     classifies this registration as a refresh of the same logical source (an idempotent
     *     re-registration of an annotated tool object) rather than a conflict. It runs under the
     *     per-name lock, so it must be cheap and non-blocking, must not read or modify this map
     *     or other shared mutable state, and may only record its outcome into state local to the
     *     caller (e.g. a per-call flag). Evaluating it here — not before the call — closes the
     *     window where an explicit replacement by another thread could be silently overwritten
     *     by a decision made against a stale binding.
     * @throws IllegalStateException if the name is taken by a different tool, the registration is
     *     not an idempotent refresh, {@code sameSourceRefresh} does not accept the bound tool, and
     *     {@code allowReplaceExisting} is false. The rejected registration writes nothing: the
     *     existing tool and its metadata both stay bound and usable.
     * @throws IllegalArgumentException if {@code toolName} is null or blank, {@code tool} or
     *     {@code registered} is null, or {@code registered} does not wrap {@code tool}
     */
    void registerTool(
            String toolName,
            AgentTool tool,
            RegisteredToolFunction registered,
            boolean allowReplaceExisting,
            Predicate<AgentTool> sameSourceRefresh) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        if (tool == null || registered == null) {
            throw new IllegalArgumentException("Tool and registration metadata cannot be null");
        }
        if (registered.getTool() != tool) {
            // The per-name check below compares identities against {@code tool} while the write
            // installs {@code registered}; letting the two disagree would break the invariant
            // that the binding and its metadata never diverge.
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
                            && !isIdempotentRefresh(existing, tool)
                            && !(sameSourceRefresh != null && sameSourceRefresh.test(existing))) {
                        // Atomic fail-fast: compute returns the existing registration unchanged,
                        // so both the tool binding and its metadata survive the rejection.
                        throw new IllegalStateException(
                                duplicateRegistrationMessage(toolName, existing, tool, registered));
                    }
                    return registered;
                });
    }

    /**
     * Register a tool with its metadata, failing fast on a name already bound to a different tool.
     *
     * @see #registerTool(String, AgentTool, RegisteredToolFunction, boolean, Predicate)
     */
    void registerTool(
            String toolName,
            AgentTool tool,
            RegisteredToolFunction registered,
            boolean allowReplaceExisting) {
        registerTool(toolName, tool, registered, allowReplaceExisting, null);
    }

    /**
     * Register a tool with its metadata, failing fast on a name already bound to a different tool.
     *
     * @see #registerTool(String, AgentTool, RegisteredToolFunction, boolean, Predicate)
     */
    void registerTool(String toolName, AgentTool tool, RegisteredToolFunction registered) {
        registerTool(toolName, tool, registered, false, null);
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
                + " Note: MCP tools are registered after local tools, so a same-named local tool"
                + " is the most likely existing tool an MCP registration collides with.";
    }

    /**
     * Whether re-registering {@code incoming} under a name already bound to {@code existing} is an
     * idempotent refresh (a legitimate re-registration of the same logical tool) rather than a
     * shadowing attempt.
     *
     * <p>Two patterns qualify:
     *
     * <ul>
     *   <li>Both are {@link McpTool}s served by the <em>same client wrapper instance</em>: an MCP
     *       client re-registering its own tools is what a reconnect or tool-list refresh looks
     *       like. The wrapper instance, not the configured client name, identifies the connection:
     *       a different wrapper claiming the same name may talk to a different server, and letting
     *       it refresh would silently take over the first wrapper's tools while the first wrapper
     *       falls out of tracking (and never gets closed).</li>
     *   <li>Both are {@link SchemaOnlyTool}s declaring an identical schema (same description,
     *       parameters, strict mode and deferred-loading flag): an unchanged external tool
     *       re-declaration. The deferred-loading flag is part of the contract — it decides whether
     *       the tool's schema is exposed immediately or only via tool search — so a re-declaration
     *       that flips it is a semantic change, not a refresh.</li>
     * </ul>
     *
     * <p>Everything else stays a fail-fast conflict — including a local tool over an MCP tool, an
     * MCP tool over a local tool, two different MCP clients (or two distinct wrappers sharing one
     * client name) claiming the same tool name, and a schema that changed under the same name.
     */
    private static boolean isIdempotentRefresh(AgentTool existing, AgentTool incoming) {
        if (existing instanceof McpTool existingMcp && incoming instanceof McpTool incomingMcp) {
            return existingMcp.isFromSameWrapper(incomingMcp);
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
        AtomicBoolean removed = new AtomicBoolean(false);
        registeredTools.computeIfPresent(
                toolName,
                (name, registration) -> {
                    if (registration.getTool() != expected) {
                        return registration;
                    }
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
