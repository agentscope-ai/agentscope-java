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
 * // Register an external tool using ToolSchema
 * ToolSchema schema = ToolSchema.builder()
 *     .name("query_database")
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
public class SchemaOnlyTool implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;

    /**
     * Creates a new SchemaOnlyTool from a ToolSchema.
     *
     * @param schema The tool schema containing name, description, and parameters
     * @throws NullPointerException if schema is null
     */
    public SchemaOnlyTool(ToolSchema schema) {
        Objects.requireNonNull(schema, "schema cannot be null");
        this.name = schema.getName();
        this.description = schema.getDescription();
        this.parameters =
                schema.getParameters() != null
                        ? Collections.unmodifiableMap(schema.getParameters())
                        : Collections.emptyMap();
    }

    /**
     * Creates a new SchemaOnlyTool with the specified name, description, and parameters.
     *
     * @param name The tool name
     * @param description The tool description
     * @param parameters The tool parameters schema
     * @throws NullPointerException if name or description is null
     */
    public SchemaOnlyTool(String name, String description, Map<String, Object> parameters) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.parameters =
                parameters != null
                        ? Collections.unmodifiableMap(parameters)
                        : Collections.emptyMap();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
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
