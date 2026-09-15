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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-call tool request config. Composed (NOT copied) with a shared, stateless {@link
 * Toolkit} to produce the tool surface for one call. Never mutates nor copies the shared toolkit.
 *
 * <p>Produced by any external-tool-injection layer (AG-UI adapter today); {@link #NONE} means "use
 * the shared toolkit as-is". {@link #externalTools()} are {@link SchemaOnlyTool} instances — in
 * this framework an external tool is by definition schema-only (no local implementation), and
 * {@code SchemaOnlyTool} carries only a schema (no {@code groupManager}/registry back-reference),
 * so they are safe to construct per-call and carry in an immutable request config. The concrete
 * type ({@code Map<String, SchemaOnlyTool>} rather than {@code Map<String, AgentTool>}) makes
 * "external tool ⇒ schema-only" a compile-time invariant.
 */
public record ToolRequestConfig(
        Map<String, SchemaOnlyTool> externalTools, // name -> external (schema-only) tool, overrides
        // backend
        ToolMergeMode mergeMode // AGENT_ONLY ⇒ NONE；否则 MERGE_EXTERNAL_PRIORITY | EXTERNAL_ONLY
        ) {

    /** Sentinel meaning "use the shared toolkit as-is". */
    public static final ToolRequestConfig NONE =
            new ToolRequestConfig(Map.of(), ToolMergeMode.AGENT_ONLY);

    public ToolRequestConfig {
        if (externalTools == null || externalTools.isEmpty()) {
            externalTools = Map.of();
        } else {
            // Preserve insertion order (callers build an ordered LinkedHashMap, and schema
            // generation is order-sensitive) while making the map immutable — Map.copyOf() would
            // drop order. Validate each entry eagerly so this value type stays fail-fast about
            // nulls (Map.copyOf-style) instead of deferring the failure to schema generation.
            Map<String, SchemaOnlyTool> copy = new LinkedHashMap<>();
            externalTools.forEach(
                    (name, tool) ->
                            copy.put(
                                    Objects.requireNonNull(
                                            name, "external tool name cannot be null"),
                                    Objects.requireNonNull(tool, "external tool cannot be null")));
            externalTools = Collections.unmodifiableMap(copy);
        }
        mergeMode = mergeMode != null ? mergeMode : ToolMergeMode.MERGE_EXTERNAL_PRIORITY;
    }

    /**
     * Resolves the tool visible for {@code name} under this request config: an external tool takes
     * priority, then in {@link ToolMergeMode#EXTERNAL_ONLY} (when deletion is allowed) the backend
     * is hidden, then the supplied backend registry is consulted. This is the single home of the
     * "external → hide-backend → registry" policy, shared by {@link
     * Toolkit#getTool(String, ToolRequestConfig)} and the executor so they cannot drift.
     */
    AgentTool resolveTool(String name, ToolRegistry registry, boolean allowToolDeletion) {
        AgentTool external = externalTools.get(name);
        if (external != null) {
            return external;
        }
        if (hidesBackend(allowToolDeletion)) {
            return null;
        }
        return registry.getTool(name);
    }

    /**
     * Whether this config hides the backend registry entirely (deletion-mode {@link
     * ToolMergeMode#EXTERNAL_ONLY}). Shared by {@link #resolveTool(String, ToolRegistry, boolean)}
     * and {@link Toolkit#getToolSchemas(java.util.Collection, ToolRequestConfig)} so the policy
     * cannot drift.
     */
    boolean hidesBackend(boolean allowToolDeletion) {
        return mergeMode == ToolMergeMode.EXTERNAL_ONLY && allowToolDeletion;
    }

    /**
     * Whether {@code name} is shadowed by an external tool, which always overrides the backend
     * regardless of merge mode. Shared by {@link #resolveTool(String, ToolRegistry, boolean)}, the
     * executor's availability gate, and {@link
     * Toolkit#getToolSchemas(java.util.Collection, ToolRequestConfig)}.
     */
    boolean overrides(String name) {
        return externalTools.containsKey(name);
    }
}
