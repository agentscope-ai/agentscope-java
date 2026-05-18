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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {

    private CalculatorTool tool;

    @BeforeEach
    void setUp() {
        tool = new CalculatorTool();
    }

    @Test
    void testGetName() {
        assertEquals("calculator.compute", tool.getName());
    }

    @Test
    void testGetDescription() {
        assertTrue(tool.getDescription().toLowerCase().contains("arithmetic"));
    }

    @Test
    void testGetInputSchema() {
        Map<String, Object> schema = tool.getInputSchema();
        assertEquals("object", schema.get("type"));
        assertTrue(schema.containsKey("properties"));
        assertTrue(schema.containsKey("required"));
    }

    @Test
    void testAdd() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "add");
        args.put("a", 5);
        args.put("b", 3);

        Object result = tool.execute(args);
        assertInstanceOf(Map.class, result);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(8.0, ((Number) resultMap.get("result")).doubleValue());
        assertEquals("add", resultMap.get("operation"));
    }

    @Test
    void testAddNegatives() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "add");
        args.put("a", -5);
        args.put("b", -3);

        Object result = tool.execute(args);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(-8.0, ((Number) resultMap.get("result")).doubleValue());
    }

    @Test
    void testSubtract() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "subtract");
        args.put("a", 10);
        args.put("b", 4);

        Object result = tool.execute(args);
        assertInstanceOf(Map.class, result);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(6.0, ((Number) resultMap.get("result")).doubleValue());
        assertEquals("subtract", resultMap.get("operation"));
    }

    @Test
    void testSubtractNegative() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "subtract");
        args.put("a", 5);
        args.put("b", -3);

        Object result = tool.execute(args);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(8.0, ((Number) resultMap.get("result")).doubleValue());
    }

    @Test
    void testMultiply() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "multiply");
        args.put("a", 4);
        args.put("b", 7);

        Object result = tool.execute(args);
        assertInstanceOf(Map.class, result);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(28.0, ((Number) resultMap.get("result")).doubleValue());
        assertEquals("multiply", resultMap.get("operation"));
    }

    @Test
    void testMultiplyByZero() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "multiply");
        args.put("a", 5);
        args.put("b", 0);

        Object result = tool.execute(args);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(0.0, ((Number) resultMap.get("result")).doubleValue());
    }

    @Test
    void testDivide() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "divide");
        args.put("a", 10);
        args.put("b", 2);

        Object result = tool.execute(args);
        assertInstanceOf(Map.class, result);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(5.0, ((Number) resultMap.get("result")).doubleValue());
        assertEquals("divide", resultMap.get("operation"));
    }

    @Test
    void testDivideDecimal() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "divide");
        args.put("a", 7);
        args.put("b", 2);

        Object result = tool.execute(args);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals(3.5, ((Number) resultMap.get("result")).doubleValue());
    }

    @Test
    void testDivideByZero() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "divide");
        args.put("a", 10);
        args.put("b", 0);

        assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
    }

    @Test
    void testInvalidOperation() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "invalid");
        args.put("a", 5);
        args.put("b", 3);

        assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
    }

    @Test
    void testInvalidArgumentType() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "add");
        args.put("a", "not a number");
        args.put("b", 3);

        assertThrows(Exception.class, () -> tool.execute(args));
    }

    @Test
    void testInvalidArgumentsNotMap() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("not a map"));
    }
}
