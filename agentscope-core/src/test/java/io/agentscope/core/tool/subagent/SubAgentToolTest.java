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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    /**
     * HITL (Human-in-the-Loop) Tests for SubAgentTool.
     *
     * <p>Tests cover:
     * <ul>
     *   <li>Suspended state detection and result building</li>
     *   <li>Resume functionality with injected tool results</li>
     *   <li>HITL enabled/disabled behavior</li>
     *   <li>Multiple suspension types handling</li>
     * </ul>
     */
    @Nested
    @DisplayName("HITL (Human-in-the-Loop) Tests")
    class HITLTests {

        @Test
        @DisplayName(
                "Should return suspended result when sub-agent is suspended with TOOL_SUSPENDED")
        void testSuspendedResultOnToolSuspended() {
            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("SuspendableAgent");
            when(mockAgent.getDescription()).thenReturn("Agent that can suspend");

            ToolUseBlock innerToolUse =
                    createToolUseBlock(
                            "inner-tool-1",
                            "external_api",
                            Map.of("url", "https://api.example.com"));

            Msg suspendedResponse =
                    createMultiContentMsg(
                            List.of(
                                    TextBlock.builder().text("Calling external API...").build(),
                                    innerToolUse),
                            io.agentscope.core.message.GenerateReason.TOOL_SUSPENDED);

            when(mockAgent.call(any(List.class))).thenReturn(Mono.just(suspendedResponse));

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input = Map.of("message", "Call the external API");
            ToolUseBlock toolUse = createToolUseBlock("tool-1", "call_suspendableagent", input);

            ToolResultBlock result = executeToolCall(tool, toolUse, input);

            assertNotNull(result);
            assertTrue(result.isSuspended(), "Result should be marked as suspended");
            assertNotNull(
                    result.getMetadata().get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID),
                    "Should contain session ID in metadata");
            assertEquals(
                    io.agentscope.core.message.GenerateReason.TOOL_SUSPENDED,
                    result.getMetadata().get(SubAgentContext.METADATA_GENERATE_REASON),
                    "Should contain generate reason in metadata");
        }

        @Test
        @DisplayName(
                "Should return suspended result when sub-agent is suspended with"
                        + " REASONING_STOP_REQUESTED")
        void testSuspendedResultOnReasoningStopRequested() {
            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("HookStopAgent");
            when(mockAgent.getDescription()).thenReturn("Agent stopped by hook");

            Msg suspendedResponse =
                    createTextMsgWithReason(
                            "Stopped for review",
                            io.agentscope.core.message.GenerateReason.REASONING_STOP_REQUESTED);

            when(mockAgent.call(any(List.class))).thenReturn(Mono.just(suspendedResponse));

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input = Map.of("message", "Process this request");
            ToolUseBlock toolUse = createToolUseBlock("tool-2", "call_hookstopagent", input);

            ToolResultBlock result = executeToolCall(tool, toolUse, input);

            assertNotNull(result);
            assertTrue(result.isSuspended(), "Result should be marked as suspended");
            assertEquals(
                    io.agentscope.core.message.GenerateReason.REASONING_STOP_REQUESTED,
                    result.getMetadata().get(SubAgentContext.METADATA_GENERATE_REASON));
        }

        @Test
        @DisplayName(
                "Should return suspended result when sub-agent is suspended with"
                        + " ACTING_STOP_REQUESTED")
        void testSuspendedResultOnActingStopRequested() {
            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("ActingStopAgent");
            when(mockAgent.getDescription()).thenReturn("Agent stopped during acting");

            Msg suspendedResponse =
                    createTextMsgWithReason(
                            "Stopped during tool execution",
                            io.agentscope.core.message.GenerateReason.ACTING_STOP_REQUESTED);

            when(mockAgent.call(any(List.class))).thenReturn(Mono.just(suspendedResponse));

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input = Map.of("message", "Execute action");
            ToolUseBlock toolUse = createToolUseBlock("tool-3", "call_actingstopagent", input);

            ToolResultBlock result = executeToolCall(tool, toolUse, input);

            assertNotNull(result);
            assertTrue(result.isSuspended(), "Result should be marked as suspended");
            assertEquals(
                    io.agentscope.core.message.GenerateReason.ACTING_STOP_REQUESTED,
                    result.getMetadata().get(SubAgentContext.METADATA_GENERATE_REASON));
        }

        @Test
        @DisplayName("Should convert suspended state to text when HITL is disabled")
        void testSuspendedStateConvertedToTextWhenHitlDisabled() {
            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("NoHitlAgent");
            when(mockAgent.getDescription()).thenReturn("Agent without HITL");

            Msg suspendedResponse =
                    createTextMsgWithReason(
                            "Suspended content",
                            io.agentscope.core.message.GenerateReason.TOOL_SUSPENDED);

            when(mockAgent.call(any(List.class))).thenReturn(Mono.just(suspendedResponse));

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(false).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input = Map.of("message", "Test message");
            ToolUseBlock toolUse = createToolUseBlock("tool-4", "call_nohitlagent", input);

            ToolResultBlock result = executeToolCall(tool, toolUse, input);

            assertNotNull(result);
            assertFalse(
                    result.isSuspended(),
                    "Result should NOT be marked as suspended when HITL disabled");
            String text = extractText(result);
            assertTrue(text.contains("session_id:"), "Should still contain session_id");
        }

        @Test
        @DisplayName("Should resume execution with submit tool results")
        void testResumeWithSubmitToolResults() {
            AtomicInteger callCount = new AtomicInteger(0);

            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("ResumableAgent");
            when(mockAgent.getDescription()).thenReturn("Agent that can resume");

            when(mockAgent.call(any(List.class)))
                    .thenAnswer(
                            invocation -> {
                                int count = callCount.incrementAndGet();
                                if (count == 1) {
                                    ToolUseBlock innerToolUse =
                                            createToolUseBlock(
                                                    "inner-tool-resume",
                                                    "database_query",
                                                    Map.of("sql", "SELECT * FROM users"));

                                    return Mono.just(
                                            createMultiContentMsg(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Querying database...")
                                                                    .build(),
                                                            innerToolUse),
                                                    io.agentscope.core.message.GenerateReason
                                                            .TOOL_SUSPENDED));
                                } else {
                                    return Mono.just(
                                            createTextMsg("Query completed: 5 users found"));
                                }
                            });

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input1 = Map.of("message", "Query the database");
            ToolUseBlock toolUse1 =
                    createToolUseBlock("tool-resume", "call_resumableagent", input1);

            ToolResultBlock suspendedResult = executeToolCall(tool, toolUse1, input1);

            assertNotNull(suspendedResult);
            assertTrue(suspendedResult.isSuspended());

            String sessionId =
                    (String)
                            suspendedResult
                                    .getMetadata()
                                    .get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID);
            assertNotNull(sessionId);

            ToolResultBlock userProvidedResult =
                    ToolResultBlock.builder()
                            .id("tool-resume")
                            .name("call_resumableagent")
                            .output(
                                    TextBlock.builder()
                                            .text("[{id: 1, name: 'Alice'}, ...]")
                                            .build())
                            .build();

            Map<String, Object> input2 = createResumeInput("Continue", sessionId);
            ToolUseBlock toolUse2 =
                    createResumeToolUse(
                            "tool-resume-",
                            "call_resumableagent",
                            input2,
                            List.of(userProvidedResult));

            ToolResultBlock resumedResult = executeToolCall(tool, toolUse2, input2);

            assertNotNull(resumedResult);
            assertFalse(resumedResult.isSuspended(), "Resumed result should not be suspended");
            String text = extractText(resumedResult);
            assertTrue(text.contains("5 users found"), "Should contain resumed response");
        }

        @Test
        @DisplayName("Should resume with empty results for hook stop")
        void testResumeWithEmptyResultsForHookStop() {
            AtomicInteger callCount = new AtomicInteger(0);

            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("HookResumeAgent");
            when(mockAgent.getDescription()).thenReturn("Agent resuming from hook stop");

            when(mockAgent.call(any(List.class)))
                    .thenAnswer(
                            invocation -> {
                                int count = callCount.incrementAndGet();
                                if (count == 1) {
                                    return Mono.just(
                                            createTextMsgWithReason(
                                                    "Paused for review",
                                                    io.agentscope.core.message.GenerateReason
                                                            .REASONING_STOP_REQUESTED));
                                } else {
                                    return Mono.just(createTextMsg("Continued after review"));
                                }
                            });

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input1 = Map.of("message", "Start task");
            ToolUseBlock toolUse1 = createToolUseBlock("tool-hook", "call_hookresumeagent", input1);

            ToolResultBlock suspendedResult = executeToolCall(tool, toolUse1, input1);

            assertTrue(suspendedResult.isSuspended());

            String sessionId =
                    (String)
                            suspendedResult
                                    .getMetadata()
                                    .get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID);

            Map<String, Object> input2 = createResumeInput("Continue", sessionId);
            ToolUseBlock toolUse2 =
                    createResumeToolUse("tool-hook", "call_hookresumeagent", input2, List.of());

            ToolResultBlock resumedResult = executeToolCall(tool, toolUse2, input2);

            assertNotNull(resumedResult);
            assertFalse(resumedResult.isSuspended());
            String text = extractText(resumedResult);
            assertTrue(text.contains("Continued after review"));
        }

        @Test
        @DisplayName("Should include inner tool use blocks in suspended result")
        void testSuspendedResultContainsInnerToolUseBlocks() {
            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("MultiToolAgent");
            when(mockAgent.getDescription()).thenReturn("Agent with multiple tools");

            ToolUseBlock innerTool1 =
                    createToolUseBlock("inner-1", "api_call", Map.of("endpoint", "/users"));

            ToolUseBlock innerTool2 =
                    createToolUseBlock("inner-2", "file_write", Map.of("path", "/tmp/result.json"));

            Msg suspendedResponse =
                    createMultiContentMsg(
                            List.of(
                                    TextBlock.builder()
                                            .text("Executing multiple operations...")
                                            .build(),
                                    innerTool1,
                                    innerTool2),
                            io.agentscope.core.message.GenerateReason.TOOL_SUSPENDED);

            when(mockAgent.call(any(List.class))).thenReturn(Mono.just(suspendedResponse));

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input = Map.of("message", "Execute operations");
            ToolUseBlock toolUse = createToolUseBlock("tool-multi", "call_multitoolagent", input);

            ToolResultBlock result = executeToolCall(tool, toolUse, input);

            assertNotNull(result);
            assertTrue(result.isSuspended());

            List<ToolUseBlock> toolUseBlocks =
                    result.getOutput().stream()
                            .filter(block -> block instanceof ToolUseBlock)
                            .map(block -> (ToolUseBlock) block)
                            .toList();

            assertEquals(2, toolUseBlocks.size(), "Should contain 2 inner tool use blocks");
            assertEquals("api_call", toolUseBlocks.get(0).getName());
            assertEquals("file_write", toolUseBlocks.get(1).getName());
        }

        @Test
        @DisplayName("Should preserve session state across suspension and resumption")
        void testSessionStatePreservedAcrossSuspension() {
            AtomicInteger callCount = new AtomicInteger(0);

            ReActAgent mockAgent = mock(ReActAgent.class);
            when(mockAgent.getName()).thenReturn("StatefulAgent");
            when(mockAgent.getDescription()).thenReturn("Agent with state");

            when(mockAgent.call(any(List.class)))
                    .thenAnswer(
                            invocation -> {
                                int count = callCount.incrementAndGet();
                                if (count == 1) {
                                    return Mono.just(
                                            createTextMsgWithReason(
                                                    "Step 1 complete, waiting...",
                                                    io.agentscope.core.message.GenerateReason
                                                            .TOOL_SUSPENDED));
                                } else if (count == 2) {
                                    return Mono.just(
                                            createTextMsgWithReason(
                                                    "Step 2 complete, waiting...",
                                                    io.agentscope.core.message.GenerateReason
                                                            .TOOL_SUSPENDED));
                                } else {
                                    return Mono.just(createTextMsg("All steps completed!"));
                                }
                            });

            SubAgentConfig config =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
            SubAgentTool tool = new SubAgentTool(() -> mockAgent, config);

            Map<String, Object> input1 = Map.of("message", "Start multi-step task");
            ToolUseBlock toolUse1 =
                    createToolUseBlock("tool-state-1", "call_statefulagent", input1);

            ToolResultBlock result1 = executeToolCall(tool, toolUse1, input1);

            assertTrue(result1.isSuspended());
            String sessionId =
                    (String)
                            result1.getMetadata().get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID);

            Map<String, Object> input2 = createResumeInput("Continue step 2", sessionId);
            ToolUseBlock toolUse2 =
                    createResumeToolUse("tool-state-2", "call_statefulagent", input2, List.of());

            ToolResultBlock result2 = executeToolCall(tool, toolUse2, input2);

            assertTrue(result2.isSuspended());
            String sessionId2 =
                    (String)
                            result2.getMetadata().get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID);
            assertEquals(sessionId, sessionId2, "Session ID should be preserved");
        }
    }

    // Helper methods

    private Agent createMockAgent(String name, String description) {
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn(name);
        when(mockAgent.getDescription()).thenReturn(description);
        return mockAgent;
    }

    private String extractText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining("\n"));
    }

    private String extractSessionId(ToolResultBlock result) {
        String text = extractText(result);
        String prefix = "session_id:";
        int start = text.indexOf(prefix);
        if (start == -1) {
            return null;
        }
        start += prefix.length();
        int end = text.indexOf('\n', start);
        return end == -1 ? text.substring(start).trim() : text.substring(start, end).trim();
    }

    /**
     * Create simple text message
     */
    private Msg createTextMsg(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * Creates a text message with GenerateReason
     */
    private Msg createTextMsgWithReason(
            String text, io.agentscope.core.message.GenerateReason reason) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .generateReason(reason)
                .build();
    }

    /**
     * Creates a message with multiple ContentBlocks
     */
    private Msg createMultiContentMsg(
            List<ContentBlock> contents, io.agentscope.core.message.GenerateReason reason) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(contents)
                .generateReason(reason)
                .build();
    }

    /**
     * Creates a simple ToolUseBlock
     */
    private ToolUseBlock createToolUseBlock(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(input).build();
    }

    /**
     * Helper method to execute tool call
     */
    private ToolResultBlock executeToolCall(
            SubAgentTool tool, ToolUseBlock toolUse, Map<String, Object> input) {
        return tool.callAsync(ToolCallParam.builder().toolUseBlock(toolUse).input(input).build())
                .block();
    }

    /**
     * Creates input for resuming with session_id
     */
    private Map<String, Object> createResumeInput(String message, String sessionId) {
        Map<String, Object> input = new HashMap<>();
        input.put("message", message);
        input.put("session_id", sessionId);
        return input;
    }

    /**
     * Creates a ToolUseBlock with previous_tool_result
     */
    private ToolUseBlock createResumeToolUse(
            String id,
            String name,
            Map<String, Object> input,
            List<ToolResultBlock> previousResults) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SubAgentHook.PREVIOUS_TOOL_RESULT, previousResults);
        return ToolUseBlock.builder().id(id).name(name).input(input).metadata(metadata).build();
    }
}
