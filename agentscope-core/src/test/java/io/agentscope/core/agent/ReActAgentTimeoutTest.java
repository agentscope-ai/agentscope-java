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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for ReActAgent timeout functionality.
 */
@DisplayName("ReActAgent Timeout Tests")
class ReActAgentTimeoutTest {

    @Test
    @DisplayName("Should timeout tool execution when exceeds toolExecutionTimeout")
    void testToolExecutionTimeout() {
        // Create a model that returns a tool call first, then a final response
        Model modelWithToolCall = createModelWithToolCallThenResponse();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SlowTools());

        ExecutionConfig toolExecutionConfig =
                ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(modelWithToolCall)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .toolExecutionConfig(toolExecutionConfig)
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Call slow_tool").build())
                        .build();

        // Tool execution should timeout and return error in tool result
        StepVerifier.create(agent.call(testMsg))
                .assertNext(
                        response -> {
                            // Agent should return a response (not error)
                            assertNotNull(response);
                            // Check memory contains tool result with timeout error
                            List<Msg> messages = agent.getMemory().getMessages();
                            boolean foundTimeoutError = false;
                            for (Msg msg : messages) {
                                if (msg.getRole() == MsgRole.TOOL) {
                                    // Check content blocks for ToolResultBlock
                                    for (ContentBlock block : msg.getContent()) {
                                        if (block instanceof ToolResultBlock) {
                                            ToolResultBlock toolResult = (ToolResultBlock) block;
                                            // Check the output of the ToolResultBlock
                                            for (ContentBlock output : toolResult.getOutput()) {
                                                if (output instanceof TextBlock) {
                                                    String text = ((TextBlock) output).getText();
                                                    if (text != null
                                                            && (text.contains(
                                                                            "Tool execution"
                                                                                    + " timeout")
                                                                    || text.contains("timeout"))) {
                                                        foundTimeoutError = true;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        if (foundTimeoutError) {
                                            break;
                                        }
                                    }
                                    if (foundTimeoutError) {
                                        break;
                                    }
                                }
                            }
                            assertTrue(
                                    foundTimeoutError,
                                    "Expected to find tool timeout error in memory");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should not apply timeout when toolExecutionConfig is null")
    void testNoTimeoutWhenToolExecutionConfigNull() {
        Model modelWithToolCall = createModelWithToolCall();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FastTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(modelWithToolCall)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        // No toolExecutionConfig
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Call fast_tool").build())
                        .build();

        // Should complete successfully even without timeout config
        Msg response = agent.call(testMsg).block();

        assertNotNull(response);
    }

    // Helper methods

    /**
     * Creates a mock model that delays responses by the specified duration.
     * Useful for testing agent call timeout behavior.
     *
     * @param delay the delay duration before responding
     * @return a Model instance that delays responses
     */
    private Model createSlowModel(Duration delay) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Mono.delay(delay)
                        .thenMany(
                                Flux.just(
                                        new ChatResponse(
                                                "test-id",
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Response from slow model")
                                                                .build()),
                                                null,
                                                null,
                                                null)));
            }

            @Override
            public String getModelName() {
                return "slow-model";
            }
        };
    }

    /**
     * Creates a mock model that responds immediately without delay.
     * Returns a simple text response without tool calls.
     *
     * @return a Model instance that responds immediately
     */
    private Model createFastModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        new ChatResponse(
                                "test-id",
                                List.of(
                                        TextBlock.builder()
                                                .text("Response from fast model")
                                                .build()),
                                null,
                                null,
                                null));
            }

            @Override
            public String getModelName() {
                return "fast-model";
            }
        };
    }

    /**
     * Creates a mock model that returns a tool call in its response.
     * The response includes a ToolUseBlock that triggers tool execution.
     * Useful for testing tool execution timeout behavior.
     *
     * @return a Model instance that returns a tool call to slow_tool
     */
    private Model createModelWithToolCall() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                // Return a response with a tool call to slow_tool
                return Flux.just(
                        new ChatResponse(
                                "test-id",
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("tool-call-1")
                                                .name("slow_tool")
                                                .input(Map.of("input", "test"))
                                                .build()),
                                null,
                                null,
                                null));
            }

            @Override
            public String getModelName() {
                return "model-with-tool-call";
            }
        };
    }

    /**
     * Creates a mock model that returns a tool call on first invocation,
     * then returns a final text response on subsequent invocations.
     * This prevents infinite loops in tests where tools fail.
     *
     * @return a Model instance that handles multiple reasoning rounds
     */
    private Model createModelWithToolCallThenResponse() {
        return new Model() {
            private final java.util.concurrent.atomic.AtomicBoolean firstCall =
                    new java.util.concurrent.atomic.AtomicBoolean(true);

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (firstCall.compareAndSet(true, false)) {
                    // First call: return a tool call
                    return Flux.just(
                            new ChatResponse(
                                    "test-id-1",
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("tool-call-1")
                                                    .name("slow_tool")
                                                    .input(Map.of("input", "test"))
                                                    .build()),
                                    null,
                                    null,
                                    null));
                } else {
                    // Subsequent calls: return final text response
                    return Flux.just(
                            new ChatResponse(
                                    "test-id-2",
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Tool execution failed due to timeout")
                                                    .build()),
                                    null,
                                    null,
                                    null));
                }
            }

            @Override
            public String getModelName() {
                return "model-with-tool-call-then-response";
            }
        };
    }

    // Test tools with slow execution

    public static class SlowTools {
        @Tool(description = "A slow tool for testing timeout")
        public String slow_tool(@ToolParam(name = "input") String input) {
            try {
                Thread.sleep(5000); // Sleep for 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Slow tool result: " + input;
        }
    }

    // Test tools with fast execution

    public static class FastTools {
        @Tool(description = "A fast tool for testing")
        public String fast_tool(@ToolParam(name = "input") String input) {
            return "Fast tool result: " + input;
        }
    }
}
