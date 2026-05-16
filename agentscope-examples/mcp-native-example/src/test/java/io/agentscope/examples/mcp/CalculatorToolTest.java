package io.agentscope.examples.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {

    @Test
    void testAdd() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "add");
        args.put("a", 5);
        args.put("b", 3);

        Object result = tool.execute(args);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(8.0, ((Number) resultMap.get("result")).doubleValue());
    }

    @Test
    void testMultiply() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "multiply");
        args.put("a", 4);
        args.put("b", 7);

        Object result = tool.execute(args);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(28.0, ((Number) resultMap.get("result")).doubleValue());
    }

    @Test
    void testDivide() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "divide");
        args.put("a", 10);
        args.put("b", 2);

        Object result = tool.execute(args);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(5.0, ((Number) resultMap.get("result")).doubleValue());
    }
}
