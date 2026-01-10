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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillBoxToolsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private SkillBox skillBox;
    private Toolkit toolkit;

    private boolean isErrorResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return false;
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .anyMatch(text -> text.startsWith("Error:"));
    }

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
        skillBox.registerSkill(skill1);

        AgentSkill skill2 =
                new AgentSkill("empty_skill", "Empty Skill", "# Empty", new HashMap<>());
        skillBox.registerSkill(skill2);

        // Register skill access tools
        skillBox.registerSkillLoadTool();
    }

    @Test
    @DisplayName("Should create load skill resource tool")
    void testCreateLoadSkillResourceTool() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        assertNotNull(tool);
        assertEquals("load_skill_through_path", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("Load and activate"));
    }

    @Test
    @DisplayName("Should load skill resource tool have correct parameters")
    void testLoadSkillResourceToolParameters() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");
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

        // Verify skillId has enum with available skills
        @SuppressWarnings("unchecked")
        Map<String, Object> skillIdParam = (Map<String, Object>) properties.get("skillId");
        assertNotNull(skillIdParam.get("enum"));
    }

    @Test
    @DisplayName("Should load skill markdown successfully")
    void testLoadSkillMarkdownSuccessfully() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-001")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

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
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        assertFalse(skillBox.isSkillActive("test_skill_custom"));

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-002")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();
        tool.callAsync(param).block(TIMEOUT);

        assertTrue(skillBox.isSkillActive("test_skill_custom"));
    }

    @Test
    @DisplayName("Should return error for non existent skill")
    void testReturnErrorForNonExistentSkill() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "non_existent_skill", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-003")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("not found"));
    }

    @Test
    @DisplayName("Should return error for missing skill id")
    void testReturnErrorForMissingSkillId() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-004")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("Missing") || content.contains("required"));
    }

    @Test
    @DisplayName("Should return error for empty skill id")
    void testReturnErrorForEmptySkillId() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-005")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }

    @Test
    @DisplayName("Should load skill resource successfully")
    void testLoadSkillResourceSuccessfully() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "config.json");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-006")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

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
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        assertFalse(skillBox.isSkillActive("test_skill_custom"));

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "data.txt");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-007")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();
        tool.callAsync(param).block(TIMEOUT);

        assertTrue(skillBox.isSkillActive("test_skill_custom"));
    }

    @Test
    @DisplayName("Should return error for non existent resource and list available resources")
    void testReturnErrorForNonExistentResource() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input =
                Map.of("skillId", "test_skill_custom", "path", "non_existent.txt");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-008")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("not found"));
        // Should also list available resources
        assertTrue(content.contains("SKILL.md"));
        assertTrue(content.contains("config.json"));
        assertTrue(content.contains("data.txt"));
    }

    @Test
    @DisplayName("Should return error for missing path parameter")
    void testReturnErrorForMissingPathParameter() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-009")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("Missing") || content.contains("required"));
    }

    @Test
    @DisplayName("Should list all resource paths when using invalid path")
    void testListAllResourcePathsWithInvalidPath() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "list");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-010")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("SKILL.md"));
        assertTrue(content.contains("config.json"));
        assertTrue(content.contains("data.txt"));
    }

    @Test
    @DisplayName("Should list only SKILL.md for skill without additional resources")
    void testListOnlySkillMdForSkillWithoutResources() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "empty_skill_custom", "path", "invalid");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-011")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("SKILL.md"));
        assertTrue(content.contains("Available resources"));
    }

    @Test
    @DisplayName("Should handle whitespace in skill id")
    void testHandleWhitespaceInSkillId() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "  ", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-012")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }

    @Test
    @DisplayName("Should handle whitespace in resource path")
    void testHandleWhitespaceInResourcePath() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "  ");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-013")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }
}
