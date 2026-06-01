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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for SkillToolFactory#loadSkillResourceImpl.
 *
 * <p>Since {@code loadSkillResourceImpl} is a private method, tests invoke it indirectly
 * through the {@code callAsync} method of the AgentTool created by
 * {@code createSkillAccessToolAgentTool()}.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class SkillToolFactoryTest {

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private Toolkit toolkit;

    private SkillToolFactory skillToolFactory;

    @BeforeEach
    void setUp() {
        skillToolFactory = new SkillToolFactory(skillRegistry, toolkit);
    }

    /**
     * Creates the skill access AgentTool.
     * Note: getAllRegisteredSkills is only called lazily inside getParameters(),
     * so it should be stubbed only in tests that actually invoke getParameters().
     *
     * @return the AgentTool for load_skill_through_path
     */
    private AgentTool createSkillAccessTool() {
        return skillToolFactory.createSkillAccessToolAgentTool();
    }

    /**
     * Creates a ToolCallParam with the given skillId and path.
     *
     * @param skillId the skill identifier
     * @param path the resource path
     * @return a ToolCallParam instance
     */
    private ToolCallParam createToolCallParam(String skillId, String path) {
        Map<String, Object> input = new HashMap<>();
        input.put("skillId", skillId);
        input.put("path", path);
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("test-call-id", "load_skill_through_path", input))
                .input(input)
                .build();
    }

    /**
     * Creates a ToolCallParam with only the given input map (for missing-parameter tests).
     *
     * @param input the input parameters map
     * @return a ToolCallParam instance
     */
    private ToolCallParam createToolCallParamWithInput(Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("test-call-id", "load_skill_through_path", input))
                .input(input)
                .build();
    }

    /**
     * Creates a test AgentSkill with given name and optional resources.
     *
     * @param name the skill name
     * @param resources the resources map, can be null
     * @return a new AgentSkill instance
     */
    private AgentSkill createTestSkill(String name, Map<String, String> resources) {
        return new AgentSkill(name, "Test description for " + name,
                "# Skill Content\nInstructions for " + name, resources, "test-source");
    }

    /**
     * Prepares the SkillRegistry mock so that the given skill is registered and retrievable.
     * Only stubs exists() and getSkill(). Does NOT stub getRegisteredSkill() —
     * tests that exercise the activation path should call stubActivation() separately.
     *
     * @param skillId the skill identifier
     * @param skill the AgentSkill to register
     */
    private void registerMockSkill(String skillId, AgentSkill skill) {
        when(skillRegistry.exists(skillId)).thenReturn(true);
        when(skillRegistry.getSkill(skillId)).thenReturn(skill);
    }

    /**
     * Stubs getRegisteredSkill() so that activateSkill() can proceed without NPE.
     * Call this only in tests where the code path reaches activateSkill().
     *
     * @param skillId the skill identifier
     */
    private void stubActivation(String skillId) {
        when(skillRegistry.getRegisteredSkill(skillId)).thenReturn(new RegisteredSkill(skillId));
    }

    @Nested
    @DisplayName("Loading SKILL.md")
    class LoadSkillMarkdownTests {

        @Test
        @DisplayName("Should return skill markdown content when path is SKILL.md")
        void shouldReturnSkillMarkdownContent() {
            AgentSkill skill = createTestSkill("my-skill", null);
            registerMockSkill("my-skill", skill);
            stubActivation("my-skill");
            when(toolkit.getToolGroup("my-skill_skill_tools")).thenReturn(null);
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("my-skill", "SKILL.md")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Successfully loaded skill: my-skill"));
            assertTrue(output.contains("Name: my-skill"));
            assertTrue(output.contains("Description: Test description for my-skill"));
            assertTrue(output.contains("Source: test-source"));
            assertTrue(output.contains("# Skill Content"));
            verify(skillRegistry).setSkillActive("my-skill", true);
        }

        @Test
        @DisplayName("Should activate skill tool group when it exists")
        void shouldActivateToolGroupWhenExists() {
            AgentSkill skill = createTestSkill("my-skill", null);
            registerMockSkill("my-skill", skill);
            stubActivation("my-skill");
            ToolGroup toolGroup = mock(ToolGroup.class);
            when(toolkit.getToolGroup("my-skill_skill_tools")).thenReturn(toolGroup);
            AgentTool tool = createSkillAccessTool();

            tool.callAsync(createToolCallParam("my-skill", "SKILL.md")).block();

            verify(skillRegistry).setSkillActive("my-skill", true);
            verify(toolkit).updateToolGroups(anyList(), eq(true));
        }
    }

    @Nested
    @DisplayName("Loading regular resources via getResource")
    class LoadResourceTests {

        @Test
        @DisplayName("Should return resource content when resource exists")
        void shouldReturnResourceContent() {
            Map<String, String> resources = Map.of(
                    "scripts/run.py", "print('hello')",
                    "config/settings.json", "{\"key\": \"value\"}");
            AgentSkill skill = createTestSkill("data-skill", resources);
            registerMockSkill("data-skill", skill);
            stubActivation("data-skill");
            when(toolkit.getToolGroup("data-skill_skill_tools")).thenReturn(null);
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("data-skill", "scripts/run.py")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Successfully loaded resource from skill: data-skill"));
            assertTrue(output.contains("Resource path: scripts/run.py"));
            assertTrue(output.contains("print('hello')"));
            verify(skillRegistry).setSkillActive("data-skill", true);
        }

        @Test
        @DisplayName("Should return error with available resources when resource not found")
        void shouldReturnErrorWhenResourceNotFound() {
            Map<String, String> resources = Map.of("docs/readme.md", "readme content");
            AgentSkill skill = createTestSkill("doc-skill", resources);
            registerMockSkill("doc-skill", skill);
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("doc-skill", "nonexistent.txt")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Resource not found: 'nonexistent.txt'"));
            assertTrue(output.contains("doc-skill"));
            assertTrue(output.contains("SKILL.md"));
            assertTrue(output.contains("docs/readme.md"));
            verify(skillRegistry, never()).setSkillActive("doc-skill", true);
        }

        @Test
        @DisplayName("Should list SKILL.md as first available resource when no resources exist")
        void shouldListSkillMdWhenNoResources() {
            AgentSkill skill = createTestSkill("empty-skill", null);
            registerMockSkill("empty-skill", skill);
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("empty-skill", "some/path.txt")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Resource not found"));
            assertTrue(output.contains("1. SKILL.md"));
        }

        @Test
        @DisplayName("Should activate skill and tool group when resource is found")
        void shouldActivateSkillAndToolGroupForResource() {
            Map<String, String> resources = Map.of("data.csv", "a,b,c");
            AgentSkill skill = createTestSkill("csv-skill", resources);
            registerMockSkill("csv-skill", skill);
            stubActivation("csv-skill");
            ToolGroup toolGroup = mock(ToolGroup.class);
            when(toolkit.getToolGroup("csv-skill_skill_tools")).thenReturn(toolGroup);
            AgentTool tool = createSkillAccessTool();

            tool.callAsync(createToolCallParam("csv-skill", "data.csv")).block();

            verify(skillRegistry).setSkillActive("csv-skill", true);
            verify(toolkit).updateToolGroups(anyList(), eq(true));
        }
    }

    @Nested
    @DisplayName("Skill validation")
    class SkillValidationTests {

        @Test
        @DisplayName("Should return error when skill does not exist")
        void shouldReturnErrorWhenSkillNotFound() {
            when(skillRegistry.exists("unknown-skill")).thenReturn(false);
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("unknown-skill", "SKILL.md")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Skill not found: 'unknown-skill'"));
        }

        @Test
        @DisplayName("Should return error when skill exists but getSkill returns null")
        void shouldReturnErrorWhenGetSkillReturnsNull() {
            when(skillRegistry.exists("broken-skill")).thenReturn(true);
            when(skillRegistry.getSkill("broken-skill")).thenReturn(null);
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("broken-skill", "SKILL.md")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Failed to load skill 'broken-skill'"));
        }
    }

    @Nested
    @DisplayName("Parameter validation in callAsync")
    class ParameterValidationTests {

        @Test
        @DisplayName("Should return error when skillId is missing")
        void shouldReturnErrorWhenSkillIdMissing() {
            AgentTool tool = createSkillAccessTool();
            ToolCallParam param = createToolCallParamWithInput(Map.of("path", "SKILL.md"));

            ToolResultBlock result = tool.callAsync(param).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Missing or empty required parameter: skillId"));
        }

        @Test
        @DisplayName("Should return error when skillId is empty string")
        void shouldReturnErrorWhenSkillIdEmpty() {
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("  ", "SKILL.md")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Missing or empty required parameter: skillId"));
        }

        @Test
        @DisplayName("Should return error when path is missing")
        void shouldReturnErrorWhenPathMissing() {
            AgentTool tool = createSkillAccessTool();
            ToolCallParam param = createToolCallParamWithInput(Map.of("skillId", "my-skill"));

            ToolResultBlock result = tool.callAsync(param).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Missing or empty required parameter: path"));
        }

        @Test
        @DisplayName("Should return error when path is empty string")
        void shouldReturnErrorWhenPathEmpty() {
            AgentTool tool = createSkillAccessTool();

            ToolResultBlock result = tool.callAsync(
                    createToolCallParam("my-skill", "   ")).block();

            assertNotNull(result);
            String output = result.getOutput().get(0).toString();
            assertTrue(output.contains("Missing or empty required parameter: path"));
        }
    }

    @Nested
    @DisplayName("Tool metadata")
    class ToolMetadataTests {

        @Test
        @DisplayName("Should have correct tool name")
        void shouldHaveCorrectToolName() {
            AgentTool tool = createSkillAccessTool();
            assertTrue("load_skill_through_path".equals(tool.getName()));
        }

        @Test
        @DisplayName("Should have non-empty description")
        void shouldHaveNonEmptyDescription() {
            AgentTool tool = createSkillAccessTool();
            assertNotNull(tool.getDescription());
            assertTrue(tool.getDescription().length() > 0);
        }

        @Test
        @DisplayName("Should have parameters with skillId and path")
        void shouldHaveRequiredParameters() {
            when(skillRegistry.getAllRegisteredSkills()).thenReturn(new ConcurrentHashMap<>());
            AgentTool tool = createSkillAccessTool();
            Map<String, Object> params = tool.getParameters();
            assertNotNull(params);
            assertTrue(params.containsKey("properties"));
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");
            assertTrue(properties.containsKey("skillId"));
            assertTrue(properties.containsKey("path"));
        }
    }
}
