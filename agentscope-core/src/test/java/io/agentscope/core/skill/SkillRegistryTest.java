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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        registry.registerSkill("test", skill);

        assertTrue(registry.exists("test"));
        assertEquals(skill, registry.getSkill("test"));
    }

    @Test
    @DisplayName("Should re-registering the same instance be idempotent")
    void testRegisterSameInstanceIsIdempotent() {
        AgentSkill skill = createSkill("v1");
        registry.registerSkill("test", skill);
        registry.registerSkill("test", skill);
        assertTrue(registry.exists("test"));
        assertEquals(skill, registry.getSkill("test"));
    }

    @Test
    @DisplayName("Should reject a different instance under an already-used name")
    void testRegisterDuplicateNameDifferentInstanceRejected() {
        registry.registerSkill("test", createSkill("v1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerSkill("test", createSkill("v2")));
        assertTrue(registry.exists("test"));
    }

    @Test
    @DisplayName("Should get skill ids")
    void testGetSkillIds() {
        registry.registerSkill("test1", createSkill("test1"));
        registry.registerSkill("test2", createSkill("test2"));

        var skillNames = registry.getSkillNames();
        assertEquals(2, skillNames.size());
        assertTrue(skillNames.contains("test1"));
        assertTrue(skillNames.contains("test2"));
    }

    @Test
    @DisplayName("Should exists")
    void testExists() {
        registry.registerSkill("test", createSkill("test"));

        assertTrue(registry.exists("test"));
        assertFalse(registry.exists("non-existent"));
    }

    @Test
    @DisplayName("Should remove skill")
    void testRemoveSkill() {
        registry.registerSkill("test", createSkill("test"));

        registry.removeSkill("test");

        assertFalse(registry.exists("test"));
        assertNull(registry.getSkill("test"));
    }

    @Test
    @DisplayName("Should remove non existent skill")
    void testRemoveNonExistentSkill() {
        registry.removeSkill("non-existent");
        // Should not throw exception
    }
}
