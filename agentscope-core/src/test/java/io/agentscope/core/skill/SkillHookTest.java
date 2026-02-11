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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.StructuredOutputHook;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
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
 * <p>
 * These tests verify that SkillHook correctly injects skill prompts during
 * PreReasoningEvent.
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
        skillBox.registerSkillLoadTool(); // Register skill loader tool
        testAgent = new TestAgent("test-agent");
    }

    @Test
    @DisplayName("Should inject skill prompt when skills are active")
    void testInjectSkillPromptWhenSkillsActive() {
        // Arrange: Register a skill and activate it using the loader tool
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Test Content", null);
        skillBox.registerSkill(skill);

        // Activate skill by calling the loader tool (this is how skills are activated
        // in practice)
        activateSkill(skill.getSkillId());

        // Verify skill is now active
        assertTrue(skillBox.isSkillActive(skill.getSkillId()), "Skill should be active");

        // Create PreReasoningEvent with one user message
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User query").build())
                        .build());

        PreReasoningEvent event =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);

        // Act: Process event through hook
        PreReasoningEvent result = skillHook.onEvent(event).block();

        // Assert: Skill prompt should be injected
        assertNotNull(result, "Event should be processed");
        assertEquals(2, result.getInputMessages().size(), "Should add skill prompt message");
        assertEquals(
                MsgRole.SYSTEM,
                result.getInputMessages().get(0).getRole(),
                "Skill prompt should be SYSTEM message");
        assertTrue(
                result.getInputMessages().get(0).getContent().toString().contains("test_skill"),
                "Skill prompt should contain skill information");
    }

    /**
     * Helper method to activate a skill using the loader tool.
     */
    private void activateSkill(String skillId) {
        toolkit.getTool("load_skill_through_path")
                .callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("test-call")
                                                .name("load_skill_through_path")
                                                .input(
                                                        java.util.Map.of(
                                                                "skillId",
                                                                skillId,
                                                                "path",
                                                                "SKILL.md"))
                                                .build())
                                .input(java.util.Map.of("skillId", skillId, "path", "SKILL.md"))
                                .build())
                .block();
    }

    @Test
    @DisplayName("Should inject prompt even when skills are registered but not active")
    void testInjectPromptForRegisteredSkills() {
        // Arrange: Register skill but don't activate it
        // Note: SkillPromptProvider returns prompt for all registered skills, not just
        // active ones
        AgentSkill skill = new AgentSkill("inactive_skill", "Inactive Skill", "# Inactive", null);
        skillBox.registerSkill(skill);

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User query").build())
                        .build());

        PreReasoningEvent event =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);

        // Act: Process event through hook
        PreReasoningEvent result = skillHook.onEvent(event).block();

        // Assert: Skill prompt should be added for registered skills
        assertNotNull(result, "Event should be processed");
        assertEquals(
                2,
                result.getInputMessages().size(),
                "Should add skill prompt for registered skills");
        assertEquals(
                MsgRole.SYSTEM,
                result.getInputMessages().get(0).getRole(),
                "Skill prompt should be SYSTEM message");
    }

    @Test
    @DisplayName("Should handle empty skill prompt gracefully")
    void testHandleEmptySkillPromptGracefully() {
        // Arrange: No skills registered at all
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User query").build())
                        .build());

        PreReasoningEvent event =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);

        // Act: Process event through hook
        PreReasoningEvent result = skillHook.onEvent(event).block();

        // Assert: Should handle gracefully without adding prompt
        assertNotNull(result, "Event should be processed");
        assertEquals(1, result.getInputMessages().size(), "Should not add empty skill prompt");
    }

    @Test
    @DisplayName("Should return correct hook priority")
    void testHookPriority() {
        assertEquals(55, skillHook.priority(), "Skill hook should have priority (55)");
    }

    @Test
    @DisplayName("[ISSUE#719]: Should append skill prompt after structured output reminder")
    void testSkillPromptAppendedAfterStructuredOutputReminder() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Test Content", null);
        skillBox.registerSkill(skill);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User query").build())
                        .build();

        Msg reminderMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Reminder").build())
                        .metadata(
                                Map.of(
                                        MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER,
                                        true,
                                        MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER_TYPE,
                                        StructuredOutputReminder.TOOL_CHOICE.toString()))
                        .build();

        List<Msg> messages = new ArrayList<>();
        messages.add(userMsg);
        messages.add(reminderMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);

        // Simulate AgentBase hook execution (SkillHook priority 55 > StructuredOutputHook
        // priority 50)
        List<Hook> hooks = new ArrayList<>();
        hooks.add(skillHook);
        hooks.add(new StructuredOutputHook(StructuredOutputReminder.TOOL_CHOICE, null, null));

        PreReasoningEvent result = notifyHooks(event, hooks).block();

        assertNotNull(result);
        assertInstanceOf(
                ToolChoice.Specific.class, result.getEffectiveGenerateOptions().getToolChoice());
        assertEquals(
                "generate_response",
                ((ToolChoice.Specific) result.getEffectiveGenerateOptions().getToolChoice())
                        .toolName());
    }

    @Test
    @DisplayName("[ISSUE#765] should inject skill prompt at first not last")
    void testInjectSkillPromptAtFirst() {
        // Arrange: Register and activate a skill
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Test Content", null);
        skillBox.registerSkill(skill);
        activateSkill(skill.getSkillId());

        // Create PreReasoningEvent with multiple messages
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text("System instruction").build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("User query").build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Assistant response").build())
                                .build());

        PreReasoningEvent event =
                new PreReasoningEvent(
                        testAgent, "test-model", GenerateOptions.builder().build(), messages);

        // Act: Process event through hook
        PreReasoningEvent result = skillHook.onEvent(event).block();

        // Assert: Skill prompt should be injected at the FIRST position
        assertNotNull(result, "Event should be processed");
        assertEquals(4, result.getInputMessages().size(), "Should add skill prompt message");

        // Verify the first message is the skill prompt (SYSTEM role)
        Msg firstMsg = result.getInputMessages().get(0);
        assertEquals(
                MsgRole.SYSTEM,
                firstMsg.getRole(),
                "First message should be SYSTEM message with skill prompt");
        assertTrue(
                firstMsg.getContent().toString().contains("test_skill"),
                "First message should contain skill information");

        // Verify original messages are preserved in order after skill prompt
        assertEquals(
                "System instruction",
                result.getInputMessages().get(1).getTextContent(),
                "Second message should be original system instruction");
        assertEquals(
                "User query",
                result.getInputMessages().get(2).getTextContent(),
                "Third message should be original user query");
        assertEquals(
                "Assistant response",
                result.getInputMessages().get(3).getTextContent(),
                "Fourth message should be original assistant response");
    }

    private <T extends HookEvent> Mono<T> notifyHooks(T event, List<Hook> hooks) {
        Mono<T> result = Mono.just(event);
        List<Hook> sortedHooks =
                hooks.stream().sorted(java.util.Comparator.comparingInt(Hook::priority)).toList();
        for (Hook hook : sortedHooks) {
            result = result.flatMap(hook::onEvent);
        }
        return result;
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
