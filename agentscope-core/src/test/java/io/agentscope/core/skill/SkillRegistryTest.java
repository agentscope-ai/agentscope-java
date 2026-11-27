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

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SkillRegistry, including the id-collision tripwire: underscore-free names make
 * the {@code name_source} id split deterministic, so a same-id overwrite of a different (name,
 * source) pair can only arrive via caller-supplied keys and is flagged rather than silent.
 */
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

        registry.registerSkill("test_custom", skill);

        assertTrue(registry.exists("test_custom"));
        assertEquals(skill, registry.getSkill("test_custom"));
    }

    @Test
    @DisplayName("Should register same skill id replaces existing")
    void testRegisterSameSkillIdReplacesExisting() {
        AgentSkill skill1 = createSkill("v1");
        registry.registerSkill("test_custom", skill1);

        AgentSkill skill2 = createSkill("v2");
        registry.registerSkill("test_custom", skill2);

        assertTrue(registry.exists("test_custom"));
        assertEquals(skill2, registry.getSkill("test_custom"));
    }

    @Test
    @DisplayName("Should keep ids unambiguous by rejecting underscore names at construction")
    void testUnderscoreNamesAreRejectedSoIdsSplitDeterministically() {
        // Names cannot contain the id separator "_", so "name_source" always splits at the
        // first underscore and distinct (name, source) pairs cannot share an id.
        assertThrows(IllegalArgumentException.class, () -> createSkill("a_b"));
        assertThrows(IllegalArgumentException.class, () -> createSkill("b-c_d"));

        AgentSkill a = new AgentSkill("a", "desc", "content", null, "b-c");
        assertEquals("a_b-c", a.getSkillId());
    }

    @Test
    @DisplayName("Should flag same-id overwrite of a different pair but not normal re-registration")
    void testMismatchedIdOverwriteIsFlaggedButNormalReregistrationIsNot() {
        AgentSkill first = new AgentSkill("a", "desc-1", "content-1", null, "custom");
        AgentSkill updated = new AgentSkill("a", "desc-1", "content-2", null, "custom");
        AgentSkill differentPair = new AgentSkill("a", "desc-2", "content-2", null, "other");

        // Normal re-registration (same name + source, new content) and a free id are not
        // collisions; only a different (name, source) pair under the same id is.
        assertFalse(SkillRegistry.isAmbiguousIdCollision(first, updated));
        assertFalse(SkillRegistry.isAmbiguousIdCollision(null, first));
        assertTrue(SkillRegistry.isAmbiguousIdCollision(first, differentPair));

        // Through caller-supplied keys the tripwire must still fire and the last skill wins.
        registry.registerSkill("a_custom", first);
        registry.registerSkill("a_custom", differentPair);

        assertEquals(Set.of("a_custom"), registry.getSkillIds());
        assertEquals(differentPair, registry.getSkill("a_custom"));
    }

    @Test
    @DisplayName("Should get skill ids")
    void testGetSkillIds() {
        registry.registerSkill("test1_custom", createSkill("test1"));
        registry.registerSkill("test2_custom", createSkill("test2"));

        var skillIds = registry.getSkillIds();
        assertEquals(2, skillIds.size());
        assertTrue(skillIds.contains("test1_custom"));
        assertTrue(skillIds.contains("test2_custom"));
    }

    @Test
    @DisplayName("Should exists")
    void testExists() {
        registry.registerSkill("test_custom", createSkill("test"));

        assertTrue(registry.exists("test_custom"));
        assertFalse(registry.exists("non-existent"));
    }

    @Test
    @DisplayName("Should remove skill")
    void testRemoveSkill() {
        registry.registerSkill("test_custom", createSkill("test"));

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
}
