/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool.test;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * Sample tools for testing.
 *
 * <p>Provides simple tool implementations for testing tool registration, execution, and parameter
 * validation.
 */
public class SampleTools {

    /**
     * Simple calculator tool - add two numbers.
     */
    @Tool(name = "add", description = "Add two numbers together")
    public int add(
            @ToolParam(description = "First number") int a,
            @ToolParam(description = "Second number") int b) {
        return a + b;
    }

    /**
     * String manipulation tool - concatenate strings.
     */
    @Tool(name = "concat", description = "Concatenate two strings")
    public String concat(
            @ToolParam(description = "First string") String str1,
            @ToolParam(description = "Second string") String str2) {
        return str1 + str2;
    }

    /**
     * Tool that throws exception.
     */
    @Tool(name = "error_tool", description = "A tool that always throws an error")
    public String errorTool(@ToolParam(description = "Error message") String message) {
        throw new RuntimeException("Tool error: " + message);
    }

    /**
     * Tool with multiple parameters.
     */
    @Tool(name = "multi_param", description = "Tool with multiple parameters")
    public String multiParam(
            @ToolParam(description = "String parameter") String str,
            @ToolParam(description = "Number parameter") int num,
            @ToolParam(description = "Boolean parameter") boolean flag) {
        return String.format("str=%s, num=%d, flag=%s", str, num, flag);
    }

    /**
     * Tool that simulates slow execution.
     */
    @Tool(name = "slow_tool", description = "A tool that takes time to execute")
    public String slowTool(@ToolParam(description = "Delay in milliseconds") int delayMs) {
        try {
            Thread.sleep(delayMs);
            return "Completed after " + delayMs + "ms";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
    }

    /**
     * Tool without parameters.
     */
    @Tool(name = "no_param", description = "Tool without parameters")
    public String noParamTool() {
        return "No parameters";
    }

    /**
     * Tool that returns complex object.
     */
    @Tool(name = "complex_return", description = "Tool that returns complex data")
    public Object complexReturn(@ToolParam(description = "Return type") String type) {
        switch (type) {
            case "string":
                return "test string";
            case "number":
                return 42;
            case "boolean":
                return true;
            case "array":
                return new int[] {1, 2, 3};
            default:
                return null;
        }
    }
}
