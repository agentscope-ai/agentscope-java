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
package io.agentscope.examples.mcp;

import io.agentscope.core.mcp.tool.Tool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple calculator tool that performs basic arithmetic operations.
 */
public class CalculatorTool implements Tool {

    @Override
    public String getName() {
        return "calculator.compute";
    }

    @Override
    public String getDescription() {
        return "Performs basic arithmetic operations (add, subtract, multiply, divide)";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        properties.put(
                "operation",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        new String[] {"add", "subtract", "multiply", "divide"}));
        properties.put("a", Map.of("type", "number"));
        properties.put("b", Map.of("type", "number"));

        schema.put("properties", properties);
        schema.put("required", List.of("operation", "a", "b"));
        return schema;
    }

    @Override
    public Object execute(Object arguments) throws Exception {
        if (!(arguments instanceof Map)) {
            throw new IllegalArgumentException("Arguments must be a map");
        }

        Map<String, Object> args = (Map<String, Object>) arguments;
        String operation = (String) args.get("operation");
        double a = ((Number) args.get("a")).doubleValue();
        double b = ((Number) args.get("b")).doubleValue();

        double result;
        switch (operation) {
            case "add":
                result = a + b;
                break;
            case "subtract":
                result = a - b;
                break;
            case "multiply":
                result = a * b;
                break;
            case "divide":
                if (b == 0) {
                    throw new IllegalArgumentException("Division by zero");
                }
                result = a / b;
                break;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }

        return Map.of("operation", operation, "a", a, "b", b, "result", result);
    }
}
