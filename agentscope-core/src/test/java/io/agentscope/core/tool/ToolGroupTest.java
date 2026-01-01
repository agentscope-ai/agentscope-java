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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolGroupTest {

    @Test
    void testBuilderWithAllFields() {
        // Act
        ToolGroup group =
                ToolGroup.builder()
                        .name("testGroup")
                        .description("Test Description")
                        .active(false)
                        .tools(Set.of("tool1", "tool2"))
                        .build();

        // Assert
        assertEquals("testGroup", group.getName());
        assertEquals("Test Description", group.getDescription());
        assertFalse(group.isActive());
        assertEquals(2, group.getTools().size());
        assertTrue(group.getTools().contains("tool1"));
        assertTrue(group.getTools().contains("tool2"));
    }

    @Test
    void testBuilderWithMinimalFields() {
        // Act
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // Assert
        assertEquals("testGroup", group.getName());
        assertEquals("", group.getDescription());
        assertTrue(group.isActive(), "Should default to active");
        assertTrue(group.getTools().isEmpty());
    }

    @Test
    void testBuilderNullName() {
        // Act & Assert
        assertThrows(NullPointerException.class, () -> ToolGroup.builder().build());
    }

    @Test
    void testBuilderNullDescription() {
        // Act
        ToolGroup group = ToolGroup.builder().name("testGroup").description(null).build();

        // Assert
        assertEquals("", group.getDescription(), "Null description should default to empty string");
    }

    @Test
    void testSetActive() {
        // Arrange
        ToolGroup group = ToolGroup.builder().name("testGroup").active(true).build();

        // Act
        group.setActive(false);

        // Assert
        assertFalse(group.isActive());

        // Act again
        group.setActive(true);

        // Assert
        assertTrue(group.isActive());
    }

    @Test
    void testAddTool() {
        // Arrange
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // Act
        group.addTool("tool1");
        group.addTool("tool2");

        // Assert
        assertEquals(2, group.getTools().size());
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testAddDuplicateTool() {
        // Arrange
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // Act
        group.addTool("tool1");
        group.addTool("tool1"); // Duplicate

        // Assert
        assertEquals(1, group.getTools().size(), "Set should not contain duplicates");
        assertTrue(group.containsTool("tool1"));
    }

    @Test
    void testRemoveTool() {
        // Arrange
        ToolGroup group =
                ToolGroup.builder().name("testGroup").tools(Set.of("tool1", "tool2")).build();

        // Act
        group.removeTool("tool1");

        // Assert
        assertEquals(1, group.getTools().size());
        assertFalse(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
    }

    @Test
    void testRemoveNonexistentTool() {
        // Arrange
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(Set.of("tool1")).build();

        // Act
        group.removeTool("nonexistent");

        // Assert
        assertEquals(1, group.getTools().size());
        assertTrue(group.containsTool("tool1"));
    }

    @Test
    void testContainsTool() {
        // Arrange
        ToolGroup group =
                ToolGroup.builder().name("testGroup").tools(Set.of("tool1", "tool2")).build();

        // Act & Assert
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
        assertFalse(group.containsTool("tool3"));
        assertFalse(group.containsTool("nonexistent"));
    }

    @Test
    void testGetToolsReturnsDefensiveCopy() {
        // Arrange
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(Set.of("tool1")).build();

        // Act
        Set<String> tools1 = group.getTools();
        tools1.add("tool2"); // Modify returned set

        // Assert
        assertFalse(
                group.containsTool("tool2"),
                "Modifications to returned set should not affect group");
        assertEquals(1, group.getTools().size());
    }

    @Test
    void testBuilderToolsCreatesDefensiveCopy() {
        // Arrange
        Set<String> originalTools = new java.util.HashSet<>(Set.of("tool1"));

        // Act
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(originalTools).build();
        originalTools.add("tool2"); // Modify original set

        // Assert
        assertFalse(
                group.containsTool("tool2"),
                "Modifications to original set should not affect group");
        assertEquals(1, group.getTools().size());
    }

    @Test
    void testBuilderChaining() {
        // Act
        ToolGroup group =
                ToolGroup.builder()
                        .name("testGroup")
                        .description("Description")
                        .active(false)
                        .tools(Set.of("tool1"))
                        .build();

        // Assert
        assertEquals("testGroup", group.getName());
        assertEquals("Description", group.getDescription());
        assertFalse(group.isActive());
        assertEquals(1, group.getTools().size());
    }

    @Test
    void testEmptyDescription() {
        // Act
        ToolGroup group = ToolGroup.builder().name("testGroup").description("").build();

        // Assert
        assertEquals("", group.getDescription());
    }

    @Test
    void testActiveDefaultValue() {
        // Act
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // Assert
        assertTrue(group.isActive(), "Default active value should be true");
    }

    @Test
    void testAddToolAfterConstruction() {
        // Arrange
        ToolGroup group = ToolGroup.builder().name("testGroup").build();

        // Act
        group.addTool("tool1");
        group.addTool("tool2");
        group.addTool("tool3");

        // Assert
        assertEquals(3, group.getTools().size());
        assertTrue(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
        assertTrue(group.containsTool("tool3"));
    }

    @Test
    void testRemoveAllTools() {
        // Arrange
        ToolGroup group =
                ToolGroup.builder().name("testGroup").tools(Set.of("tool1", "tool2")).build();

        // Act
        group.removeTool("tool1");
        group.removeTool("tool2");

        // Assert
        assertTrue(group.getTools().isEmpty());
    }

    @Test
    void testMixedOperations() {
        // Arrange
        ToolGroup group = ToolGroup.builder().name("testGroup").tools(Set.of("tool1")).build();

        // Act
        group.addTool("tool2");
        group.addTool("tool3");
        group.removeTool("tool1");
        group.setActive(false);

        // Assert
        assertEquals(2, group.getTools().size());
        assertFalse(group.containsTool("tool1"));
        assertTrue(group.containsTool("tool2"));
        assertTrue(group.containsTool("tool3"));
        assertFalse(group.isActive());
    }
}
