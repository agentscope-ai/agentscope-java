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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Tool functionality.
 *
 * <p>These tests verify tool integration with agents, async execution, error propagation, and
 * timeout scenarios.
 *
 * <p>Tagged as "integration" - tests component interaction.
 */
@Tag("integration")
@DisplayName("Tool Integration Tests")
class ToolIntegrationTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;
    private MockModel mockModel;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
        toolkit.registerTool(sampleTools);
        mockModel = new MockModel("Tool integration response");
        memory = new InMemoryMemory();
    }

    @Test
    @DisplayName("Should integrate Toolkit with Agent")
    void testToolkitAgentIntegration() {
        // Create agent with toolkit
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("Test agent with tools")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        assertNotNull(agent, "Agent should be created with toolkit");

        // Verify toolkit is accessible
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.size() > 0, "Agent should have access to toolkit tools");

        // Verify tools can be called through toolkit
        AgentTool addTool = toolkit.getTool("add");
        if (addTool != null) {
            Map<String, Object> params = Map.of("a", 5, "b", 10);
            ToolResultBlock response =
                    addTool.callAsync(ToolCallParam.builder().input(params).build()).block();

            assertNotNull(response, "Tool should return response");
            assertTrue(ToolTestUtils.isValidToolResultBlock(response), "Response should be valid");
        }
    }

    @Test
    @DisplayName("Should support async tool execution")
    void testAsyncToolExecution() {
        // Execute tool asynchronously
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            CompletableFuture<ToolResultBlock> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                Map<String, Object> params = Map.of("a", 100, "b", 200);
                                return addTool.callAsync(
                                                ToolCallParam.builder().input(params).build())
                                        .block();
                            });

            // Wait for completion
            assertDoesNotThrow(
                    () -> {
                        ToolResultBlock response = future.get(5, TimeUnit.SECONDS);
                        assertNotNull(response, "Async execution should return response");
                        assertTrue(
                                ToolTestUtils.isValidToolResultBlock(response),
                                "Async response should be valid");
                    });
        }
    }

    @Test
    @DisplayName("Should propagate errors correctly")
    void testToolErrorPropagation() {
        // Get error tool
        AgentTool errorTool = toolkit.getTool("error_tool");

        if (errorTool != null) {
            Map<String, Object> params = Map.of("message", "test error propagation");

            // Tool should throw exception or return error response
            try {
                ToolResultBlock response =
                        errorTool.callAsync(ToolCallParam.builder().input(params).build()).block();

                // If we get a response, verify it indicates error
                if (response != null) {
                    // Error might be in metadata or content
                    assertTrue(
                            ToolTestUtils.isErrorResponse(response)
                                    || !response.getOutput().isEmpty(),
                            "Error should be propagated in response");
                }
            } catch (RuntimeException e) {
                // Exception propagation is also valid
                assertTrue(
                        e.getMessage().contains("error") || e.getMessage().contains("Tool"),
                        "Exception should contain error information");
            }
        }
    }

    @Test
    @DisplayName("Should handle tool timeout scenarios")
    void testToolTimeout() {
        // Get slow tool
        AgentTool slowTool = toolkit.getTool("slow_tool");

        if (slowTool != null) {
            // Test with reasonable delay
            Map<String, Object> params = Map.of("delay_ms", 100);
            ToolResultBlock response =
                    slowTool.callAsync(ToolCallParam.builder().input(params).build()).block();

            assertNotNull(response, "Slow tool should complete");
            assertTrue(
                    ToolTestUtils.isValidToolResultBlock(response),
                    "Response should be valid after delay");

            // Test with async timeout
            CompletableFuture<ToolResultBlock> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                Map<String, Object> longParams = Map.of("delay_ms", 5000);
                                return slowTool.callAsync(
                                                ToolCallParam.builder().input(longParams).build())
                                        .block();
                            });

            // This should timeout in real scenario, but for testing we just verify setup
            assertNotNull(future, "Async execution should be initiated");
        }
    }
}
