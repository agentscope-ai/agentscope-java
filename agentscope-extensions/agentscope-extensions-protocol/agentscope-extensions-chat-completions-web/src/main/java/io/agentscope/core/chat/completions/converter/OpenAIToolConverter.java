/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.chat.completions.converter;

import io.agentscope.core.chat.completions.model.OpenAITool;
import io.agentscope.core.chat.completions.model.OpenAIToolFunction;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for converting OpenAI tool format to AgentScope ToolSchema.
 *
 * <p>This converter handles the transformation from OpenAI's tool format (used in Chat Completions
 * API requests) to AgentScope's internal ToolSchema format. Tools converted by this converter are
 * intended to be registered as schema-only tools, which will trigger tool suspension when called.
 */
public class OpenAIToolConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIToolConverter.class);

    /**
     * Converts a list of OpenAI tools to AgentScope ToolSchemas.
     *
     * <p>Only tools with type "function" are converted. Other tool types are skipped with a warning.
     *
     * @param tools The list of OpenAI tools to convert (may be null or empty)
     * @return A list of converted ToolSchema objects; returns an empty list if input is null or
     *     empty
     */
    public List<ToolSchema> convertToToolSchemas(List<OpenAITool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<ToolSchema> schemas = new ArrayList<>();

        for (OpenAITool tool : tools) {
            if (tool == null) {
                log.warn("Skipping null tool in conversion");
                continue;
            }

            // Only support function type tools for now
            if (!"function".equals(tool.getType())) {
                log.warn(
                        "Skipping tool with unsupported type: {}. Only 'function' type is"
                                + " supported",
                        tool.getType());
                continue;
            }

            OpenAIToolFunction function = tool.getFunction();
            if (function == null) {
                log.warn("Skipping tool with null function definition");
                continue;
            }

            String name = function.getName();
            String description = function.getDescription();
            Map<String, Object> parameters = function.getParameters();

            // Validate required fields
            if (name == null || name.isBlank()) {
                log.warn("Skipping tool with null or empty name");
                continue;
            }

            if (description == null || description.isBlank()) {
                log.warn("Skipping tool '{}' with null or empty description", name);
                // Use empty string as fallback for description
                description = "";
            }

            try {
                ToolSchema.Builder schemaBuilder =
                        ToolSchema.builder().name(name).description(description);

                if (parameters != null) {
                    schemaBuilder.parameters(parameters);
                }

                if (function.getStrict() != null) {
                    schemaBuilder.strict(function.getStrict());
                }

                ToolSchema schema = schemaBuilder.build();
                schemas.add(schema);
                log.debug("Converted OpenAI tool to ToolSchema: {}", name);

            } catch (Exception e) {
                log.error("Failed to convert tool '{}' to ToolSchema: {}", name, e.getMessage(), e);
            }
        }

        return schemas;
    }

    /**
     * Converts a single OpenAI tool to a ToolSchema.
     *
     * @param tool The OpenAI tool to convert
     * @return The converted ToolSchema, or null if conversion fails
     */
    public ToolSchema convertToToolSchema(OpenAITool tool) {
        if (tool == null) {
            return null;
        }

        List<ToolSchema> schemas = convertToToolSchemas(List.of(tool));
        return schemas.isEmpty() ? null : schemas.get(0);
    }
}
