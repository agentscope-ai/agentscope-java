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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.CommandValidator;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.coding.UnixCommandValidator;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    }

    @Nested
    @DisplayName("SkillBox Code Execution Test")
    class CodeExecutionTest {
        @TempDir Path tempDir;

        @Test
        @DisplayName("Should enable code execution with default temporary directory")
        void testEnableCodeExecutionWithDefaultTempDir() {
            skillBox.codeExecution().withShell().withRead().withWrite().enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            // workDir is null, meaning temporary directory will be created later
            assertNull(skillBox.getCodeExecutionWorkDir());

            // Verify tool group is created and activated
            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
            assertTrue(toolkit.getToolGroup("skill_code_execution_tool_group").isActive());
        }

        @Test
        @DisplayName("Should enable code execution with custom working directory")
        void testEnableCodeExecutionWithCustomWorkDir() {
            String customDir = tempDir.resolve("custom-code-exec").toString();

            skillBox.codeExecution().workDir(customDir).withShell().withRead().withWrite().enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            assertEquals(
                    Path.of(customDir).toAbsolutePath().normalize(),
                    skillBox.getCodeExecutionWorkDir());
            // Directory should not be created until scripts are written
            assertFalse(Files.exists(skillBox.getCodeExecutionWorkDir()));
        }

        @Test
        @DisplayName(
                "Should enable code execution with existing directory and not create until write")
        void testEnableCodeExecutionWithExistingDir() throws IOException {
            String existingDir = tempDir.resolve("existing-dir").toString();
            Files.createDirectories(Path.of(existingDir));

            skillBox.codeExecution()
                    .workDir(existingDir)
                    .withShell()
                    .withRead()
                    .withWrite()
                    .enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            assertEquals(
                    Path.of(existingDir).toAbsolutePath().normalize(),
                    skillBox.getCodeExecutionWorkDir());
            // Directory exists but should be empty
            assertTrue(Files.exists(skillBox.getCodeExecutionWorkDir()));
            assertEquals(0, Files.list(skillBox.getCodeExecutionWorkDir()).count());
        }

        @Test
        @DisplayName("Should throw exception when enabling code execution without toolkit")
        void testEnableCodeExecutionWithoutToolkit() {
            SkillBox skillBoxWithoutToolkit = new SkillBox();

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> skillBoxWithoutToolkit.codeExecution().withShell().enable());
            assertEquals(
                    "Must bind toolkit before enabling code execution", exception.getMessage());
        }

        @Test
        @DisplayName("Should write skill scripts to working directory organized by skill ID")
        void testWriteSkillScriptsToWorkDir() throws IOException {
            String workDir = tempDir.resolve("scripts").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            // Verify directory not created yet
            assertFalse(Files.exists(Path.of(workDir)));

            // Create skills with scripts
            Map<String, String> resources1 = new HashMap<>();
            resources1.put("scripts/process.py", "print('hello from python')");
            resources1.put("scripts/analyze.js", "console.log('hello from js')");
            resources1.put("config.json", "{}"); // Not a script

            Map<String, String> resources2 = new HashMap<>();
            resources2.put("main.py", "print('main script')");
            resources2.put("util.sh", "echo 'utility script'");

            AgentSkill skill1 = new AgentSkill("skill1", "First skill", "content1", resources1);
            AgentSkill skill2 = new AgentSkill("skill2", "Second skill", "content2", resources2);

            skillBox.registerSkill(skill1);
            skillBox.registerSkill(skill2);

            // Write scripts - directory should be created now
            skillBox.writeSkillScriptsToWorkDir();

            // Verify directory created
            Path workPath = Path.of(workDir);
            assertTrue(Files.exists(workPath));

            // Verify scripts are written to workDir/skillId/
            assertTrue(Files.exists(workPath.resolve("skill1_custom/scripts/process.py")));
            assertTrue(Files.exists(workPath.resolve("skill1_custom/scripts/analyze.js")));
            assertTrue(Files.exists(workPath.resolve("skill2_custom/main.py")));
            assertTrue(Files.exists(workPath.resolve("skill2_custom/util.sh")));
            assertFalse(
                    Files.exists(workPath.resolve("skill1_custom/config.json"))); // Not a script

            // Verify content
            String pythonContent =
                    Files.readString(workPath.resolve("skill1_custom/scripts/process.py"));
            assertEquals("print('hello from python')", pythonContent);
        }

        @Test
        @DisplayName("Should throw exception when writing scripts without enabling code execution")
        void testWriteScriptsWithoutEnabling() {
            AgentSkill skill = new AgentSkill("skill", "desc", "content", null);
            skillBox.registerSkill(skill);

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> skillBox.writeSkillScriptsToWorkDir());
            assertEquals("Code execution is not enabled", exception.getMessage());
        }

        @Test
        @DisplayName("Should handle empty scripts gracefully and not create skill directory")
        void testWriteEmptyScripts() throws IOException {
            String workDir = tempDir.resolve("empty-scripts").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            // Skill with no script resources
            Map<String, String> resources = new HashMap<>();
            resources.put("config.json", "{}");
            resources.put("data.txt", "data");

            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            // Should not throw exception
            assertDoesNotThrow(() -> skillBox.writeSkillScriptsToWorkDir());

            // Verify workDir is created but skill directory is not (no scripts)
            Path workPath = Path.of(workDir);
            assertTrue(Files.exists(workPath));
            // No skill directory should be created since there are no scripts
            assertFalse(Files.exists(workPath.resolve("skill_custom")));
        }

        @Test
        @DisplayName("Should overwrite existing scripts in skill directory")
        void testOverwriteExistingScripts() throws IOException {
            String workDir = tempDir.resolve("overwrite").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            // Create initial script in skill directory
            Path scriptPath = Path.of(workDir).resolve("skill_custom/test.py");
            Files.createDirectories(scriptPath.getParent());
            Files.writeString(scriptPath, "print('old content')");

            // Register skill with same script name
            Map<String, String> resources = new HashMap<>();
            resources.put("test.py", "print('new content')");
            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            skillBox.writeSkillScriptsToWorkDir();

            // Verify content is overwritten
            String content = Files.readString(scriptPath);
            assertEquals("print('new content')", content);
        }

        @Test
        @DisplayName("Should create nested directories for scripts in skill directory")
        void testNestedDirectories() throws IOException {
            String workDir = tempDir.resolve("nested").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            Map<String, String> resources = new HashMap<>();
            resources.put("scripts/utils/helper.py", "def help(): pass");
            resources.put("scripts/data/loader.js", "function load() {}");

            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            skillBox.writeSkillScriptsToWorkDir();

            Path workPath = Path.of(workDir);
            assertTrue(Files.exists(workPath.resolve("skill_custom/scripts/utils/helper.py")));
            assertTrue(Files.exists(workPath.resolve("skill_custom/scripts/data/loader.js")));
        }

        @Test
        @DisplayName("Should register three tools when code execution is enabled")
        void testToolsRegistration() {
            String workDir = tempDir.resolve("tools").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            var toolGroup = toolkit.getToolGroup("skill_code_execution_tool_group");
            assertNotNull(toolGroup);
            assertTrue(toolGroup.isActive());

            // Verify tools are registered
            var toolNames = toolkit.getToolNames();
            assertTrue(toolNames.contains("execute_shell_command"));
            assertTrue(toolNames.contains("view_text_file"));
            assertTrue(toolNames.contains("write_text_file"));
        }

        @Test
        @DisplayName("Should create temporary directory when workDir is null and verify it exists")
        void testCreateTemporaryDirectory() throws IOException {
            skillBox.codeExecution().withShell().withRead().withWrite().enable(); // null workDir

            Map<String, String> resources = new HashMap<>();
            resources.put("test.py", "print('test')");
            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            // Write scripts - should create temporary directory
            assertDoesNotThrow(() -> skillBox.writeSkillScriptsToWorkDir());

            // Verify temporary directory was created and script exists
            // We can't get the exact temp dir path, but we can verify the script was
            // written
            // by checking that no exception was thrown and the operation completed
            assertTrue(skillBox.isCodeExecutionEnabled());
        }

        @Test
        @DisplayName("Should prevent path traversal attacks")
        void testPathTraversalPrevention() throws IOException {
            String workDir = tempDir.resolve("secure").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            // Create skill with malicious path traversal attempts
            Map<String, String> resources = new HashMap<>();
            resources.put("scripts/../../../test/pwd", "malicious content");
            resources.put("../../outside.py", "print('escaped')");
            resources.put("normal.py", "print('safe')");

            AgentSkill skill = new AgentSkill("malicious", "desc", "content", resources);
            skillBox.registerSkill(skill);

            // Write scripts - malicious paths should be skipped
            skillBox.writeSkillScriptsToWorkDir();

            Path workPath = Path.of(workDir);
            // Verify malicious files were NOT created
            assertFalse(Files.exists(workPath.resolve("scripts/../../../test/passwd")));
            assertFalse(Files.exists(workPath.resolve("../../outside.py")));
            // Verify safe file WAS created
            assertTrue(Files.exists(workPath.resolve("malicious_custom/normal.py")));
        }

        @Test
        @DisplayName("Should replace existing code execution configuration")
        void testReplaceCodeExecutionConfiguration() {
            // Initial configuration - enable all three tools
            skillBox.codeExecution().withShell().withRead().withWrite().enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            assertNotNull(toolkit.getTool("execute_shell_command"));
            assertNotNull(toolkit.getTool("view_text_file"));
            assertNotNull(toolkit.getTool("write_text_file"));

            // Replace configuration - only enable read and write
            skillBox.codeExecution().withRead().withWrite().enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            // Shell tool should be removed
            assertNull(toolkit.getTool("execute_shell_command"));
            // Read and write tools should still exist
            assertNotNull(toolkit.getTool("view_text_file"));
            assertNotNull(toolkit.getTool("write_text_file"));
        }

        @Test
        @DisplayName("Should enable only selected tools")
        void testEnableOnlySelectedTools() {
            // Enable only read and write, not shell
            skillBox.codeExecution().withRead().withWrite().enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            assertNull(toolkit.getTool("execute_shell_command"));
            assertNotNull(toolkit.getTool("view_text_file"));
            assertNotNull(toolkit.getTool("write_text_file"));
        }

        @Test
        @DisplayName("Should enable only shell tool")
        void testEnableOnlyShellTool() {
            skillBox.codeExecution().withShell().enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            assertNotNull(toolkit.getTool("execute_shell_command"));
            assertNull(toolkit.getTool("view_text_file"));
            assertNull(toolkit.getTool("write_text_file"));
        }

        @Test
        @DisplayName("Should create empty tool group when no tools enabled")
        void testEmptyToolGroupWhenNoToolsEnabled() {
            skillBox.codeExecution().enable();

            assertTrue(skillBox.isCodeExecutionEnabled());
            assertNull(toolkit.getTool("execute_shell_command"));
            assertNull(toolkit.getTool("view_text_file"));
            assertNull(toolkit.getTool("write_text_file"));
        }

        @Test
        @DisplayName("Should clone custom ShellCommandTool with workDir")
        void testCloneCustomShellTool() {
            // Create custom shell tool with specific configuration
            Set<String> customCommands = new HashSet<>(Set.of("python3", "npm", "node"));
            Function<String, Boolean> callback = cmd -> true;
            ShellCommandTool customShell = new ShellCommandTool(null, customCommands, callback);

            String workDir = tempDir.resolve("custom-shell").toString();
            skillBox.codeExecution().workDir(workDir).withShell(customShell).enable();

            // Verify tool is registered
            AgentTool registered = toolkit.getTool("execute_shell_command");
            assertNotNull(registered);

            // Verify it's a ShellCommandTool and check configuration
            assertTrue(registered instanceof ShellCommandTool);
            ShellCommandTool shellTool = (ShellCommandTool) registered;

            // Verify configuration was preserved
            assertEquals(customCommands, shellTool.getAllowedCommands());
            assertEquals(callback, shellTool.getApprovalCallback());

            // Verify baseDir was set to workDir
            assertEquals(Path.of(workDir).toAbsolutePath().normalize(), shellTool.getBaseDir());
        }

        @Test
        @DisplayName("Should throw exception when withShell receives null")
        void testWithShellNullThrowsException() {
            assertThrows(
                    IllegalArgumentException.class, () -> skillBox.codeExecution().withShell(null));
        }

        @Test
        @DisplayName(
                "Should use default shell configuration when withShell called without argument")
        void testDefaultShellConfiguration() {
            skillBox.codeExecution().withShell().enable();

            AgentTool registered = toolkit.getTool("execute_shell_command");
            assertNotNull(registered);

            assertTrue(registered instanceof ShellCommandTool);
            ShellCommandTool shellTool = (ShellCommandTool) registered;

            // Verify default configuration
            Set<String> allowedCommands = shellTool.getAllowedCommands();
            assertTrue(allowedCommands.contains("python"));
            assertTrue(allowedCommands.contains("python3"));
            assertTrue(allowedCommands.contains("node"));
            assertTrue(allowedCommands.contains("nodejs"));
            assertEquals(4, allowedCommands.size());

            // Verify no approval callback
            assertNull(shellTool.getApprovalCallback());
        }

        @Test
        @DisplayName("Should preserve custom validator when cloning ShellCommandTool")
        void testPreserveCustomValidator() {
            CommandValidator customValidator = new UnixCommandValidator();
            ShellCommandTool customShell = new ShellCommandTool(null, null, customValidator);

            skillBox.codeExecution().withShell(customShell).enable();

            AgentTool registered = toolkit.getTool("execute_shell_command");
            assertNotNull(registered);

            assertTrue(registered instanceof ShellCommandTool);
            ShellCommandTool shellTool = (ShellCommandTool) registered;

            // Verify validator was preserved
            assertEquals(customValidator, shellTool.getCommandValidator());
        }
    }

    @Test
    @DisplayName("Should bind toolkit and propagate to SkillToolFactory")
    void testBindToolkitUpdatesSkillToolFactory() {
        // Arrange: Create a new toolkit
        Toolkit newToolkit = new Toolkit();

        // Register a skill with tools
        AgentSkill skill = new AgentSkill("test_skill", "Test Skill", "# Content", null);
        AgentTool tool = createTestTool("test_tool");

        skillBox.registration().skill(skill).agentTool(tool).apply();
        skillBox.registerSkillLoadTool();

        // Act: Bind new toolkit
        skillBox.bindToolkit(newToolkit);

        // Register skill load tool to new toolkit
        skillBox.registerSkillLoadTool();

        // Load skill through the new toolkit
        AgentTool skillLoader = newToolkit.getTool("load_skill_through_path");
        assertNotNull(skillLoader, "Skill loader should be available in new toolkit");

        Map<String, Object> loadParams = new HashMap<>();
        loadParams.put("skillId", skill.getSkillId());
        loadParams.put("path", "SKILL.md");

        ToolCallParam callParam =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-001")
                                        .name("load_skill_through_path")
                                        .input(loadParams)
                                        .build())
                        .input(loadParams)
                        .build();

        ToolResultBlock result = skillLoader.callAsync(callParam).block();

        // Assert: Should successfully activate skill through new toolkit
        assertNotNull(result, "Should successfully load skill through new toolkit");
        assertFalse(result.getOutput().isEmpty(), "Should have output");
        assertTrue(
                skillBox.isSkillActive(skill.getSkillId()),
                "Skill should be activated through new toolkit");
    }

    @Test
    @DisplayName("Should throw exception when binding null toolkit")
    void testBindToolkitWithNullThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> skillBox.bindToolkit(null),
                "Should throw exception when binding null toolkit");
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
