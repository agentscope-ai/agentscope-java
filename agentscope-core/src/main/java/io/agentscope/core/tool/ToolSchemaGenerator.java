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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.util.JsonSchemaUtils;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates JSON Schema for tool parameters.
 * This class handles the conversion of Java method signatures to JSON Schema
 * format
 * compatible with OpenAI's function calling API.
 */
class ToolSchemaGenerator {

    /**
     * Generate parameter schema for a method.
     *
     * @param method the method to generate schema for
     * @return JSON Schema map in OpenAI format
     */
    Map<String, Object> generateParameterSchema(Method method) {
        return generateParameterSchema(method, Collections.emptySet());
    }

    /**
     * Generate parameter schema for a method with excluded parameters.
     *
     * <p>
     * This overload allows excluding certain parameters from the generated schema,
     * which is
     * useful for preset parameters that should not be exposed to the agent.
     *
     * @param method        the method to generate schema for
     * @param excludeParams set of parameter names to exclude from the schema (may
     *                      be null or empty)
     * @return JSON Schema map in OpenAI format
     */
    Map<String, Object> generateParameterSchema(Method method, Set<String> excludeParams) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            // Skip framework parameters like ToolEmitter and Agent - they should not be in
            // the
            // schema
            if (param.getType() == ToolEmitter.class || param.getType() == Agent.class) {
                continue;
            }

            ParameterInfo info = extractParameterInfo(param);

            // Skip excluded parameters (e.g., preset parameters)
            if (excludeParams != null && excludeParams.contains(info.name)) {
                continue;
            }

            properties.put(info.name, info.schema);
            if (info.required) {
                required.add(info.name);
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Extract parameter information from a Parameter.
     *
     * @param param the parameter to extract info from
     * @return ParameterInfo containing name, schema, and required flag
     */
    private ParameterInfo extractParameterInfo(Parameter param) {
        ToolParam toolParam = param.getAnnotation(ToolParam.class);

        // Use name from @ToolParam annotation, fallback to reflection-based name
        String paramName = (toolParam != null) ? toolParam.name() : param.getName();

        // Generate schema using JsonSchemaUtils with full type support (including
        // generics)
        Map<String, Object> paramSchema =
                JsonSchemaUtils.generateSchemaFromType(param.getParameterizedType());

        boolean required = false;
        if (toolParam != null) {
            if (!toolParam.description().isEmpty()) {
                paramSchema.put("description", toolParam.description());
            }
            required = toolParam.required();
        }

        return new ParameterInfo(paramName, paramSchema, required);
    }

    /**
     * Internal class to hold parameter information.
     */
    private static class ParameterInfo {
        final String name;
        final Map<String, Object> schema;
        final boolean required;

        ParameterInfo(String name, Map<String, Object> schema, boolean required) {
            this.name = name;
            this.schema = schema;
            this.required = required;
        }
    }
}
