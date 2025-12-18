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

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SkillGroupManager.
 *
 * <p>Tests verify skill group lifecycle management, activation control, and skill-to-group
 * associations.
 */
class SkillGroupManagerTest {

    private SkillGroupManager manager;

    @BeforeEach
    void setUp() {
        manager = new SkillGroupManager();
    }

    @Test
    @DisplayName("Should create skill group with all parameters")
    void testCreateSkillGroupWithAllParameters() {
        // Act
        manager.createSkillGroup("data_analysis", "Data analysis skills", true);

        // Assert
        SkillGroup group = manager.getSkillGroup("data_analysis");
        assertNotNull(group);
        assertEquals("data_analysis", group.getName());
        assertEquals("Data analysis skills", group.getDescription());
        assertTrue(group.isActive());
        assertTrue(manager.getActiveGroups().contains("data_analysis"));
    }

    @Test
    @DisplayName("Should create skill group default active")
    void testCreateSkillGroupDefaultActive() {
        // Act
        manager.createSkillGroup("calculation", "Calculation skills");

        // Assert
        SkillGroup group = manager.getSkillGroup("calculation");
        assertNotNull(group);
        assertTrue(group.isActive());
        assertTrue(manager.getActiveGroups().contains("calculation"));
    }

    @Test
    @DisplayName("Should create skill group inactive")
    void testCreateSkillGroupInactive() {
        // Act
        manager.createSkillGroup("admin", "Admin skills", false);

        // Assert
        SkillGroup group = manager.getSkillGroup("admin");
        assertNotNull(group);
        assertFalse(group.isActive());
        assertFalse(manager.getActiveGroups().contains("admin"));
    }

    @Test
    @DisplayName("Should throw exception for duplicate skill group")
    void testCreateDuplicateSkillGroup() {
        // Arrange
        manager.createSkillGroup("data_analysis", "Data analysis skills");

        // Act & Assert
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.createSkillGroup("data_analysis", "Different description"));

        assertTrue(exception.getMessage().contains("already exists"));
        assertTrue(exception.getMessage().contains("data_analysis"));
    }

    @Test
    @DisplayName("Should update skill groups to activate")
    void testUpdateSkillGroupsActivate() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1", false);
        manager.createSkillGroup("group2", "Group 2", false);

        // Act
        manager.updateSkillGroups(List.of("group1", "group2"), true);

        // Assert
        assertTrue(manager.getSkillGroup("group1").isActive());
        assertTrue(manager.getSkillGroup("group2").isActive());
        assertTrue(manager.getActiveGroups().contains("group1"));
        assertTrue(manager.getActiveGroups().contains("group2"));
    }

    @Test
    @DisplayName("Should update skill groups to deactivate")
    void testUpdateSkillGroupsDeactivate() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1", true);
        manager.createSkillGroup("group2", "Group 2", true);

        // Act
        manager.updateSkillGroups(List.of("group1"), false);

        // Assert
        assertFalse(manager.getSkillGroup("group1").isActive());
        assertTrue(manager.getSkillGroup("group2").isActive());
        assertFalse(manager.getActiveGroups().contains("group1"));
        assertTrue(manager.getActiveGroups().contains("group2"));
    }

    @Test
    @DisplayName("Should throw exception for updating nonexistent skill group")
    void testUpdateNonexistentSkillGroup() {
        // Act & Assert
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.updateSkillGroups(List.of("nonexistent"), true));

        assertTrue(exception.getMessage().contains("does not exist"));
        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    @Test
    @DisplayName("Should remove skill groups")
    void testRemoveSkillGroups() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1");
        manager.createSkillGroup("group2", "Group 2");
        manager.addSkillToGroup("group1", "skill1");
        manager.addSkillToGroup("group1", "skill2");
        manager.addSkillToGroup("group2", "skill3");

        // Act
        Set<String> removedSkills = manager.removeSkillGroups(List.of("group1", "group2"));

        // Assert
        assertEquals(3, removedSkills.size());
        assertTrue(removedSkills.contains("skill1"));
        assertTrue(removedSkills.contains("skill2"));
        assertTrue(removedSkills.contains("skill3"));
        assertNull(manager.getSkillGroup("group1"));
        assertNull(manager.getSkillGroup("group2"));
        assertFalse(manager.getActiveGroups().contains("group1"));
        assertFalse(manager.getActiveGroups().contains("group2"));
    }

    @Test
    @DisplayName("Should handle removing nonexistent skill group")
    void testRemoveNonexistentSkillGroup() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1");

        // Act - should not throw, just log warning
        Set<String> removedSkills = manager.removeSkillGroups(List.of("nonexistent", "group1"));

        // Assert
        assertNotNull(removedSkills);
        assertNull(manager.getSkillGroup("group1"));
    }

    @Test
    @DisplayName("Should get activated notes when empty")
    void testGetActivatedNotesEmpty() {
        // Act
        String notes = manager.getActivatedNotes();

        // Assert
        assertTrue(notes.contains("No skill groups"));
    }

    @Test
    @DisplayName("Should get activated notes with groups")
    void testGetActivatedNotesWithGroups() {
        // Arrange
        manager.createSkillGroup("data_analysis", "Data analysis skills", true);
        manager.createSkillGroup("calculation", "Calculation skills", true);
        manager.createSkillGroup("admin", "Admin skills", false);

        // Act
        String notes = manager.getActivatedNotes();

        // Assert
        assertTrue(notes.contains("Activated skill groups"));
        assertTrue(notes.contains("data_analysis"));
        assertTrue(notes.contains("Data analysis skills"));
        assertTrue(notes.contains("calculation"));
        assertTrue(notes.contains("Calculation skills"));
        assertFalse(notes.contains("admin"));
    }

    @Test
    @DisplayName("Should validate group exists")
    void testValidateGroupExists() {
        // Arrange
        manager.createSkillGroup("existing", "Existing group");

        // Act & Assert - should not throw
        manager.validateGroupExists("existing");
    }

    @Test
    @DisplayName("Should throw exception when validating nonexistent group")
    void testValidateGroupDoesNotExist() {
        // Act & Assert
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.validateGroupExists("nonexistent"));

        assertTrue(exception.getMessage().contains("does not exist"));
        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    @Test
    @DisplayName("Should return true for ungrouped skills")
    void testIsInActiveGroupUngrouped() {
        // Act & Assert
        assertTrue(manager.isInActiveGroup(null));
    }

    @Test
    @DisplayName("Should return true for active group")
    void testIsInActiveGroupActive() {
        // Arrange
        manager.createSkillGroup("active", "Active group", true);

        // Act & Assert
        assertTrue(manager.isInActiveGroup("active"));
    }

    @Test
    @DisplayName("Should return false for inactive group")
    void testIsInActiveGroupInactive() {
        // Arrange
        manager.createSkillGroup("inactive", "Inactive group", false);

        // Act & Assert
        assertFalse(manager.isInActiveGroup("inactive"));
    }

    @Test
    @DisplayName("Should return false for nonexistent group")
    void testIsInActiveGroupNonexistent() {
        // Act & Assert
        assertFalse(manager.isInActiveGroup("nonexistent"));
    }

    @Test
    @DisplayName("Should add skill to group")
    void testAddSkillToGroup() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1");

        // Act
        manager.addSkillToGroup("group1", "skill1");
        manager.addSkillToGroup("group1", "skill2");

        // Assert
        SkillGroup group = manager.getSkillGroup("group1");
        assertTrue(group.containsSkill("skill1"));
        assertTrue(group.containsSkill("skill2"));
    }

    @Test
    @DisplayName("Should handle adding skill to nonexistent group")
    void testAddSkillToNonexistentGroup() {
        // Act - should not throw, just do nothing
        manager.addSkillToGroup("nonexistent", "skill1");

        // Assert
        assertNull(manager.getSkillGroup("nonexistent"));
    }

    @Test
    @DisplayName("Should remove skill from group")
    void testRemoveSkillFromGroup() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1");
        manager.addSkillToGroup("group1", "skill1");
        manager.addSkillToGroup("group1", "skill2");

        // Act
        manager.removeSkillFromGroup("group1", "skill1");

        // Assert
        SkillGroup group = manager.getSkillGroup("group1");
        assertFalse(group.containsSkill("skill1"));
        assertTrue(group.containsSkill("skill2"));
    }

    @Test
    @DisplayName("Should handle removing skill from nonexistent group")
    void testRemoveSkillFromNonexistentGroup() {
        // Act - should not throw, just do nothing
        manager.removeSkillFromGroup("nonexistent", "skill1");

        // Assert
        assertNull(manager.getSkillGroup("nonexistent"));
    }

    @Test
    @DisplayName("Should get all skill group names")
    void testGetSkillGroupNames() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1");
        manager.createSkillGroup("group2", "Group 2");
        manager.createSkillGroup("group3", "Group 3");

        // Act
        Set<String> names = manager.getSkillGroupNames();

        // Assert
        assertEquals(3, names.size());
        assertTrue(names.contains("group1"));
        assertTrue(names.contains("group2"));
        assertTrue(names.contains("group3"));
    }

    @Test
    @DisplayName("Should return empty set when no groups exist")
    void testGetSkillGroupNamesEmpty() {
        // Act
        Set<String> names = manager.getSkillGroupNames();

        // Assert
        assertTrue(names.isEmpty());
    }

    @Test
    @DisplayName("Should get active groups")
    void testGetActiveGroups() {
        // Arrange
        manager.createSkillGroup("active1", "Active 1", true);
        manager.createSkillGroup("active2", "Active 2", true);
        manager.createSkillGroup("inactive", "Inactive", false);

        // Act
        List<String> activeGroups = manager.getActiveGroups();

        // Assert
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("active1"));
        assertTrue(activeGroups.contains("active2"));
        assertFalse(activeGroups.contains("inactive"));
    }

    @Test
    @DisplayName("Should return empty list when no active groups")
    void testGetActiveGroupsEmpty() {
        // Arrange
        manager.createSkillGroup("inactive", "Inactive", false);

        // Act
        List<String> activeGroups = manager.getActiveGroups();

        // Assert
        assertTrue(activeGroups.isEmpty());
    }

    @Test
    @DisplayName("Should set active groups")
    void testSetActiveGroups() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1", false);
        manager.createSkillGroup("group2", "Group 2", false);
        manager.createSkillGroup("group3", "Group 3", true);

        // Act
        manager.setActiveGroups(List.of("group1", "group2"));

        // Assert
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("group1"));
        assertTrue(activeGroups.contains("group2"));

        // Check that groups are marked as active
        assertTrue(manager.getSkillGroup("group1").isActive());
        assertTrue(manager.getSkillGroup("group2").isActive());
    }

    @Test
    @DisplayName("Should handle setting active groups with nonexistent")
    void testSetActiveGroupsWithNonexistent() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1", false);

        // Act - should not throw, just ignore nonexistent
        manager.setActiveGroups(List.of("group1", "nonexistent"));

        // Assert
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("group1"));
        assertTrue(activeGroups.contains("nonexistent"));
        assertTrue(manager.getSkillGroup("group1").isActive());
    }

    @Test
    @DisplayName("Should get skill group by name")
    void testGetSkillGroup() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1");

        // Act
        SkillGroup group = manager.getSkillGroup("group1");

        // Assert
        assertNotNull(group);
        assertEquals("group1", group.getName());
        assertEquals("Group 1", group.getDescription());
    }

    @Test
    @DisplayName("Should return null for nonexistent skill group")
    void testGetSkillGroupNonexistent() {
        // Act
        SkillGroup group = manager.getSkillGroup("nonexistent");

        // Assert
        assertNull(group);
    }

    @Test
    @DisplayName("Should prevent duplicates in active groups")
    void testUpdatePreventsDuplicatesInActiveGroups() {
        // Arrange
        manager.createSkillGroup("group1", "Group 1", true);

        // Act - activate the same group multiple times
        manager.updateSkillGroups(List.of("group1"), true);
        manager.updateSkillGroups(List.of("group1"), true);

        // Assert - should not have duplicates
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(1, activeGroups.stream().filter(g -> g.equals("group1")).count());
    }

    @Test
    @DisplayName("Should handle complex scenario")
    void testComplexScenario() {
        // Arrange
        manager.createSkillGroup("data_analysis", "Data analysis skills", true);
        manager.createSkillGroup("calculation", "Calculation skills", true);
        manager.createSkillGroup("admin", "Admin skills", false);

        manager.addSkillToGroup("data_analysis", "statistics");
        manager.addSkillToGroup("data_analysis", "visualization");
        manager.addSkillToGroup("calculation", "math");

        // Act - deactivate data_analysis, activate admin
        manager.updateSkillGroups(List.of("data_analysis"), false);
        manager.updateSkillGroups(List.of("admin"), true);

        // Assert
        assertFalse(manager.isInActiveGroup("data_analysis"));
        assertTrue(manager.isInActiveGroup("calculation"));
        assertTrue(manager.isInActiveGroup("admin"));

        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("calculation"));
        assertTrue(activeGroups.contains("admin"));

        // Remove data_analysis
        Set<String> removedSkills = manager.removeSkillGroups(List.of("data_analysis"));
        assertEquals(2, removedSkills.size());
        assertTrue(removedSkills.contains("statistics"));
        assertTrue(removedSkills.contains("visualization"));

        assertNull(manager.getSkillGroup("data_analysis"));
        assertEquals(2, manager.getSkillGroupNames().size());
    }
}
