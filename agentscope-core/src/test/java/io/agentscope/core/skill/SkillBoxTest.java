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
import java.util.Arrays;
import java.util.Base64;
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

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
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

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
            assertEquals(
                    Path.of(customDir).toAbsolutePath().normalize(),
                    skillBox.getCodeExecutionWorkDir());
            // Directory should not be created until scripts are written
            assertFalse(Files.exists(skillBox.getCodeExecutionWorkDir()));
        }

        @Test
        @DisplayName("Should default uploadDir to workDir/skills")
        void testUploadDirDefaultsToWorkDirSkills() {
            String customDir = tempDir.resolve("default-upload").toString();

            skillBox.codeExecution().workDir(customDir).withShell().withRead().withWrite().enable();

            Path expectedUploadDir =
                    Path.of(customDir).toAbsolutePath().normalize().resolve("skills");
            assertEquals(expectedUploadDir, skillBox.getUploadDir());
            assertFalse(Files.exists(expectedUploadDir));
        }

        @Test
        @DisplayName("Should allow custom uploadDir independent from workDir")
        void testCustomUploadDir() throws IOException {
            String customWorkDir = tempDir.resolve("custom-work").toString();
            String customUploadDir = tempDir.resolve("custom-upload").toString();

            skillBox.codeExecution()
                    .workDir(customWorkDir)
                    .uploadDir(customUploadDir)
                    .withShell()
                    .withRead()
                    .withWrite()
                    .enable();

            assertEquals(
                    Path.of(customUploadDir).toAbsolutePath().normalize(), skillBox.getUploadDir());

            Map<String, String> resources = new HashMap<>();
            resources.put("scripts/main.py", "print('ok')");
            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            skillBox.uploadSkillFiles();

            Path uploadPath = Path.of(customUploadDir);
            assertTrue(Files.exists(uploadPath.resolve("skill_custom/scripts/main.py")));
        }

        @Test
        @DisplayName("Should reject conflicting filter configuration")
        void testFileFilterMutualExclusion() {
            SkillFileFilter filter = path -> true;

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            skillBox.codeExecution()
                                    .fileFilter(filter)
                                    .includeFolders(Set.of("scripts"))
                                    .enable());

            assertThrows(
                    IllegalStateException.class,
                    () ->
                            skillBox.codeExecution()
                                    .fileFilter(filter)
                                    .includeExtensions(Set.of(".json"))
                                    .enable());
        }

        @Test
        @DisplayName("Should apply include folders and extensions with OR logic")
        void testIncludeFoldersAndExtensionsOrLogic() throws IOException {
            String workDir = tempDir.resolve("filter-or").toString();
            skillBox.codeExecution()
                    .workDir(workDir)
                    .includeFolders(Set.of("scripts"))
                    .includeExtensions(Set.of("json"))
                    .withWrite()
                    .enable();

            Map<String, String> resources = new HashMap<>();
            resources.put("scripts/data.txt", "folder match");
            resources.put("config.json", "extension match");
            resources.put("main.py", "no match");

            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            skillBox.uploadSkillFiles();

            Path uploadPath = Path.of(workDir).resolve("skills/skill_custom");
            assertTrue(Files.exists(uploadPath.resolve("scripts/data.txt")));
            assertTrue(Files.exists(uploadPath.resolve("config.json")));
            assertFalse(Files.exists(uploadPath.resolve("main.py")));
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

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
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
        @DisplayName("Should upload skill files to upload directory organized by skill ID")
        void testUploadSkillFilesToUploadDir() throws IOException {
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

            // Upload files - directory should be created now
            skillBox.uploadSkillFiles();

            // Verify directory created
            Path uploadPath = Path.of(workDir).resolve("skills");
            assertTrue(Files.exists(uploadPath));

            // Verify files are uploaded to workDir/skills/skillId/
            assertTrue(Files.exists(uploadPath.resolve("skill1_custom/scripts/process.py")));
            assertTrue(Files.exists(uploadPath.resolve("skill1_custom/scripts/analyze.js")));
            assertTrue(Files.exists(uploadPath.resolve("skill2_custom/main.py")));
            assertTrue(Files.exists(uploadPath.resolve("skill2_custom/util.sh")));
            assertFalse(Files.exists(uploadPath.resolve("skill1_custom/config.json"))); // Filtered

            // Verify content
            String pythonContent =
                    Files.readString(uploadPath.resolve("skill1_custom/scripts/process.py"));
            assertEquals("print('hello from python')", pythonContent);
        }

        @Test
        @DisplayName("Should decode base64 resources on upload")
        void testUploadBase64ResourceDecoding() throws IOException {
            String workDir = tempDir.resolve("base64-upload").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            byte[] original = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
            String encoded = "base64:" + Base64.getEncoder().encodeToString(original);

            Map<String, String> resources = new HashMap<>();
            resources.put("scripts/binary.bin", encoded);
            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            skillBox.uploadSkillFiles();

            Path uploadPath = Path.of(workDir).resolve("skills/skill_custom/scripts/binary.bin");
            assertTrue(Files.exists(uploadPath));
            byte[] written = Files.readAllBytes(uploadPath);
            assertEquals(original.length, written.length);
            assertTrue(Arrays.equals(original, written));
        }

        @Test
        @DisplayName("Should upload even without enabling code execution")
        void testUploadWithoutEnabling() {
            AgentSkill skill = new AgentSkill("skill", "desc", "content", null);
            skillBox.registerSkill(skill);

            assertDoesNotThrow(() -> skillBox.uploadSkillFiles());
        }

        @Test
        @DisplayName("Should handle empty uploads and not create skill directory")
        void testUploadEmptyFiles() throws IOException {
            String workDir = tempDir.resolve("empty-scripts").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            // Skill with no script resources
            Map<String, String> resources = new HashMap<>();
            resources.put("config.json", "{}");
            resources.put("data.txt", "data");

            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            // Should not throw exception
            assertDoesNotThrow(() -> skillBox.uploadSkillFiles());

            // Verify uploadDir is created but skill directory is not (no files)
            Path uploadPath = Path.of(workDir).resolve("skills");
            assertTrue(Files.exists(uploadPath));
            // No skill directory should be created since there are no files
            assertFalse(Files.exists(uploadPath.resolve("skill_custom")));
        }

        @Test
        @DisplayName("Should overwrite existing files in skill directory")
        void testOverwriteExistingFiles() throws IOException {
            String workDir = tempDir.resolve("overwrite").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            // Create initial script in skill directory
            Path scriptPath = Path.of(workDir).resolve("skills/skill_custom/test.py");
            Files.createDirectories(scriptPath.getParent());
            Files.writeString(scriptPath, "print('old content')");

            // Register skill with same script name
            Map<String, String> resources = new HashMap<>();
            resources.put("test.py", "print('new content')");
            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            skillBox.uploadSkillFiles();

            // Verify content is overwritten
            String content = Files.readString(scriptPath);
            assertEquals("print('new content')", content);
        }

        @Test
        @DisplayName("Should create nested directories for uploaded files")
        void testNestedDirectories() throws IOException {
            String workDir = tempDir.resolve("nested").toString();
            skillBox.codeExecution().workDir(workDir).withShell().withRead().withWrite().enable();

            Map<String, String> resources = new HashMap<>();
            resources.put("scripts/utils/helper.py", "def help(): pass");
            resources.put("scripts/data/loader.js", "function load() {}");

            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            skillBox.uploadSkillFiles();

            Path uploadPath = Path.of(workDir).resolve("skills");
            assertTrue(Files.exists(uploadPath.resolve("skill_custom/scripts/utils/helper.py")));
            assertTrue(Files.exists(uploadPath.resolve("skill_custom/scripts/data/loader.js")));
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
        @DisplayName("Should create temporary directory when workDir is null")
        void testCreateTemporaryDirectory() throws IOException {
            skillBox.codeExecution().withShell().withRead().withWrite().enable(); // null workDir

            Map<String, String> resources = new HashMap<>();
            resources.put("test.py", "print('test')");
            AgentSkill skill = new AgentSkill("skill", "desc", "content", resources);
            skillBox.registerSkill(skill);

            // Write scripts - should create temporary directory
            assertDoesNotThrow(() -> skillBox.uploadSkillFiles());

            // Verify temporary directory was created and script exists
            // We can't get the exact temp dir path, but we can verify the script was
            // written
            // by checking that no exception was thrown and the operation completed
            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
            assertNotNull(skillBox.getUploadDir());
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
            skillBox.uploadSkillFiles();

            Path uploadPath = Path.of(workDir).resolve("skills");
            // Verify malicious files were NOT created
            assertFalse(Files.exists(uploadPath.resolve("scripts/../../../test/passwd")));
            assertFalse(Files.exists(uploadPath.resolve("../../outside.py")));
            // Verify safe file WAS created
            assertTrue(Files.exists(uploadPath.resolve("malicious_custom/normal.py")));
        }

        @Test
        @DisplayName("Should replace existing code execution configuration")
        void testReplaceCodeExecutionConfiguration() {
            // Initial configuration - enable all three tools
            skillBox.codeExecution().withShell().withRead().withWrite().enable();

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
            assertNotNull(toolkit.getTool("execute_shell_command"));
            assertNotNull(toolkit.getTool("view_text_file"));
            assertNotNull(toolkit.getTool("write_text_file"));

            // Replace configuration - only enable read and write
            skillBox.codeExecution().withRead().withWrite().enable();

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
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

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
            assertNull(toolkit.getTool("execute_shell_command"));
            assertNotNull(toolkit.getTool("view_text_file"));
            assertNotNull(toolkit.getTool("write_text_file"));
        }

        @Test
        @DisplayName("Should enable only shell tool")
        void testEnableOnlyShellTool() {
            skillBox.codeExecution().withShell().enable();

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
            assertNotNull(toolkit.getTool("execute_shell_command"));
            assertNull(toolkit.getTool("view_text_file"));
            assertNull(toolkit.getTool("write_text_file"));
        }

        @Test
        @DisplayName("Should create empty tool group when no tools enabled")
        void testEmptyToolGroupWhenNoToolsEnabled() {
            skillBox.codeExecution().enable();

            assertNotNull(toolkit.getToolGroup("skill_code_execution_tool_group"));
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
    @DisplayName("Should allow shared tool across multiple skills")
    void testSharedToolAcrossSkillsActivation() {
        skillBox.registerSkillLoadTool();

        AgentSkill skillA = new AgentSkill("skill_a", "Skill A", "# A", null);
        AgentSkill skillB = new AgentSkill("skill_b", "Skill B", "# B", null);
        AgentTool sharedTool = createTestTool("shared_tool");

        skillBox.registration().skill(skillA).agentTool(sharedTool).apply();
        skillBox.registration().skill(skillB).agentTool(sharedTool).apply();

        String groupA = skillA.getSkillId() + "_skill_tools";
        String groupB = skillB.getSkillId() + "_skill_tools";

        assertNotNull(toolkit.getToolGroup(groupA));
        assertNotNull(toolkit.getToolGroup(groupB));
        assertFalse(toolkit.getToolGroup(groupA).isActive());
        assertFalse(toolkit.getToolGroup(groupB).isActive());

        Map<String, Object> loadInputA = Map.of("skillId", skillA.getSkillId(), "path", "SKILL.md");
        ToolUseBlock loadCallA =
                ToolUseBlock.builder()
                        .id("load-a")
                        .name("load_skill_through_path")
                        .input(loadInputA)
                        .content(
                                "{\"skillId\":\""
                                        + skillA.getSkillId()
                                        + "\",\"path\":\"SKILL.md\"}")
                        .build();
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(loadCallA).input(loadInputA).build())
                .block();

        assertTrue(toolkit.getToolGroup(groupA).isActive());
        assertFalse(toolkit.getToolGroup(groupB).isActive());

        ToolResultBlock resultWithGroupA = callSharedTool();
        assertNotNull(resultWithGroupA);
        assertFalse(isErrorResult(resultWithGroupA));

        skillBox.deactivateAllSkills();
        skillBox.syncToolGroupStates();

        ToolResultBlock resultWithNone = callSharedTool();
        assertNotNull(resultWithNone);
        assertTrue(isErrorResult(resultWithNone));

        Map<String, Object> loadInputB = Map.of("skillId", skillB.getSkillId(), "path", "SKILL.md");
        ToolUseBlock loadCallB =
                ToolUseBlock.builder()
                        .id("load-b")
                        .name("load_skill_through_path")
                        .input(loadInputB)
                        .content(
                                "{\"skillId\":\""
                                        + skillB.getSkillId()
                                        + "\",\"path\":\"SKILL.md\"}")
                        .build();
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(loadCallB).input(loadInputB).build())
                .block();

        assertTrue(toolkit.getToolGroup(groupB).isActive());

        ToolResultBlock resultWithGroupB = callSharedTool();
        assertNotNull(resultWithGroupB);
        assertFalse(isErrorResult(resultWithGroupB));

        toolkit.callTool(ToolCallParam.builder().toolUseBlock(loadCallA).input(loadInputA).build())
                .block();
        assertTrue(toolkit.getToolGroup(groupA).isActive());
        assertTrue(toolkit.getToolGroup(groupB).isActive());
        ToolResultBlock resultWithBoth = callSharedTool();
        assertNotNull(resultWithBoth);
        assertFalse(isErrorResult(resultWithBoth));
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

    private ToolResultBlock callSharedTool() {
        Map<String, Object> input = Map.of();
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-shared")
                        .name("shared_tool")
                        .input(input)
                        .content("{}")
                        .build();
        return toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).input(input).build())
                .block();
    }

    private boolean isErrorResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return false;
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .anyMatch(text -> text != null && text.startsWith("Error:"));
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
