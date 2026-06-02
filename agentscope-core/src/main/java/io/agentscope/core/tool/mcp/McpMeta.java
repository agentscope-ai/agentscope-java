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
package io.agentscope.core.tool.mcp;

import io.agentscope.core.agent.RuntimeContext;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Type-namespace marker for MCP (Model Context Protocol) request metadata.
 *
 * <p>Register an instance of this class in a {@link RuntimeContext} (via
 * {@link RuntimeContext} or {@code RuntimeContext}) to have its
 * entries automatically included as the {@code meta} field of every MCP
 * {@link McpSchema.CallToolRequest} sent through {@link McpTool}.
 *
 * <p>Only objects registered under the {@code McpMeta.class} type key are extracted — other
 * context objects (DI beans, configs, etc.) are never leaked into MCP meta.
 *
 * <p><b>Usage examples:</b>
 *
 * <p>1) Via {@code RuntimeContext}:
 * <pre>{@code
 * RuntimeContext rtCtx = RuntimeContext.builder()
 *     .put(McpMeta.class, new McpMeta(Map.of("traceId", "abc-123")))
 *     .build();
 * }</pre>
 *
 * <p>2) Merging multiple meta sources (later entries override earlier ones for the same key):
 * <pre>{@code
 * McpMeta base = new McpMeta(Map.of("traceId", "abc-123"));
 * McpMeta extra = new McpMeta(Map.of("callbackUrl", "https://hook.example.com"));
 * McpMeta merged = McpMeta.merge(base, extra);
 * }</pre>
 *
 * @see McpTool
 * @see RuntimeContext
 */
public record McpMeta(Map<String, Object> entries) {

    /**
     * Creates an McpMeta with the given entries.
     *
     * @param entries the metadata entries (may be null or empty); the map is defensively copied
     */
    public McpMeta(Map<String, Object> entries) {
        this.entries =
                entries != null
                        ? Collections.unmodifiableMap(new HashMap<>(entries))
                        : Collections.emptyMap();
    }

    /**
     * Returns the metadata entries as an unmodifiable map.
     *
     * @return the entries, never null (may be empty)
     */
    @Override
    public Map<String, Object> entries() {
        return entries;
    }

    /**
     * Returns whether this meta contains no entries.
     *
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Merges multiple {@code McpMeta} instances into one.
     *
     * <p>Entries from later instances override earlier ones for the same key (last-wins).
     *
     * @param metas the meta instances to merge (nulls are silently skipped)
     * @return a new merged McpMeta, or an empty one if all inputs are null/empty
     */
    public static McpMeta merge(McpMeta... metas) {
        if (metas == null || metas.length == 0) {
            return new McpMeta(null);
        }
        Map<String, Object> merged = new HashMap<>();
        for (McpMeta meta : metas) {
            if (meta != null && !meta.entries.isEmpty()) {
                merged.putAll(meta.entries);
            }
        }
        return new McpMeta(merged);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        McpMeta mcpMeta = (McpMeta) o;
        return Objects.equals(entries, mcpMeta.entries);
    }

    @Override
    public String toString() {
        return "McpMeta" + entries;
    }
}
