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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    private AgentSkill createSkill(String name) {
        return new AgentSkill(name, "desc", "content", null);
    }

    @Test
    @DisplayName("Should register new skill")
    void testRegisterNewSkill() {
        AgentSkill skill = createSkill("test");
        RegisteredSkill registered = new RegisteredSkill("test_custom");

        registry.registerSkill("test_custom", skill, registered);

        assertTrue(registry.exists("test_custom"));
        assertEquals(skill, registry.getSkill("test_custom"));
        assertEquals(registered, registry.getRegisteredSkill("test_custom"));
    }

    @Test
    @DisplayName("Should register same skill id replaces existing")
    void testRegisterSameSkillIdReplacesExisting() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");

        registry.registerSkill("test_custom", skill1, registered);

        // Register again with same ID - should replace
        AgentSkill skill2 = createSkill("v2");
        registry.registerSkill("test_custom", skill2, registered);

        // Skill should still exist and be replaced
        assertTrue(registry.exists("test_custom"));
        assertEquals(skill2, registry.getSkill("test_custom"));
    }

    @Test
    @DisplayName("Should set skill active")
    void testSetSkillActive() {
        AgentSkill skill = createSkill("test");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill, registered);

        // Initially inactive
        assertFalse(registered.isActive());

        // Activate
        registry.setSkillActive("test_custom", true);
        assertTrue(registered.isActive());

        // Deactivate
        registry.setSkillActive("test_custom", false);
        assertFalse(registered.isActive());
    }

    @Test
    @DisplayName("Should set all skills active")
    void testSetAllSkillsActive() {
        AgentSkill skill1 = createSkill("test1");
        RegisteredSkill registered1 = new RegisteredSkill("test1_custom");
        registry.registerSkill("test1_custom", skill1, registered1);

        AgentSkill skill2 = createSkill("test2");
        RegisteredSkill registered2 = new RegisteredSkill("test2_custom");
        registry.registerSkill("test2_custom", skill2, registered2);

        // Activate all
        registry.setAllSkillsActive(true);
        assertTrue(registered1.isActive());
        assertTrue(registered2.isActive());

        // Deactivate all
        registry.setAllSkillsActive(false);
        assertFalse(registered1.isActive());
        assertFalse(registered2.isActive());
    }

    @Test
    @DisplayName("Should get skill ids")
    void testGetSkillIds() {
        AgentSkill skill1 = createSkill("test1");
        RegisteredSkill registered1 = new RegisteredSkill("test1_custom");
        registry.registerSkill("test1_custom", skill1, registered1);

        AgentSkill skill2 = createSkill("test2");
        RegisteredSkill registered2 = new RegisteredSkill("test2_custom");
        registry.registerSkill("test2_custom", skill2, registered2);

        var skillIds = registry.getSkillIds();
        assertEquals(2, skillIds.size());
        assertTrue(skillIds.contains("test1_custom"));
        assertTrue(skillIds.contains("test2_custom"));
    }

    @Test
    @DisplayName("Should get all registered skills")
    void testGetAllRegisteredSkills() {
        AgentSkill skill1 = createSkill("test1");
        RegisteredSkill registered1 = new RegisteredSkill("test1_custom");
        registry.registerSkill("test1_custom", skill1, registered1);

        AgentSkill skill2 = createSkill("test2");
        RegisteredSkill registered2 = new RegisteredSkill("test2_custom");
        registry.registerSkill("test2_custom", skill2, registered2);

        Map<String, RegisteredSkill> allRegistered = registry.getAllRegisteredSkills();
        assertEquals(2, allRegistered.size());
        assertEquals(registered1, allRegistered.get("test1_custom"));
        assertEquals(registered2, allRegistered.get("test2_custom"));
    }

    @Test
    @DisplayName("Should exists")
    void testExists() {
        AgentSkill skill = createSkill("test");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill, registered);

        assertTrue(registry.exists("test_custom"));
        assertFalse(registry.exists("non-existent"));
    }

    @Test
    @DisplayName("Should remove skill")
    void testRemoveSkill() {
        AgentSkill skill = createSkill("test");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill, registered);

        registry.removeSkill("test_custom");

        assertFalse(registry.exists("test_custom"));
        assertNull(registry.getSkill("test_custom"));
    }

    @Test
    @DisplayName("Should remove non existent skill")
    void testRemoveNonExistentSkill() {
        registry.removeSkill("non-existent");
        // Should not throw exception
    }

    @Test
    @DisplayName("Should operations on non existent skill")
    void testOperationsOnNonExistentSkill() {
        // These should not throw exceptions
        registry.setSkillActive("non-existent", true);

        assertNull(registry.getSkill("non-existent"));
        assertNull(registry.getRegisteredSkill("non-existent"));
    }
}
