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
package io.agentscope.core.llm.interfacesweb.responses;

import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts OpenAI Responses function tools to AgentScope tool schemas. */
public class ResponsesToolConverter {

    public List<ToolSchema> convert(List<ResponsesTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<ToolSchema> schemas = new ArrayList<>();
        for (ResponsesTool tool : tools) {
            ToolSchema schema = convert(tool);
            if (schema != null) {
                schemas.add(schema);
            }
        }
        return schemas;
    }

    private ToolSchema convert(ResponsesTool tool) {
        if (tool == null) {
            return null;
        }
        if (tool.getType() != null && !"function".equals(tool.getType())) {
            return null;
        }

        String name = tool.getName();
        String description = tool.getDescription();
        Map<String, Object> parameters = tool.getParameters();
        Boolean strict = tool.getStrict();

        if (tool.getFunction() != null) {
            Map<String, Object> function = tool.getFunction();
            name = value(function, "name", name);
            description = value(function, "description", description);
            Object params = function.get("parameters");
            if (params instanceof Map<?, ?> map) {
                parameters = castMap(map);
            }
            Object strictValue = function.get("strict");
            if (strictValue instanceof Boolean bool) {
                strict = bool;
            }
        }

        if (name == null || name.isBlank()) {
            return null;
        }
        if (description == null) {
            description = "";
        }
        return ToolSchema.builder()
                .name(name)
                .description(description)
                .parameters(parameters != null ? parameters : Map.of("type", "object"))
                .strict(strict)
                .build();
    }

    private String value(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private Map<String, Object> castMap(Map<?, ?> map) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
