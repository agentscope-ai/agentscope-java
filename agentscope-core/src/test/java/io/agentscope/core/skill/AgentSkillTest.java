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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentSkillTest {

    @Test
    @DisplayName("Should constructor with all parameters")
    void testConstructorWithAllParameters() {
        Map<String, String> resources = new HashMap<>();
        resources.put("config.json", "{\"key\": \"value\"}");
        resources.put("data.txt", "sample data");

        AgentSkill skill =
                new AgentSkill("test_skill", "A test skill", "Skill content here", resources);

        assertNotNull(skill);
        assertEquals("test_skill", skill.getName());
        assertEquals("A test skill", skill.getDescription());
        assertEquals("Skill content here", skill.getSkillContent());
        assertEquals("custom", skill.getSource());
        assertEquals(2, skill.getResources().size());
        assertEquals("{\"key\": \"value\"}", skill.getResources().get("config.json"));
    }

    @Test
    @DisplayName("Should constructor with custom source")
    void testConstructorWithCustomSource() {
        Map<String, String> resources = Map.of("file.txt", "content");

        AgentSkill skill =
                new AgentSkill(
                        "github_skill", "From GitHub", "GitHub content", resources, "github");

        assertEquals("github", skill.getSource());
        assertEquals("github_skill_github", skill.getSkillId());
    }

    @Test
    @DisplayName("Should constructor with null parameters")
    void testConstructorWithNullParameters() {
        // Null resources
        AgentSkill skill1 = new AgentSkill("skill", "description", "content", null);
        assertNotNull(skill1.getResources());
        assertTrue(skill1.getResources().isEmpty());

        // Null source defaults to "custom"
        AgentSkill skill2 = new AgentSkill("skill", "description", "content", null, null);
        assertEquals("custom", skill2.getSource());
    }

    @Test
    @DisplayName("Should constructor validates name")
    void testConstructorValidatesName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentSkill(null, "description", "content", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentSkill("", "description", "content", null));
    }

    @Test
    @DisplayName("Should constructor validates description")
    void testConstructorValidatesDescription() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentSkill("name", null, "content", null));
        assertThrows(
                IllegalArgumentException.class, () -> new AgentSkill("name", "", "content", null));
    }

    @Test
    @DisplayName("Should constructor validates content")
    void testConstructorValidatesContent() {
        assertThrows(
                IllegalArgumentException.class, () -> new AgentSkill("name", "desc", null, null));
        assertThrows(
                IllegalArgumentException.class, () -> new AgentSkill("name", "desc", "", null));
    }

    @Test
    @DisplayName("Should skill id uniqueness")
    void testSkillIdUniqueness() {
        AgentSkill skill1 = new AgentSkill("same_name", "desc", "content", null, "source1");
        AgentSkill skill2 = new AgentSkill("same_name", "desc", "content", null, "source2");

        assertEquals("same_name_source1", skill1.getSkillId());
        assertEquals("same_name_source2", skill2.getSkillId());
    }

    @Test
    @DisplayName("Should resources immutability")
    void testResourcesImmutability() {
        Map<String, String> originalResources = new HashMap<>();
        originalResources.put("file.txt", "original");
        AgentSkill skill = new AgentSkill("name", "desc", "content", originalResources);

        // Modify original map after construction
        originalResources.put("file.txt", "modified");
        originalResources.put("new_file.txt", "new content");

        // Skill's resources should not be affected
        assertEquals("original", skill.getResources().get("file.txt"));
        assertEquals(1, skill.getResources().size());

        // Modify returned map
        Map<String, String> retrievedResources = skill.getResources();
        retrievedResources.put("another.txt", "another");

        // Original skill resources should still not be affected
        assertEquals(1, skill.getResources().size());
    }

    @Test
    @DisplayName("Should builder create from scratch")
    void testBuilderCreateFromScratch() {
        AgentSkill skill =
                AgentSkill.builder()
                        .name("builder_skill")
                        .description("Built with builder")
                        .skillContent("Builder content")
                        .source("custom_source")
                        .addResource("res.txt", "resource content")
                        .build();

        assertEquals("builder_skill", skill.getName());
        assertEquals("Built with builder", skill.getDescription());
        assertEquals("Builder content", skill.getSkillContent());
        assertEquals("custom_source", skill.getSource());
        assertEquals(1, skill.getResources().size());
    }

    @Test
    @DisplayName("Should builder to builder")
    void testBuilderToBuilder() {
        AgentSkill original =
                new AgentSkill("original", "Original desc", "content", Map.of("f.txt", "c"), "gh");

        AgentSkill modified =
                original.toBuilder()
                        .description("Modified description")
                        .addResource("new.txt", "new content")
                        .build();

        assertEquals("original", modified.getName()); // Unchanged
        assertEquals("Modified description", modified.getDescription()); // Changed
        assertEquals(2, modified.getResources().size()); // Added resource
    }

    @Test
    @DisplayName("Should builder validation")
    void testBuilderValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSkill.builder().description("desc").skillContent("content").build());
    }

    @Test
    @DisplayName("Should builder resource operations")
    void testBuilderResourceOperations() {
        AgentSkill original =
                new AgentSkill("name", "desc", "content", Map.of("f1.txt", "c1", "f2.txt", "c2"));

        // Add resource
        AgentSkill withAdded = original.toBuilder().addResource("f3.txt", "c3").build();
        assertEquals(3, withAdded.getResources().size());

        // Remove resource
        AgentSkill withRemoved = original.toBuilder().removeResource("f1.txt").build();
        assertEquals(1, withRemoved.getResources().size());
        assertTrue(withRemoved.getResources().containsKey("f2.txt"));

        // Replace resources
        AgentSkill withReplaced =
                original.toBuilder().resources(Map.of("new.txt", "new content")).build();
        assertEquals(1, withReplaced.getResources().size());

        // Clear resources
        AgentSkill withCleared = original.toBuilder().clearResources().build();
        assertTrue(withCleared.getResources().isEmpty());

        // Chain operations
        AgentSkill chained =
                original.toBuilder()
                        .removeResource("f1.txt")
                        .addResource("f3.txt", "c3")
                        .addResource("f4.txt", "c4")
                        .build();
        assertEquals(3, chained.getResources().size());
    }

    @Test
    @DisplayName("Should edge cases")
    void testEdgeCases() {
        // Long content
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            longContent.append("Line ").append(i).append("\n");
        }
        AgentSkill skill1 = new AgentSkill("name", "desc", longContent.toString(), null);
        assertTrue(skill1.getSkillContent().length() > 10000);

        // Special characters
        AgentSkill skill2 = new AgentSkill("skill-v1.0", "desc", "content", null);
        assertEquals("skill-v1.0", skill2.getName());

        // Unicode
        AgentSkill skill3 = new AgentSkill("技能名称", "技能描述：测试", "技能内容\n包含中文", null, "来源");
        assertEquals("技能名称", skill3.getName());
        assertEquals("技能名称_来源", skill3.getSkillId());

        // Many resources
        Map<String, String> manyResources = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            manyResources.put("file" + i + ".txt", "content" + i);
        }
        AgentSkill skill4 = new AgentSkill("name", "desc", "content", manyResources);
        assertEquals(50, skill4.getResources().size());
    }

    @Test
    @DisplayName("Should return resource paths")
    void testGetResourcePaths() {
        Map<String, String> resources = new HashMap<>();
        resources.put("scripts/process.py", "print('hello')");
        resources.put("assets/data.json", "{\"key\": \"value\"}");
        resources.put("config.yaml", "name: test");

        AgentSkill skill = new AgentSkill("test", "desc", "content", resources);
        Set<String> paths = skill.getResourcePaths();

        assertEquals(3, paths.size());
        assertTrue(paths.contains("scripts/process.py"));
        assertTrue(paths.contains("assets/data.json"));
        assertTrue(paths.contains("config.yaml"));
        assertThrows(UnsupportedOperationException.class, () -> paths.add("new.txt"));
    }

    @Test
    @DisplayName("Should return resource content by path")
    void testGetResourceByPath() {
        Map<String, String> resources = new HashMap<>();
        resources.put("scripts/process.py", "print('hello')");

        AgentSkill skill = new AgentSkill("test", "desc", "content", resources);

        assertEquals("print('hello')", skill.getResource("scripts/process.py"));
        assertEquals(null, skill.getResource("missing.txt"));
    }

    @Test
    @DisplayName("Should return empty paths when no resources")
    void testGetResourcePathsNoResources() {
        AgentSkill skill = new AgentSkill("test", "desc", "content", null);
        Set<String> paths = skill.getResourcePaths();

        assertNotNull(paths);
        assertTrue(paths.isEmpty());
    }
}
