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
    void testStructuredOutputToolBased() {
        Memory memory = new InMemoryMemory();

        // Create a mock model that returns:
        // 1. First call: tool call for generate_response
        // 2. Second call (after tool execution): simple text response (finished)
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
                            // Check if we have any TOOL role messages (tool execution results)
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
                                // First call: return tool use for generate_response
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
                                // Second call (after tool execution): return simple text
                                // (no more tool calls, indicating we're done)
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

        // Call agent and extract structured data from response message
        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getMetadata());

        // Extract structured data from metadata
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);

        // Verify
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);
    }

    @Test
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
                            // Check if we have any TOOL role messages
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
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
                                // Second call: return text (finished)
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

        // Call agent and extract structured data from response message
        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getMetadata());

        // Extract structured data from metadata
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);

        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);
    }
}
