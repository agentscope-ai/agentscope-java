package io.agentscope.examples.mcp;

import io.agentscope.core.mcp.tool.Tool;
import java.util.HashMap;
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
        schema.put("required", new String[] {"operation", "a", "b"});
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
