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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionedSkillTest {

    private AgentSkill createSkill(String name) {
        return new AgentSkill(name, "desc", "content", null);
    }

    @Test
    @DisplayName("Should constructor creates initial version")
    void testConstructorCreatesInitialVersion() {
        AgentSkill skill = createSkill("test");
        VersionedSkill versioned = new VersionedSkill(skill);

        assertEquals(skill, versioned.getLatestSkill());
        assertNotNull(versioned.getLatestVersionId());

        List<String> versionIds = versioned.getAllVersionIds();
        assertEquals(2, versionIds.size()); // actual version ID + "latest" alias
        assertTrue(versionIds.contains("latest"));
    }

    @Test
    @DisplayName("Should add version as latest")
    void testAddVersionAsLatest() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);
        String firstVersionId = versioned.getLatestVersionId();

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        // Latest should be updated
        assertEquals(skill2, versioned.getLatestSkill());
        assertEquals("v2.0", versioned.getLatestVersionId());

        // Old version should still be accessible
        assertEquals(skill1, versioned.getSkillByVersionId(firstVersionId));

        // Should have 3 version IDs: first, "v2.0", "latest"
        assertEquals(3, versioned.getAllVersionIds().size());
    }

    @Test
    @DisplayName("Should add version as old")
    void testAddVersionAsOld() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "old-v2", false);

        // Latest should remain unchanged
        assertEquals(skill1, versioned.getLatestSkill());

        // Old version should be accessible
        assertEquals(skill2, versioned.getSkillByVersionId("old-v2"));
    }

    @Test
    @DisplayName("Should add version skips same reference")
    void testAddVersionSkipsSameReference() {
        AgentSkill skill = createSkill("test");
        VersionedSkill versioned = new VersionedSkill(skill);

        int initialVersionCount = versioned.getAllVersionIds().size();

        // Try to add the same skill object again
        versioned.addVersion(skill, "duplicate", true);

        // Version count should not change
        assertEquals(initialVersionCount, versioned.getAllVersionIds().size());
    }

    @Test
    @DisplayName("Should add version with null version id")
    void testAddVersionWithNullVersionId() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, null, true);

        // Should have auto-generated version ID
        String versionId = versioned.getLatestVersionId();
        assertNotNull(versionId);
        assertFalse(versionId.isEmpty());
    }

    @Test
    @DisplayName("Should get skill by version id")
    void testGetSkillByVersionId() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);
        String v1Id = versioned.getLatestVersionId();

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        // Get by "latest" alias
        assertEquals(skill2, versioned.getSkillByVersionId("latest"));

        // Get by actual latest version ID
        assertEquals(skill2, versioned.getSkillByVersionId("v2.0"));

        // Get old version
        assertEquals(skill1, versioned.getSkillByVersionId(v1Id));

        // Non-existent version
        assertNull(versioned.getSkillByVersionId("non-existent"));
    }

    @Test
    @DisplayName("Should promote to latest")
    void testPromoteToLatest() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);
        String v1Id = versioned.getLatestVersionId();

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        assertEquals(skill2, versioned.getLatestSkill());
        // Promote v1 back to latest
        versioned.promoteToLatest(v1Id);

        // v1 should now be latest
        assertEquals(skill1, versioned.getLatestSkill());
        assertEquals(v1Id, versioned.getLatestVersionId());

        // v2.0 should now be in old versions
        assertEquals(skill2, versioned.getSkillByVersionId("v2.0"));
    }

    @Test
    @DisplayName("Should promote to latest ignores non existent")
    void testPromoteToLatestIgnoresNonExistent() {
        AgentSkill skill = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill);

        String originalVersionId = versioned.getLatestVersionId();

        // Try to promote non-existent version
        versioned.promoteToLatest("non-existent");

        // Latest should remain unchanged
        assertEquals(originalVersionId, versioned.getLatestVersionId());
    }

    @Test
    @DisplayName("Should remove version")
    void testRemoveVersion() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);
        String v1Id = versioned.getLatestVersionId();

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        // Remove old version
        versioned.removeVersion(v1Id);

        // Old version should no longer exist
        assertNull(versioned.getSkillByVersionId(v1Id));

        // Latest should remain unchanged
        assertEquals(skill2, versioned.getLatestSkill());
    }

    @Test
    @DisplayName("Should remove version ignores latest")
    void testRemoveVersionIgnoresLatest() {
        AgentSkill skill = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill);

        String latestVersionId = versioned.getLatestVersionId();

        // Try to remove latest version
        versioned.removeVersion(latestVersionId);

        // Latest should still exist
        assertEquals(skill, versioned.getLatestSkill());
        assertEquals(latestVersionId, versioned.getLatestVersionId());
    }

    @Test
    @DisplayName("Should get all version ids")
    void testGetAllVersionIds() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        AgentSkill skill3 = createSkill("v3");
        versioned.addVersion(skill3, "v3.0", false);

        List<String> allVersionIds = versioned.getAllVersionIds();

        // Should contain: v1's ID, "v2.0", "v3.0", "latest"
        assertEquals(4, allVersionIds.size());
        assertTrue(allVersionIds.contains("v2.0"));
        assertTrue(allVersionIds.contains("v3.0"));
        assertTrue(allVersionIds.contains("latest"));
    }

    @Test
    @DisplayName("Should get old version ids")
    void testGetOldVersionIds() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);
        String v1Id = versioned.getLatestVersionId();

        // Initially no old versions
        assertTrue(versioned.getOldVersionIds().isEmpty());

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        // Now should have one old version
        List<String> oldVersionIds = versioned.getOldVersionIds();
        assertEquals(1, oldVersionIds.size());
        assertTrue(oldVersionIds.contains(v1Id));
    }

    @Test
    @DisplayName("Should get old version skills")
    void testGetOldVersionSkills() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        List<AgentSkill> oldSkills = versioned.getOldVersionSkills();
        assertEquals(1, oldSkills.size());
        assertEquals(skill1, oldSkills.get(0));
    }

    @Test
    @DisplayName("Should has version")
    void testHasVersion() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);
        String v1Id = versioned.getLatestVersionId();

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        // "latest" alias always exists
        assertTrue(versioned.hasVersion("latest"));

        // Latest version ID exists
        assertTrue(versioned.hasVersion("v2.0"));

        // Old version exists
        assertTrue(versioned.hasVersion(v1Id));

        // Non-existent version
        assertFalse(versioned.hasVersion("non-existent"));
    }

    @Test
    @DisplayName("Should clear old versions")
    void testClearOldVersions() {
        AgentSkill skill1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(skill1);

        AgentSkill skill2 = createSkill("v2");
        versioned.addVersion(skill2, "v2.0", true);

        AgentSkill skill3 = createSkill("v3");
        versioned.addVersion(skill3, "v3.0", false);

        // Should have 2 old versions
        assertEquals(2, versioned.getOldVersionSkills().size());

        versioned.clearOldVersions();

        // Old versions should be cleared
        assertTrue(versioned.getOldVersionSkills().isEmpty());
        assertTrue(versioned.getOldVersionIds().isEmpty());

        // Latest should remain
        assertEquals(skill2, versioned.getLatestSkill());
    }

    @Test
    @DisplayName("Should complex version management")
    void testComplexVersionManagement() {
        AgentSkill v1 = createSkill("v1");
        VersionedSkill versioned = new VersionedSkill(v1);
        String v1Id = versioned.getLatestVersionId();

        // Add v2 as latest
        AgentSkill v2 = createSkill("v2");
        versioned.addVersion(v2, "v2.0", true);

        // Add v3 as old
        AgentSkill v3 = createSkill("v3");
        versioned.addVersion(v3, "v3.0", false);

        // Add v4 as latest
        AgentSkill v4 = createSkill("v4");
        versioned.addVersion(v4, "v4.0", true);

        // Current state: latest=v4, old=[v1, v2.0, v3.0]
        assertEquals(v4, versioned.getLatestSkill());
        assertEquals(3, versioned.getOldVersionSkills().size());

        // Promote v3.0 to latest
        versioned.promoteToLatest("v3.0");

        // Current state: latest=v3, old=[v1, v2.0, v4.0]
        assertEquals(v3, versioned.getLatestSkill());
        assertTrue(versioned.hasVersion("v4.0"));

        // Remove v2.0
        versioned.removeVersion("v2.0");

        // Current state: latest=v3, old=[v1, v4.0]
        assertEquals(2, versioned.getOldVersionSkills().size());
        assertFalse(versioned.hasVersion("v2.0"));
    }
}
