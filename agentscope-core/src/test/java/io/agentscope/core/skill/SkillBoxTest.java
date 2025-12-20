/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SkillBox.
 *
 * <p>These tests verify skill registration, versioning, group management, and integration with
 * Toolkit.
 *
 * <p>Migrated from ToolkitTest after architectural refactoring that moved skill management from
 * Toolkit to SkillBox.
 */
@Tag("unit")
class SkillBoxTest {

    private SkillBox skillBox;
    private MockToolkit mockToolkit;

    @BeforeEach
    void setUp() {
        mockToolkit = new MockToolkit();
        skillBox = new SkillBox(mockToolkit);
    }

    @Test
    @DisplayName("Should get skill by id")
    void testGetSkillById() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        AgentSkill retrieved = skillBox.getSkill("test_skill_custom");

        assertNotNull(retrieved);
        assertEquals("test_skill", retrieved.getName());
        assertEquals("Test Skill", retrieved.getDescription());
    }

    @Test
    @DisplayName("Should throw exception for null skill id")
    void testThrowExceptionForNullSkillId() {
        assertThrows(IllegalArgumentException.class, () -> skillBox.getSkill(null));
    }

    @Test
    @DisplayName("Should register skill version")
    void testRegisterSkillVersion() {
        AgentSkill skill1 = new AgentSkill("test_skill", "Version 1", "# V1", null);
        skillBox.registerAgentSkill(skill1);

        AgentSkill skill2 = new AgentSkill("test_skill", "Version 2", "# V2", null);
        skillBox.registerSkillVersion("test_skill_custom", skill2, "v2");

        // After registerSkillVersion, the new version becomes the latest
        AgentSkill latest = skillBox.getSkill("test_skill_custom");
        assertEquals("Version 2", latest.getDescription());

        // The old version can still be accessed
        List<String> versions = skillBox.listSkillVersions("test_skill_custom");
        assertTrue(versions.size() >= 2);
    }

    @Test
    @DisplayName("Should rollback skill version")
    void testRollbackSkillVersion() {
        AgentSkill skill1 = new AgentSkill("test_skill", "Version 1", "# V1", null);
        skillBox.registerAgentSkill(skill1);

        AgentSkill skill2 = new AgentSkill("test_skill", "Version 2", "# V2", null);
        skillBox.registerSkillVersion("test_skill_custom", skill2, "v2");

        skillBox.rollbackSkillVersion("test_skill_custom", "v2");

        AgentSkill latest = skillBox.getSkill("test_skill_custom");
        assertEquals("Version 2", latest.getDescription());
    }

    @Test
    @DisplayName("Should list skill versions")
    void testListSkillVersions() {
        AgentSkill skill1 = new AgentSkill("test_skill", "Version 1", "# V1", null);
        skillBox.registerAgentSkill(skill1);

        AgentSkill skill2 = new AgentSkill("test_skill", "Version 2", "# V2", null);
        skillBox.registerSkillVersion("test_skill_custom", skill2, "v2");

        List<String> versions = skillBox.listSkillVersions("test_skill_custom");

        assertNotNull(versions);
        assertTrue(versions.size() >= 2);
        assertTrue(versions.contains("v2"));
        assertTrue(versions.contains("latest"));
    }

    @Test
    @DisplayName("Should get latest skill version id")
    void testGetLatestSkillVersionId() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        String latestVersionId = skillBox.getLatestSkillVersionId("test_skill_custom");

        assertNotNull(latestVersionId);
    }

    @Test
    @DisplayName("Should clear skill old versions")
    void testClearSkillOldVersions() {
        AgentSkill skill1 = new AgentSkill("test_skill", "Version 1", "# V1", null);
        skillBox.registerAgentSkill(skill1);

        AgentSkill skill2 = new AgentSkill("test_skill", "Version 2", "# V2", null);
        skillBox.registerSkillVersion("test_skill_custom", skill2, "v2");

        AgentSkill skill3 = new AgentSkill("test_skill", "Version 3", "# V3", null);
        skillBox.registerSkillVersion("test_skill_custom", skill3, "v3");

        skillBox.clearSkillOldVersions("test_skill_custom");

        List<String> versions = skillBox.listSkillVersions("test_skill_custom");
        // Should only have latest and "latest" alias
        assertTrue(versions.size() <= 2);
    }

    @Test
    @DisplayName("Should remove skill")
    void testRemoveSkill() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        assertTrue(skillBox.skillExists("test_skill_custom"));

        skillBox.removeSkill("test_skill_custom");

        assertFalse(skillBox.skillExists("test_skill_custom"));
    }

    @Test
    @DisplayName("Should check skill exists")
    void testCheckSkillExists() {
        assertFalse(skillBox.skillExists("non_existent_skill"));

        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        assertTrue(skillBox.skillExists("test_skill_custom"));
    }

    @Test
    @DisplayName("Should register skill load tools")
    void testRegisterSkillLoadTools() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        skillBox.registerSkillLoadTools(mockToolkit);

        assertNotNull(mockToolkit.getTool("skill_md_load_tool"));
        assertNotNull(mockToolkit.getTool("skill_resources_load_tool"));
        assertNotNull(mockToolkit.getTool("get_all_resources_path_tool"));
    }

    @Test
    @DisplayName("Should create tool group when registering skill")
    void testCreateToolGroupWhenRegisteringSkill() {
        AgentSkill skill = new AgentSkill("my_skill", "My Skill", "# Content", null);

        // Before registration, the tool group should not exist
        String toolsGroupName = skill.getSkillId() + "_skill_tools";
        assertNull(
                mockToolkit.getToolGroup(toolsGroupName),
                "Tool group should not exist before skill registration");

        // Register the skill
        skillBox.registration()
                .agentTool(mockToolkit.getTool(TestConstants.CALCULATOR_TOOL_NAME))
                .skill(skill)
                .apply();

        // After registration, the tool group should be created
        assertNotNull(
                mockToolkit.getToolGroup(toolsGroupName),
                "Tool group should be created after skill registration");

        // Verify the tool group properties
        var toolGroup = mockToolkit.getToolGroup(toolsGroupName);
        assertEquals(toolsGroupName, toolGroup.getName());
    }

    @Test
    @DisplayName(
            "Should not create duplicate tool group when registering same skill multiple times")
    void testNoDuplicateToolGroupForSameSkill() {
        AgentSkill skill1 = new AgentSkill("test_skill", "Version 1", "# V1", null);
        String toolsGroupName = skill1.getSkillId() + "_skill_tools";

        skillBox.registration()
                .agentTool(mockToolkit.getTool(TestConstants.CALCULATOR_TOOL_NAME))
                .skill(skill1)
                .apply();

        var toolGroup1 = mockToolkit.getToolGroup(toolsGroupName);
        assertNotNull(toolGroup1, "Tool group should be created on first registration");

        // Register a new version of the same skill
        AgentSkill skill2 = new AgentSkill("test_skill", "Version 2", "# V2", null);
        skillBox.registerSkillVersion("test_skill_custom", skill2, "v2");

        // The tool group should still exist and be the same
        var toolGroup2 = mockToolkit.getToolGroup(toolsGroupName);
        assertNotNull(toolGroup2, "Tool group should still exist after version registration");
        assertEquals(toolGroup1.getName(), toolGroup2.getName());
    }

    @Test
    @DisplayName("Should throw exception for null skill id in version operations")
    void testThrowExceptionForNullSkillIdInVersionOperations() {
        AgentSkill skill = new AgentSkill("test", "Test", "# Content", null);

        assertThrows(
                IllegalArgumentException.class,
                () -> skillBox.registerSkillVersion(null, skill, "v2"));
        assertThrows(
                IllegalArgumentException.class, () -> skillBox.rollbackSkillVersion(null, "v2"));
        assertThrows(IllegalArgumentException.class, () -> skillBox.listSkillVersions(null));
        assertThrows(IllegalArgumentException.class, () -> skillBox.getLatestSkillVersionId(null));
        assertThrows(IllegalArgumentException.class, () -> skillBox.removeSkillVersion(null, "v2"));
        assertThrows(IllegalArgumentException.class, () -> skillBox.clearSkillOldVersions(null));
        assertThrows(IllegalArgumentException.class, () -> skillBox.removeSkill(null));
        assertThrows(IllegalArgumentException.class, () -> skillBox.skillExists(null));
    }
}
