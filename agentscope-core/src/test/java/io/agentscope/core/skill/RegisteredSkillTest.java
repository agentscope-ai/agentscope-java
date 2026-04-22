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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegisteredSkillTest {

    @Test
    @DisplayName("Should create registered skill")
    void testCreate() {
        RegisteredSkill skill = new RegisteredSkill("skill_id");

        assertEquals("skill_id", skill.getSkillId());
        assertFalse(skill.isActive());
    }

    @Test
    @DisplayName("Should default to inactive state")
    void testDefaultInactiveState() {
        RegisteredSkill skill = new RegisteredSkill("test_skill");

        assertFalse(skill.isActive());
    }

    @Test
    @DisplayName("Should activate skill")
    void testActivateSkill() {
        RegisteredSkill skill = new RegisteredSkill("test_skill");

        skill.setActive(true);

        assertTrue(skill.isActive());
    }

    @Test
    @DisplayName("Should deactivate skill")
    void testDeactivateSkill() {
        RegisteredSkill skill = new RegisteredSkill("test_skill");
        skill.setActive(true);

        skill.setActive(false);

        assertFalse(skill.isActive());
    }

    @Test
    @DisplayName("Should toggle activation state")
    void testToggleActivationState() {
        RegisteredSkill skill = new RegisteredSkill("test_skill");

        skill.setActive(true);
        assertTrue(skill.isActive());

        skill.setActive(false);
        assertFalse(skill.isActive());

        skill.setActive(true);
        assertTrue(skill.isActive());
    }

    @Test
    @DisplayName("Should generate tools group name")
    void testGenerateToolsGroupName() {
        RegisteredSkill skill = new RegisteredSkill("my_skill");

        String toolsGroupName = skill.getToolsGroupName();

        assertEquals("my_skill_skill_tools", toolsGroupName);
    }

    @Test
    @DisplayName("Should generate tools group name with special characters")
    void testGenerateToolsGroupNameWithSpecialChars() {
        RegisteredSkill skill = new RegisteredSkill("skill-123_custom");

        String toolsGroupName = skill.getToolsGroupName();

        assertEquals("skill-123_custom_skill_tools", toolsGroupName);
    }

    @Test
    @DisplayName("Should maintain immutable skill id")
    void testImmutableSkillId() {
        RegisteredSkill skill = new RegisteredSkill("original_id");

        assertEquals("original_id", skill.getSkillId());

        skill.setActive(true);
        assertEquals("original_id", skill.getSkillId());
    }
}
