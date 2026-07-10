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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * An external tool implementation that only contains schema definition without execution logic.
 *
 * <p>This class is used for registering external tools that will be executed outside the framework.
 * When a model returns a call to a SchemaOnlyTool, the framework will catch the
 * {@link ToolSuspendException} thrown by {@link #callAsync(ToolCallParam)} and convert it to a
 * pending {@link ToolResultBlock}, then return a suspended message to the user.
 *
 * <p>The {@link #callAsync(ToolCallParam)} method throws a {@link ToolSuspendException}
 * to signal that this tool requires external execution.
 *
 * <p>Example usage:
 * <pre>{@code
 * ToolSchema schema = ToolSchema.builder()
 *     .name("query_database")
 *     .title("Query Database")
 *     .description("Query external database")
 *     .parameters(Map.of(
 *         "type", "object",
 *         "properties", Map.of("sql", Map.of("type", "string")),
 *         "required", List.of("sql")
 *     ))
 *     .build();
 *
 * toolkit.registerSchema(schema);
 * // Or: toolkit.registerAgentTool(new SchemaOnlyTool(schema));
 * }</pre>
 *
 * @see ToolSchema
 * @see Toolkit#registerSchema(ToolSchema)
 * @see Toolkit#isExternalTool(String)
 */
public class SchemaOnlyTool extends ToolBase {

    private final Boolean strict;

    /**
     * Creates a new SchemaOnlyTool from a ToolSchema.
     *
     * @param schema The tool schema containing name, description, parameters, and strict mode
     * @throws NullPointerException if schema is null
     */
    public SchemaOnlyTool(ToolSchema schema) {
        this(
                Objects.requireNonNull(schema, "schema cannot be null").getName(),
                schema.getTitle(),
                schema.getDescription(),
                schema.getParameters(),
                schema.getStrict());
    }

    /**
     * Creates a new SchemaOnlyTool with the specified name, description, and parameters.
     *
     * <p>Strict mode is set to null (unspecified).
     *
     * @param name The tool name
     * @param description The tool description
     * @param parameters The tool parameters schema
     * @throws NullPointerException if name or description is null
     */
    public SchemaOnlyTool(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, null);
    }

    /**
     * Creates a new SchemaOnlyTool with the specified name, description, parameters, and strict
     * mode configuration.
     *
     * <p>The parameters map is defensively copied to prevent external mutations from affecting
     * the tool's internal state.
     *
     * @param name        The tool name
     * @param description The tool description
     * @param parameters  The tool parameters schema
     * @param strict      Whether the tool should use strict schema validation (null if unspecified)
     * @throws NullPointerException if name or description is null
     */
    public SchemaOnlyTool(
            String name, String description, Map<String, Object> parameters, Boolean strict) {
        this(name, null, description, parameters, strict);
    }

    /**
     * Creates a new SchemaOnlyTool with the specified name, title, description, parameters, and strict
     * mode configuration.
     *
     * <p>The parameters map is defensively copied to prevent external mutations from affecting
     * the tool's internal state.
     *
     * @param name        The tool name
     * @param title        The tool title
     * @param description The tool description
     * @param parameters  The tool parameters schema
     * @param strict      Whether the tool should use strict schema validation (null if unspecified)
     * @throws NullPointerException if name or description is null
     */
    public SchemaOnlyTool(
            String name,
            String title,
            String description,
            Map<String, Object> parameters,
            Boolean strict) {
        super(
                ToolBase.builder()
                        .name(Objects.requireNonNull(name, "name cannot be null"))
                        .title(title)
                        .description(
                                Objects.requireNonNull(description, "description cannot be null"))
                        .inputSchema(
                                parameters != null
                                        ? Collections.unmodifiableMap(new HashMap<>(parameters))
                                        : Collections.emptyMap())
                        .externalTool(true)
                        .readOnly(false)
                        .concurrencySafe(true));
        this.strict = strict;
    }

    @Override
    public Boolean getStrict() {
        return strict;
    }

    /**
     * Throws a ToolSuspendException to signal that this tool requires external execution.
     *
     * <p>The framework will catch this exception and convert it to a pending {@link ToolResultBlock}.
     * The agent will then return a suspended message with {@code GenerateReason.TOOL_SUSPENDED}
     * containing the tool use blocks that need external execution.
     *
     * @param param The tool call parameters (ignored)
     * @return Never returns normally
     * @throws ToolSuspendException always, to signal external execution is required
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.error(new ToolSuspendException());
    }
}
