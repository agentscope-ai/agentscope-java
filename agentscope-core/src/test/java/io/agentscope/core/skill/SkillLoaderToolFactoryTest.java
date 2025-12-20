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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillLoaderToolFactoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private SkillRegistry skillRegistry;
    private SkillLoaderToolFactory factory;

    private boolean isErrorResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return false;
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof io.agentscope.core.message.TextBlock)
                .map(block -> ((io.agentscope.core.message.TextBlock) block).getText())
                .anyMatch(text -> text != null && text.startsWith("Error:"));
    }

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        factory = new SkillLoaderToolFactory(skillRegistry);

        // Register test skills
        Map<String, String> resources1 = new HashMap<>();
        resources1.put("config.json", "{\"key\": \"value\"}");
        resources1.put("data.txt", "sample data");

        AgentSkill skill1 =
                new AgentSkill("test_skill", "Test Skill", "# Test Content", resources1);
        RegisteredSkill registered1 = new RegisteredSkill("test_skill_custom");
        skillRegistry.registerSkill("test_skill_custom", skill1, registered1);

        AgentSkill skill2 =
                new AgentSkill("empty_skill", "Empty Skill", "# Empty", new HashMap<>());
        RegisteredSkill registered2 = new RegisteredSkill("empty_skill_custom");
        skillRegistry.registerSkill("empty_skill_custom", skill2, registered2);
    }

    @Test
    @DisplayName("Should create skill md load tool")
    void testCreateSkillMdLoadTool() {
        AgentTool tool = factory.createSkillMdLoadTool();

        assertNotNull(tool);
        assertEquals("skill_md_load_tool", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("markdown content"));
    }

    @Test
    @DisplayName("Should create skill resources load tool")
    void testCreateSkillResourcesLoadTool() {
        AgentTool tool = factory.createSkillResourcesLoadTool();

        assertNotNull(tool);
        assertEquals("skill_resources_load_tool", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("resource file"));
    }

    @Test
    @DisplayName("Should create get all resources path tool")
    void testCreateGetAllResourcesPathTool() {
        AgentTool tool = factory.createGetAllResourcesPathTool();

        assertNotNull(tool);
        assertEquals("get_all_resources_path_tool", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("resource file paths"));
    }

    @Test
    @DisplayName("Should skill md load tool have correct parameters")
    void testSkillMdLoadToolParameters() {
        AgentTool tool = factory.createSkillMdLoadTool();
        Map<String, Object> params = tool.getParameters();

        assertNotNull(params);
        assertEquals("object", params.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("skillId"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) params.get("required");
        assertTrue(required.contains("skillId"));
    }

    @Test
    @DisplayName("Should skill resources load tool have correct parameters")
    void testSkillResourcesLoadToolParameters() {
        AgentTool tool = factory.createSkillResourcesLoadTool();
        Map<String, Object> params = tool.getParameters();

        assertNotNull(params);
        assertEquals("object", params.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("skillId"));
        assertTrue(properties.containsKey("path"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) params.get("required");
        assertTrue(required.contains("skillId"));
        assertTrue(required.contains("path"));
    }

    @Test
    @DisplayName("Should get all resources path tool have correct parameters")
    void testGetAllResourcesPathToolParameters() {
        AgentTool tool = factory.createGetAllResourcesPathTool();
        Map<String, Object> params = tool.getParameters();

        assertNotNull(params);
        assertEquals("object", params.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("skillId"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) params.get("required");
        assertTrue(required.contains("skillId"));
    }

    @Test
    @DisplayName("Should load skill markdown successfully")
    void testLoadSkillMarkdownSuccessfully() {
        AgentTool tool = factory.createSkillMdLoadTool();

        Map<String, Object> input = Map.of("skillId", "test_skill_custom");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertFalse(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("test_skill"));
        assertTrue(content.contains("Test Content"));
    }

    @Test
    @DisplayName("Should activate skill when loading markdown")
    void testActivateSkillWhenLoadingMarkdown() {
        AgentTool tool = factory.createSkillMdLoadTool();

        assertFalse(skillRegistry.getRegisteredSkill("test_skill_custom").isActive());

        Map<String, Object> input = Map.of("skillId", "test_skill_custom");
        ToolCallParam param = ToolCallParam.builder().input(input).build();
        tool.callAsync(param).block(TIMEOUT);

        assertTrue(skillRegistry.getRegisteredSkill("test_skill_custom").isActive());
    }

    @Test
    @DisplayName("Should return error for non existent skill")
    void testReturnErrorForNonExistentSkill() {
        AgentTool tool = factory.createSkillMdLoadTool();

        Map<String, Object> input = Map.of("skillId", "non_existent_skill");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("not found"));
    }

    @Test
    @DisplayName("Should return error for missing skill id")
    void testReturnErrorForMissingSkillId() {
        AgentTool tool = factory.createSkillMdLoadTool();

        Map<String, Object> input = new HashMap<>();
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("Missing") || content.contains("required"));
    }

    @Test
    @DisplayName("Should return error for empty skill id")
    void testReturnErrorForEmptySkillId() {
        AgentTool tool = factory.createSkillMdLoadTool();

        Map<String, Object> input = Map.of("skillId", "");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }

    @Test
    @DisplayName("Should load skill resource successfully")
    void testLoadSkillResourceSuccessfully() {
        AgentTool tool = factory.createSkillResourcesLoadTool();

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "config.json");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertFalse(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("config.json"));
        assertTrue(content.contains("{\"key\": \"value\"}"));
    }

    @Test
    @DisplayName("Should activate skill when loading resource")
    void testActivateSkillWhenLoadingResource() {
        AgentTool tool = factory.createSkillResourcesLoadTool();

        assertFalse(skillRegistry.getRegisteredSkill("test_skill_custom").isActive());

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "data.txt");
        ToolCallParam param = ToolCallParam.builder().input(input).build();
        tool.callAsync(param).block(TIMEOUT);

        assertTrue(skillRegistry.getRegisteredSkill("test_skill_custom").isActive());
    }

    @Test
    @DisplayName("Should return error for non existent resource")
    void testReturnErrorForNonExistentResource() {
        AgentTool tool = factory.createSkillResourcesLoadTool();

        Map<String, Object> input =
                Map.of("skillId", "test_skill_custom", "path", "non_existent.txt");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("not found"));
    }

    @Test
    @DisplayName("Should return error for missing path parameter")
    void testReturnErrorForMissingPathParameter() {
        AgentTool tool = factory.createSkillResourcesLoadTool();

        Map<String, Object> input = Map.of("skillId", "test_skill_custom");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("Missing") || content.contains("required"));
    }

    @Test
    @DisplayName("Should get all resource paths successfully")
    void testGetAllResourcePathsSuccessfully() {
        AgentTool tool = factory.createGetAllResourcesPathTool();

        Map<String, Object> input = Map.of("skillId", "test_skill_custom");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertFalse(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("config.json"));
        assertTrue(content.contains("data.txt"));
        assertTrue(content.contains("2 total"));
    }

    @Test
    @DisplayName("Should activate skill when getting resource paths")
    void testActivateSkillWhenGettingResourcePaths() {
        AgentTool tool = factory.createGetAllResourcesPathTool();

        assertFalse(skillRegistry.getRegisteredSkill("test_skill_custom").isActive());

        Map<String, Object> input = Map.of("skillId", "test_skill_custom");
        ToolCallParam param = ToolCallParam.builder().input(input).build();
        tool.callAsync(param).block(TIMEOUT);

        assertTrue(skillRegistry.getRegisteredSkill("test_skill_custom").isActive());
    }

    @Test
    @DisplayName("Should return empty message for skill without resources")
    void testReturnEmptyMessageForSkillWithoutResources() {
        AgentTool tool = factory.createGetAllResourcesPathTool();

        Map<String, Object> input = Map.of("skillId", "empty_skill_custom");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertFalse(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("No resources"));
    }

    @Test
    @DisplayName("Should include skill enum in parameters when skills exist")
    void testIncludeSkillEnumInParameters() {
        AgentTool tool = factory.createSkillMdLoadTool();
        Map<String, Object> params = tool.getParameters();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");

        @SuppressWarnings("unchecked")
        Map<String, Object> skillIdParam = (Map<String, Object>) properties.get("skillId");

        assertNotNull(skillIdParam.get("enum"));

        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) skillIdParam.get("enum");
        assertTrue(enumValues.contains("test_skill_custom"));
        assertTrue(enumValues.contains("empty_skill_custom"));
    }

    @Test
    @DisplayName("Should handle whitespace in skill id")
    void testHandleWhitespaceInSkillId() {
        AgentTool tool = factory.createSkillMdLoadTool();

        Map<String, Object> input = Map.of("skillId", "  ");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }

    @Test
    @DisplayName("Should handle whitespace in resource path")
    void testHandleWhitespaceInResourcePath() {
        AgentTool tool = factory.createSkillResourcesLoadTool();

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "  ");
        ToolCallParam param = ToolCallParam.builder().input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }
}
