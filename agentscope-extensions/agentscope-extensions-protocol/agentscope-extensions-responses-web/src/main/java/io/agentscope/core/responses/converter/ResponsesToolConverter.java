/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.responses.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.responses.model.ResponsesTool;
import java.util.ArrayList;
import java.util.List;

/** Converts Responses tools and tool choices to AgentScope model types. */
public class ResponsesToolConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Convert request-level Responses tools into AgentScope tool schemas.
     *
     * <p>Only {@code type=function} tools are executable by the local AgentScope toolkit. OpenAI
     * hosted-tool request shapes are accepted by the DTO layer, but this compatibility starter does
     * not execute OpenAI hosted services by default.
     *
     * @param tools Responses tool declarations
     * @return AgentScope tool schemas for function tools
     */
    public List<ToolSchema> convertToToolSchemas(List<ResponsesTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<ToolSchema> schemas = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            ToolSchema schema = convertToToolSchema(tools.get(i), "tools[" + i + "]");
            if (schema != null) {
                schemas.add(schema);
            }
        }
        return schemas;
    }

    /**
     * Convert one Responses function tool into an AgentScope {@link ToolSchema}.
     *
     * @param tool Responses tool declaration
     * @param param Request parameter path used in validation errors
     * @return Converted schema, or {@code null} for non-function tools
     */
    public ToolSchema convertToToolSchema(ResponsesTool tool, String param) {
        if (tool == null) {
            throw ResponsesValidationException.invalid("Tool entry must not be null", param);
        }
        if (!"function".equals(tool.getType())) {
            return null;
        }
        if (tool.getName() == null || tool.getName().isBlank()) {
            throw ResponsesValidationException.invalid("Function tool name is required", param);
        }
        JsonNode parameters = jsonNode(tool.getParameters());
        if (parameters != null && !parameters.isNull() && !parameters.isObject()) {
            throw ResponsesValidationException.invalid(
                    "Function tool parameters must be a JSON object", param + ".parameters");
        }

        ToolSchema.Builder builder =
                ToolSchema.builder()
                        .name(tool.getName())
                        .description(tool.getDescription() != null ? tool.getDescription() : "");
        if (parameters != null && !parameters.isNull()) {
            builder.parameters(OBJECT_MAPPER.convertValue(parameters, new TypeReference<>() {}));
        }
        if (tool.getStrict() != null) {
            builder.strict(tool.getStrict());
        }
        return builder.build();
    }

    /**
     * Convert Responses {@code tool_choice} into AgentScope's tool-choice model.
     *
     * <p>The Responses API accepts both string values such as {@code auto} and object values such
     * as {@code {"type":"function","name":"get_weather"}}. AgentScope represents those as a
     * typed {@link ToolChoice} hierarchy.
     *
     * @param value Raw tool choice from the request DTO
     * @return Converted tool choice, or {@code null} when no local mapping is needed
     */
    public ToolChoice convertToolChoice(Object value) {
        JsonNode toolChoice = jsonNode(value);
        if (toolChoice == null || toolChoice.isNull()) {
            return null;
        }
        if (toolChoice.isTextual()) {
            return switch (toolChoice.asText()) {
                case "auto" -> new ToolChoice.Auto();
                case "none" -> new ToolChoice.None();
                case "required" -> new ToolChoice.Required();
                default ->
                        throw ResponsesValidationException.unsupported(
                                "Unsupported tool_choice value: " + toolChoice.asText(),
                                "tool_choice");
            };
        }
        if (!toolChoice.isObject()) {
            throw ResponsesValidationException.invalid(
                    "tool_choice must be a string or object", "tool_choice");
        }
        String type = text(toolChoice.get("type"));
        if (!"function".equals(type)) {
            return null;
        }
        String name = text(toolChoice.get("name"));
        if (name == null || name.isBlank()) {
            JsonNode function = toolChoice.get("function");
            if (function != null && function.isObject()) {
                name = text(function.get("name"));
            }
        }
        if (name == null || name.isBlank()) {
            throw ResponsesValidationException.invalid(
                    "Function tool_choice requires a non-empty name", "tool_choice.name");
        }
        return new ToolChoice.Specific(name);
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private JsonNode jsonNode(Object value) {
        return value == null ? null : OBJECT_MAPPER.valueToTree(value);
    }
}
