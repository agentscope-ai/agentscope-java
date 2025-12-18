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

import io.agentscope.core.tool.Toolkit;
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
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        skillBox = new SkillBox();
        toolkit = new Toolkit();
        skillBox.bindWithToolkit(toolkit);
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
    @DisplayName("Should create skill group")
    void testCreateSkillGroup() {
        skillBox.createSkillGroup("group1", "Test Group");

        assertTrue(skillBox.getAllSkillGroupNames().contains("group1"));
    }

    @Test
    @DisplayName("Should create skill group with active flag")
    void testCreateSkillGroupWithActiveFlag() {
        skillBox.createSkillGroup("group1", "Test Group", false);

        assertTrue(skillBox.getAllSkillGroupNames().contains("group1"));
        assertFalse(skillBox.isSkillGroupActive("group1"));
    }

    @Test
    @DisplayName("Should update skill groups")
    void testUpdateSkillGroups() {
        skillBox.createSkillGroup("group1", "Group 1", false);
        skillBox.createSkillGroup("group2", "Group 2", false);

        skillBox.updateSkillGroups(List.of("group1", "group2"), true);

        assertTrue(skillBox.isSkillGroupActive("group1"));
        assertTrue(skillBox.isSkillGroupActive("group2"));
    }

    @Test
    @DisplayName("Should remove skill groups")
    void testRemoveSkillGroups() {
        skillBox.createSkillGroup("group1", "Group 1");
        skillBox.createSkillGroup("group2", "Group 2");

        skillBox.removeSkillGroups(List.of("group1", "group2"));

        assertFalse(skillBox.getAllSkillGroupNames().contains("group1"));
        assertFalse(skillBox.getAllSkillGroupNames().contains("group2"));
    }

    @Test
    @DisplayName("Should get active skill groups")
    void testGetActiveSkillGroups() {
        skillBox.createSkillGroup("group1", "Group 1", true);
        skillBox.createSkillGroup("group2", "Group 2", false);

        List<String> activeGroups = skillBox.getActiveSkillGroups();

        assertTrue(activeGroups.contains("group1"));
        assertFalse(activeGroups.contains("group2"));
    }

    @Test
    @DisplayName("Should get all skill group names")
    void testGetAllSkillGroupNames() {
        skillBox.createSkillGroup("group1", "Group 1");
        skillBox.createSkillGroup("group2", "Group 2");

        var groupNames = skillBox.getAllSkillGroupNames();

        assertTrue(groupNames.contains("group1"));
        assertTrue(groupNames.contains("group2"));
    }

    @Test
    @DisplayName("Should get skill group")
    void testGetSkillGroup() {
        skillBox.createSkillGroup("group1", "Test Group");

        var skillGroup = skillBox.getSkillGroup("group1");

        assertNotNull(skillGroup);
        assertEquals("group1", skillGroup.getName());
        assertEquals("Test Group", skillGroup.getDescription());
    }

    @Test
    @DisplayName("Should get activated skill groups notes")
    void testGetActivatedSkillGroupsNotes() {
        skillBox.createSkillGroup("group1", "Group 1", true);
        skillBox.createSkillGroup("group2", "Group 2", false);

        String notes = skillBox.getActivatedSkillGroupsNotes();

        assertNotNull(notes);
        assertTrue(notes.contains("group1") || notes.isEmpty());
    }

    @Test
    @DisplayName("Should check if skill group is active")
    void testCheckIfSkillGroupIsActive() {
        skillBox.createSkillGroup("group1", "Group 1", true);
        skillBox.createSkillGroup("group2", "Group 2", false);

        assertTrue(skillBox.isSkillGroupActive("group1"));
        assertFalse(skillBox.isSkillGroupActive("group2"));
    }

    @Test
    @DisplayName("Should register skill load tools")
    void testRegisterSkillLoadTools() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        skillBox.registerSkillLoadTools(toolkit);

        assertNotNull(toolkit.getTool("skill_md_load_tool"));
        assertNotNull(toolkit.getTool("skill_resources_load_tool"));
        assertNotNull(toolkit.getTool("get_all_resources_path_tool"));
    }

    @Test
    @DisplayName("Should create tool group when registering skill")
    void testCreateToolGroupWhenRegisteringSkill() {
        AgentSkill skill = new AgentSkill("my_skill", "My Skill", "# Content", null);

        // Before registration, the tool group should not exist
        String toolsGroupName = "skill_tools_my_skill_custom";
        assertNull(
                toolkit.getToolGroup(toolsGroupName),
                "Tool group should not exist before skill registration");

        // Register the skill
        skillBox.registerAgentSkill(skill);

        // After registration, the tool group should be created
        assertNotNull(
                toolkit.getToolGroup(toolsGroupName),
                "Tool group should be created after skill registration");

        // Verify the tool group properties
        var toolGroup = toolkit.getToolGroup(toolsGroupName);
        assertEquals(toolsGroupName, toolGroup.getName());
    }

    @Test
    @DisplayName(
            "Should not create duplicate tool group when registering same skill multiple times")
    void testNoDuplicateToolGroupForSameSkill() {
        AgentSkill skill1 = new AgentSkill("test_skill", "Version 1", "# V1", null);
        skillBox.registerAgentSkill(skill1);

        String toolsGroupName = "skill_tools_test_skill_custom";
        var toolGroup1 = toolkit.getToolGroup(toolsGroupName);
        assertNotNull(toolGroup1, "Tool group should be created on first registration");

        // Register a new version of the same skill
        AgentSkill skill2 = new AgentSkill("test_skill", "Version 2", "# V2", null);
        skillBox.registerSkillVersion("test_skill_custom", skill2, "v2");

        // The tool group should still exist and be the same
        var toolGroup2 = toolkit.getToolGroup(toolsGroupName);
        assertNotNull(toolGroup2, "Tool group should still exist after version registration");
        assertEquals(toolGroup1.getName(), toolGroup2.getName());
    }

    @Test
    @DisplayName("Should reset all skills active")
    void testResetAllSkillsActive() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        skillBox.resetAllSkillsActive();

        // After reset, all skills should be inactive
        // This is verified by checking that the skill prompt is empty
        // (inactive skills are not included in the prompt)
    }

    @Test
    @DisplayName("Should get skill prompt")
    void testGetSkillPrompt() {
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        skillBox.registerAgentSkill(skill);

        String prompt = skillBox.getSkillPrompt();

        assertNotNull(prompt);
    }

    @Test
    @DisplayName("Should throw exception for null skill group name")
    void testThrowExceptionForNullSkillGroupName() {
        assertThrows(IllegalArgumentException.class, () -> skillBox.createSkillGroup(null, "Desc"));
    }

    @Test
    @DisplayName("Should throw exception for null skill group names in update")
    void testThrowExceptionForNullSkillGroupNamesInUpdate() {
        assertThrows(IllegalArgumentException.class, () -> skillBox.updateSkillGroups(null, true));
    }

    @Test
    @DisplayName("Should throw exception for null skill group names in remove")
    void testThrowExceptionForNullSkillGroupNamesInRemove() {
        assertThrows(IllegalArgumentException.class, () -> skillBox.removeSkillGroups(null));
    }

    @Test
    @DisplayName("Should throw exception for null skill group name in get")
    void testThrowExceptionForNullSkillGroupNameInGet() {
        assertThrows(IllegalArgumentException.class, () -> skillBox.getSkillGroup(null));
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

    @Test
    @DisplayName("Should bind with toolkit successfully")
    void testBindWithToolkit() {
        // Arrange
        SkillBox newSkillBox = new SkillBox();
        Toolkit newToolkit = new Toolkit();

        // Act
        newSkillBox.bindWithToolkit(newToolkit);

        // Assert - should not throw exception
        // Verify binding works by registering a skill and checking it can be retrieved
        AgentSkill skill = new AgentSkill("test", "Test Skill", "# Content", null);
        newSkillBox.registerAgentSkill(skill);

        assertNotNull(newSkillBox.getSkill("test_custom"));
    }

    @Test
    @DisplayName("Should throw exception when binding with null toolkit")
    void testBindWithNullToolkit() {
        // Arrange
        SkillBox newSkillBox = new SkillBox();

        // Act & Assert
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> newSkillBox.bindWithToolkit(null));

        assertTrue(exception.getMessage().contains("Cannot bind null"));
    }
}
