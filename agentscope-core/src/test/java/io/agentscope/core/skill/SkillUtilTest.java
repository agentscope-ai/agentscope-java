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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.util.SkillUtil;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillUtilTest {

    @Test
    @DisplayName("Should create from markdown with default source")
    void testCreateFromMarkdownWithDefaultSource() {
        String skillMd =
                "---\n"
                        + "name: test_skill\n"
                        + "description: A test skill for validation\n"
                        + "---\n"
                        + "# Skill Content\n"
                        + "This is the skill implementation.";

        Map<String, String> resources = Map.of("config.json", "{\"key\": \"value\"}");

        AgentSkill skill = SkillUtil.createFrom(skillMd, resources);

        assertNotNull(skill);
        assertEquals("test_skill", skill.getName());
        assertEquals("A test skill for validation", skill.getDescription());
        assertTrue(skill.getSkillContent().contains("Skill Content"));
        assertEquals("custom", skill.getSource());
        assertEquals(1, skill.getResources().size());
    }

    @Test
    @DisplayName("Should create from markdown with custom source")
    void testCreateFromMarkdownWithCustomSource() {
        String skillMd =
                "---\n"
                        + "name: github_skill\n"
                        + "description: From GitHub repository\n"
                        + "---\n"
                        + "Content here";

        AgentSkill skill = SkillUtil.createFrom(skillMd, null, "github");

        assertEquals("github_skill", skill.getName());
        assertEquals("github", skill.getSource());
        assertEquals("github_skill_github", skill.getSkillId());
    }

    @Test
    @DisplayName("Should create from markdown with null resources")
    void testCreateFromMarkdownWithNullResources() {
        String skillMd = "---\nname: skill\ndescription: desc\n---\nContent";

        AgentSkill skill = SkillUtil.createFrom(skillMd, null);

        assertNotNull(skill);
        assertTrue(skill.getResources().isEmpty());
    }

    @Test
    @DisplayName("Should create from markdown with null source")
    void testCreateFromMarkdownWithNullSource() {
        String skillMd = "---\nname: skill\ndescription: desc\n---\nContent";

        AgentSkill skill = SkillUtil.createFrom(skillMd, null, null);

        assertEquals("custom", skill.getSource());
    }

    @Test
    @DisplayName("Should create from throws exception for missing name")
    void testCreateFromThrowsExceptionForMissingName() {
        String skillMd = "---\ndescription: desc\n---\nContent";

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));

        assertTrue(exception.getMessage().contains("name"));
        assertTrue(exception.getMessage().contains("description"));
    }

    @Test
    @DisplayName("Should create from throws exception for missing description")
    void testCreateFromThrowsExceptionForMissingDescription() {
        String skillMd = "---\nname: test\n---\nContent";

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));

        assertTrue(exception.getMessage().contains("description"));
    }

    @Test
    @DisplayName("Should create from throws exception for empty name")
    void testCreateFromThrowsExceptionForEmptyName() {
        String skillMd = "---\nname: \ndescription: desc\n---\nContent";

        assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
    }

    @Test
    @DisplayName("Should create from throws exception for empty description")
    void testCreateFromThrowsExceptionForEmptyDescription() {
        String skillMd = "---\nname: test\ndescription: \n---\nContent";

        assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
    }

    @Test
    @DisplayName("Should create from throws exception for missing content")
    void testCreateFromThrowsExceptionForMissingContent() {
        String skillMd = "---\nname: test\ndescription: desc\n---";

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));

        assertTrue(exception.getMessage().contains("content"));
    }

    @Test
    @DisplayName("Should create from throws exception for no frontmatter")
    void testCreateFromThrowsExceptionForNoFrontmatter() {
        String skillMd = "Just content without frontmatter";

        assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
    }

    @Test
    @DisplayName("Should create from edge cases")
    void testCreateFromEdgeCases() {
        // Multiline content
        String multilineSkillMd =
                "---\n"
                        + "name: multiline\n"
                        + "description: Multi-line skill\n"
                        + "---\n"
                        + "Line 1\n"
                        + "Line 2\n"
                        + "Line 3";
        AgentSkill skill1 = SkillUtil.createFrom(multilineSkillMd, null);
        assertTrue(skill1.getSkillContent().contains("Line 2"));

        // Special characters in metadata (use quotes for values with colons)
        String specialCharsSkillMd =
                "---\n"
                        + "name: skill-v1.0_test\n"
                        + "description: 'Special chars @#$%'\n"
                        + "---\n"
                        + "Content";
        AgentSkill skill2 = SkillUtil.createFrom(specialCharsSkillMd, null);
        assertEquals("skill-v1.0_test", skill2.getName());

        // Unicode characters
        String unicodeSkillMd =
                "---\n" + "name: 测试技能\n" + "description: 这是一个测试\n" + "---\n" + "技能内容";
        AgentSkill skill3 = SkillUtil.createFrom(unicodeSkillMd, null, "中文源");
        assertEquals("测试技能", skill3.getName());
        assertEquals("中文源", skill3.getSource());

        // Additional metadata fields (should be ignored)
        String extraFieldsSkillMd =
                "---\n"
                        + "name: skill\n"
                        + "description: desc\n"
                        + "version: 1.0.0\n"
                        + "author: John\n"
                        + "---\n"
                        + "Content";
        AgentSkill skill4 = SkillUtil.createFrom(extraFieldsSkillMd, null);
        assertEquals("skill", skill4.getName());
    }

    @Test
    @DisplayName("Should create from with numeric metadata")
    void testCreateFromWithNumericMetadata() {
        String skillMd = "---\nname: 123\ndescription: 456\n---\nContent";

        AgentSkill skill = SkillUtil.createFrom(skillMd, null);

        assertEquals("123", skill.getName());
        assertEquals("456", skill.getDescription());
    }
}
