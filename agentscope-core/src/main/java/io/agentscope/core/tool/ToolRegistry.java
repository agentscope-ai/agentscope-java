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

import java.util.HashSet;
import java.util.Map;
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
 * storage to support concurrent tool registration and lookup operations. Tool instance and
 * registration metadata are stored together in a single compound map entry, so put/remove of the
 * two are a single atomic operation.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve {@link AgentTool} implementations by name</li>
 *   <li>Maintain {@link RegisteredToolFunction} metadata for schema generation</li>
 *   <li>Support dynamic tool removal for group-based activation</li>
 * </ul>
 */
class ToolRegistry {

    private record Entry(AgentTool tool, RegisteredToolFunction registered) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Register a tool with its metadata.
     *
     * @param toolName Tool name
     * @param tool AgentTool implementation
     * @param registered RegisteredToolFunction wrapper with metadata
     */
    void registerTool(String toolName, AgentTool tool, RegisteredToolFunction registered) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        entries.put(toolName, new Entry(tool, registered));
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
        Entry e = entries.get(name);
        return e != null ? e.tool() : null;
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
        Entry e = entries.get(name);
        return e != null ? e.registered() : null;
    }

    /**
     * Get all tool names.
     *
     * @return Set of tool names
     */
    Set<String> getToolNames() {
        return new HashSet<>(entries.keySet());
    }

    /**
     * Get all registered tool functions.
     *
     * @return Map of tool name to RegisteredToolFunction
     */
    Map<String, RegisteredToolFunction> getAllRegisteredTools() {
        Map<String, RegisteredToolFunction> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().registered() != null) {
                result.put(e.getKey(), e.getValue().registered());
            }
        }
        return result;
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
        entries.remove(toolName);
    }

    /**
     * Atomically remove a tool only if the current instance matches the expected one.
     * Uses {@link ConcurrentHashMap#remove(Object, Object)} to avoid TOCTOU races.
     *
     * <p><b>Identity semantics</b>: The guard check ({@code existing.tool() == expected})
     * compares the expected tool by reference ({@code ==}), not via {@link Object#equals}.
     * Two {@code AgentTool} instances that are {@link Object#equals equal} but not the same
     * reference will not match — this guards against accidental removal of a tool that was
     * re-registered under the same name by another caller. The CAS at
     * {@link ConcurrentHashMap#remove(Object, Object)} additionally depends on the
     * {@code Entry} record's {@link Object#equals}, which compares both the
     * {@code AgentTool} and {@code RegisteredToolFunction} fields; callers that
     * rebuild {@code Entry} objects (e.g. via {@code copyTo}) must ensure
     * {@code RegisteredToolFunction} equality remains stable across rebuilds.
     *
     * @param toolName Tool name to remove
     * @param expected The expected {@link AgentTool} instance, compared by reference
     *        ({@code ==}), not by {@link Object#equals}
     * @return true if the tool was removed, false if it was already replaced or absent
     */
    boolean removeToolIfSame(String toolName, AgentTool expected) {
        Entry existing = entries.get(toolName);
        if (existing != null && existing.tool() == expected) {
            return entries.remove(toolName, existing);
        }
        return false;
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
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            String toolName = e.getKey();
            Entry entry = e.getValue();
            target.entries.put(
                    toolName,
                    new Entry(
                            entry.tool(),
                            entry.registered() == null
                                    ? null
                                    : new RegisteredToolFunction(
                                            entry.tool(),
                                            entry.registered().getExtendedModel(),
                                            entry.registered().getMcpClientName(),
                                            entry.registered().getPresetParameters())));
        }
    }
}
