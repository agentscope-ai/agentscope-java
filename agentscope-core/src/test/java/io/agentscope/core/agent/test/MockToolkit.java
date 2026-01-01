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
package io.agentscope.core.agent.test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Mock Toolkit implementation for testing.
 * <p>
 * This mock provides a configurable toolkit that can simulate tool calls
 * without executing actual tool code.
 */
public class MockToolkit extends Toolkit {

    private final Map<String, Function<Map<String, Object>, String>> toolBehaviors;
    private final List<String> toolCallHistory;
    private int callCount = 0;

    public MockToolkit() {
        super();
        this.toolBehaviors = new HashMap<>();
        this.toolCallHistory = new ArrayList<>();
        registerDefaultTools();
    }

    /**
     * Register a simple tool that returns a fixed result.
     */
    public MockToolkit withTool(String toolName, String result) {
        toolBehaviors.put(toolName, args -> result);
        registerMockTool(toolName, "Mock tool: " + toolName);
        return this;
    }

    /**
     * Register a tool with custom behavior.
     */
    public MockToolkit withTool(String toolName, Function<Map<String, Object>, String> behavior) {
        toolBehaviors.put(toolName, behavior);
        registerMockTool(toolName, "Mock tool with custom behavior: " + toolName);
        return this;
    }

    /**
     * Register a tool that throws an error.
     */
    public MockToolkit withErrorTool(String toolName, String errorMessage) {
        toolBehaviors.put(
                toolName,
                args -> {
                    throw new RuntimeException(errorMessage);
                });
        registerMockTool(toolName, "Mock tool that throws error: " + toolName);
        return this;
    }

    /**
     * Register default test tools.
     */
    private void registerDefaultTools() {
        // Calculator tool
        withTool(
                TestConstants.CALCULATOR_TOOL_NAME,
                args -> {
                    String operation = (String) args.getOrDefault("operation", "add");
                    double a = ((Number) args.getOrDefault("a", 0)).doubleValue();
                    double b = ((Number) args.getOrDefault("b", 0)).doubleValue();

                    double result =
                            switch (operation) {
                                case "add" -> a + b;
                                case "subtract" -> a - b;
                                case "multiply" -> a * b;
                                case "divide" -> b != 0 ? a / b : Double.NaN;
                                default -> 0.0;
                            };

                    return String.valueOf(result);
                });

        // Finish tool (for ReAct agents)
        withTool(
                TestConstants.FINISH_TOOL_NAME,
                args -> {
                    String response = (String) args.getOrDefault("response", "");
                    return response;
                });

        // Simple test tool
        withTool(TestConstants.TEST_TOOL_NAME, args -> TestConstants.TEST_TOOL_CALL_RESULT);
    }

    /**
     * Register a mock tool with the toolkit.
     */
    private void registerMockTool(String name, String description) {
        AgentTool tool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return name;
                    }

                    @Override
                    public String getDescription() {
                        return description;
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<String, Object>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.fromCallable(
                                () -> {
                                    callCount++;
                                    toolCallHistory.add(name);

                                    try {
                                        Function<Map<String, Object>, String> behavior =
                                                toolBehaviors.get(name);
                                        if (behavior != null) {
                                            String result = behavior.apply(param.getInput());
                                            return ToolResultBlock.of(
                                                    TextBlock.builder().text(result).build());
                                        }
                                        return ToolResultBlock.of(
                                                TextBlock.builder()
                                                        .text("Default mock result for " + name)
                                                        .build());
                                    } catch (Exception e) {
                                        return ToolResultBlock.error(e.getMessage());
                                    }
                                });
                    }
                };

        registerTool(tool);
    }

    /**
     * Get the total number of tool calls made.
     */
    public int getCallCount() {
        return callCount;
    }

    /**
     * Get the history of tool calls (tool names in order).
     */
    public List<String> getToolCallHistory() {
        return new ArrayList<>(toolCallHistory);
    }

    /**
     * Check if a specific tool was called.
     */
    public boolean wasToolCalled(String toolName) {
        return toolCallHistory.contains(toolName);
    }

    /**
     * Get the number of times a specific tool was called.
     */
    public int getToolCallCount(String toolName) {
        return (int) toolCallHistory.stream().filter(name -> name.equals(toolName)).count();
    }

    /**
     * Reset the mock state.
     */
    public void reset() {
        this.callCount = 0;
        this.toolCallHistory.clear();
    }
}
