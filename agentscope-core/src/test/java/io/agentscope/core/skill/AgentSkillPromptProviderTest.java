/*
 * Copyright 2024-2026 the original author or authors.
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

package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentSkillPromptProviderTest {

    private SkillRegistry skillRegistry;
    private AgentSkillPromptProvider provider;

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        provider = new AgentSkillPromptProvider(skillRegistry);
    }

    @Test
    @DisplayName("Should return empty string when no skills registered")
    void testNoSkillsReturnsEmpty() {
        String prompt = provider.getSkillSystemPrompt();

        assertEquals("", prompt);
    }

    @Test
    @DisplayName("Should generate prompt for single skill")
    void testSingleSkill() {
        AgentSkill skill =
                new AgentSkill("test_skill", "Test Skill Description", "# Content", null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom");
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("# Agent Skills"));
        assertTrue(prompt.contains("## test_skill_custom"));
        assertTrue(prompt.contains("Test Skill Description"));
        assertTrue(prompt.contains("check \"SKILL.md\" for how to use this skill"));
    }

    @Test
    @DisplayName("Should generate prompt for multiple skills")
    void testMultipleSkills() {
        AgentSkill skill1 = new AgentSkill("skill1", "First Skill", "# Content1", null);
        RegisteredSkill registered1 = new RegisteredSkill("skill1_custom");
        skillRegistry.registerSkill("skill1_custom", skill1, registered1);

        AgentSkill skill2 = new AgentSkill("skill2", "Second Skill", "# Content2", null);
        RegisteredSkill registered2 = new RegisteredSkill("skill2_custom");
        skillRegistry.registerSkill("skill2_custom", skill2, registered2);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("# Agent Skills"));
        assertTrue(prompt.contains("## skill1_custom"));
        assertTrue(prompt.contains("First Skill"));
        assertTrue(prompt.contains("## skill2_custom"));
        assertTrue(prompt.contains("Second Skill"));
    }

    @Test
    @DisplayName("Should generate correct prompt format")
    void testPromptFormat() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Description", "# Content", null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom");
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.startsWith("# Agent Skills\n"));
        assertTrue(prompt.contains("specialized capabilities you can load on-demand"));
        assertTrue(prompt.contains("skill_md_load_tool"));
        assertTrue(prompt.contains("Only load skill details when you actually need them"));
        assertTrue(prompt.contains("## test_skill_custom\nTest Description"));
    }

    @Test
    @DisplayName("Should handle skills with special characters in description")
    void testSpecialCharactersInDescription() {
        AgentSkill skill =
                new AgentSkill(
                        "test_skill",
                        "Description with \"quotes\" and 'apostrophes'",
                        "# Content",
                        null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom");
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("Description with \"quotes\" and 'apostrophes'"));
    }
}
