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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Adapter to bridge calculator tool to AgentScope's Toolkit.
 *
 * <p>This allows the calculator tool to be used with ReActAgent.
 */
public class CalculatorToolAdapter {

    private static final Logger logger = Logger.getLogger(CalculatorToolAdapter.class.getName());

    /**
     * Performs arithmetic operations.
     *
     * @param operation The operation: "add", "subtract", "multiply", or "divide"
     * @param a First operand
     * @param b Second operand
     * @return Result of the operation
     */
    @Tool(description = "Performs arithmetic operations: add, subtract, multiply, or divide")
    public Map<String, Object> compute(
            @ToolParam(
                            name = "operation",
                            description =
                                    "Operation to perform: 'add', 'subtract', 'multiply', or"
                                            + " 'divide'")
                    String operation,
            @ToolParam(name = "a", description = "First operand") double a,
            @ToolParam(name = "b", description = "Second operand") double b) {
        logger.info(
                String.format(
                        "[TOOL CALLED] compute(operation=%s, a=%.2f, b=%.2f)", operation, a, b));
        double result;
        switch (operation.toLowerCase()) {
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

        Map<String, Object> response = new HashMap<>();
        response.put("operation", operation);
        response.put("a", a);
        response.put("b", b);
        response.put("result", result);
        logger.info(
                String.format(
                        "[TOOL RESULT] %s: %.2f %s %.2f = %.2f",
                        operation.toUpperCase(), a, operation, b, result));
        return response;
    }
}
