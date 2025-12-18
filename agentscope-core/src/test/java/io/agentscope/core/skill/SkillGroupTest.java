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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SkillGroup.
 *
 * <p>Tests verify skill group creation, skill management, and activation state.
 */
class SkillGroupTest {

    @Test
    @DisplayName("Should create skill group with all parameters")
    void testCreateSkillGroupWithAllParameters() {
        // Act
        SkillGroup group =
                SkillGroup.builder()
                        .name("data_analysis")
                        .description("Data analysis related skills")
                        .active(true)
                        .build();

        // Assert
        assertNotNull(group);
        assertEquals("data_analysis", group.getName());
        assertEquals("Data analysis related skills", group.getDescription());
        assertTrue(group.isActive());
        assertTrue(group.getSkills().isEmpty());
    }

    @Test
    @DisplayName("Should create skill group with default values")
    void testCreateSkillGroupWithDefaults() {
        // Act
        SkillGroup group = SkillGroup.builder().name("test_group").build();

        // Assert
        assertNotNull(group);
        assertEquals("test_group", group.getName());
        assertEquals("", group.getDescription());
        assertTrue(group.isActive()); // Default is active
        assertTrue(group.getSkills().isEmpty());
    }

    @Test
    @DisplayName("Should throw exception when name is null")
    void testCreateSkillGroupWithNullName() {
        // Act & Assert
        assertThrows(
                NullPointerException.class, () -> SkillGroup.builder().description("Test").build());
    }

    @Test
    @DisplayName("Should add skill to group")
    void testAddSkill() {
        // Arrange
        SkillGroup group = SkillGroup.builder().name("test_group").build();

        // Act
        group.addSkill("skill1");
        group.addSkill("skill2");

        // Assert
        Set<String> skills = group.getSkills();
        assertEquals(2, skills.size());
        assertTrue(skills.contains("skill1"));
        assertTrue(skills.contains("skill2"));
    }

    @Test
    @DisplayName("Should remove skill from group")
    void testRemoveSkill() {
        // Arrange
        SkillGroup group = SkillGroup.builder().name("test_group").build();
        group.addSkill("skill1");
        group.addSkill("skill2");

        // Act
        group.removeSkill("skill1");

        // Assert
        Set<String> skills = group.getSkills();
        assertEquals(1, skills.size());
        assertFalse(skills.contains("skill1"));
        assertTrue(skills.contains("skill2"));
    }

    @Test
    @DisplayName("Should check if skill exists in group")
    void testContainsSkill() {
        // Arrange
        SkillGroup group = SkillGroup.builder().name("test_group").build();
        group.addSkill("skill1");

        // Act & Assert
        assertTrue(group.containsSkill("skill1"));
        assertFalse(group.containsSkill("skill2"));
    }

    @Test
    @DisplayName("Should toggle activation state")
    void testSetActive() {
        // Arrange
        SkillGroup group = SkillGroup.builder().name("test_group").active(true).build();

        // Act & Assert - initially active
        assertTrue(group.isActive());

        // Deactivate
        group.setActive(false);
        assertFalse(group.isActive());

        // Reactivate
        group.setActive(true);
        assertTrue(group.isActive());
    }

    @Test
    @DisplayName("Should return defensive copy of skills")
    void testGetSkillsDefensiveCopy() {
        // Arrange
        SkillGroup group = SkillGroup.builder().name("test_group").build();
        group.addSkill("skill1");

        // Act
        Set<String> skills1 = group.getSkills();
        skills1.add("skill2"); // Modify the returned set

        Set<String> skills2 = group.getSkills();

        // Assert - original group should not be affected
        assertEquals(1, skills2.size());
        assertTrue(skills2.contains("skill1"));
        assertFalse(skills2.contains("skill2"));
    }

    @Test
    @DisplayName("Should create group with initial skills")
    void testCreateGroupWithInitialSkills() {
        // Arrange
        Set<String> initialSkills = Set.of("skill1", "skill2", "skill3");

        // Act
        SkillGroup group = SkillGroup.builder().name("test_group").skills(initialSkills).build();

        // Assert
        Set<String> skills = group.getSkills();
        assertEquals(3, skills.size());
        assertTrue(skills.contains("skill1"));
        assertTrue(skills.contains("skill2"));
        assertTrue(skills.contains("skill3"));
    }

    @Test
    @DisplayName("Should handle duplicate skill additions")
    void testAddDuplicateSkill() {
        // Arrange
        SkillGroup group = SkillGroup.builder().name("test_group").build();

        // Act
        group.addSkill("skill1");
        group.addSkill("skill1"); // Add same skill again

        // Assert - Set should prevent duplicates
        Set<String> skills = group.getSkills();
        assertEquals(1, skills.size());
        assertTrue(skills.contains("skill1"));
    }

    @Test
    @DisplayName("Should handle removing non-existent skill")
    void testRemoveNonExistentSkill() {
        // Arrange
        SkillGroup group = SkillGroup.builder().name("test_group").build();
        group.addSkill("skill1");

        // Act - should not throw
        group.removeSkill("nonexistent");

        // Assert
        Set<String> skills = group.getSkills();
        assertEquals(1, skills.size());
        assertTrue(skills.contains("skill1"));
    }

    @Test
    @DisplayName("Should create inactive group")
    void testCreateInactiveGroup() {
        // Act
        SkillGroup group =
                SkillGroup.builder()
                        .name("inactive_group")
                        .description("Inactive group")
                        .active(false)
                        .build();

        // Assert
        assertNotNull(group);
        assertFalse(group.isActive());
    }

    @Test
    @DisplayName("Should handle empty description")
    void testEmptyDescription() {
        // Act
        SkillGroup group = SkillGroup.builder().name("test_group").description("").build();

        // Assert
        assertEquals("", group.getDescription());
    }

    @Test
    @DisplayName("Should handle null description")
    void testNullDescription() {
        // Act
        SkillGroup group = SkillGroup.builder().name("test_group").description(null).build();

        // Assert
        assertEquals("", group.getDescription()); // Should default to empty string
    }
}
