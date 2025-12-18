/*
 * Copyright 2024-2025 the original author or authors.
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentSkillPromptProviderTest {

    private SkillRegistry skillRegistry;
    private SkillGroupManager skillGroupManager;
    private AgentSkillPromptProvider provider;

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        skillGroupManager = new SkillGroupManager();
        provider = new AgentSkillPromptProvider(skillGroupManager, skillRegistry);
    }

    @Test
    @DisplayName("Should return empty string when no skills registered")
    void testNoSkillsReturnsEmpty() {
        String prompt = provider.getSkillSystemPrompt();

        assertEquals("", prompt);
    }

    @Test
    @DisplayName("Should generate prompt for single active ungrouped skill")
    void testSingleUngroupedSkill() {
        AgentSkill skill =
                new AgentSkill("test_skill", "Test Skill Description", "# Content", null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom", null);
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("# Agent Skills"));
        assertTrue(prompt.contains("## test_skill_custom"));
        assertTrue(prompt.contains("Test Skill Description"));
        assertTrue(prompt.contains("check \"SKILL.md\" for how to use this skill"));
    }

    @Test
    @DisplayName("Should generate prompt for multiple active skills")
    void testMultipleActiveSkills() {
        AgentSkill skill1 = new AgentSkill("skill1", "First Skill", "# Content1", null);
        RegisteredSkill registered1 = new RegisteredSkill("skill1_custom", null);
        skillRegistry.registerSkill("skill1_custom", skill1, registered1);

        AgentSkill skill2 = new AgentSkill("skill2", "Second Skill", "# Content2", null);
        RegisteredSkill registered2 = new RegisteredSkill("skill2_custom", null);
        skillRegistry.registerSkill("skill2_custom", skill2, registered2);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("# Agent Skills"));
        assertTrue(prompt.contains("## skill1_custom"));
        assertTrue(prompt.contains("First Skill"));
        assertTrue(prompt.contains("## skill2_custom"));
        assertTrue(prompt.contains("Second Skill"));
    }

    @Test
    @DisplayName("Should exclude inactive skills from prompt")
    void testInactiveSkillsExcluded() {
        skillGroupManager.createSkillGroup("group1", "Test Group", false);

        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom", "group1");
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String prompt = provider.getSkillSystemPrompt();

        assertEquals("", prompt);
    }

    @Test
    @DisplayName("Should include skills from active groups")
    void testActiveGroupSkillsIncluded() {
        skillGroupManager.createSkillGroup("group1", "Test Group", true);

        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom", "group1");
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("# Agent Skills"));
        assertTrue(prompt.contains("## test_skill_custom"));
        assertTrue(prompt.contains("Test Skill"));
    }

    @Test
    @DisplayName("Should filter skills by group activation state")
    void testGroupFiltering() {
        skillGroupManager.createSkillGroup("active_group", "Active Group", true);
        skillGroupManager.createSkillGroup("inactive_group", "Inactive Group", false);

        AgentSkill skill1 = new AgentSkill("skill1", "Active Skill", "# Content1", null);
        RegisteredSkill registered1 = new RegisteredSkill("skill1_custom", "active_group");
        skillRegistry.registerSkill("skill1_custom", skill1, registered1);

        AgentSkill skill2 = new AgentSkill("skill2", "Inactive Skill", "# Content2", null);
        RegisteredSkill registered2 = new RegisteredSkill("skill2_custom", "inactive_group");
        skillRegistry.registerSkill("skill2_custom", skill2, registered2);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("Active Skill"));
        assertFalse(prompt.contains("Inactive Skill"));
    }

    @Test
    @DisplayName("Should always include ungrouped skills")
    void testUngroupedSkillsAlwaysIncluded() {
        skillGroupManager.createSkillGroup("group1", "Test Group", false);

        AgentSkill ungroupedSkill =
                new AgentSkill("ungrouped", "Ungrouped Skill", "# Content", null);
        RegisteredSkill registered1 = new RegisteredSkill("ungrouped_custom", null);
        skillRegistry.registerSkill("ungrouped_custom", ungroupedSkill, registered1);

        AgentSkill groupedSkill = new AgentSkill("grouped", "Grouped Skill", "# Content", null);
        RegisteredSkill registered2 = new RegisteredSkill("grouped_custom", "group1");
        skillRegistry.registerSkill("grouped_custom", groupedSkill, registered2);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("Ungrouped Skill"));
        assertFalse(prompt.contains("Grouped Skill"));
    }

    @Test
    @DisplayName("Should generate correct prompt format")
    void testPromptFormat() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Description", "# Content", null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom", null);
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
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom", null);
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("Description with \"quotes\" and 'apostrophes'"));
    }

    @Test
    @DisplayName("Should handle mixed grouped and ungrouped skills")
    void testMixedGroupedAndUngroupedSkills() {
        skillGroupManager.createSkillGroup("group1", "Test Group", true);

        AgentSkill ungrouped = new AgentSkill("ungrouped", "Ungrouped", "# Content", null);
        RegisteredSkill registered1 = new RegisteredSkill("ungrouped_custom", null);
        skillRegistry.registerSkill("ungrouped_custom", ungrouped, registered1);

        AgentSkill grouped = new AgentSkill("grouped", "Grouped", "# Content", null);
        RegisteredSkill registered2 = new RegisteredSkill("grouped_custom", "group1");
        skillRegistry.registerSkill("grouped_custom", grouped, registered2);

        String prompt = provider.getSkillSystemPrompt();

        assertTrue(prompt.contains("Ungrouped"));
        assertTrue(prompt.contains("Grouped"));
    }

    @Test
    @DisplayName("Should update prompt when group activation changes")
    void testDynamicGroupActivation() {
        skillGroupManager.createSkillGroup("group1", "Test Group", false);

        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        RegisteredSkill registered = new RegisteredSkill("test_skill_custom", "group1");
        skillRegistry.registerSkill("test_skill_custom", skill, registered);

        String promptBefore = provider.getSkillSystemPrompt();
        assertEquals("", promptBefore);

        skillGroupManager.updateSkillGroups(java.util.List.of("group1"), true);

        String promptAfter = provider.getSkillSystemPrompt();
        assertTrue(promptAfter.contains("Test Skill"));
    }
}
