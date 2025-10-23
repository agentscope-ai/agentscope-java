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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for ParallelToolExecutor.
 *
 * <p>These tests verify execution paths that invoke the executor itself so regressions in
 * scheduling, ordering, timeout handling, and error propagation are detected.
 */
@Tag("unit")
@DisplayName("ParallelToolExecutor Unit Tests")
class ParallelToolExecutorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private Toolkit toolkit;
    private ParallelToolExecutor executor;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        toolkit.registerTool(new SampleTools());
        executor = new ParallelToolExecutor(toolkit);
    }

    @Test
    @DisplayName("Should execute multiple tool calls in parallel")
    void shouldExecuteToolsInParallel() {
        ToolUseBlock addCall =
                ToolUseBlock.builder()
                        .id("call-add")
                        .name("add")
                        .input(Map.of("a", 10, "b", 20))
                        .build();
        ToolUseBlock concatCall =
                ToolUseBlock.builder()
                        .id("call-concat")
                        .name("concat")
                        .input(Map.of("str1", "Hello", "str2", "World"))
                        .build();

        List<ToolResultBlock> responses =
                executor.executeTools(List.of(addCall, concatCall), true).block(TIMEOUT);

        assertNotNull(responses, "Executor should return responses for tool calls");
        assertEquals(2, responses.size(), "All tool calls should be executed");

        Map<String, ToolResultBlock> responsesById =
                responses.stream()
                        .collect(Collectors.toMap(ToolResultBlock::getId, Function.identity()));

        ToolResultBlock addResponse = responsesById.get("call-add");
        ToolResultBlock concatResponse = responsesById.get("call-concat");

        assertNotNull(addResponse, "Add tool response should be present");
        assertEquals("30", extractFirstText(addResponse), "Add tool result mismatch");

        assertNotNull(concatResponse, "Concat tool response should be present");
        assertEquals(
                "\"HelloWorld\"", extractFirstText(concatResponse), "Concat tool result mismatch");
    }

    @Test
    @DisplayName("Should maintain call order when executing sequentially")
    void shouldExecuteToolsSequentiallyInOrder() {
        ToolUseBlock firstCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("add")
                        .input(Map.of("a", 1, "b", 2))
                        .build();
        ToolUseBlock secondCall =
                ToolUseBlock.builder()
                        .id("call-2")
                        .name("concat")
                        .input(Map.of("str1", "A", "str2", "B"))
                        .build();

        List<ToolResultBlock> responses =
                executor.executeTools(List.of(firstCall, secondCall), false).block(TIMEOUT);

        assertNotNull(responses, "Sequential execution should return responses");
        assertEquals(2, responses.size(), "Expected two responses in order");
        assertEquals("call-1", responses.get(0).getId(), "First response should match first call");
        assertEquals(
                "call-2", responses.get(1).getId(), "Second response should match second call");
        assertEquals("3", extractFirstText(responses.get(0)), "First response payload mismatch");
        assertEquals(
                "\"AB\"", extractFirstText(responses.get(1)), "Second response payload mismatch");
    }

    @Test
    @DisplayName("Should wrap tool errors inside executor response")
    void shouldReturnErrorWhenToolThrows() {
        ToolUseBlock errorCall =
                ToolUseBlock.builder()
                        .id("call-error")
                        .name("error_tool")
                        .input(Map.of("message", "test failure"))
                        .build();

        List<ToolResultBlock> responses =
                executor.executeTools(List.of(errorCall), true).block(TIMEOUT);

        assertNotNull(responses, "Executor should return an error response");
        assertEquals(1, responses.size(), "Single failing call should yield one response");

        String content = extractFirstText(responses.get(0));
        assertEquals(
                "Error: Tool execution failed: Tool error: test failure",
                content,
                "Error message should be wrapped by executor");
    }

    @Test
    @DisplayName("Should expose executor statistics")
    void shouldExposeExecutorStats() {
        Map<String, Object> stats = executor.getExecutorStats();

        assertNotNull(stats, "Executor stats should be available");
        assertTrue(
                stats.containsKey("executorType") || stats.containsKey("poolSize"),
                "Stats map should not be empty");
    }

    @Test
    @DisplayName("Should NOT specially handle InterruptedException in error path")
    void testToolErrorWithoutInterruptSpecialCase() {
        // Create a tool that throws RuntimeException with InterruptedException cause
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "interrupted_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that simulates interrupted error";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(Map<String, Object> arguments) {
                        return Mono.error(
                                new RuntimeException(
                                        "Execution error",
                                        new InterruptedException("Thread interrupted")));
                    }
                });

        ToolUseBlock interruptedCall =
                ToolUseBlock.builder()
                        .id("call-interrupt")
                        .name("interrupted_tool")
                        .input(Map.of())
                        .build();

        List<ToolResultBlock> responses =
                executor.executeTools(List.of(interruptedCall), true).block(TIMEOUT);

        assertNotNull(responses, "Should return error response");
        assertEquals(1, responses.size(), "Should have one response");

        String errorText = extractFirstText(responses.get(0));
        // Should be standard error format, not special interrupted result
        assertTrue(
                errorText.startsWith("Error:"), "Should use standard error format: " + errorText);
        assertTrue(
                errorText.contains("Tool execution failed")
                        || errorText.contains("Execution error"),
                "Should contain error message");
    }

    @Test
    @DisplayName("Should handle concurrent tool execution with errors")
    void testConcurrentToolExecutionWithErrors() {
        // Register a tool that sometimes fails (thread-safe counter)
        AtomicInteger callCount = new AtomicInteger(0);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "flaky_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that fails on first call";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(Map<String, Object> arguments) {
                        int count = callCount.getAndIncrement();
                        if (count == 0) {
                            return Mono.error(new RuntimeException("First call failed"));
                        }
                        return Mono.just(
                                ToolResultBlock.of(
                                        TextBlock.builder()
                                                .text("Call " + count + " succeeded")
                                                .build()));
                    }
                });

        // Execute multiple calls in parallel
        ToolUseBlock call1 =
                ToolUseBlock.builder().id("call-1").name("flaky_tool").input(Map.of()).build();
        ToolUseBlock call2 =
                ToolUseBlock.builder().id("call-2").name("flaky_tool").input(Map.of()).build();
        ToolUseBlock call3 =
                ToolUseBlock.builder()
                        .id("call-3")
                        .name("add")
                        .input(Map.of("a", 1, "b", 2))
                        .build();

        List<ToolResultBlock> responses =
                executor.executeTools(List.of(call1, call2, call3), true).block(TIMEOUT);

        assertNotNull(responses, "Should return responses");
        assertEquals(3, responses.size(), "Should have three responses");

        // Count how many calls succeeded vs failed
        long errorCount =
                responses.stream().filter(r -> extractFirstText(r).startsWith("Error:")).count();
        long successCount =
                responses.stream()
                        .filter(
                                r ->
                                        extractFirstText(r).contains("succeeded")
                                                || extractFirstText(r).equals("3"))
                        .count();

        // Exactly one flaky_tool call should fail (the first one to execute)
        // and two should succeed (one flaky_tool + one add)
        assertEquals(1, errorCount, "Exactly one call should fail");
        assertEquals(2, successCount, "Exactly two calls should succeed");
    }

    @Test
    @DisplayName("Should format all error messages consistently")
    void testErrorMessageFormat() {
        // Register various failing tools
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "null_pointer_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that throws NPE";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", new HashMap<>());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(Map<String, Object> arguments) {
                        return Mono.error(new NullPointerException("Null value encountered"));
                    }
                });

        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "illegal_arg_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Tool that throws IllegalArgumentException";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", new HashMap<>());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(Map<String, Object> arguments) {
                        return Mono.error(
                                new IllegalArgumentException("Invalid argument provided"));
                    }
                });

        // Execute both tools
        ToolUseBlock npeCall =
                ToolUseBlock.builder().id("npe").name("null_pointer_tool").input(Map.of()).build();
        ToolUseBlock argCall =
                ToolUseBlock.builder().id("arg").name("illegal_arg_tool").input(Map.of()).build();

        List<ToolResultBlock> responses =
                executor.executeTools(List.of(npeCall, argCall), true).block(TIMEOUT);

        assertNotNull(responses, "Should return responses");
        assertEquals(2, responses.size(), "Should have two responses");

        // Both should follow same error format
        for (int i = 0; i < responses.size(); i++) {
            String errorText = extractFirstText(responses.get(i));
            assertTrue(
                    errorText.startsWith("Error:"),
                    "Error " + i + " should start with 'Error:': " + errorText);
            assertTrue(
                    errorText.contains("Tool execution failed")
                            || errorText.contains("encountered")
                            || errorText.contains("provided"),
                    "Error " + i + " should contain meaningful message: " + errorText);
        }
    }

    private String extractFirstText(ToolResultBlock response) {
        assertTrue(
                ToolTestUtils.isValidToolResultBlock(response),
                "Tool response should contain content");
        ContentBlock contentBlock = response.getOutput();
        return ((TextBlock) contentBlock).getText();
    }
}
