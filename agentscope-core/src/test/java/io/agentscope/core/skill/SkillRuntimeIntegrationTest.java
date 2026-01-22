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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Integration tests for Skill runtime flow.
 *
 * <p>
 * These tests verify the complete skill activation and usage flow, including:
 * <ul>
 * <li>Skill and tool group activation when loading skills</li>
 * <li>Tool availability after skill activation</li>
 * <li>Multi-turn conversation with persistent tool activation</li>
 * <li>Integration with ReActAgent</li>
 * </ul>
 */
@Tag("integration")
class SkillRuntimeIntegrationTest {
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
    }

    // ==================== Simulated Integration Tests ====================

    @Test
    @DisplayName("Complete skill activation flow without real agent")
    void testCompleteSkillActivationFlow() {
        // Step 1: Setup skill with tools
        AgentTool calculatorAdd = createCalculatorTool("calculator_add", "Add two numbers");
        AgentTool calculatorMultiply =
                createCalculatorTool("calculator_multiply", "Multiply two numbers");

        AgentSkill calculatorSkill =
                new AgentSkill(
                        "calculator",
                        "Calculator Skill",
                        "# Calculator\nProvides math operations",
                        null);

        skillBox.registration().skill(calculatorSkill).agentTool(calculatorAdd).apply();
        skillBox.registration().skill(calculatorSkill).agentTool(calculatorMultiply).apply();

        String skillId = calculatorSkill.getSkillId();
        String toolGroupName = skillId + "_skill_tools";

        // Step 2: Verify initial state - skill and tool group inactive
        assertFalse(skillBox.isSkillActive(skillId), "Skill should be inactive initially");
        assertNotNull(toolkit.getToolGroup(toolGroupName), "ToolGroup should exist");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be inactive initially");

        // Step 3: Simulate LLM calling load_skill_through_path
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

        ToolResultBlock loadResult = skillLoader.callAsync(callParam).block();

        // Step 4: Verify skill and tool group are both activated
        assertNotNull(loadResult, "Load result should not be null");
        assertFalse(loadResult.getOutput().isEmpty(), "Load should succeed");
        assertTrue(skillBox.isSkillActive(skillId), "Skill should be activated after loading");
        assertTrue(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "ToolGroup should be activated when skill is loaded");

        // Step 5: Verify tools are accessible
        assertNotNull(toolkit.getTool("calculator_add"), "calculator_add should be accessible");
        assertNotNull(
                toolkit.getTool("calculator_multiply"), "calculator_multiply should be accessible");

        // Step 6: Trigger PreReasoningEvent to inject skill prompt
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Calculate 2 + 3").build())
                        .build());

        PreReasoningEvent preReasoningEvent =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);
        PreReasoningEvent result = skillHook.onEvent(preReasoningEvent).block();

        // Step 7: Verify skill prompt was injected
        assertNotNull(result, "PreReasoningEvent should be processed");
        assertEquals(
                2, result.getInputMessages().size(), "Should add skill prompt to input messages");
        assertEquals(
                MsgRole.SYSTEM,
                result.getInputMessages().get(1).getRole(),
                "Skill prompt should be SYSTEM message");

        // Step 8: Verify skill and tool group remain active
        assertTrue(skillBox.isSkillActive(skillId), "Skill should remain active");
        assertTrue(
                toolkit.getToolGroup(toolGroupName).isActive(), "ToolGroup should remain active");
    }

    @Test
    @DisplayName("Multiple skills activation in sequence")
    void testMultipleSkillsActivationSequence() {
        // Setup multiple skills
        AgentSkill mathSkill = new AgentSkill("math", "Math Skill", "# Math", null);
        AgentSkill weatherSkill = new AgentSkill("weather", "Weather Skill", "# Weather", null);

        AgentTool mathTool = createCalculatorTool("math_add", "Math addition");
        AgentTool weatherTool = createCalculatorTool("get_weather", "Get weather");

        skillBox.registration().skill(mathSkill).agentTool(mathTool).apply();
        skillBox.registration().skill(weatherSkill).agentTool(weatherTool).apply();

        String mathSkillId = mathSkill.getSkillId();
        String weatherSkillId = weatherSkill.getSkillId();
        String mathToolGroupName = mathSkillId + "_skill_tools";
        String weatherToolGroupName = weatherSkillId + "_skill_tools";

        // Load first skill
        loadSkill(mathSkillId);

        // Verify first skill activated
        assertTrue(skillBox.isSkillActive(mathSkillId), "Math skill should be activated");
        assertTrue(
                toolkit.getToolGroup(mathToolGroupName).isActive(),
                "Math tool group should be activated");
        assertFalse(
                skillBox.isSkillActive(weatherSkillId), "Weather skill should still be inactive");
        assertFalse(
                toolkit.getToolGroup(weatherToolGroupName).isActive(),
                "Weather tool group should still be inactive");

        // Load second skill
        loadSkill(weatherSkillId);

        // Verify both skills activated
        assertTrue(skillBox.isSkillActive(mathSkillId), "Math skill should remain activated");
        assertTrue(skillBox.isSkillActive(weatherSkillId), "Weather skill should be activated");
        assertTrue(
                toolkit.getToolGroup(mathToolGroupName).isActive(),
                "Math tool group should remain activated");
        assertTrue(
                toolkit.getToolGroup(weatherToolGroupName).isActive(),
                "Weather tool group should be activated");
        assertTrue(
                toolkit.getActiveGroups().contains(mathToolGroupName),
                "Math tool group should be activated");
        assertTrue(
                toolkit.getActiveGroups().contains(weatherToolGroupName),
                "Weather tool group should be activated");
    }

    @Test
    @DisplayName("Skill with actual tools integration")
    void testSkillWithActualToolsIntegration() {
        // Create a skill with a functional tool
        AgentTool counterTool =
                new AgentTool() {
                    private int count = 0;

                    @Override
                    public String getName() {
                        return "increment_counter";
                    }

                    @Override
                    public String getDescription() {
                        return "Increment a counter";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        count++;
                        return Mono.just(ToolResultBlock.text("Counter: " + count));
                    }
                };

        AgentSkill counterSkill = new AgentSkill("counter", "Counter Skill", "# Counter", null);
        skillBox.registration().skill(counterSkill).agentTool(counterTool).apply();

        String skillId = counterSkill.getSkillId();

        // Load skill
        loadSkill(skillId);

        // Verify skill activated
        assertTrue(skillBox.isSkillActive(skillId), "Skill should be activated");

        // Call the tool multiple times
        AgentTool tool = toolkit.getTool("increment_counter");
        assertNotNull(tool, "Tool should be accessible");

        ToolCallParam callParam =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-001")
                                        .name("increment_counter")
                                        .input(Map.of())
                                        .build())
                        .input(Map.of())
                        .build();

        ToolResultBlock result1 = tool.callAsync(callParam).block();
        assertTrue(result1.getOutput().get(0).toString().contains("Counter: 1"));

        ToolResultBlock result2 = tool.callAsync(callParam).block();
        assertTrue(result2.getOutput().get(0).toString().contains("Counter: 2"));
    }

    // ==================== Helper Methods ====================

    /**
     * Helper method to load a skill by calling the load_skill_through_path tool.
     */
    private void loadSkill(String skillId) {
        AgentTool skillLoader = toolkit.getTool("load_skill_through_path");
        Map<String, Object> loadParams = new HashMap<>();
        loadParams.put("skillId", skillId);
        loadParams.put("path", "SKILL.md");

        ToolCallParam callParam =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-" + System.currentTimeMillis())
                                        .name("load_skill_through_path")
                                        .input(loadParams)
                                        .build())
                        .input(loadParams)
                        .build();

        skillLoader.callAsync(callParam).block();
    }

    /**
     * Helper method to create a simple calculator tool.
     */
    private AgentTool createCalculatorTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
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
                        ToolResultBlock.of(TextBlock.builder().text("Result: 42").build()));
            }
        };
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
}
