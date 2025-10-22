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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

    private String extractFirstText(ToolResultBlock response) {
        assertTrue(
                ToolTestUtils.isValidToolResultBlock(response),
                "Tool response should contain content");
        ContentBlock contentBlock = response.getOutput();
        return ((TextBlock) contentBlock).getText();
    }
}
