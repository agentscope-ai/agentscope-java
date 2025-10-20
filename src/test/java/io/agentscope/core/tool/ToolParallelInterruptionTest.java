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

import io.agentscope.core.exception.ToolInterrupter;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolkitConfig.ParallelInterruptStrategy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for parallel tool interruption functionality.
 */
@DisplayName("Tool Parallel Interruption Tests")
class ToolParallelInterruptionTest {

    private Toolkit toolkit;

    @Tool(name = "fast_tool", description = "A fast tool")
    public String fastTool(@ToolParam(name = "input", description = "input") String input) {
        return "fast_result";
    }

    @Tool(name = "slow_tool", description = "A slow tool")
    public String slowTool(@ToolParam(name = "input", description = "input") String input)
            throws InterruptedException {
        Thread.sleep(100);
        return "slow_result";
    }

    @Tool(name = "interrupting_tool", description = "A tool that interrupts itself")
    public String interruptingTool(@ToolParam(name = "input", description = "input") String input) {
        ToolInterrupter.interrupt("Tool interrupted itself");
        return "should_not_reach_here";
    }

    @AfterEach
    void cleanup() {
        ToolInterrupter.reset();
    }

    @BeforeEach
    void setUp() {
        // Will be set up in each test
    }

    @Test
    @DisplayName("Should execute tools in parallel successfully")
    void testParallelExecutionWithoutInterruption() {
        ToolkitConfig config = ToolkitConfig.builder().parallel(true).build();

        toolkit = new Toolkit(config);
        toolkit.registerTool(this);

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("fast_tool")
                        .input(new HashMap<>())
                        .build());
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("slow_tool")
                        .input(new HashMap<>())
                        .build());

        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        assertEquals(2, responses.size());
    }

    @Test
    @DisplayName("Should handle INTERRUPT_SINGLE strategy")
    void testInterruptSingleStrategy() {
        ToolkitConfig config =
                ToolkitConfig.builder()
                        .parallel(true)
                        .parallelInterruptStrategy(ParallelInterruptStrategy.INTERRUPT_SINGLE)
                        .build();

        toolkit = new Toolkit(config);
        toolkit.registerTool(this);

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("interrupting_tool")
                        .input(new HashMap<>())
                        .build());
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("fast_tool")
                        .input(new HashMap<>())
                        .build());

        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        assertEquals(2, responses.size());

        // At least one should be interrupted
        boolean hasInterrupted = responses.stream().anyMatch(ToolResponse::isInterrupted);
        assertTrue(hasInterrupted, "At least one tool should be interrupted");
    }

    @Test
    @DisplayName("Should handle INTERRUPT_ALL strategy")
    void testInterruptAllStrategy() {
        ToolkitConfig config =
                ToolkitConfig.builder()
                        .parallel(true)
                        .parallelInterruptStrategy(ParallelInterruptStrategy.INTERRUPT_ALL)
                        .build();

        toolkit = new Toolkit(config);
        toolkit.registerTool(this);

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("interrupting_tool")
                        .input(new HashMap<>())
                        .build());
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("slow_tool")
                        .input(new HashMap<>())
                        .build());

        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        // With INTERRUPT_ALL, we get one interrupted response
        assertTrue(responses.size() >= 1);
        assertTrue(responses.get(0).isInterrupted());
    }

    @Test
    @DisplayName("Should execute tools sequentially when parallel=false")
    void testSequentialExecution() {
        ToolkitConfig config = ToolkitConfig.builder().parallel(false).build();

        toolkit = new Toolkit(config);
        toolkit.registerTool(this);

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("fast_tool")
                        .input(new HashMap<>())
                        .build());
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("slow_tool")
                        .input(new HashMap<>())
                        .build());

        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        assertEquals(2, responses.size());
    }

    @Test
    @DisplayName("Should handle interruption in sequential execution")
    void testSequentialInterruption() {
        ToolkitConfig config = ToolkitConfig.builder().parallel(false).build();

        toolkit = new Toolkit(config);
        toolkit.registerTool(this);

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("interrupting_tool")
                        .input(new HashMap<>())
                        .build());
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("fast_tool")
                        .input(new HashMap<>())
                        .build());

        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        // In sequential execution, first tool interrupts, second won't run
        assertTrue(responses.size() >= 1);
        assertTrue(responses.get(0).isInterrupted());
    }

    @Test
    @DisplayName("Should handle empty tool calls list")
    void testEmptyToolCalls() {
        ToolkitConfig config = ToolkitConfig.builder().parallel(true).build();

        toolkit = new Toolkit(config);
        toolkit.registerTool(this);

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        assertTrue(responses.isEmpty());
    }

    @Test
    @DisplayName("Should handle single tool call")
    void testSingleToolCall() {
        ToolkitConfig config = ToolkitConfig.builder().parallel(true).build();

        toolkit = new Toolkit(config);
        toolkit.registerTool(this);

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("fast_tool")
                        .input(new HashMap<>())
                        .build());

        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        assertEquals(1, responses.size());
        // Verify the response is successful (not interrupted)
        assertTrue(!responses.get(0).isInterrupted());
    }

    @Test
    @DisplayName("Should detect interrupted tool even when exception is caught")
    void testInterruptionDetectionAfterCatch() {
        // This test verifies that ThreadLocal state tracking works
        ToolkitConfig config = ToolkitConfig.builder().parallel(false).build();

        toolkit = new Toolkit(config);

        // Register a tool that catches the interruption exception
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "catching_tool", description = "Tool that catches interruption")
                    public String catchingTool(
                            @ToolParam(name = "input", description = "input") String input) {
                        try {
                            ToolInterrupter.interrupt("Test");
                        } catch (Exception e) {
                            // Tool catches the exception
                        }
                        return "should_not_return_this";
                    }
                });

        List<ToolUseBlock> toolCalls = new ArrayList<>();
        toolCalls.add(
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("catching_tool")
                        .input(new HashMap<>())
                        .build());

        List<ToolResponse> responses = toolkit.callTools(toolCalls).block();

        assertNotNull(responses);
        assertEquals(1, responses.size());
        // Framework should detect interruption even though tool caught the exception
        assertTrue(responses.get(0).isInterrupted());
    }
}
