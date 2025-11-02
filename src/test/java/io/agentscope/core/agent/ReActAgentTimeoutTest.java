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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.TimeoutConfig;
import io.agentscope.core.model.ToolSchema;
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
    @DisplayName("Should timeout when agent call exceeds configured agentCallTimeout")
    void testAgentCallTimeout() {
        // Create a slow model that delays 5 seconds
        Model slowModel = createSlowModel(Duration.ofSeconds(5));

        TimeoutConfig timeoutConfig =
                TimeoutConfig.builder().agentCallTimeout(Duration.ofMillis(100)).build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(slowModel)
                        .memory(new InMemoryMemory())
                        .timeoutConfig(timeoutConfig)
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should timeout
        StepVerifier.create(agent.call(testMsg))
                .expectErrorMatches(
                        error ->
                                error instanceof RuntimeException
                                        && error.getMessage().contains("Agent call timeout"))
                .verify();
    }

    @Test
    @DisplayName("Should complete successfully when agent call is within timeout")
    void testAgentCallWithinTimeout() {
        Model fastModel = createFastModel();

        TimeoutConfig timeoutConfig =
                TimeoutConfig.builder().agentCallTimeout(Duration.ofSeconds(10)).build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(fastModel)
                        .memory(new InMemoryMemory())
                        .timeoutConfig(timeoutConfig)
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should complete successfully
        Msg response = agent.call(testMsg).block();

        assertNotNull(response);
        assertNotNull(response.getContent());
    }

    @Test
    @DisplayName("Should not apply timeout when timeoutConfig is null")
    void testNoTimeoutWhenNull() {
        Model slowModel = createSlowModel(Duration.ofMillis(500));

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(slowModel)
                        .memory(new InMemoryMemory())
                        // No timeoutConfig
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should complete successfully even if slow (no timeout configured)
        Msg response = agent.call(testMsg).block();

        assertNotNull(response);
    }

    @Test
    @DisplayName("Should not apply timeout when agentCallTimeout is null")
    void testNoTimeoutWhenAgentCallTimeoutNull() {
        Model slowModel = createSlowModel(Duration.ofMillis(500));

        TimeoutConfig timeoutConfig =
                TimeoutConfig.builder()
                        .toolExecutionTimeout(Duration.ofSeconds(30))
                        // agentCallTimeout is null
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(slowModel)
                        .memory(new InMemoryMemory())
                        .timeoutConfig(timeoutConfig)
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should complete successfully (no agent call timeout configured)
        Msg response = agent.call(testMsg).block();

        assertNotNull(response);
    }

    @Test
    @DisplayName("Should timeout tool execution when exceeds toolExecutionTimeout")
    void testToolExecutionTimeout() {
        // Create a model that returns a tool call to slow_tool
        Model modelWithToolCall = createModelWithToolCall();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SlowTools());

        TimeoutConfig timeoutConfig =
                TimeoutConfig.builder().toolExecutionTimeout(Duration.ofMillis(100)).build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(modelWithToolCall)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .timeoutConfig(timeoutConfig)
                        .build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Call slow_tool").build())
                        .build();

        // Tool execution should timeout
        StepVerifier.create(agent.call(testMsg))
                .expectErrorMatches(
                        error ->
                                error instanceof RuntimeException
                                        && error.getMessage().contains("Tool execution timeout"))
                .verify();
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
                                null));
            }

            @Override
            public String getModelName() {
                return "model-with-tool-call";
            }
        };
    }

    // Test tools with slow execution

    public static class SlowTools {
        @io.agentscope.core.tool.Tool(description = "A slow tool for testing timeout")
        public String slow_tool(@io.agentscope.core.tool.ToolParam(name = "input") String input) {
            try {
                Thread.sleep(5000); // Sleep for 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Slow tool result: " + input;
        }
    }
}
