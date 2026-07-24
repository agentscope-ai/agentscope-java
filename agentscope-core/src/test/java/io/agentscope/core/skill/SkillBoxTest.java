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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for SkillBox.
 *
 * <p>
 * These tests verify skill registration.
 */
@Tag("unit")
class SkillBoxTest {

    private SkillBox skillBox;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        skillBox = new SkillBox(toolkit);
        toolkit.registerTool(skillBox);
    }

    @Nested
    @DisplayName("SkillBox Basic Skill Management Test")
    class SkillBoxBasic {
        @Test
        @DisplayName("Should get skill by id")
        void testGetSkillById() {
            AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
            skillBox.registerSkill(skill);

            AgentSkill retrieved = skillBox.getSkill(skill.getSkillId());

            assertNotNull(retrieved);
            assertEquals("test_skill", retrieved.getName());
            assertEquals("Test Skill", retrieved.getDescription());
        }

        @Test
        @DisplayName("Should throw exception for null skill id")
        void testThrowExceptionForNullSkillId() {
            assertThrows(IllegalArgumentException.class, () -> skillBox.getSkill(null));
        }

        @Test
        @DisplayName("Should remove skill")
        void testRemoveSkill() {
            AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
            skillBox.registerSkill(skill);

            assertTrue(skillBox.exists("test_skill_custom"));

            skillBox.removeSkill("test_skill_custom");

            assertFalse(skillBox.exists(skill.getSkillId()));
        }

        @Test
        @DisplayName("Should check skill exists")
        void testCheckSkillExists() {
            assertFalse(skillBox.exists("non_existent_skill"));

            AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
            skillBox.registerSkill(skill);

            assertTrue(skillBox.exists(skill.getSkillId()));
        }

        @Test
        @DisplayName("Should register skill load tools")
        void testRegisterSkillLoadTools() {
            // After registerSkillAccessTools is called, the tool should be available
            skillBox.registerSkillLoadTool();
            assertNotNull(toolkit.getTool("load_skill_through_path"));
        }

        @Test
        @DisplayName("Should create tool group when registering skill")
        void testCreateToolGroupWhenRegisteringSkill() {
            AgentSkill skill = new AgentSkill("my_skill", "My Skill", "# Content", null);

            // Before registration, the tool group should not exist
            String toolsGroupName = skill.getSkillId() + "_skill_tools";
            assertNull(
                    toolkit.getToolGroup(toolsGroupName),
                    "Tool group should not exist before skill registration");

            // Create a simple test tool
            AgentTool testTool = createTestTool("test_tool");

            // Register the skill with tool
            skillBox.registration().agentTool(testTool).skill(skill).apply();

            // After registration, the tool group should be created
            assertNotNull(
                    toolkit.getToolGroup(toolsGroupName),
                    "Tool group should be created after skill registration");

            // Verify the tool group properties
            var toolGroup = toolkit.getToolGroup(toolsGroupName);
            assertEquals(toolsGroupName, toolGroup.getName());
        }

        @Test
        @DisplayName("Should throw exception for null skill id in operations")
        void testThrowExceptionForNullSkillIdInOperations() {
            assertThrows(IllegalArgumentException.class, () -> skillBox.removeSkill(null));
            assertThrows(IllegalArgumentException.class, () -> skillBox.exists(null));
            assertThrows(IllegalArgumentException.class, () -> skillBox.setSkillActive(null, true));
        }

        @Test
        @DisplayName("Should successfully register when only tool object is provided")
        void testSuccessfullyRegisterWhenOnlyToolObjectProvided() {
            TestToolObject toolObject = new TestToolObject();
            AgentSkill skill =
                    new AgentSkill(
                            "Tool Object Only Skill",
                            "Skill with only tool object",
                            "# Tool Object",
                            null);

            // Should not throw - only one registration type
            skillBox.registration().skill(skill).tool(toolObject).apply();

            assertTrue(skillBox.exists(skill.getSkillId()));
            assertNotNull(toolkit.getTool("test_tool_method"), "Tool should be registered");
        }

        @Test
        @DisplayName("Should successfully register when only agent tool is provided")
        void testSuccessfullyRegisterWhenOnlyAgentToolProvided() {
            AgentTool agentTool = createTestTool("agent_tool_only");
            AgentSkill skill =
                    new AgentSkill(
                            "Agent Tool Only Skill",
                            "Skill with only agent tool",
                            "# Agent Tool",
                            null);

            // Should not throw - only one registration type
            skillBox.registration().skill(skill).agentTool(agentTool).apply();

            assertTrue(skillBox.exists(skill.getSkillId()));
            assertNotNull(toolkit.getTool("agent_tool_only"), "Agent tool should be registered");
        }

        @Test
        @DisplayName("Should bind ALL agent tools when multiple are registered on one skill")
        void testMultipleAgentToolsAllBoundToSkillGroup() {
            AgentTool first = createTestTool("multi_tool_first");
            AgentTool second = createTestTool("multi_tool_second");
            AgentSkill skill =
                    new AgentSkill(
                            "Multi Tool Skill", "Skill with two agent tools", "# Multi", null);

            // 连续两次 agentTool()：此前第二次会覆盖第一次，导致只有 second 被绑进门控组
            skillBox.registration().skill(skill).agentTool(first).agentTool(second).apply();

            String groupName = skill.getSkillId() + "_skill_tools";
            assertNotNull(toolkit.getToolGroup(groupName), "skill tool group should exist");
            assertTrue(
                    toolkit.getToolGroup(groupName).getTools().contains("multi_tool_first"),
                    "first tool must also be bound into the skill group (regression: was"
                            + " overwritten)");
            assertTrue(
                    toolkit.getToolGroup(groupName).getTools().contains("multi_tool_second"),
                    "second tool must be bound into the skill group");
            assertFalse(
                    toolkit.getToolGroup(groupName).isActive(),
                    "skill tool group must start inactive (gated until skill is loaded)");

            assertNotNull(toolkit.getTool("multi_tool_first"));
            assertNotNull(toolkit.getTool("multi_tool_second"));
        }

        @Test
        @DisplayName("Should successfully register when only mcp client is provided")
        void testSuccessfullyRegisterWhenOnlyMcpClientProvided() {
            McpClientWrapper mcpClient = mock(McpClientWrapper.class);
            McpSchema.Tool mockToolInfo =
                    new McpSchema.Tool(
                            "mcp_only_tool",
                            null,
                            "MCP only tool",
                            new McpSchema.JsonSchema("object", null, null, null, null, null),
                            null,
                            null,
                            null);
            when(mcpClient.listTools()).thenReturn(Mono.just(List.of(mockToolInfo)));
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.initialize()).thenReturn(Mono.empty());
            when(mcpClient.getName()).thenReturn("mcp-only-client");

            AgentSkill skill =
                    new AgentSkill(
                            "MCP Only Skill", "Skill with only MCP client", "# MCP Only", null);

            // Should not throw - only one registration type
            skillBox.registration().skill(skill).mcpClient(mcpClient).apply();

            assertTrue(skillBox.exists(skill.getSkillId()));
            assertNotNull(toolkit.getTool("mcp_only_tool"), "MCP tool should be registered");
        }

        @Test
        @DisplayName("Should return empty set when no skills are registered")
        void testGetAllSkillIdsWhenEmpty() {
            var skillIds = skillBox.getAllSkillIds();

            assertNotNull(skillIds, "Skill IDs set should not be null");
            assertTrue(
                    skillIds.isEmpty(), "Skill IDs set should be empty when no skills registered");
        }

        @Test
        @DisplayName("Should return all skill IDs when multiple skills are registered")
        void testGetAllSkillIdsWithMultipleSkills() {
            AgentSkill skill1 = new AgentSkill("skill_one", "Skill One", "# Content 1", null);
            AgentSkill skill2 = new AgentSkill("skill_two", "Skill Two", "# Content 2", null);
            AgentSkill skill3 = new AgentSkill("skill_three", "Skill Three", "# Content 3", null);

            skillBox.registerSkill(skill1);
            skillBox.registerSkill(skill2);
            skillBox.registerSkill(skill3);

            var skillIds = skillBox.getAllSkillIds();

            assertNotNull(skillIds, "Skill IDs set should not be null");
            assertEquals(3, skillIds.size(), "Should have exactly three skill IDs");
            assertTrue(skillIds.contains(skill1.getSkillId()), "Should contain first skill ID");
            assertTrue(skillIds.contains(skill2.getSkillId()), "Should contain second skill ID");
            assertTrue(skillIds.contains(skill3.getSkillId()), "Should contain third skill ID");
        }

        @Test
        @DisplayName("Should throw exception when setting active state for non-existent skill")
        void testThrowExceptionForNonExistentSkillInSetActive() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> skillBox.setSkillActive("fake_non_existent_skill", true));
            assertEquals(
                    "Skill ID does not exist: fake_non_existent_skill", exception.getMessage());
        }

        @Test
        @DisplayName("Should update skill active state and automatically sync toolkit")
        void testSetSkillActiveAutoSyncsState() {
            AgentSkill skill =
                    new AgentSkill("test_active_skill", "Test Active Skill", "# Content", null);
            AgentTool testTool = createTestTool("active_test_tool");

            skillBox.registration().skill(skill).agentTool(testTool).apply();

            String toolsGroupName = skill.getSkillId() + "_skill_tools";

            assertFalse(
                    skillBox.isSkillActive(skill.getSkillId()),
                    "Skill should be inactive initially");
            assertNotNull(toolkit.getToolGroup(toolsGroupName), "ToolGroup should be created");
            assertFalse(
                    toolkit.getToolGroup(toolsGroupName).isActive(),
                    "ToolGroup should be inactive initially");

            skillBox.setSkillActive(skill.getSkillId(), true);

            assertTrue(
                    skillBox.isSkillActive(skill.getSkillId()),
                    "Skill logical state should be active now");
            assertTrue(
                    toolkit.getToolGroup(toolsGroupName).isActive(),
                    "ToolGroup should be AUTOMATICALLY activated without explicit sync");

            skillBox.setSkillActive(skill.getSkillId(), false);

            assertFalse(
                    skillBox.isSkillActive(skill.getSkillId()),
                    "Skill logical state should be inactive");
            assertFalse(
                    toolkit.getToolGroup(toolsGroupName).isActive(),
                    "ToolGroup should be AUTOMATICALLY deactivated without explicit sync");
        }

        @Test
        @DisplayName("Should successfully set active state for a skill without bound tools")
        void testSetSkillActiveWithoutTools() {
            AgentSkill purePromptSkill =
                    new AgentSkill(
                            "pure_prompt_skill",
                            "Skill without tools",
                            "# Just a pure prompt instruction",
                            null);
            skillBox.registerSkill(purePromptSkill);

            String toolsGroupName = purePromptSkill.getSkillId() + "_skill_tools";
            assertNull(
                    toolkit.getToolGroup(toolsGroupName),
                    "ToolGroup should not exist for pure prompt skill");

            assertDoesNotThrow(
                    () -> skillBox.setSkillActive(purePromptSkill.getSkillId(), true),
                    "Should not throw exception when activating a non-tool-bind skill");
            assertTrue(
                    skillBox.isSkillActive(purePromptSkill.getSkillId()),
                    "Logical state should be active");

            assertDoesNotThrow(
                    () -> skillBox.setSkillActive(purePromptSkill.getSkillId(), false),
                    "Should not throw exception when deactivating a non-tool-bind skill");
            assertFalse(
                    skillBox.isSkillActive(purePromptSkill.getSkillId()),
                    "Logical state should be inactive");
        }
    }

    private AgentTool createTestTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Test tool: " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(
                        ToolResultBlock.of(TextBlock.builder().text("Test result").build()));
            }
        };
    }

    /**
     * Test tool class with @Tool annotated methods for testing tool object
     * registration.
     */
    private static class TestToolObject {

        @Tool(name = "test_tool_method", description = "A test tool method")
        public Mono<ToolResultBlock> testToolMethod(
                @ToolParam(name = "input", description = "Test input") String input) {
            return Mono.just(
                    ToolResultBlock.of(TextBlock.builder().text("Result: " + input).build()));
        }
    }
}
