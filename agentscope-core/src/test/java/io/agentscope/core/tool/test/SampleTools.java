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
package io.agentscope.core.tool.test;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

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
            @ToolParam(name = "a", description = "First number") int a,
            @ToolParam(name = "b", description = "Second number") int b) {
        return a + b;
    }

    /**
     * String manipulation tool - concatenate strings.
     */
    @Tool(name = "concat", description = "Concatenate two strings")
    public String concat(
            @ToolParam(name = "str1", description = "First string") String str1,
            @ToolParam(name = "str2", description = "Second string") String str2) {
        return str1 + str2;
    }

    /**
     * Tool that throws exception.
     */
    @Tool(name = "error_tool", description = "A tool that always throws an error")
    public String errorTool(
            @ToolParam(name = "message", description = "Error message") String message) {
        throw new RuntimeException("Tool error: " + message);
    }

    /**
     * Tool with multiple parameters.
     */
    @Tool(name = "multi_param", description = "Tool with multiple parameters")
    public String multiParam(
            @ToolParam(name = "str", description = "String parameter") String str,
            @ToolParam(name = "num", description = "Number parameter") int num,
            @ToolParam(name = "flag", description = "Boolean parameter") boolean flag) {
        return String.format("str=%s, num=%d, flag=%s", str, num, flag);
    }

    /**
     * Tool that simulates slow execution.
     */
    @Tool(name = "slow_tool", description = "A tool that takes time to execute")
    public String slowTool(
            @ToolParam(name = "delayMs", description = "Delay in milliseconds") int delayMs) {
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
    public Object complexReturn(
            @ToolParam(name = "type", description = "Return type") String type) {
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

    /**
     * Async tool using CompletableFuture - add two numbers.
     */
    @Tool(name = "async_add", description = "Asynchronously add two numbers")
    public CompletableFuture<Integer> asyncAdd(
            @ToolParam(name = "a", description = "First number") int a,
            @ToolParam(name = "b", description = "Second number") int b) {
        return CompletableFuture.supplyAsync(() -> a + b);
    }

    /**
     * Async tool using Mono - concatenate strings.
     */
    @Tool(name = "async_concat", description = "Asynchronously concatenate two strings")
    public Mono<String> asyncConcat(
            @ToolParam(name = "str1", description = "First string") String str1,
            @ToolParam(name = "str2", description = "Second string") String str2) {
        return Mono.fromCallable(() -> str1 + str2);
    }

    /**
     * Async tool using Mono that simulates delay.
     */
    @Tool(name = "async_delayed", description = "Async tool with simulated delay")
    public Mono<String> asyncDelayed(
            @ToolParam(name = "delayMs", description = "Delay in milliseconds") int delayMs) {
        return Mono.delay(java.time.Duration.ofMillis(delayMs))
                .map(tick -> "Completed after " + delayMs + "ms");
    }

    /**
     * Async tool that throws error.
     */
    @Tool(name = "async_error", description = "Async tool that fails")
    public CompletableFuture<String> asyncError(
            @ToolParam(name = "message", description = "Error message") String message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Async error: " + message));
        return future;
    }
}
