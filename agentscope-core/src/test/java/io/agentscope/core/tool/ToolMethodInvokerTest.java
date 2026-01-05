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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolMethodInvoker, focusing on convertFromString and parameter conversion.
 */
class ToolMethodInvokerTest {

    private ToolMethodInvoker invoker;
    private ToolResultConverter responseConverter;

    @BeforeEach
    void setUp() {
        responseConverter = new DefaultToolResultConverter();
        invoker = new ToolMethodInvoker(responseConverter);
    }

    private ToolResultBlock invokeWithParam(
            Object tools, Method method, Map<String, Object> input) {
        ToolUseBlock toolUseBlock = new ToolUseBlock("test-id", method.getName(), input);
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();
        return invoker.invokeAsync(tools, method, param, responseConverter).block();
    }

    // Test class with various method signatures for testing
    static class TestTools {
        public int intMethod(@ToolParam(name = "value", description = "value") int value) {
            return value;
        }

        public Integer integerMethod(
                @ToolParam(name = "value", description = "value") Integer value) {
            return value;
        }

        public long longMethod(@ToolParam(name = "value", description = "value") long value) {
            return value;
        }

        public Long longObjectMethod(@ToolParam(name = "value", description = "value") Long value) {
            return value;
        }

        public double doubleMethod(@ToolParam(name = "value", description = "value") double value) {
            return value;
        }

        public Double doubleObjectMethod(
                @ToolParam(name = "value", description = "value") Double value) {
            return value;
        }

        public float floatMethod(@ToolParam(name = "value", description = "value") float value) {
            return value;
        }

        public Float floatObjectMethod(
                @ToolParam(name = "value", description = "value") Float value) {
            return value;
        }

        public boolean booleanMethod(
                @ToolParam(name = "value", description = "value") boolean value) {
            return value;
        }

        public Boolean booleanObjectMethod(
                @ToolParam(name = "value", description = "value") Boolean value) {
            return value;
        }

        public String stringMethod(@ToolParam(name = "value", description = "value") String value) {
            return value;
        }

        public void voidMethod() {
            // do nothing
        }

        public String nullMethod() {
            return null;
        }

        public String multiParamMethod(
                @ToolParam(name = "str", description = "str") String str,
                @ToolParam(name = "num", description = "num") int num,
                @ToolParam(name = "flag", description = "flag") boolean flag) {
            return str + num + flag;
        }

        public String throwsException() {
            throw new RuntimeException("Test exception");
        }

        public int parsableIntString(
                @ToolParam(name = "value", description = "value") String value) {
            return Integer.parseInt(value);
        }
    }

    @Test
    void testConvertFromString_Integer() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("intMethod", int.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "42");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("42", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_IntegerObject() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("integerMethod", Integer.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "100");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("100", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_Long() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("longMethod", long.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "9876543210");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("9876543210", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_LongObject() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("longObjectMethod", Long.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "123456789012345");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("123456789012345", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_Double() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("doubleMethod", double.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "3.14159");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("3.14159", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_DoubleObject() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("doubleObjectMethod", Double.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "2.71828");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("2.71828", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_Float() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("floatMethod", float.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "1.5");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("1.5", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_FloatObject() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("floatObjectMethod", Float.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "2.5");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("2.5", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_Boolean() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("booleanMethod", boolean.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "true");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("true", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_BooleanObject() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("booleanObjectMethod", Boolean.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "false");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("false", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_String() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("stringMethod", String.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "hello");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        // Strings are serialized as JSON strings with quotes
        Assertions.assertEquals("\"hello\"", ToolTestUtils.extractContent(response));
    }

    @Test
    void testInvoke_WithDirectTypeMatch() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("intMethod", int.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", 42); // Direct integer, not string

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("42", ToolTestUtils.extractContent(response));
    }

    @Test
    void testInvoke_WithNullParameter() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("stringMethod", String.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", null);

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("null", ToolTestUtils.extractContent(response));
    }

    @Test
    void testInvoke_WithMissingParameter() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("stringMethod", String.class);

        Map<String, Object> input = new HashMap<>();
        // No "value" key

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("null", ToolTestUtils.extractContent(response));
    }

    @Test
    void testInvoke_VoidMethod() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("voidMethod");

        Map<String, Object> input = new HashMap<>();

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("null", ToolTestUtils.extractContent(response));
    }

    @Test
    void testInvoke_NullReturn() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("nullMethod");

        Map<String, Object> input = new HashMap<>();

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        Assertions.assertEquals("null", ToolTestUtils.extractContent(response));
    }

    @Test
    void testInvoke_MultipleParameters() throws Exception {
        TestTools tools = new TestTools();
        Method method =
                TestTools.class.getMethod(
                        "multiParamMethod", String.class, int.class, boolean.class);

        Map<String, Object> input = new HashMap<>();
        input.put("str", "test");
        input.put("num", "123");
        input.put("flag", "true");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        // Strings are serialized as JSON strings with quotes
        Assertions.assertEquals("\"test123true\"", ToolTestUtils.extractContent(response));
    }

    @Test
    void testInvoke_MethodThrowsException() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("throwsException");

        Map<String, Object> input = new HashMap<>();

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        String content = ToolTestUtils.extractContent(response);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(content.contains("Tool execution failed"));
        Assertions.assertTrue(content.contains("Test exception"));
    }

    @Test
    void testConvertFromString_InvalidInteger() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("intMethod", int.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "not-a-number");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        String content = ToolTestUtils.extractContent(response);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(content.contains("Tool execution failed"));
    }

    @Test
    void testConvertFromString_InvalidDouble() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("doubleMethod", double.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "not-a-double");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        String content = ToolTestUtils.extractContent(response);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(content.contains("Tool execution failed"));
    }

    @Test
    void testConvertFromString_EmptyString() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("stringMethod", String.class);

        Map<String, Object> input = new HashMap<>();
        input.put("value", "");

        ToolResultBlock response = invokeWithParam(tools, method, input);

        Assertions.assertNotNull(response);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response));
        // Empty strings are serialized as JSON strings with quotes
        Assertions.assertEquals("\"\"", ToolTestUtils.extractContent(response));
    }

    @Test
    void testConvertFromString_BooleanFalseVariants() throws Exception {
        TestTools tools = new TestTools();
        Method method = TestTools.class.getMethod("booleanMethod", boolean.class);

        // "false" string
        Map<String, Object> input1 = new HashMap<>();
        input1.put("value", "false");
        ToolResultBlock response1 = invokeWithParam(tools, method, input1);
        Assertions.assertNotNull(response1);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response1));
        Assertions.assertEquals("false", ToolTestUtils.extractContent(response1));

        // Any non-"true" string becomes false
        Map<String, Object> input2 = new HashMap<>();
        input2.put("value", "anything-else");
        ToolResultBlock response2 = invokeWithParam(tools, method, input2);
        Assertions.assertNotNull(response2);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response2));
        Assertions.assertEquals("false", ToolTestUtils.extractContent(response2));
    }

    @Test
    void testConvertFromString_LargeNumbers() throws Exception {
        TestTools tools = new TestTools();

        // Test Long max value
        Method longMethod = TestTools.class.getMethod("longMethod", long.class);
        Map<String, Object> input1 = new HashMap<>();
        input1.put("value", String.valueOf(Long.MAX_VALUE));
        ToolResultBlock response1 = invokeWithParam(tools, longMethod, input1);
        Assertions.assertNotNull(response1);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response1));
        Assertions.assertEquals(
                String.valueOf(Long.MAX_VALUE), ToolTestUtils.extractContent(response1));

        // Test Double max value
        Method doubleMethod = TestTools.class.getMethod("doubleMethod", double.class);
        Map<String, Object> input2 = new HashMap<>();
        input2.put("value", String.valueOf(Double.MAX_VALUE));
        ToolResultBlock response2 = invokeWithParam(tools, doubleMethod, input2);
        Assertions.assertNotNull(response2);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response2));
        Assertions.assertEquals(
                String.valueOf(Double.MAX_VALUE), ToolTestUtils.extractContent(response2));
    }

    @Test
    void testConvertFromString_NegativeNumbers() throws Exception {
        TestTools tools = new TestTools();

        // Test negative integer
        Method intMethod = TestTools.class.getMethod("intMethod", int.class);
        Map<String, Object> input1 = new HashMap<>();
        input1.put("value", "-42");
        ToolResultBlock response1 = invokeWithParam(tools, intMethod, input1);
        Assertions.assertNotNull(response1);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response1));
        Assertions.assertEquals("-42", ToolTestUtils.extractContent(response1));

        // Test negative double
        Method doubleMethod = TestTools.class.getMethod("doubleMethod", double.class);
        Map<String, Object> input2 = new HashMap<>();
        input2.put("value", "-3.14");
        ToolResultBlock response2 = invokeWithParam(tools, doubleMethod, input2);
        Assertions.assertNotNull(response2);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response2));
        Assertions.assertEquals("-3.14", ToolTestUtils.extractContent(response2));
    }

    @Test
    void testConvertFromString_ZeroValues() throws Exception {
        TestTools tools = new TestTools();

        // Test zero integer
        Method intMethod = TestTools.class.getMethod("intMethod", int.class);
        Map<String, Object> input1 = new HashMap<>();
        input1.put("value", "0");
        ToolResultBlock response1 = invokeWithParam(tools, intMethod, input1);
        Assertions.assertNotNull(response1);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response1));
        Assertions.assertEquals("0", ToolTestUtils.extractContent(response1));

        // Test zero double
        Method doubleMethod = TestTools.class.getMethod("doubleMethod", double.class);
        Map<String, Object> input2 = new HashMap<>();
        input2.put("value", "0.0");
        ToolResultBlock response2 = invokeWithParam(tools, doubleMethod, input2);
        Assertions.assertNotNull(response2);
        Assertions.assertFalse(ToolTestUtils.isErrorResponse(response2));
        Assertions.assertEquals("0.0", ToolTestUtils.extractContent(response2));
    }
}
