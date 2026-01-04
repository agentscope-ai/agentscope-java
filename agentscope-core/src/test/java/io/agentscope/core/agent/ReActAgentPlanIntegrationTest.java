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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Integration tests for ReActAgent with PlanNotebook configuration.
 *
 * <p>Tests the automatic registration of plan tools and hooks when PlanNotebook is configured via
 * the builder.
 */
class ReActAgentPlanIntegrationTest {

    private Model mockModel;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);

        // Mock model to return empty response
        ChatResponse emptyResponse =
                ChatResponse.builder().id("test-id").content(List.of()).build();

        when(mockModel.stream(anyList(), any(), any(GenerateOptions.class)))
                .thenReturn(Flux.just(emptyResponse));
    }

    @Test
    void testPlanNotebookConfiguration() {
        // Create a custom PlanNotebook
        PlanNotebook planNotebook = PlanNotebook.builder().maxSubtasks(5).build();

        // Create agent with planNotebook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .planNotebook(planNotebook)
                        .build();

        // Verify PlanNotebook is accessible
        assertNotNull(agent.getPlanNotebook(), "PlanNotebook should be accessible");
        assertSame(
                planNotebook, agent.getPlanNotebook(), "PlanNotebook should be the same instance");
    }

    @Test
    void testEnablePlanWithDefaults() {
        // Create agent with default PlanNotebook via enablePlan()
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .enablePlan()
                        .build();

        // Verify PlanNotebook is created and accessible
        assertNotNull(agent.getPlanNotebook(), "PlanNotebook should be created by enablePlan()");
    }

    @Test
    void testPlanToolsAutoRegistration() {
        // Create a custom PlanNotebook
        PlanNotebook planNotebook = PlanNotebook.builder().build();

        // Create agent with planNotebook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .planNotebook(planNotebook)
                        .build();

        // Verify plan tools are registered in toolkit
        Toolkit toolkit = agent.getToolkit();
        assertNotNull(toolkit, "Toolkit should not be null");

        // Check for plan-related tools
        AgentTool createPlanTool = toolkit.getTool("create_plan");
        assertNotNull(createPlanTool, "create_plan tool should be registered");

        AgentTool finishPlanTool = toolkit.getTool("finish_plan");
        assertNotNull(finishPlanTool, "finish_plan tool should be registered");

        AgentTool updateSubtaskTool = toolkit.getTool("update_subtask_state");
        assertNotNull(updateSubtaskTool, "update_subtask_state tool should be registered");

        AgentTool finishSubtaskTool = toolkit.getTool("finish_subtask");
        assertNotNull(finishSubtaskTool, "finish_subtask tool should be registered");
    }

    @Test
    void testPlanHintHookAutoRegistration() {
        // Create a plan with a subtask
        PlanNotebook planNotebook = PlanNotebook.builder().build();

        // Create a simple plan
        SubTask subtask = new SubTask("Test Task", "Test description", "Expected outcome");
        planNotebook
                .createPlanWithSubTasks(
                        "Test Plan", "Test plan description", "Expected outcome", List.of(subtask))
                .block();

        // Create agent with planNotebook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .planNotebook(planNotebook)
                        .maxIters(1)
                        .build();

        // Verify hooks are registered
        List<Hook> hooks = agent.getHooks();
        assertNotNull(hooks, "Hooks list should not be null");
        assertFalse(hooks.isEmpty(), "At least one hook should be registered");

        // Verify the plan hint hook is working by checking if it injects hint messages
        PreReasoningEvent event =
                new PreReasoningEvent(
                        agent,
                        "test-model",
                        null,
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .name("user")
                                        .content(TextBlock.builder().text("Test message").build())
                                        .build()));

        // Find the plan hint hook and test it
        boolean foundPlanHook = false;
        int originalSize = event.getInputMessages().size();
        for (Hook hook : hooks) {
            Mono<HookEvent> result = hook.onEvent(event);
            PreReasoningEvent modifiedEvent = (PreReasoningEvent) result.block();

            // Check if the hook added a hint message
            if (modifiedEvent.getInputMessages().size() > originalSize) {
                foundPlanHook = true;
                // Verify the last message contains plan hint
                Msg lastMsg =
                        modifiedEvent
                                .getInputMessages()
                                .get(modifiedEvent.getInputMessages().size() - 1);
                String messageText =
                        lastMsg.getFirstContentBlock() instanceof TextBlock
                                ? ((TextBlock) lastMsg.getFirstContentBlock()).getText()
                                : "";
                assertTrue(
                        messageText.contains("<system-hint>"),
                        "Plan hint should be wrapped in system-hint tags");
                break;
            }
        }

        assertTrue(foundPlanHook, "Plan hint hook should be registered and working");
    }

    @Test
    void testWithoutPlanNotebook() {
        // Create agent without planNotebook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .build();

        // Verify PlanNotebook is null
        assertNull(agent.getPlanNotebook(), "PlanNotebook should be null when not configured");

        // Verify plan tools are not registered
        Toolkit toolkit = agent.getToolkit();
        assertNotNull(toolkit, "Toolkit should not be null");

        AgentTool createPlanTool = toolkit.getTool("create_plan");
        assertNull(
                createPlanTool, "create_plan tool should not be registered without PlanNotebook");
    }

    @Test
    void testPlanNotebookWithCustomConfiguration() {
        // Create a custom PlanNotebook with specific configuration
        PlanNotebook planNotebook = PlanNotebook.builder().maxSubtasks(10).build();

        // Create agent with custom planNotebook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .planNotebook(planNotebook)
                        .build();

        // Verify the custom configuration is preserved
        assertNotNull(agent.getPlanNotebook(), "PlanNotebook should not be null");
        assertSame(
                planNotebook,
                agent.getPlanNotebook(),
                "Should use the provided PlanNotebook instance");
    }

    @Test
    void testBuilderChaining() {
        // Test that builder methods can be chained fluently
        PlanNotebook planNotebook = PlanNotebook.builder().build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("Test prompt")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .maxIters(5)
                        .planNotebook(planNotebook)
                        .build();

        assertNotNull(agent, "Agent should be created successfully");
        assertNotNull(agent.getPlanNotebook(), "PlanNotebook should be set");
        assertEquals("TestAgent", agent.getName(), "Agent name should match");
        assertEquals(5, agent.getMaxIters(), "MaxIters should match");
    }

    @Test
    void testEnablePlanChaining() {
        // Test enablePlan() can be chained with other builder methods
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("Test prompt")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .maxIters(5)
                        .enablePlan()
                        .build();

        assertNotNull(agent, "Agent should be created successfully");
        assertNotNull(agent.getPlanNotebook(), "PlanNotebook should be created");
        assertEquals("TestAgent", agent.getName(), "Agent name should match");
    }

    @Test
    void testMultipleAgentsWithSamePlanNotebook() {
        // Create a single PlanNotebook instance
        PlanNotebook sharedPlanNotebook = PlanNotebook.builder().build();

        // Create two agents sharing the same PlanNotebook
        ReActAgent agent1 =
                ReActAgent.builder()
                        .name("Agent1")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .planNotebook(sharedPlanNotebook)
                        .build();

        ReActAgent agent2 =
                ReActAgent.builder()
                        .name("Agent2")
                        .model(mockModel)
                        .memory(new InMemoryMemory())
                        .planNotebook(sharedPlanNotebook)
                        .build();

        // Verify both agents have the same PlanNotebook instance
        assertSame(
                agent1.getPlanNotebook(),
                agent2.getPlanNotebook(),
                "Both agents should share the same PlanNotebook instance");

        // Verify both have plan tools registered
        assertNotNull(agent1.getToolkit().getTool("create_plan"), "Agent1 should have plan tools");
        assertNotNull(agent2.getToolkit().getTool("create_plan"), "Agent2 should have plan tools");
    }

    @Test
    void testPlanToolsGetToolSchemas() {
        // Create agent with planNotebook
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .planNotebook(planNotebook)
                        .build();

        // Get tool schemas
        List<ToolSchema> schemas = agent.getToolkit().getToolSchemas();
        assertNotNull(schemas, "Tool schemas should not be null");
        assertFalse(schemas.isEmpty(), "Tool schemas should include plan tools");

        // Verify plan tools are in the schemas
        boolean foundCreatePlan =
                schemas.stream().anyMatch(schema -> "create_plan".equals(schema.getName()));
        assertTrue(foundCreatePlan, "create_plan should be in tool schemas");
    }
}
