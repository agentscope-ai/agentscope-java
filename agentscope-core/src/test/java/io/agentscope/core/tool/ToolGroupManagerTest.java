/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolGroupManagerTest {

    private ToolGroupManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolGroupManager();
    }

    @Test
    void testCreateToolGroupWithAllParameters() {
        // Act
        manager.createToolGroup("analytics", "Analytics tools", true);

        // Assert
        ToolGroup group = manager.getToolGroup("analytics");
        assertNotNull(group);
        assertEquals("analytics", group.getName());
        assertEquals("Analytics tools", group.getDescription());
        assertTrue(group.isActive());
        assertTrue(manager.getActiveGroups().contains("analytics"));
    }

    @Test
    void testCreateToolGroupDefaultActive() {
        // Act
        manager.createToolGroup("search", "Search tools");

        // Assert
        ToolGroup group = manager.getToolGroup("search");
        assertNotNull(group);
        assertTrue(group.isActive());
        assertTrue(manager.getActiveGroups().contains("search"));
    }

    @Test
    void testCreateToolGroupInactive() {
        // Act
        manager.createToolGroup("admin", "Admin tools", false);

        // Assert
        ToolGroup group = manager.getToolGroup("admin");
        assertNotNull(group);
        assertFalse(group.isActive());
        assertFalse(manager.getActiveGroups().contains("admin"));
    }

    @Test
    void testCreateDuplicateToolGroup() {
        // Arrange
        manager.createToolGroup("analytics", "Analytics tools");

        // Act & Assert
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.createToolGroup("analytics", "Different description"));

        assertTrue(exception.getMessage().contains("already exists"));
        assertTrue(exception.getMessage().contains("analytics"));
    }

    @Test
    void testUpdateToolGroupsActivate() {
        // Arrange
        manager.createToolGroup("group1", "Group 1", false);
        manager.createToolGroup("group2", "Group 2", false);

        // Act
        manager.updateToolGroups(List.of("group1", "group2"), true);

        // Assert
        assertTrue(manager.getToolGroup("group1").isActive());
        assertTrue(manager.getToolGroup("group2").isActive());
        assertTrue(manager.getActiveGroups().contains("group1"));
        assertTrue(manager.getActiveGroups().contains("group2"));
    }

    @Test
    void testUpdateToolGroupsDeactivate() {
        // Arrange
        manager.createToolGroup("group1", "Group 1", true);
        manager.createToolGroup("group2", "Group 2", true);

        // Act
        manager.updateToolGroups(List.of("group1"), false);

        // Assert
        assertFalse(manager.getToolGroup("group1").isActive());
        assertTrue(manager.getToolGroup("group2").isActive());
        assertFalse(manager.getActiveGroups().contains("group1"));
        assertTrue(manager.getActiveGroups().contains("group2"));
    }

    @Test
    void testUpdateNonexistentToolGroup() {
        // Act & Assert
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.updateToolGroups(List.of("nonexistent"), true));

        assertTrue(exception.getMessage().contains("does not exist"));
        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    @Test
    void testRemoveToolGroups() {
        // Arrange
        manager.createToolGroup("group1", "Group 1");
        manager.createToolGroup("group2", "Group 2");
        manager.addToolToGroup("group1", "tool1");
        manager.addToolToGroup("group1", "tool2");
        manager.addToolToGroup("group2", "tool3");

        // Act
        Set<String> removedTools = manager.removeToolGroups(List.of("group1", "group2"));

        // Assert
        assertEquals(3, removedTools.size());
        assertTrue(removedTools.contains("tool1"));
        assertTrue(removedTools.contains("tool2"));
        assertTrue(removedTools.contains("tool3"));
        assertNull(manager.getToolGroup("group1"));
        assertNull(manager.getToolGroup("group2"));
        assertFalse(manager.getActiveGroups().contains("group1"));
        assertFalse(manager.getActiveGroups().contains("group2"));
    }

    @Test
    void testRemoveNonexistentToolGroup() {
        // Arrange
        manager.createToolGroup("group1", "Group 1");

        // Act - should not throw, just log warning
        Set<String> removedTools = manager.removeToolGroups(List.of("nonexistent", "group1"));

        // Assert
        assertNotNull(removedTools);
        assertNull(manager.getToolGroup("group1"));
    }

    @Test
    void testGetActivatedNotesEmpty() {
        // Act
        String notes = manager.getActivatedNotes();

        // Assert
        assertTrue(notes.contains("No tool groups"));
    }

    @Test
    void testGetActivatedNotesWithGroups() {
        // Arrange
        manager.createToolGroup("analytics", "Analytics tools", true);
        manager.createToolGroup("search", "Search tools", true);
        manager.createToolGroup("admin", "Admin tools", false);

        // Act
        String notes = manager.getActivatedNotes();

        // Assert
        assertTrue(notes.contains("Activated tool groups"));
        assertTrue(notes.contains("analytics"));
        assertTrue(notes.contains("Analytics tools"));
        assertTrue(notes.contains("search"));
        assertTrue(notes.contains("Search tools"));
        assertFalse(notes.contains("admin"));
    }

    @Test
    void testValidateGroupExists() {
        // Arrange
        manager.createToolGroup("existing", "Existing group");

        // Act & Assert - should not throw
        manager.validateGroupExists("existing");
    }

    @Test
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
    void testIsActiveToolUngrouped() {
        // Act & Assert
        assertTrue(manager.isActiveTool("ungroupedTool"));
    }

    @Test
    void testIsActiveToolInactive() {
        // Arrange
        manager.createToolGroup("inactive", "Inactive group", false);
        manager.addToolToGroup("inactive", "tool1");

        // Act & Assert
        assertFalse(manager.isActiveTool("tool1"));
    }

    @Test
    void testIsInActiveGroupToolNotAnyTool() {
        // Arrange
        manager.createToolGroup("group1", "Group 1", true);

        // Act & Assert
        assertTrue(manager.isActiveTool("ungroupedTool"));
    }

    @Test
    void testIsActiveGroupActive() {
        // Arrange
        manager.createToolGroup("active", "Active group", true);

        // Act & Assert
        assertTrue(manager.isActiveGroup("active"));
    }

    @Test
    void testIsActiveGroupInactive() {
        // Arrange
        manager.createToolGroup("inactive", "Inactive group", false);

        // Act & Assert
        assertFalse(manager.isActiveGroup("inactive"));
    }

    @Test
    void testIsActiveGroupNonexistent() {
        // Act & Assert
        assertFalse(manager.isActiveGroup("nonexistent"));
    }

    @Test
    void testAddToolToGroup() {
        // Arrange
        manager.createToolGroup("group1", "Group 1");

        // Act
        manager.addToolToGroup("group1", "tool1");
        manager.addToolToGroup("group1", "tool2");

        // Assert
        ToolGroup group = manager.getToolGroup("group1");
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testAddToolToNonexistentGroup() {
        // Act - should not throw, just do nothing
        manager.addToolToGroup("nonexistent", "tool1");

        // Assert
        assertNull(manager.getToolGroup("nonexistent"));
    }

    @Test
    void testRemoveToolFromGroup() {
        // Arrange
        manager.createToolGroup("group1", "Group 1");
        manager.addToolToGroup("group1", "tool1");
        manager.addToolToGroup("group1", "tool2");

        // Act
        manager.removeToolFromGroup("group1", "tool1");

        // Assert
        ToolGroup group = manager.getToolGroup("group1");
        assertFalse(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testRemoveToolFromNonexistentGroup() {
        // Act - should not throw, just do nothing
        manager.removeToolFromGroup("nonexistent", "tool1");

        // Assert
        assertNull(manager.getToolGroup("nonexistent"));
    }

    @Test
    void testGetToolGroupNames() {
        // Arrange
        manager.createToolGroup("group1", "Group 1");
        manager.createToolGroup("group2", "Group 2");
        manager.createToolGroup("group3", "Group 3");

        // Act
        Set<String> names = manager.getToolGroupNames();

        // Assert
        assertEquals(3, names.size());
        assertTrue(names.contains("group1"));
        assertTrue(names.contains("group2"));
        assertTrue(names.contains("group3"));
    }

    @Test
    void testGetToolGroupNamesEmpty() {
        // Act
        Set<String> names = manager.getToolGroupNames();

        // Assert
        assertTrue(names.isEmpty());
    }

    @Test
    void testGetActiveGroups() {
        // Arrange
        manager.createToolGroup("active1", "Active 1", true);
        manager.createToolGroup("active2", "Active 2", true);
        manager.createToolGroup("inactive", "Inactive", false);

        // Act
        List<String> activeGroups = manager.getActiveGroups();

        // Assert
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("active1"));
        assertTrue(activeGroups.contains("active2"));
        assertFalse(activeGroups.contains("inactive"));
    }

    @Test
    void testGetActiveGroupsEmpty() {
        // Arrange
        manager.createToolGroup("inactive", "Inactive", false);

        // Act
        List<String> activeGroups = manager.getActiveGroups();

        // Assert
        assertTrue(activeGroups.isEmpty());
    }

    @Test
    void testSetActiveGroups() {
        // Arrange
        manager.createToolGroup("group1", "Group 1", false);
        manager.createToolGroup("group2", "Group 2", false);
        manager.createToolGroup("group3", "Group 3", true);

        // Act
        manager.setActiveGroups(List.of("group1", "group2"));

        // Assert
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("group1"));
        assertTrue(activeGroups.contains("group2"));

        // Check that groups are marked as active
        assertTrue(manager.getToolGroup("group1").isActive());
        assertTrue(manager.getToolGroup("group2").isActive());
    }

    @Test
    void testSetActiveGroupsWithNonexistent() {
        // Arrange
        manager.createToolGroup("group1", "Group 1", false);

        // Act - should not throw, just ignore nonexistent
        manager.setActiveGroups(List.of("group1", "nonexistent"));

        // Assert
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("group1"));
        assertTrue(activeGroups.contains("nonexistent"));
        assertTrue(manager.getToolGroup("group1").isActive());
    }

    @Test
    void testGetToolGroup() {
        // Arrange
        manager.createToolGroup("group1", "Group 1");

        // Act
        ToolGroup group = manager.getToolGroup("group1");

        // Assert
        assertNotNull(group);
        assertEquals("group1", group.getName());
        assertEquals("Group 1", group.getDescription());
    }

    @Test
    void testGetToolGroupNonexistent() {
        // Act
        ToolGroup group = manager.getToolGroup("nonexistent");

        // Assert
        assertNull(group);
    }

    @Test
    void testUpdatePreventsDuplicatesInActiveGroups() {
        // Arrange
        manager.createToolGroup("group1", "Group 1", true);

        // Act - activate the same group multiple times
        manager.updateToolGroups(List.of("group1"), true);
        manager.updateToolGroups(List.of("group1"), true);

        // Assert - should not have duplicates
        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(1, activeGroups.stream().filter(g -> g.equals("group1")).count());
    }

    @Test
    void testComplexScenario() {
        // Arrange
        manager.createToolGroup("analytics", "Analytics tools", true);
        manager.createToolGroup("search", "Search tools", true);
        manager.createToolGroup("admin", "Admin tools", false);

        manager.addToolToGroup("analytics", "chart");
        manager.addToolToGroup("analytics", "report");
        manager.addToolToGroup("search", "google");

        // Act - deactivate analytics, activate admin
        manager.updateToolGroups(List.of("analytics"), false);
        manager.updateToolGroups(List.of("admin"), true);

        // Assert
        assertFalse(manager.isActiveGroup("analytics"));
        assertTrue(manager.isActiveGroup("search"));
        assertTrue(manager.isActiveGroup("admin"));
        assertFalse(manager.isActiveTool("chart"));
        assertTrue(manager.isActiveTool("google"));

        List<String> activeGroups = manager.getActiveGroups();
        assertEquals(2, activeGroups.size());
        assertTrue(activeGroups.contains("search"));
        assertTrue(activeGroups.contains("admin"));

        // Remove analytics
        Set<String> removedTools = manager.removeToolGroups(List.of("analytics"));
        assertEquals(2, removedTools.size());
        assertTrue(removedTools.contains("chart"));
        assertTrue(removedTools.contains("report"));

        assertNull(manager.getToolGroup("analytics"));
        assertEquals(2, manager.getToolGroupNames().size());
    }
}
