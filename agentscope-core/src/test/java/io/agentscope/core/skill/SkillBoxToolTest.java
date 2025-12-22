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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillBoxToolTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private SkillBox skillBox;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        skillBox = new SkillBox(toolkit);

        // Register test skills
        Map<String, String> resources1 = new HashMap<>();
        resources1.put("config.json", "{\"key\": \"value\"}");
        resources1.put("data.txt", "sample data");

        AgentSkill skill1 =
                new AgentSkill("test_skill", "Test Skill", "# Test Content", resources1);
        skillBox.registerAgentSkill(skill1);

        AgentSkill skill2 =
                new AgentSkill("empty_skill", "Empty Skill", "# Empty", new HashMap<>());
        skillBox.registerAgentSkill(skill2);

        // Register skill load tools
        skillBox.registerSkillLoadTools();
    }

    @Test
    @DisplayName("Should load skill markdown successfully")
    void testLoadSkillMarkdownSuccessfully() {
        String result = skillBox.loadSkillMd("test_skill_custom").block(TIMEOUT);

        assertNotNull(result);
        assertFalse(result.startsWith("Error:"));
        assertTrue(result.contains("test_skill"));
        assertTrue(result.contains("Test Content"));
    }

    @Test
    @DisplayName("Should activate skill when loading markdown")
    void testActivateSkillWhenLoadingMarkdown() {
        assertFalse(skillBox.isSkillActive("test_skill_custom"));

        skillBox.loadSkillMd("test_skill_custom").block(TIMEOUT);

        assertTrue(skillBox.isSkillActive("test_skill_custom"));
    }

    @Test
    @DisplayName("Should return error for non existent skill")
    void testReturnErrorForNonExistentSkill() {
        String result = skillBox.loadSkillMd("non_existent_skill").block(TIMEOUT);

        assertNotNull(result);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("not found"));
    }

    @Test
    @DisplayName("Should return error for empty skill id")
    void testReturnErrorForEmptySkillId() {
        String result = skillBox.loadSkillMd("").block(TIMEOUT);

        assertNotNull(result);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    @DisplayName("Should load skill resource successfully")
    void testLoadSkillResourceSuccessfully() {
        String result =
                skillBox.loadSkillResource("test_skill_custom", "config.json").block(TIMEOUT);

        assertNotNull(result);
        assertFalse(result.startsWith("Error:"));
        assertTrue(result.contains("config.json"));
        assertTrue(result.contains("{\"key\": \"value\"}"));
    }

    @Test
    @DisplayName("Should activate skill when loading resource")
    void testActivateSkillWhenLoadingResource() {
        assertFalse(skillBox.isSkillActive("test_skill_custom"));

        skillBox.loadSkillResource("test_skill_custom", "data.txt").block(TIMEOUT);

        assertTrue(skillBox.isSkillActive("test_skill_custom"));
    }

    @Test
    @DisplayName("Should return error for non existent resource")
    void testReturnErrorForNonExistentResource() {
        String result =
                skillBox.loadSkillResource("test_skill_custom", "non_existent.txt").block(TIMEOUT);

        assertNotNull(result);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("not found"));
    }

    @Test
    @DisplayName("Should return error for missing path parameter")
    void testReturnErrorForMissingPathParameter() {
        String result = skillBox.loadSkillResource("test_skill_custom", "").block(TIMEOUT);

        assertNotNull(result);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    @DisplayName("Should get all resource paths successfully")
    void testGetAllResourcePathsSuccessfully() {
        String result = skillBox.getAllResourcesPath("test_skill_custom").block(TIMEOUT);

        assertNotNull(result);
        assertFalse(result.startsWith("Error:"));
        assertTrue(result.contains("config.json"));
        assertTrue(result.contains("data.txt"));
        assertTrue(result.contains("2 total"));
    }

    @Test
    @DisplayName("Should activate skill when getting resource paths")
    void testActivateSkillWhenGettingResourcePaths() {
        assertFalse(skillBox.isSkillActive("test_skill_custom"));

        skillBox.getAllResourcesPath("test_skill_custom").block(TIMEOUT);

        assertTrue(skillBox.isSkillActive("test_skill_custom"));
    }

    @Test
    @DisplayName("Should return message for skill without resources")
    void testReturnMessageForSkillWithoutResources() {
        String result = skillBox.getAllResourcesPath("empty_skill_custom").block(TIMEOUT);

        assertNotNull(result);
        assertFalse(result.startsWith("Error:"));
        assertTrue(result.contains("no resources"));
    }

    @Test
    @DisplayName("Should handle whitespace in skill id")
    void testHandleWhitespaceInSkillId() {
        String result = skillBox.loadSkillMd("  ").block(TIMEOUT);

        assertNotNull(result);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    @DisplayName("Should handle whitespace in resource path")
    void testHandleWhitespaceInResourcePath() {
        String result = skillBox.loadSkillResource("test_skill_custom", "  ").block(TIMEOUT);

        assertNotNull(result);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    @DisplayName("Should register skill load tools to toolkit")
    void testRegisterSkillLoadToolsToToolkit() {
        // Tools should be registered after calling registerSkillLoadTools in setUp
        assertNotNull(toolkit.getTool("skill_md_load_tool"));
        assertNotNull(toolkit.getTool("skill_resources_load_tool"));
        assertNotNull(toolkit.getTool("get_all_resources_path_tool"));
    }
}
