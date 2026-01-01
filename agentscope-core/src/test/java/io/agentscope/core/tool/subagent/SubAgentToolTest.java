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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for SubAgentTool and related classes. */
@DisplayName("SubAgent Tool Tests")
class SubAgentToolTest {

    @Test
    @DisplayName("Should create SubAgentTool with default configuration")
    void testCreateWithDefaults() {
        // Create a mock agent
        Agent mockAgent = createMockAgent("TestAgent", "Test description");

        SubAgentTool tool = new SubAgentTool(() -> mockAgent, null);

        assertEquals("call_testagent", tool.getName());
        assertEquals("Test description", tool.getDescription());
        assertNotNull(tool.getParameters());
    }

    @Test
    @DisplayName("Should use agent name for tool name generation")
    void testToolNameGeneration() {
        Agent mockAgent = createMockAgent("Research Agent", "Research tasks");

        SubAgentTool tool = new SubAgentTool(() -> mockAgent, null);

        assertEquals("call_research_agent", tool.getName());
    }

    @Test
    @DisplayName("Should use custom tool name from config")
    void testCustomToolName() {
        Agent mockAgent = createMockAgent("TestAgent", "Test description");

        SubAgentConfig config =
                SubAgentConfig.builder()
                        .toolName("custom_tool")
                        .description("Custom description")
                        .build();

        SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

        assertEquals("custom_tool", tool.getName());
        assertEquals("Custom description", tool.getDescription());
    }

    @Test
    @DisplayName("Should generate correct schema")
    void testConversationSchema() {
        Agent mockAgent = createMockAgent("TestAgent", "Test");

        SubAgentTool tool = new SubAgentTool(() -> mockAgent, SubAgentConfig.defaults());

        Map<String, Object> schema = tool.getParameters();
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("message"));
        assertTrue(properties.containsKey("session_id"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("message"));
        assertFalse(required.contains("session_id"));
    }

    @Test
    @DisplayName("Should create new agent for each call but preserve state via session")
    void testConversationUsesSession() {
        AtomicInteger creationCount = new AtomicInteger(0);

        SubAgentProvider<Agent> provider =
                () -> {
                    creationCount.incrementAndGet();
                    Agent agent = mock(Agent.class);
                    when(agent.getName()).thenReturn("TestAgent");
                    when(agent.getDescription()).thenReturn("Test");
                    when(agent.call(any(List.class)))
                            .thenReturn(
                                    Mono.just(
                                            Msg.builder()
                                                    .role(MsgRole.ASSISTANT)
                                                    .content(
                                                            TextBlock.builder()
                                                                    .text("Response")
                                                                    .build())
                                                    .build()));
                    return agent;
                };

        SubAgentTool tool =
                new SubAgentTool(provider, SubAgentConfig.builder().forwardEvents(false).build());

        // First call - creates new session
        Map<String, Object> input1 = new HashMap<>();
        input1.put("message", "Hello");
        ToolUseBlock toolUse1 =
                ToolUseBlock.builder().id("1").name("call_testagent").input(input1).build();

        ToolResultBlock result1 =
                tool.callAsync(ToolCallParam.builder().toolUseBlock(toolUse1).input(input1).build())
                        .block();

        // Extract session_id from result
        String sessionId = extractSessionId(result1);
        assertNotNull(sessionId);

        // Second call with same session_id - creates new agent but loads state from session
        Map<String, Object> input2 = new HashMap<>();
        input2.put("message", "How are you?");
        input2.put("session_id", sessionId);
        ToolUseBlock toolUse2 =
                ToolUseBlock.builder().id("2").name("call_testagent").input(input2).build();

        tool.callAsync(ToolCallParam.builder().toolUseBlock(toolUse2).input(input2).build())
                .block();

        // Should have created 3 agents: 1 for initialization + 1 for first call + 1 for second call
        // Each call creates a new agent, but state is preserved via Session
        assertEquals(3, creationCount.get());
    }

    @Test
    @DisplayName("Should execute and return result with session_id")
    void testConversationExecution() {
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("TestAgent");
        when(mockAgent.getDescription()).thenReturn("Test agent");
        when(mockAgent.call(any(List.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder().text("Hello there!").build())
                                        .build()));

        SubAgentTool tool =
                new SubAgentTool(
                        () -> mockAgent, SubAgentConfig.builder().forwardEvents(false).build());

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Hello");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_testagent").input(input).build();

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().toolUseBlock(toolUse).input(input).build())
                        .block();

        assertNotNull(result);
        String text = extractText(result);
        assertTrue(text.contains("session_id:"));
        assertTrue(text.contains("Hello there!"));
    }

    @Test
    @DisplayName("Should register sub-agent via Toolkit")
    void testToolkitRegistration() {
        Agent mockAgent = createMockAgent("HelperAgent", "A helpful agent");

        Toolkit toolkit = new Toolkit();
        toolkit.registration().subAgent(() -> mockAgent).apply();

        assertNotNull(toolkit.getTool("call_helperagent"));
        assertEquals("A helpful agent", toolkit.getTool("call_helperagent").getDescription());
    }

    @Test
    @DisplayName("Should register sub-agent with custom config via Toolkit")
    void testToolkitRegistrationWithConfig() {
        Agent mockAgent = createMockAgent("ExpertAgent", "An expert agent");

        SubAgentConfig config =
                SubAgentConfig.builder()
                        .toolName("ask_expert")
                        .description("Ask the expert a question")
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registration().subAgent(() -> mockAgent, config).apply();

        assertNotNull(toolkit.getTool("ask_expert"));
        assertEquals("Ask the expert a question", toolkit.getTool("ask_expert").getDescription());
    }

    @Test
    @DisplayName("Should register sub-agent to a group")
    void testToolkitRegistrationWithGroup() {
        Agent mockAgent = createMockAgent("Worker", "A worker agent");

        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("workers", "Worker agents group", true);
        toolkit.registration().subAgent(() -> mockAgent).group("workers").apply();

        assertNotNull(toolkit.getTool("call_worker"));
        assertTrue(toolkit.getToolGroup("workers").getTools().contains("call_worker"));
    }

    @Test
    @DisplayName("Should forward events when forwardEvents is true and emitter is provided")
    void testEventForwardingEnabled() {
        // Create mock agent that supports streaming
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("StreamAgent");
        when(mockAgent.getDescription()).thenReturn("Streaming agent");

        Msg responseMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Thinking...").build())
                        .build();

        // Mock stream() to return events
        Event reasoningEvent = new Event(EventType.REASONING, responseMsg, true);
        when(mockAgent.stream(any(List.class), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        // Configure with forwardEvents=true (default)
        SubAgentConfig config = SubAgentConfig.builder().forwardEvents(true).build();

        SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

        // Track emitted chunks
        List<ToolResultBlock> emittedChunks = new ArrayList<>();
        ToolEmitter testEmitter = emittedChunks::add;

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Hello");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_streamagent").input(input).build();

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolUse)
                                        .input(input)
                                        .emitter(testEmitter)
                                        .build())
                        .block();

        assertNotNull(result);
        // Verify stream() was called (not call())
        verify(mockAgent).stream(any(List.class), any(StreamOptions.class));
        verify(mockAgent, never()).call(any(List.class));
        // Verify events were forwarded
        assertFalse(emittedChunks.isEmpty());
    }

    @Test
    @DisplayName("Should not use streaming when forwardEvents is false")
    void testEventForwardingDisabled() {
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("NonStreamAgent");
        when(mockAgent.getDescription()).thenReturn("Non-streaming agent");
        when(mockAgent.call(any(List.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder().text("Response").build())
                                        .build()));

        // Configure with forwardEvents=false
        SubAgentConfig config = SubAgentConfig.builder().forwardEvents(false).build();

        SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

        List<ToolResultBlock> emittedChunks = new ArrayList<>();
        ToolEmitter testEmitter = emittedChunks::add;

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Hello");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_nonstreamagent").input(input).build();

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolUse)
                                        .input(input)
                                        .emitter(testEmitter)
                                        .build())
                        .block();

        assertNotNull(result);
        // Verify call() was used (not stream())
        verify(mockAgent).call(any(List.class));
        verify(mockAgent, never()).stream(any(List.class), any(StreamOptions.class));
        // Verify no events were forwarded
        assertTrue(emittedChunks.isEmpty());
    }

    @Test
    @DisplayName("Should use streaming with NoOpToolEmitter when emitter is not provided")
    void testStreamingWithNoOpEmitter() {
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("TestAgent");
        when(mockAgent.getDescription()).thenReturn("Test agent");

        Msg responseMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response").build())
                        .build();
        Event event = new Event(EventType.REASONING, responseMsg, true);
        when(mockAgent.stream(any(List.class), any(StreamOptions.class)))
                .thenReturn(Flux.just(event));

        // forwardEvents=true by default, but no emitter provided
        SubAgentTool tool = new SubAgentTool(() -> mockAgent, SubAgentConfig.defaults());

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Hello");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_testagent").input(input).build();

        // No emitter in param - will use NoOpToolEmitter
        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().toolUseBlock(toolUse).input(input).build())
                        .block();

        assertNotNull(result);
        // Should still use stream() with NoOpToolEmitter
        verify(mockAgent).stream(any(List.class), any(StreamOptions.class));
        verify(mockAgent, never()).call(any(List.class));
    }

    @Test
    @DisplayName("SubAgentConfig should have forwardEvents true by default")
    void testForwardEventsDefaultsToTrue() {
        SubAgentConfig config = SubAgentConfig.defaults();
        assertTrue(config.isForwardEvents());
    }

    @Test
    @DisplayName("Should use custom StreamOptions when provided")
    void testCustomStreamOptions() {
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("CustomStreamAgent");
        when(mockAgent.getDescription()).thenReturn("Custom streaming agent");

        Msg responseMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response").build())
                        .build();

        Event event = new Event(EventType.REASONING, responseMsg, true);
        when(mockAgent.stream(any(List.class), any(StreamOptions.class)))
                .thenReturn(Flux.just(event));

        // Custom StreamOptions with only REASONING events
        StreamOptions customOptions =
                StreamOptions.builder().eventTypes(EventType.REASONING).incremental(true).build();

        SubAgentConfig config =
                SubAgentConfig.builder().forwardEvents(true).streamOptions(customOptions).build();

        assertEquals(customOptions, config.getStreamOptions());

        SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

        List<ToolResultBlock> emittedChunks = new ArrayList<>();
        ToolEmitter testEmitter = emittedChunks::add;

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Hello");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_customstreamagent").input(input).build();

        tool.callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(toolUse)
                                .input(input)
                                .emitter(testEmitter)
                                .build())
                .block();

        // Verify stream was called with custom options
        verify(mockAgent).stream(any(List.class), any(StreamOptions.class));
    }

    // Helper methods

    private Agent createMockAgent(String name, String description) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        when(agent.getDescription()).thenReturn(description);
        when(agent.call(any(List.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder().text("Response").build())
                                        .build()));
        return agent;
    }

    private String extractText(ToolResultBlock result) {
        if (result.getOutput() == null || result.getOutput().isEmpty()) {
            return "";
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
    }

    private String extractSessionId(ToolResultBlock result) {
        String text = extractText(result);
        if (text.startsWith("session_id: ")) {
            int endIndex = text.indexOf("\n");
            if (endIndex > 0) {
                return text.substring("session_id: ".length(), endIndex);
            } else {
                // Handle case where no newline exists (session_id is the entire text)
                return text.substring("session_id: ".length());
            }
        }
        return null;
    }
}
