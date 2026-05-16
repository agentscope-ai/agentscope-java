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
