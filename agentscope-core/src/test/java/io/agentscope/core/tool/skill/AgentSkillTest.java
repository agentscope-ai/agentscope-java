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

package io.agentscope.core.tool.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for AgentSkill class.
 *
 * <p>Tests cover both constructors, all getters, and various error scenarios.
 */
@DisplayName("AgentSkill Tests")
class AgentSkillTest {

    // ========== Constructor 1: YAML Frontmatter ==========

    @Nested
    @DisplayName("Constructor with YAML Frontmatter")
    class YamlFrontmatterConstructorTests {

        @Test
        @DisplayName("Should create skill from valid YAML frontmatter")
        void testCreateFromValidYamlFrontmatter() {
            String skillContent =
                    """
                    ---
                    name: test_skill
                    description: Test skill description
                    ---
                    # Main Content
                    This is the skill content.
                    """;

            AgentSkill skill = new AgentSkill(skillContent);

            assertNotNull(skill);
            assertEquals("test_skill", skill.getName());
            assertEquals("Test skill description", skill.getDescription());
            assertEquals(skillContent, skill.getSkillContent());
        }

        @Test
        @DisplayName("Should create skill with complex YAML metadata")
        void testCreateFromComplexYaml() {
            String skillContent =
                    """
                    ---
                    name: complex_skill
                    description: A more complex skill
                    version: 1.0.0
                    tags:
                      - ai
                      - nlp
                    ---
                    # Complex Skill
                    Content here.
                    """;

            AgentSkill skill = new AgentSkill(skillContent);

            assertNotNull(skill);
            assertEquals("complex_skill", skill.getName());
            assertEquals("A more complex skill", skill.getDescription());
        }

        @Test
        @DisplayName("Should handle numeric and boolean values in YAML")
        void testYamlWithDifferentTypes() {
            String skillContent =
                    """
                    ---
                    name: 123
                    description: true
                    ---
                    # Content
                    """;

            AgentSkill skill = new AgentSkill(skillContent);

            assertEquals("123", skill.getName());
            assertEquals("true", skill.getDescription());
        }

        @Test
        @DisplayName("Should throw exception when YAML frontmatter is missing")
        void testMissingYamlFrontmatter() {
            String skillContent = "# Just Content\nNo frontmatter here.";

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill(skillContent),
                            "Should throw exception when YAML frontmatter is missing");

            assertTrue(
                    exception
                            .getMessage()
                            .contains("YAML Front Matter including `name` and `description`"));
        }

        @Test
        @DisplayName("Should throw exception when name field is missing")
        void testMissingNameField() {
            String skillContent =
                    """
                    ---
                    description: Only description here
                    ---
                    # Content
                    """;

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill(skillContent),
                            "Should throw exception when name field is missing");

            assertTrue(
                    exception
                            .getMessage()
                            .contains("YAML Front Matter including `name` and `description`"));
        }

        @Test
        @DisplayName("Should throw exception when description field is missing")
        void testMissingDescriptionField() {
            String skillContent =
                    """
                    ---
                    name: test_skill
                    ---
                    # Content
                    """;

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill(skillContent),
                            "Should throw exception when description field is missing");

            assertTrue(
                    exception
                            .getMessage()
                            .contains("YAML Front Matter including `name` and `description`"));
        }

        @Test
        @DisplayName("Should throw exception when YAML syntax is invalid")
        void testInvalidYamlSyntax() {
            String skillContent =
                    """
                    ---
                    name: test
                    description: [unclosed array
                    ---
                    # Content
                    """;

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AgentSkill(skillContent),
                    "Should throw exception for invalid YAML syntax");
        }

        @Test
        @DisplayName("Should throw exception when YAML is a list instead of map")
        void testYamlListInsteadOfMap() {
            String skillContent =
                    """
                    ---
                    - value1
                    - value2
                    ---
                    # Content
                    """;

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AgentSkill(skillContent),
                    "Should throw exception when YAML is a list");
        }

        @Test
        @DisplayName("Should throw exception for null content")
        void testNullContentYamlConstructor() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill((String) null),
                            "Should throw exception for null content");
        }

        @Test
        @DisplayName("Should throw exception for empty content")
        void testEmptyContentYamlConstructor() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill(""),
                            "Should throw exception for empty content");

            assertTrue(
                    exception
                            .getMessage()
                            .contains("YAML Front Matter including `name` and `description`"));
        }
    }

    // ========== Constructor 2: Explicit Parameters ==========

    @Nested
    @DisplayName("Constructor with Explicit Parameters")
    class ExplicitParametersConstructorTests {

        @Test
        @DisplayName("Should create skill with valid parameters")
        void testCreateWithValidParameters() {
            String name = "custom_skill";
            String description = "Custom skill description";
            String content = "# Custom Skill\nThis is the content.";

            AgentSkill skill = new AgentSkill(name, description, content);

            assertNotNull(skill);
            assertEquals(name, skill.getName());
            assertEquals(description, skill.getDescription());
            assertEquals(content, skill.getSkillContent());
        }

        @Test
        @DisplayName("Should create skill without YAML frontmatter")
        void testCreateWithoutYamlFrontmatter() {
            String name = "no_yaml_skill";
            String description = "Skill without YAML";
            String content = "Just plain content without YAML frontmatter";

            AgentSkill skill = new AgentSkill(name, description, content);

            assertEquals(name, skill.getName());
            assertEquals(description, skill.getDescription());
            assertEquals(content, skill.getSkillContent());
        }

        @Test
        @DisplayName("Should throw exception when name is null")
        void testNullName() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill(null, "description", "content"),
                            "Should throw exception when name is null");

            assertTrue(
                    exception.getMessage().contains("`name`, `description`, and `skillContent`"));
        }

        @Test
        @DisplayName("Should throw exception when name is empty")
        void testEmptyName() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill("", "description", "content"),
                            "Should throw exception when name is empty");

            assertTrue(
                    exception.getMessage().contains("`name`, `description`, and `skillContent`"));
        }

        @Test
        @DisplayName("Should throw exception when description is null")
        void testNullDescription() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill("name", null, "content"),
                            "Should throw exception when description is null");

            assertTrue(
                    exception.getMessage().contains("`name`, `description`, and `skillContent`"));
        }

        @Test
        @DisplayName("Should throw exception when description is empty")
        void testEmptyDescription() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill("name", "", "content"),
                            "Should throw exception when description is empty");

            assertTrue(
                    exception.getMessage().contains("`name`, `description`, and `skillContent`"));
        }

        @Test
        @DisplayName("Should throw exception when skillContent is null")
        void testNullSkillContent() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill("name", "description", null),
                            "Should throw exception when skillContent is null");

            assertTrue(
                    exception.getMessage().contains("`name`, `description`, and `skillContent`"));
        }

        @Test
        @DisplayName("Should throw exception when skillContent is empty")
        void testEmptySkillContent() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new AgentSkill("name", "description", ""),
                            "Should throw exception when skillContent is empty");

            assertTrue(
                    exception.getMessage().contains("`name`, `description`, and `skillContent`"));
        }

        @Test
        @DisplayName("Should throw exception when all parameters are null")
        void testAllParametersNull() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AgentSkill(null, null, null),
                    "Should throw exception when all parameters are null");
        }

        @Test
        @DisplayName("Should throw exception when all parameters are empty")
        void testAllParametersEmpty() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AgentSkill("", "", ""),
                    "Should throw exception when all parameters are empty");
        }
    }
}
