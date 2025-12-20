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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
    @DisplayName("Should register same skill id behavior")
    void testRegisterSameSkillIdBehavior() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");

        registry.registerSkill("test_custom", skill1, registered);

        // Register again with same ID - behavior depends on VersionedSkill implementation
        AgentSkill skill2 = createSkill("v2");
        registry.registerSkill("test_custom", skill2, registered);

        // Skill should still exist
        assertTrue(registry.exists("test_custom"));
        assertNotNull(registry.getSkill("test_custom"));
    }

    @Test
    @DisplayName("Should add new version")
    void testAddNewVersion() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        assertEquals(skill2, registry.getSkill("test_custom"));
        assertEquals("v2.0", registry.getLatestVersionId("test_custom"));
    }

    @Test
    @DisplayName("Should add old version")
    void testAddOldVersion() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);

        AgentSkill skill2 = createSkill("v2");
        registry.addOldVersion("test_custom", skill2, "old-v2");

        // Latest should remain unchanged
        assertEquals(skill1, registry.getSkill("test_custom"));

        // Old version should be accessible
        assertEquals(skill2, registry.getSkillVersion("test_custom", "old-v2"));
    }

    @Test
    @DisplayName("Should promote version to latest")
    void testPromoteVersionToLatest() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);
        String v1Id = registry.getLatestVersionId("test_custom");

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        // Promote v1 back to latest
        registry.promoteVersionToLatest("test_custom", v1Id);

        assertEquals(skill1, registry.getSkill("test_custom"));
        assertEquals(v1Id, registry.getLatestVersionId("test_custom"));
    }

    @Test
    @DisplayName("Should get skill version")
    void testGetSkillVersion() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        // Get latest by "latest" alias
        assertEquals(skill2, registry.getSkillVersion("test_custom", "latest"));

        // Get by version ID
        assertEquals(skill2, registry.getSkillVersion("test_custom", "v2.0"));

        // Non-existent skill
        assertNull(registry.getSkillVersion("non-existent", "latest"));

        // Non-existent version
        assertNull(registry.getSkillVersion("test_custom", "non-existent"));
    }

    @Test
    @DisplayName("Should list version ids")
    void testListVersionIds() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        List<String> versionIds = registry.listVersionIds("test_custom");
        assertTrue(versionIds.contains("v2.0"));
        assertTrue(versionIds.contains("latest"));

        // Non-existent skill
        assertTrue(registry.listVersionIds("non-existent").isEmpty());
    }

    @Test
    @DisplayName("Should remove version")
    void testRemoveVersion() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);
        String v1Id = registry.getLatestVersionId("test_custom");

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        // Remove old version
        registry.removeVersion("test_custom", v1Id);

        assertNull(registry.getSkillVersion("test_custom", v1Id));
        assertEquals(skill2, registry.getSkill("test_custom")); // Latest unchanged
    }

    @Test
    @DisplayName("Should clear old versions")
    void testClearOldVersions() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);
        String v1Id = registry.getLatestVersionId("test_custom");

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        registry.clearOldVersions("test_custom");

        // Old version should be removed
        assertNull(registry.getSkillVersion("test_custom", v1Id));

        // Latest should remain
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
    @DisplayName("Should remove skill without old versions")
    void testRemoveSkillWithoutOldVersions() {
        AgentSkill skill = createSkill("test");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill, registered);

        registry.removeSkill("test_custom", false);

        assertFalse(registry.exists("test_custom"));
        assertNull(registry.getSkill("test_custom"));
    }

    @Test
    @DisplayName("Should remove skill with old versions non forced")
    void testRemoveSkillWithOldVersionsNonForced() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        // Try to remove without force
        registry.removeSkill("test_custom", false);

        // Should still exist
        assertTrue(registry.exists("test_custom"));
    }

    @Test
    @DisplayName("Should remove skill with old versions forced")
    void testRemoveSkillWithOldVersionsForced() {
        AgentSkill skill1 = createSkill("v1");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill1, registered);

        AgentSkill skill2 = createSkill("v2");
        registry.addNewVersion("test_custom", skill2, "v2.0");

        // Force remove
        registry.removeSkill("test_custom", true);

        // Should be removed
        assertFalse(registry.exists("test_custom"));
    }

    @Test
    @DisplayName("Should remove non existent skill")
    void testRemoveNonExistentSkill() {
        registry.removeSkill("non-existent", true);
        // Should not throw exception
    }

    @Test
    @DisplayName("Should operations on non existent skill")
    void testOperationsOnNonExistentSkill() {
        // These should not throw exceptions
        registry.addNewVersion("non-existent", createSkill("test"), "v1");
        registry.addOldVersion("non-existent", createSkill("test"), "v1");
        registry.promoteVersionToLatest("non-existent", "v1");
        registry.removeVersion("non-existent", "v1");
        registry.clearOldVersions("non-existent");
        registry.setSkillActive("non-existent", true);

        assertNull(registry.getSkill("non-existent"));
        assertNull(registry.getRegisteredSkill("non-existent"));
        assertNull(registry.getLatestVersionId("non-existent"));
    }

    @Test
    @DisplayName("Should get latest version id")
    void testGetLatestVersionId() {
        AgentSkill skill = createSkill("test");
        RegisteredSkill registered = new RegisteredSkill("test_custom");
        registry.registerSkill("test_custom", skill, registered);

        String versionId = registry.getLatestVersionId("test_custom");
        assertNotNull(versionId);

        // Non-existent skill
        assertNull(registry.getLatestVersionId("non-existent"));
    }
}
