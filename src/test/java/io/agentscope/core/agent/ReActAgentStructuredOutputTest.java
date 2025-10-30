/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCapabilities;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReActAgentStructuredOutputTest {

    private Toolkit toolkit;

    static class WeatherResponse {
        public String location;
        public String temperature;
        public String condition;
    }

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Fix mock model to properly simulate tool execution flow")
    void testStructuredOutputToolBased() {
        Memory memory = new InMemoryMemory();

        // Create a mock model that returns tool call for generate_response
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            // Count tool result messages to determine which response to send
                            long toolResultCount =
                                    msgs.stream()
                                            .filter(
                                                    m ->
                                                            m.getRole() == MsgRole.TOOL
                                                                    || (m.getRole()
                                                                                    == MsgRole
                                                                                            .ASSISTANT
                                                                            && m
                                                                                            .getFirstContentBlock()
                                                                                    instanceof
                                                                                    io.agentscope
                                                                                            .core
                                                                                            .message
                                                                                            .ToolResultBlock))
                                            .count();

                            if (toolResultCount == 0) {
                                // First call: return tool use
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // After tool execution: return simple text (done)
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Response generated")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        // Create agent with TOOL_BASED strategy
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .memory(memory)
                        .structuredOutputStrategy(StructuredOutputStrategy.TOOL_BASED)
                        .build();

        // Execute structured output call
        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        WeatherResponse result = agent.call(inputMsg, WeatherResponse.class).block();

        // Verify
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);
    }

    @Test
    void testStructuredOutputNativeNotImplemented() {
        // Create a model with native structured output capability
        Model modelWithNative =
                new Model() {
                    @Override
                    public reactor.core.publisher.Flux<ChatResponse> stream(
                            List<Msg> messages,
                            List<io.agentscope.core.model.ToolSchema> tools,
                            io.agentscope.core.model.GenerateOptions options) {
                        return reactor.core.publisher.Flux.just(
                                ChatResponse.builder()
                                        .id("msg_123")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("test response")
                                                                .build()))
                                        .usage(new ChatUsage(10, 20, 30))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "test-model-native";
                    }

                    @Override
                    public ModelCapabilities getCapabilities() {
                        return ModelCapabilities.builder()
                                .supportsNativeStructuredOutput(true)
                                .build();
                    }
                };

        Memory memory = new InMemoryMemory();

        // Create agent with NATIVE strategy
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(modelWithNative)
                        .toolkit(toolkit)
                        .memory(memory)
                        .structuredOutputStrategy(StructuredOutputStrategy.NATIVE)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        // Should throw UnsupportedOperationException for now
        assertThrows(
                UnsupportedOperationException.class,
                () -> agent.call(inputMsg, WeatherResponse.class).block());
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Fix mock model to properly simulate tool execution flow")
    void testStructuredOutputAutoFallbackToToolBased() {
        Memory memory = new InMemoryMemory();

        // Create a mock model that returns tool call, then text
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            long toolResultCount =
                                    msgs.stream().filter(m -> m.getRole() == MsgRole.TOOL).count();

                            if (toolResultCount == 0) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        // Create agent with AUTO strategy (default)
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        // Should fallback to tool-based and succeed
        WeatherResponse result = agent.call(inputMsg, WeatherResponse.class).block();

        assertNotNull(result);
        assertEquals("San Francisco", result.location);
    }

    @Test
    void testStructuredOutputNoSupport() {
        // Create a model with no tool calling support
        Model modelNoSupport =
                new Model() {
                    @Override
                    public reactor.core.publisher.Flux<ChatResponse> stream(
                            List<Msg> messages,
                            List<io.agentscope.core.model.ToolSchema> tools,
                            io.agentscope.core.model.GenerateOptions options) {
                        return reactor.core.publisher.Flux.just(
                                ChatResponse.builder()
                                        .id("msg_123")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("test response")
                                                                .build()))
                                        .usage(new ChatUsage(10, 20, 30))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "basic-model";
                    }

                    @Override
                    public ModelCapabilities getCapabilities() {
                        return ModelCapabilities.builder()
                                .supportsNativeStructuredOutput(false)
                                .supportsToolCalling(false)
                                .build();
                    }
                };

        Memory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(modelNoSupport)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        // Should throw exception as no strategy is available
        assertThrows(
                UnsupportedOperationException.class,
                () -> agent.call(inputMsg, WeatherResponse.class).block());
    }
}
