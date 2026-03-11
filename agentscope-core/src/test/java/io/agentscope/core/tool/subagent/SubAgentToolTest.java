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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolExecutionContext;
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

        SubAgentTool tool = new SubAgentTool(context -> mockAgent, null);

        assertEquals("call_testagent", tool.getName());
        assertEquals("Test description", tool.getDescription());
        assertNotNull(tool.getParameters());
    }

    @Test
    @DisplayName("Should use agent name for tool name generation")
    void testToolNameGeneration() {
        Agent mockAgent = createMockAgent("Research Agent", "Research tasks");

        SubAgentTool tool = new SubAgentTool(context -> mockAgent, null);

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

        SubAgentTool tool = new SubAgentTool(context -> mockAgent, config);

        assertEquals("custom_tool", tool.getName());
        assertEquals("Custom description", tool.getDescription());
    }

    @Test
    @DisplayName("Should generate correct schema")
    void testConversationSchema() {
        Agent mockAgent = createMockAgent("TestAgent", "Test");

        SubAgentTool tool = new SubAgentTool(context -> mockAgent, SubAgentConfig.defaults());

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
                context -> {
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
                        context -> mockAgent,
                        SubAgentConfig.builder().forwardEvents(false).build());

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
    @DisplayName("Should handle explicit null session_id and create new session")
    void testNullSessionIdHandling() {
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("TestAgent");
        when(mockAgent.getDescription()).thenReturn("Test agent");
        when(mockAgent.call(any(List.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                TextBlock.builder()
                                                        .text("Response with null session")
                                                        .build())
                                        .build()));

        SubAgentTool tool =
                new SubAgentTool(
                        context -> mockAgent,
                        SubAgentConfig.builder().forwardEvents(false).build());

        // Explicitly pass null for session_id (simulating LLM behavior)
        Map<String, Object> input = new HashMap<>();
        input.put("session_id", null);
        input.put("message", "Hello");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_testagent").input(input).build();

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().toolUseBlock(toolUse).input(input).build())
                        .block();

        assertNotNull(result);
        String text = extractText(result);
        assertTrue(text.contains("session_id:"));
        assertTrue(text.contains("Response with null session"));
        // Should generate a new session_id when null is provided
        String sessionId = extractSessionId(result);
        assertNotNull(sessionId);
        assertFalse(sessionId.isEmpty());
    }

    @Test
    @DisplayName("Should inherit session ID from parent in SHARED mode")
    void testSessionIdInheritanceInSharedMode() {
        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("TestAgent");
        when(mockAgent.getDescription()).thenReturn("Test agent");
        when(mockAgent.call(any(List.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder().text("Response").build())
                                        .build()));

        SubAgentTool tool =
                new SubAgentTool(
                        context -> mockAgent,
                        SubAgentConfig.builder()
                                .forwardEvents(false)
                                .contextSharingMode(ContextSharingMode.SHARED)
                                .build());

        // Create context with parent session ID
        String parentSessionId = "parent_session_123";
        ToolExecutionContext toolContext =
                ToolExecutionContext.builder()
                        .register(SubAgentTool.CONTEXT_KEY_PARENT_SESSION_ID, parentSessionId)
                        .build();

        // Don't pass session_id - should inherit from parent
        Map<String, Object> input = new HashMap<>();
        input.put("message", "Hello");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_testagent").input(input).build();

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolUse)
                                        .input(input)
                                        .context(toolContext)
                                        .build())
                        .block();

        assertNotNull(result);
        String sessionId = extractSessionId(result);
        assertNotNull(sessionId);
        // Session ID should be derived from parent session
        assertTrue(
                sessionId.startsWith(parentSessionId + "_"),
                "Session ID should be derived from parent: " + sessionId);
    }

    @Test
    @DisplayName("Should forward images from parent memory in SHARED mode")
    void testImageForwardingInSharedMode() {
        // Capture the message passed to the sub-agent
        final List<Msg> capturedMessages = new ArrayList<>();

        Agent mockAgent = mock(Agent.class);
        when(mockAgent.getName()).thenReturn("ImageAnalyzer");
        when(mockAgent.getDescription()).thenReturn("Image analysis agent");
        when(mockAgent.call(any(List.class)))
                .thenAnswer(
                        invocation -> {
                            List<Msg> msgs = invocation.getArgument(0);
                            capturedMessages.addAll(msgs);
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .content(
                                                    TextBlock.builder()
                                                            .text("Image analyzed")
                                                            .build())
                                            .build());
                        });

        SubAgentTool tool =
                new SubAgentTool(
                        context -> mockAgent,
                        SubAgentConfig.builder()
                                .forwardEvents(false)
                                .contextSharingMode(ContextSharingMode.SHARED)
                                .build());

        // Create parent agent with memory containing an image
        InMemoryMemory parentMemory = new InMemoryMemory();
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(
                                new io.agentscope.core.message.Base64Source(
                                        "image/png", "base64imagedata"))
                        .build();
        Msg userMsgWithImage =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Analyze this image").build())
                        .content(imageBlock)
                        .build();
        parentMemory.addMessage(userMsgWithImage);

        // Use ReActAgent mock since getMemory() is defined there
        io.agentscope.core.ReActAgent parentAgent = mock(io.agentscope.core.ReActAgent.class);
        when(parentAgent.getMemory()).thenReturn(parentMemory);

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Please analyze");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_imageanalyzer").input(input).build();

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .toolUseBlock(toolUse)
                                        .input(input)
                                        .agent(parentAgent)
                                        .build())
                        .block();

        assertNotNull(result);
        assertFalse(capturedMessages.isEmpty());

        // Verify the message sent to sub-agent contains the image
        Msg sentMsg = capturedMessages.get(0);
        List<ImageBlock> sentImages = sentMsg.getContentBlocks(ImageBlock.class);
        assertFalse(sentImages.isEmpty(), "Image should be forwarded to sub-agent");
        assertEquals(1, sentImages.size());
    }

    @Test
    @DisplayName("Should register sub-agent via Toolkit")
    void testToolkitRegistration() {
        Agent mockAgent = createMockAgent("HelperAgent", "A helpful agent");

        Toolkit toolkit = new Toolkit();
        toolkit.registration().subAgent(context -> mockAgent).apply();

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
        toolkit.registration().subAgent(context -> mockAgent, config).apply();

        assertNotNull(toolkit.getTool("ask_expert"));
        assertEquals("Ask the expert a question", toolkit.getTool("ask_expert").getDescription());
    }

    @Test
    @DisplayName("Should register sub-agent to a group")
    void testToolkitRegistrationWithGroup() {
        Agent mockAgent = createMockAgent("Worker", "A worker agent");

        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("workers", "Worker agents group", true);
        toolkit.registration().subAgent(context -> mockAgent).group("workers").apply();

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

        SubAgentTool tool = new SubAgentTool(context -> mockAgent, config);

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

        SubAgentTool tool = new SubAgentTool(context -> mockAgent, config);

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
        SubAgentTool tool = new SubAgentTool(context -> mockAgent, SubAgentConfig.defaults());

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

        SubAgentTool tool = new SubAgentTool(context -> mockAgent, config);

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

    // Context Sharing Mode Tests

    @Test
    @DisplayName("SubAgentConfig should have SHARED context mode by default")
    void testDefaultContextSharingMode() {
        SubAgentConfig config = SubAgentConfig.defaults();
        assertEquals(ContextSharingMode.SHARED, config.getContextSharingMode());
    }

    @Test
    @DisplayName("Should share memory in SHARED mode")
    void testSharedMemoryMode() {
        // Create parent agent with memory
        Memory parentMemory = new InMemoryMemory();
        parentMemory.addMessage(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Parent message").build())
                        .build());

        ReActAgent parentAgent = mock(ReActAgent.class);
        when(parentAgent.getName()).thenReturn("ParentAgent");
        when(parentAgent.getMemory()).thenReturn(parentMemory);

        // Create sub-agent
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn("SubAgent");
        when(mockSubAgent.getDescription()).thenReturn("Sub agent");
        when(mockSubAgent.getMemory()).thenReturn(parentMemory); // Returns shared memory
        when(mockSubAgent.call(anyList()))
                .thenAnswer(
                        invocation ->
                                Mono.just(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .content(TextBlock.builder().text("Done").build())
                                                .build()));

        // Create a context-aware provider that captures the memory passed in context
        final Memory[] capturedMemory = new Memory[1];
        SubAgentProvider<ReActAgent> provider =
                new SubAgentProvider<>() {
                    @Override
                    public ReActAgent provide() {
                        // Called by constructor to derive name/description
                        return mockSubAgent;
                    }

                    @Override
                    public ReActAgent provideWithContext(SubAgentContext context) {
                        capturedMemory[0] = context.getMemoryToUse();
                        return mockSubAgent;
                    }
                };

        SubAgentConfig config =
                SubAgentConfig.builder()
                        .contextSharingMode(ContextSharingMode.SHARED)
                        .forwardEvents(false)
                        .build();

        SubAgentTool tool = new SubAgentTool(provider, config);

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Test");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_subagent").input(input).build();

        tool.callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(toolUse)
                                .input(input)
                                .agent(parentAgent)
                                .build())
                .block();

        // Verify the provider received a forked copy with same content (SHARED mode)
        // Note: SHARED mode now forks the memory to remove pending tool calls,
        // so it's a different instance but with the same message content
        assertNotNull(capturedMemory[0]);
        assertEquals(parentMemory.getMessages().size(), capturedMemory[0].getMessages().size());
    }

    @Test
    @DisplayName("Should fork memory in FORK mode")
    void testForkMemoryMode() {
        // Create parent agent with memory
        Memory parentMemory = new InMemoryMemory();
        parentMemory.addMessage(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Parent message").build())
                        .build());

        ReActAgent parentAgent = mock(ReActAgent.class);
        when(parentAgent.getName()).thenReturn("ParentAgent");
        when(parentAgent.getMemory()).thenReturn(parentMemory);

        // Create sub-agent
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn("SubAgent");
        when(mockSubAgent.getDescription()).thenReturn("Sub agent");
        when(mockSubAgent.call(anyList()))
                .thenAnswer(
                        invocation ->
                                Mono.just(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .content(TextBlock.builder().text("Done").build())
                                                .build()));

        // Create a context-aware provider that captures the memory passed in context
        final Memory[] capturedMemory = new Memory[1];
        SubAgentProvider<ReActAgent> provider =
                new SubAgentProvider<>() {
                    @Override
                    public ReActAgent provide() {
                        // Called by constructor to derive name/description
                        return mockSubAgent;
                    }

                    @Override
                    public ReActAgent provideWithContext(SubAgentContext context) {
                        capturedMemory[0] = context.getMemoryToUse();
                        return mockSubAgent;
                    }
                };

        SubAgentConfig config =
                SubAgentConfig.builder()
                        .contextSharingMode(ContextSharingMode.FORK)
                        .forwardEvents(false)
                        .build();

        SubAgentTool tool = new SubAgentTool(provider, config);

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Test");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_subagent").input(input).build();

        tool.callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(toolUse)
                                .input(input)
                                .agent(parentAgent)
                                .build())
                .block();

        // Verify the provider received a forked memory (not the same instance, but a copy)
        assertNotNull(capturedMemory[0]);
        assertNotSame(parentMemory, capturedMemory[0]);
        assertEquals(1, capturedMemory[0].getMessages().size());

        // Parent memory should not be modified
        assertEquals(1, parentMemory.getMessages().size());
    }

    @Test
    @DisplayName("Should use independent memory in NEW mode")
    void testNewMemoryMode() {
        // Create parent agent with memory
        Memory parentMemory = new InMemoryMemory();
        parentMemory.addMessage(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Parent message").build())
                        .build());

        ReActAgent parentAgent = mock(ReActAgent.class);
        when(parentAgent.getName()).thenReturn("ParentAgent");
        when(parentAgent.getMemory()).thenReturn(parentMemory);

        // Create sub-agent
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn("SubAgent");
        when(mockSubAgent.getDescription()).thenReturn("Sub agent");
        when(mockSubAgent.call(anyList()))
                .thenAnswer(
                        invocation ->
                                Mono.just(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .content(TextBlock.builder().text("Done").build())
                                                .build()));

        // Create a context-aware provider that captures the memory passed in context
        final Memory[] capturedMemory = new Memory[1];
        SubAgentProvider<ReActAgent> provider =
                new SubAgentProvider<>() {
                    @Override
                    public ReActAgent provide() {
                        // Called by constructor to derive name/description
                        return mockSubAgent;
                    }

                    @Override
                    public ReActAgent provideWithContext(SubAgentContext context) {
                        capturedMemory[0] = context.getMemoryToUse();
                        return mockSubAgent;
                    }
                };

        SubAgentConfig config =
                SubAgentConfig.builder()
                        .contextSharingMode(ContextSharingMode.NEW)
                        .forwardEvents(false)
                        .build();

        SubAgentTool tool = new SubAgentTool(provider, config);

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Test");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_subagent").input(input).build();

        tool.callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(toolUse)
                                .input(input)
                                .agent(parentAgent)
                                .build())
                .block();

        // In NEW mode, the provider should receive null memory (indicating independent memory)
        assertNull(capturedMemory[0]);
    }

    @Test
    @DisplayName("SHARED mode should share images in memory")
    void testSharedMemoryWithImages() {
        // Create parent agent with memory containing images
        Memory parentMemory = new InMemoryMemory();
        Msg msgWithImage =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Look at this").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.com/photo.jpg")
                                                                .build())
                                                .build()))
                        .build();
        parentMemory.addMessage(msgWithImage);

        ReActAgent parentAgent = mock(ReActAgent.class);
        when(parentAgent.getName()).thenReturn("ParentAgent");
        when(parentAgent.getMemory()).thenReturn(parentMemory);

        // Create sub-agent
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn("SubAgent");
        when(mockSubAgent.getDescription()).thenReturn("Sub agent");
        when(mockSubAgent.getMemory()).thenReturn(parentMemory); // Shared memory
        when(mockSubAgent.call(anyList()))
                .thenAnswer(
                        invocation ->
                                Mono.just(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .content(
                                                        TextBlock.builder()
                                                                .text("Analyzed")
                                                                .build())
                                                .build()));

        // Create a context-aware provider that captures the memory passed in context
        final Memory[] capturedMemory = new Memory[1];
        SubAgentProvider<ReActAgent> provider =
                new SubAgentProvider<>() {
                    @Override
                    public ReActAgent provide() {
                        // Called by constructor to derive name/description
                        return mockSubAgent;
                    }

                    @Override
                    public ReActAgent provideWithContext(SubAgentContext context) {
                        capturedMemory[0] = context.getMemoryToUse();
                        return mockSubAgent;
                    }
                };

        SubAgentConfig config =
                SubAgentConfig.builder()
                        .contextSharingMode(ContextSharingMode.SHARED)
                        .forwardEvents(false)
                        .build();

        SubAgentTool tool = new SubAgentTool(provider, config);

        Map<String, Object> input = new HashMap<>();
        input.put("message", "Analyze the image");
        ToolUseBlock toolUse =
                ToolUseBlock.builder().id("1").name("call_subagent").input(input).build();

        tool.callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(toolUse)
                                .input(input)
                                .agent(parentAgent)
                                .build())
                .block();

        // Verify provider received a forked copy with the image content
        // Note: SHARED mode now forks the memory to remove pending tool calls
        assertNotNull(capturedMemory[0]);
        assertEquals(parentMemory.getMessages().size(), capturedMemory[0].getMessages().size());
    }

    @Test
    @DisplayName("Memory.fork() should create independent copy")
    void testMemoryFork() {
        Memory original = new InMemoryMemory();
        original.addMessage(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Message 1").build())
                        .build());
        original.addMessage(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response 1").build())
                        .build());

        // Fork the memory
        Memory forked = original.fork();

        // Verify fork has same messages
        assertEquals(2, forked.getMessages().size());
        assertEquals("Message 1", forked.getMessages().get(0).getTextContent());

        // Add to fork should not affect original
        forked.addMessage(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Message 2").build())
                        .build());

        assertEquals(3, forked.getMessages().size());
        assertEquals(2, original.getMessages().size());
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
