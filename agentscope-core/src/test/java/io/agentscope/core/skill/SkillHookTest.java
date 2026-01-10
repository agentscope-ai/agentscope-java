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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for SkillHook.
 *
 * <p>These tests verify Hook lifecycle.
 */
@Tag("unit")
class SkillHookTest {
    private SkillBox skillBox;
    private Toolkit toolkit;
    private Agent testAgent;
    private Hook skillHook;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        skillBox = new SkillBox(toolkit);
        skillHook = new SkillHook(skillBox);
        skillBox.registerSkillLoadTool();
        testAgent = new TestAgent("test-agent");
        AgentSkill skill = new AgentSkill("empty_skill", "Empty Skill", "# Empty", null);
        skillBox.registration().skill(skill).apply(); // Should handle skill with no tools correctly
    }

    // ==================== Hook Lifecycle Tests ====================

    @Test
    @DisplayName("Step 0: Initial state - skill and toolGroup should be inactive")
    void testStep0_InitialState() {
        // Arrange: Setup skill with tools
        AgentTool skillTool1 = createTestTool("calculator_add");
        AgentTool skillTool2 = createTestTool("calculator_multiply");

        AgentSkill calculatorSkill =
                new AgentSkill(
                        "calculator",
                        "Calculator Skill",
                        "# Calculator\nProvides math operations",
                        null);

        // Register skill with its tools
        skillBox.registration().skill(calculatorSkill).agentTool(skillTool1).apply();
        skillBox.registration().skill(calculatorSkill).agentTool(skillTool2).apply();

        String skillId = calculatorSkill.getSkillId();
        String toolGroupName = skillId + "_skill_tools";

        // Assert: Verify initial state
        assertFalse(skillBox.isSkillActive(skillId), "Skill should be inactive initially");
        assertNotNull(
                toolkit.getToolGroup(toolGroupName), "ToolGroup should exist after registration");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be inactive initially");
        assertEquals("calculator_add", toolkit.getTool("calculator_add").getName());
        assertEquals("calculator_multiply", toolkit.getTool("calculator_multiply").getName());
    }

    @Test
    @DisplayName("Step 1: PreCallEvent should not change inactive state")
    void testStep1_PreCallEventOnInactiveState() {
        // Arrange: Setup skill
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        AgentTool skillTool = createTestTool("test_tool");

        skillBox.registration().skill(skill).agentTool(skillTool).apply();

        String skillId = skill.getSkillId();
        String toolGroupName = skillId + "_skill_tools";

        // Verify pre-state
        assertFalse(skillBox.isSkillActive(skillId), "Skill should be inactive before PreCall");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be inactive before PreCall");

        // Act: Trigger PreCallEvent
        PreCallEvent preCallEvent = new PreCallEvent(testAgent, Collections.emptyList());
        skillHook.onEvent(preCallEvent).block();

        // Assert: State should remain inactive
        assertFalse(
                skillBox.isSkillActive(skillId), "Skill should remain inactive after PreCallEvent");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should remain inactive after PreCallEvent");
    }

    @Test
    @DisplayName("Step 2: SkillLoaderTool should activate skill but not toolGroup")
    void testStep2_SkillLoaderActivatesSkillOnly() {
        // Arrange: Setup skill with tools
        AgentTool skillTool = createTestTool("test_tool");
        AgentSkill skill = new AgentSkill("calculator", "Calculator", "# Calc", null);

        skillBox.registration().skill(skill).agentTool(skillTool).apply();

        String skillId = skill.getSkillId();
        String toolGroupName = skillId + "_skill_tools";

        // Verify pre-state
        assertFalse(skillBox.isSkillActive(skillId), "Skill should be inactive before loading");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be inactive before loading");

        // Act: Mock LLM calling skill loader tool
        AgentTool skillLoader = toolkit.getTool("load_skill_through_path");
        Map<String, Object> loadParams = new HashMap<>();
        loadParams.put("skillId", skillId);
        loadParams.put("path", "SKILL.md");

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call-001")
                        .name("load_skill_through_path")
                        .input(loadParams)
                        .build();

        ToolCallParam callParam =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(loadParams).build();

        ToolResultBlock result = skillLoader.callAsync(callParam).block();

        // Assert: Skill activated, but toolGroup still inactive
        assertNotNull(result, "SkillLoader should return result");
        assertTrue(
                skillBox.isSkillActive(skillId),
                "Skill should be activated after loader tool call");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should still be inactive (not activated until PreReasoning)");
    }

    @Test
    @DisplayName("Step 3: PreReasoningEvent should activate toolGroup for active skills")
    void testStep3_PreReasoningEventActivatesToolGroup() {
        // Arrange: Setup skill and activate it
        AgentTool skillTool = createTestTool("calc_tool");
        AgentSkill skill = new AgentSkill("math", "Math Skill", "# Math", null);

        skillBox.registration().skill(skill).agentTool(skillTool).apply();

        String skillId = skill.getSkillId();
        String toolGroupName = skillId + "_skill_tools";

        // Load skill via loader tool (simulate LLM)
        AgentTool skillLoader = toolkit.getTool("load_skill_through_path");
        Map<String, Object> loadParams = new HashMap<>();
        loadParams.put("skillId", skillId);
        loadParams.put("path", "SKILL.md");

        ToolCallParam callParam =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-001")
                                        .name("load_skill_through_path")
                                        .input(loadParams)
                                        .build())
                        .input(loadParams)
                        .build();

        skillLoader.callAsync(callParam).block();

        // Verify skill is active, toolGroup is not
        assertTrue(skillBox.isSkillActive(skillId), "Skill should be active before PreReasoning");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be inactive before PreReasoning");

        // Act: Trigger PreReasoningEvent
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Calculate").build())
                        .build());

        PreReasoningEvent preReasoningEvent =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);
        PreReasoningEvent result = skillHook.onEvent(preReasoningEvent).block();

        // Assert: ToolGroup should now be activated
        assertNotNull(result, "PreReasoningEvent should be processed");
        assertTrue(
                skillBox.isSkillActive(skillId), "Skill should remain active after PreReasoning");
        assertTrue(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be activated after PreReasoningEvent");

        // Verify skill prompt was added
        assertEquals(
                2, result.getInputMessages().size(), "Should add skill prompt to input messages");
        assertEquals(
                MsgRole.SYSTEM,
                result.getInputMessages().get(1).getRole(),
                "Skill prompt should be SYSTEM message");
    }

    @Test
    @DisplayName("Step 4: PostCallEvent should deactivate both skill and toolGroup")
    void testStep4_PostCallEventDeactivatesAll() {
        // Arrange: Setup and activate skill
        AgentTool skillTool = createTestTool("tool1");
        AgentSkill skill = new AgentSkill("weather", "Weather Skill", "# Weather", null);

        skillBox.registration().skill(skill).agentTool(skillTool).apply();

        String skillId = skill.getSkillId();
        String toolGroupName = skillId + "_skill_tools";

        // Activate skill via loader tool
        AgentTool skillLoader = toolkit.getTool("load_skill_through_path");
        Map<String, Object> loadParams = new HashMap<>();
        loadParams.put("skillId", skillId);
        loadParams.put("path", "SKILL.md");

        ToolCallParam callParam =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-001")
                                        .name("load_skill_through_path")
                                        .input(loadParams)
                                        .build())
                        .input(loadParams)
                        .build();

        skillLoader.callAsync(callParam).block();

        // Activate toolGroup via PreReasoningEvent
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Weather query").build())
                        .build());

        PreReasoningEvent preReasoningEvent =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);
        skillHook.onEvent(preReasoningEvent).block();

        // Verify both are active
        assertTrue(skillBox.isSkillActive(skillId), "Skill should be active before PostCallEvent");
        assertTrue(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be active before PostCallEvent");

        // Act: Trigger PostCallEvent
        Msg responseMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Weather response").build())
                        .build();
        PostCallEvent postCallEvent = new PostCallEvent(testAgent, responseMsg);
        skillHook.onEvent(postCallEvent).block();

        // Assert: Both should be deactivated
        assertFalse(
                skillBox.isSkillActive(skillId), "Skill should be deactivated after PostCallEvent");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be deactivated after PostCallEvent");
    }

    @Test
    @DisplayName("Should verify Hook priority")
    void testHookPriority() {
        assertEquals(10, skillHook.priority(), "Skill hook should have high priority (10)");
    }

    /**
     * Simple test agent for testing Hook events.
     */
    private static class TestAgent extends AgentBase {
        TestAgent(String name) {
            super(name);
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Test response").build())
                            .build());
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Interrupted").build())
                            .build());
        }
    }

    /**
     * Helper method to create a simple test tool.
     */
    private AgentTool createTestTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Test tool: " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                schema.put("properties", new HashMap<String, Object>());
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(
                        ToolResultBlock.of(TextBlock.builder().text("Test result").build()));
            }
        };
    }
}
