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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for SkillBox.
 *
 * <p>These tests verify skill registration.
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
        assertNotNull(toolkit.getTool("skill_md_load_tool"));
        assertNotNull(toolkit.getTool("skill_resources_load_tool"));
        assertNotNull(toolkit.getTool("get_all_resources_path_tool"));
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
    }

    @Test
    @DisplayName("Should register skill with tool object")
    void testRegisterSkillWithToolObject() {
        AgentSkill skill =
                new AgentSkill("Test Tool Skill", "Skill with tool object", "# Test Tool", null);

        skillBox.registration().skill(skill).tool(new TestToolObject()).apply();

        assertTrue(skillBox.exists(skill.getSkillId()));
        AgentTool tool = toolkit.getTool("test_tool_method");
        assertNotNull(tool, "Tool from tool object should be registered");
        assertEquals("test_tool_method", tool.getName());
    }

    @Test
    @DisplayName("Should register skill with mcp client")
    void testRegisterSkillWithMcpClient() {
        // Mock MCP client
        McpClientWrapper mcpClient = mock(McpClientWrapper.class);
        McpSchema.Tool mockToolInfo =
                new McpSchema.Tool(
                        "mcp_test_tool",
                        null,
                        "Test MCP tool",
                        new McpSchema.JsonSchema("object", null, null, null, null, null),
                        null,
                        null,
                        null);
        when(mcpClient.listTools()).thenReturn(Mono.just(List.of(mockToolInfo)));
        when(mcpClient.isInitialized()).thenReturn(true);
        when(mcpClient.initialize()).thenReturn(Mono.empty());
        when(mcpClient.getName()).thenReturn("test-mcp-client");

        AgentSkill skill = new AgentSkill("MCP Skill", "Skill with MCP client", "# MCP Test", null);

        skillBox.registration().skill(skill).mcpClient(mcpClient).apply();

        assertTrue(skillBox.exists(skill.getSkillId()));
        AgentTool tool = toolkit.getTool("mcp_test_tool");
        assertNotNull(tool, "Tool from MCP client should be registered");
        assertEquals("mcp_test_tool", tool.getName());
    }

    @Test
    @DisplayName("Should throw exception when registering multiple tool types")
    void testThrowExceptionWhenRegisteringMultipleToolTypes() {
        AgentTool agentTool = createTestTool("custom_agent_tool");
        TestToolObject toolObject = new TestToolObject();

        AgentSkill skill =
                new AgentSkill(
                        "Mixed Skill",
                        "Skill with both agent tool and tool object",
                        "# Mixed",
                        null);

        // Should throw when trying to register both agentTool and tool object
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                skillBox.registration()
                                        .skill(skill)
                                        .agentTool(agentTool)
                                        .tool(toolObject)
                                        .apply());

        assertTrue(
                exception.getMessage().contains("Cannot set multiple registration types"),
                "Exception message should mention multiple registration types");
    }

    @Test
    @DisplayName("Should throw exception when registering tool object and mcp client")
    void testThrowExceptionWhenRegisteringToolObjectAndMcpClient() {
        TestToolObject toolObject = new TestToolObject();
        McpClientWrapper mcpClient = mock(McpClientWrapper.class);

        AgentSkill skill =
                new AgentSkill("Mixed Skill", "Skill with multiple types", "# Mixed", null);

        // Should throw when trying to register both tool object and mcp client
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                skillBox.registration()
                                        .skill(skill)
                                        .tool(toolObject)
                                        .mcpClient(mcpClient)
                                        .apply());

        assertTrue(
                exception.getMessage().contains("Cannot set multiple registration types"),
                "Exception message should mention multiple registration types");
    }

    @Test
    @DisplayName("Should throw exception when registering agent tool and mcp client")
    void testThrowExceptionWhenRegisteringAgentToolAndMcpClient() {
        AgentTool agentTool = createTestTool("custom_agent_tool");
        McpClientWrapper mcpClient = mock(McpClientWrapper.class);

        AgentSkill skill =
                new AgentSkill("Mixed Skill", "Skill with multiple types", "# Mixed", null);

        // Should throw when trying to register both agent tool and mcp client
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                skillBox.registration()
                                        .skill(skill)
                                        .agentTool(agentTool)
                                        .mcpClient(mcpClient)
                                        .apply());

        assertTrue(
                exception.getMessage().contains("Cannot set multiple registration types"),
                "Exception message should mention multiple registration types");
    }

    /**
     * Helper method to create a simple test tool.
     */
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
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                schema.put("properties", new HashMap<String, Object>());
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(
                        ToolResultBlock.of(TextBlock.builder().text("Test result").build()));
            }
        };
    }

    /**
     * Test tool class with @Tool annotated methods for testing tool object registration.
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
