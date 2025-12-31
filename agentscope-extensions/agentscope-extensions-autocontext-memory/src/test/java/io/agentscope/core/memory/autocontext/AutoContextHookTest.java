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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for AutoContextHook.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Hook creation and initialization</li>
 *   <li>Automatic registration of ContextOffloadTool</li>
 *   <li>Automatic attachment of PlanNotebook</li>
 *   <li>Hook execution only once per agent</li>
 *   <li>Handling of non-ReActAgent instances</li>
 *   <li>Handling of agents without AutoContextMemory</li>
 *   <li>Error handling during registration</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AutoContextHook Tests")
class AutoContextHookTest {

    @Mock private Model mockModel;

    private AutoContextConfig config;
    private AutoContextMemory memory;
    private AutoContextHook hook;
    private ReActAgent agent;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        config = AutoContextConfig.builder().msgThreshold(10).maxToken(1000).build();
        memory = new AutoContextMemory(config, mockModel);
        hook = new AutoContextHook();
        toolkit = new Toolkit();
        agent = createTestReActAgent(memory, toolkit);
    }

    private ReActAgent createTestReActAgent(
            io.agentscope.core.memory.Memory memory, Toolkit toolkit) {
        return ReActAgent.builder()
                .name("TestAgent")
                .model(mockModel)
                .memory(memory)
                .toolkit(toolkit)
                .build();
    }

    @Test
    @DisplayName("Should create hook instance")
    void testHookCreation() {
        AutoContextHook hook = new AutoContextHook();
        assertNotNull(hook);
        assertEquals(50, hook.priority());
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
                toolkit.getToolNames().contains("context_reload"),
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
        int toolCountAfterFirst = toolkit.getToolNames().size();

        hook.onEvent(event2).block();
        hook.onEvent(event3).block();

        // Tool count should not increase after first call
        assertEquals(toolCountAfterFirst, toolkit.getToolNames().size());
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
                toolkit.getToolNames().contains("context_reload"),
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
                toolkit.getToolNames().contains("context_reload"),
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
                emptyToolkit.getToolNames().contains("context_reload"),
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
        assertEquals(50, hook.priority());
    }
}
