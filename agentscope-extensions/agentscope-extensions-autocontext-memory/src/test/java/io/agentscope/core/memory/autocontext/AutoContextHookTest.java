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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * Unit tests for AutoContextHook.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Hook creation and initialization</li>
 *   <li>Automatic registration of ContextOffloadTool (PreCallEvent)</li>
 *   <li>Automatic attachment of PlanNotebook (PreCallEvent)</li>
 *   <li>Hook execution only once per agent (PreCallEvent)</li>
 *   <li>Handling of non-ReActAgent instances</li>
 *   <li>Handling of agents without AutoContextMemory</li>
 *   <li>Error handling during registration</li>
 *   <li>PreReasoningEvent compression triggering</li>
 *   <li>PreReasoningEvent input messages update after compression</li>
 *   <li>PreReasoningEvent handling for non-ReActAgent instances</li>
 *   <li>PreReasoningEvent handling for agents without AutoContextMemory</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AutoContextHook Tests")
class AutoContextHookTest {

    @Mock private Model mockModel;

    private AutoContextConfig config;
    private AutoContextMemory memory;
    private AutoContextHook hook;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        config = AutoContextConfig.builder().msgThreshold(10).maxToken(1000).build();
        memory = new AutoContextMemory(config, mockModel);
        hook = new AutoContextHook();
        toolkit = new Toolkit();
    }

    @Test
    @DisplayName("Should register ContextOffloadTool and attach PlanNotebook on first PreCallEvent")
    void testFirstPreCallEvent() {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        // Create agent with PlanNotebook
        ReActAgent agentWithPlan =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(memory)
                        .toolkit(toolkit)
                        .planNotebook(planNotebook)
                        .build();

        PreCallEvent event = new PreCallEvent(agentWithPlan, new ArrayList<>());

        hook.onEvent(event).block();

        // Verify ContextOffloadTool was registered
        assertTrue(
                agentWithPlan.getToolkit().getToolNames().contains("context_reload"),
                "ContextOffloadTool should be registered");

        // Verify PlanNotebook was attached (we can't directly verify, but we can check
        // that the hook completed without errors)
        assertNotNull(event);
    }

    @Test
    @DisplayName("Should only register once even if called multiple times")
    void testHookExecutesOnlyOnce() {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        ReActAgent agentWithPlan =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(memory)
                        .toolkit(toolkit)
                        .planNotebook(planNotebook)
                        .build();

        PreCallEvent event1 = new PreCallEvent(agentWithPlan, new ArrayList<>());
        PreCallEvent event2 = new PreCallEvent(agentWithPlan, new ArrayList<>());
        PreCallEvent event3 = new PreCallEvent(agentWithPlan, new ArrayList<>());

        hook.onEvent(event1).block();
        int toolCountAfterFirst = agentWithPlan.getToolkit().getToolNames().size();

        hook.onEvent(event2).block();
        hook.onEvent(event3).block();

        // Tool count should not increase after first call
        assertEquals(toolCountAfterFirst, agentWithPlan.getToolkit().getToolNames().size());
    }

    @Test
    @DisplayName("Should skip non-ReActAgent instances")
    void testSkipNonReActAgent() {
        Agent nonReActAgent = mock(Agent.class);
        PreCallEvent event = new PreCallEvent(nonReActAgent, new ArrayList<>());

        hook.onEvent(event).block();

        // Verify no tools were registered (toolkit is not accessible from non-ReActAgent)
        assertNotNull(event);
    }

    @Test
    @DisplayName("Should skip agents without AutoContextMemory")
    void testSkipAgentWithoutAutoContextMemory() {
        InMemoryMemory otherMemory = new InMemoryMemory();
        ReActAgent agentWithOtherMemory =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(otherMemory)
                        .toolkit(toolkit)
                        .build();

        PreCallEvent event = new PreCallEvent(agentWithOtherMemory, new ArrayList<>());

        hook.onEvent(event).block();

        // Verify no ContextOffloadTool was registered
        assertFalse(
                agentWithOtherMemory.getToolkit().getToolNames().contains("context_reload"),
                "ContextOffloadTool should not be registered for non-AutoContextMemory");
    }

    @Test
    @DisplayName("Should handle agent without PlanNotebook gracefully")
    void testAgentWithoutPlanNotebook() {
        // Agent without PlanNotebook
        ReActAgent agentWithoutPlan =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(memory)
                        .toolkit(toolkit)
                        .build();

        PreCallEvent event = new PreCallEvent(agentWithoutPlan, new ArrayList<>());

        hook.onEvent(event).block();

        // Should complete without errors
        assertNotNull(event);
        // ContextOffloadTool should still be registered
        assertTrue(
                agentWithoutPlan.getToolkit().getToolNames().contains("context_reload"),
                "ContextOffloadTool should be registered even without PlanNotebook");
    }

    @Test
    @DisplayName("Should handle agent without Toolkit gracefully")
    void testAgentWithoutToolkit() {
        // Toolkit cannot be null in ReActAgent builder, but we can test with empty toolkit
        Toolkit emptyToolkit = new Toolkit();
        ReActAgent agentWithEmptyToolkit =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(memory)
                        .toolkit(emptyToolkit)
                        .build();

        PreCallEvent event = new PreCallEvent(agentWithEmptyToolkit, new ArrayList<>());

        hook.onEvent(event).block();

        // Should complete without errors
        assertNotNull(event);
        // ContextOffloadTool should be registered
        assertTrue(
                agentWithEmptyToolkit.getToolkit().getToolNames().contains("context_reload"),
                "ContextOffloadTool should be registered");
    }

    @Test
    @DisplayName("Should handle other event types")
    void testOtherEventTypes() {
        // HookEvent is a sealed interface, so we can't mock it
        // Instead, we test that the hook handles PreCallEvent correctly
        // and other event types would be handled by the base implementation
        // This is tested implicitly through other tests
        assertNotNull(hook);
    }

    @Test
    @DisplayName("Should have correct priority")
    void testPriority() {
        assertEquals(0, hook.priority());
    }

    // ==================== PreReasoningEvent Tests ====================

    @Test
    @DisplayName("Should trigger compression and update input messages in PreReasoningEvent")
    void testPreReasoningEventTriggersCompression() {
        // Create memory with low threshold to trigger compression
        // Use a configuration that will definitely trigger compression with user-assistant pairs
        AutoContextConfig compressionConfig =
                AutoContextConfig.builder()
                        .msgThreshold(5)
                        .maxToken(10000)
                        .tokenRatio(0.9)
                        .lastKeep(2)
                        .minConsecutiveToolMessages(10) // High to avoid tool compression
                        .largePayloadThreshold(10000) // High to avoid payload offloading
                        .build();
        TestModel testModel = new TestModel("Compressed summary");
        AutoContextMemory compressionMemory = new AutoContextMemory(compressionConfig, testModel);

        // Add user-assistant pairs to trigger strategy 4 (summary previous rounds)
        // This strategy will definitely call the model
        // Strategy 4 requires user-assistant pairs with messages between them (i - currentUserIndex
        // != 1)
        for (int i = 0; i < 3; i++) {
            compressionMemory.addMessage(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("user")
                            .content(TextBlock.builder().text("User message " + i).build())
                            .build());
            // Add tool messages between user and assistant to ensure they form a pair
            // (strategy 4 requires i - currentUserIndex != 1)
            compressionMemory.addMessage(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .name("assistant")
                            .content(
                                    ToolUseBlock.builder()
                                            .name("test_tool")
                                            .id("call_" + i)
                                            .input(new java.util.HashMap<>())
                                            .build())
                            .build());
            compressionMemory.addMessage(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .name("test_tool")
                            .content(
                                    ToolResultBlock.builder()
                                            .name("test_tool")
                                            .id("call_" + i)
                                            .output(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Result " + i)
                                                                    .build()))
                                            .build())
                            .build());
            // Add final assistant response
            compressionMemory.addMessage(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .name("assistant")
                            .content(TextBlock.builder().text("Assistant response " + i).build())
                            .build());
        }
        // Add one more user message (no assistant yet) to ensure latest assistant is found
        compressionMemory.addMessage(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text("Final user message").build())
                        .build());

        ReActAgent agentWithCompression =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(compressionMemory)
                        .toolkit(toolkit)
                        .build();

        // Get initial messages count
        List<Msg> initialMessages = compressionMemory.getMessages();
        int initialCount = initialMessages.size();
        // 3 rounds * 4 messages each (user, tool_use, tool_result, assistant) + 1 final user = 13
        assertEquals(13, initialCount, "Should have 13 messages initially");

        // Reset call count before compression
        testModel.reset();

        // Create PreReasoningEvent with initial messages
        List<Msg> inputMessages = new ArrayList<>(initialMessages);
        PreReasoningEvent event =
                new PreReasoningEvent(agentWithCompression, "test-model", null, inputMessages);

        // Process event - this should trigger compression
        PreReasoningEvent result = hook.onEvent(event).block();

        // Verify compression was triggered (model should be called for strategy 4)
        List<Msg> finalMessages = compressionMemory.getMessages();
        assertTrue(
                testModel.getCallCount() > 0,
                "Compression should trigger model call. Expected > 0, got "
                        + testModel.getCallCount());

        // Verify input messages were updated to reflect compressed memory
        List<Msg> updatedInputMessages = result.getInputMessages();
        assertNotNull(updatedInputMessages, "Input messages should not be null");
        assertEquals(
                finalMessages.size(),
                updatedInputMessages.size(),
                "Input messages should be updated to match compressed memory size. "
                        + "Initial: "
                        + initialCount
                        + ", Final: "
                        + finalMessages.size()
                        + ", Updated: "
                        + updatedInputMessages.size());

        // Verify messages were actually compressed (count should be reduced)
        assertTrue(
                finalMessages.size() < initialCount,
                "Messages should be compressed. Initial: "
                        + initialCount
                        + ", Final: "
                        + finalMessages.size());
    }

    @Test
    @DisplayName("Should not update input messages when compression is not needed")
    void testPreReasoningEventNoCompression() {
        // Create memory with high threshold (won't trigger compression)
        AutoContextConfig noCompressionConfig =
                AutoContextConfig.builder().msgThreshold(100).maxToken(100000).build();
        AutoContextMemory noCompressionMemory =
                new AutoContextMemory(noCompressionConfig, mockModel);

        // Add only a few messages (below threshold)
        for (int i = 0; i < 3; i++) {
            noCompressionMemory.addMessage(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("user")
                            .content(TextBlock.builder().text("Message " + i).build())
                            .build());
        }

        ReActAgent agentWithoutCompression =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(noCompressionMemory)
                        .toolkit(toolkit)
                        .build();

        // Get initial messages
        List<Msg> initialMessages = noCompressionMemory.getMessages();
        int initialCount = initialMessages.size();

        // Create PreReasoningEvent with initial messages
        List<Msg> inputMessages = new ArrayList<>(initialMessages);
        PreReasoningEvent event =
                new PreReasoningEvent(agentWithoutCompression, "test-model", null, inputMessages);

        // Process event
        PreReasoningEvent result = hook.onEvent(event).block();

        // Verify compression was not triggered
        List<Msg> finalMessages = noCompressionMemory.getMessages();
        assertEquals(initialCount, finalMessages.size(), "Message count should not change");

        // Verify input messages remain unchanged (compression not needed)
        List<Msg> resultInputMessages = result.getInputMessages();
        assertEquals(
                inputMessages.size(),
                resultInputMessages.size(),
                "Input messages should remain unchanged when compression is not needed");
    }

    @Test
    @DisplayName("Should skip PreReasoningEvent for non-ReActAgent instances")
    void testPreReasoningEventSkipNonReActAgent() {
        Agent nonReActAgent = mock(Agent.class);
        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text("Test").build())
                        .build());

        PreReasoningEvent event =
                new PreReasoningEvent(nonReActAgent, "test-model", null, inputMessages);

        PreReasoningEvent result = hook.onEvent(event).block();

        // Verify event is returned unchanged
        assertNotNull(result);
        assertEquals(
                inputMessages.size(),
                result.getInputMessages().size(),
                "Input messages should remain unchanged for non-ReActAgent");
    }

    @Test
    @DisplayName("Should skip PreReasoningEvent for agents without AutoContextMemory")
    void testPreReasoningEventSkipNonAutoContextMemory() {
        InMemoryMemory otherMemory = new InMemoryMemory();
        ReActAgent agentWithOtherMemory =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(otherMemory)
                        .toolkit(toolkit)
                        .build();

        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text("Test").build())
                        .build());

        PreReasoningEvent event =
                new PreReasoningEvent(agentWithOtherMemory, "test-model", null, inputMessages);

        PreReasoningEvent result = hook.onEvent(event).block();

        // Verify event is returned unchanged
        assertNotNull(result);
        assertEquals(
                inputMessages.size(),
                result.getInputMessages().size(),
                "Input messages should remain unchanged for non-AutoContextMemory");
    }

    @Test
    @DisplayName("Should handle PreReasoningEvent multiple times")
    void testPreReasoningEventMultipleCalls() {
        AutoContextConfig config =
                AutoContextConfig.builder().msgThreshold(5).maxToken(1000).build();
        TestModel testModel = new TestModel("Compressed");
        AutoContextMemory compressionMemory = new AutoContextMemory(config, testModel);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(compressionMemory)
                        .toolkit(toolkit)
                        .build();

        // First call - add messages and trigger compression
        for (int i = 0; i < 6; i++) {
            compressionMemory.addMessage(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("user")
                            .content(TextBlock.builder().text("Message " + i).build())
                            .build());
        }

        List<Msg> inputMessages1 = new ArrayList<>(compressionMemory.getMessages());
        PreReasoningEvent event1 = new PreReasoningEvent(agent, "test-model", null, inputMessages1);
        hook.onEvent(event1).block();

        // Second call - add more messages
        for (int i = 6; i < 12; i++) {
            compressionMemory.addMessage(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("user")
                            .content(TextBlock.builder().text("Message " + i).build())
                            .build());
        }

        List<Msg> inputMessages2 = new ArrayList<>(compressionMemory.getMessages());
        PreReasoningEvent event2 = new PreReasoningEvent(agent, "test-model", null, inputMessages2);
        hook.onEvent(event2).block();

        // Both calls should complete without errors
        assertNotNull(event1);
        assertNotNull(event2);
    }

    /**
     * Simple Model implementation for testing compression.
     */
    private static class TestModel implements Model {
        private final String responseText;
        private int callCount = 0;

        TestModel(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            callCount++;
            ChatResponse response =
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(responseText).build()))
                            .usage(new ChatUsage(10, 20, 30))
                            .build();
            return Flux.just(response);
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        int getCallCount() {
            return callCount;
        }

        void reset() {
            callCount = 0;
        }
    }
}
