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

import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ExtendedModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.CommandValidator;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkillBox implements StateModule {
    private static final Logger logger = LoggerFactory.getLogger(SkillBox.class);

    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final AgentSkillPromptProvider skillPromptProvider;
    private final SkillToolFactory skillToolFactory;
    private Toolkit toolkit;
    private Path codeExecutionWorkDir;

    public SkillBox() {
        this(null);
    }

    public SkillBox(Toolkit toolkit) {
        this.skillPromptProvider = new AgentSkillPromptProvider(skillRegistry);
        this.skillToolFactory = new SkillToolFactory(skillRegistry, toolkit);
        this.toolkit = toolkit;
    }

    /**
     * Gets the skill system prompt for registered skills.
     *
     * <p>This prompt provides information about available skills that the agent
     * can dynamically load and use during execution.
     *
     * @return The skill system prompt, or empty string if no skills exist
     */
    public String getSkillPrompt() {
        return skillPromptProvider.getSkillSystemPrompt();
    }

    /**
     * Create a fluent builder for registering skills with optional configuration.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Register skill
     * skillBox.registration()
     *     .skill(skill)
     *     .apply();
     *
     * // Register skill with tool
     * skillBox.registration()
     *     .skill(skill) // same reference skill will not be registered again
     *     .tool(toolObject)
     *     .apply();
     * }</pre>
     *
     * @return A new ToolRegistration builder
     */
    public SkillRegistration registration() {
        return new SkillRegistration(this);
    }

    /**
     * Binds a toolkit to the skill box.
     *
     * <p>
     * This method binds the toolkit to both the skill box and its internal skill
     * tool factory.
     * Since ReActAgent uses a deep copy of the Toolkit, rebinding is necessary to
     * ensure the
     * skill tool factory references the correct toolkit instance.
     *
     * @param toolkit The toolkit to bind to the skill box
     * @throws IllegalArgumentException if the toolkit is null
     */
    public void bindToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }
        this.toolkit = toolkit;
        // ReActAgent uses a deep copy of Toolkit, so we need to rebind it here
        this.skillToolFactory.bindToolkit(toolkit);
    }

    /**
     * Synchronize tool group states based on skill activation status with a specific toolkit.
     *
     * <p>Updates the toolkit's tool groups to reflect the current activation state of skills.
     * Active skills will have their tool groups enabled, inactive skills will have their
     * tool groups disabled.
     */
    public void syncToolGroupStates() {
        if (toolkit == null) {
            return;
        }
        List<String> inactiveSkillToolGroups = new ArrayList<>();
        List<String> activeSkillToolGroups = new ArrayList<>();

        // Dynamically update active/inactive tool groups based on skills' states
        for (RegisteredSkill registeredSkill : skillRegistry.getAllRegisteredSkills().values()) {
            if (toolkit.getToolGroup(registeredSkill.getToolsGroupName()) == null) {
                continue; // Skip uncreated skill tools
            }
            if (!registeredSkill.isActive()) {
                inactiveSkillToolGroups.add(registeredSkill.getToolsGroupName());
                continue; // Skip inactive skill's tools, its tools won't be included
            }
            activeSkillToolGroups.add(registeredSkill.getToolsGroupName());
        }
        toolkit.updateToolGroups(inactiveSkillToolGroups, false);
        toolkit.updateToolGroups(activeSkillToolGroups, true);
        logger.debug(
                "Active Skill Tool Groups updated {}, inactive Skill Tool Groups updated {}",
                activeSkillToolGroups,
                inactiveSkillToolGroups);
    }

    /**
     * Where the skill is active. If a skill is active, this means skill is being using by LLM.
     * LLM use load tool activate the skill.
     * @param skillId
     * @return true if the skill is active
     */
    public boolean isSkillActive(String skillId) {
        RegisteredSkill registeredSkill = skillRegistry.getRegisteredSkill(skillId);
        if (registeredSkill == null) {
            return false;
        }
        return registeredSkill.isActive();
    }

    // ==================== Skill Management ====================

    /**
     * Registers an agent skill.
     *
     * <p>Skills can be dynamically loaded by agents using skill access tools.
     * When a skill is loaded, its associated tools become available to the agent.
     *
     * <p><b>Version Management:</b>
     * <ul>
     *   <li>First registration: Creates initial version of the skill</li>
     *   <li>Subsequent registrations with same skill object (by reference): No new version created</li>
     *   <li>Registrations with different skill object: Creates new version (snapshot)</li>
     * </ul>
     *
     * <p><b>Usage example:</b>
     * <pre>{@code
     * AgentSkill mySkill = new AgentSkill("my_skill", "Description", "Content", null);
     *
     * skillBox.registerSkill(mySkill);
     * skillBox.registerSkill(my_skill); // do nothing
     * }</pre>
     *
     * @param skill The agent skill to register
     * @throws IllegalArgumentException if skill is null
     */
    public void registerSkill(AgentSkill skill) {
        if (skill == null) {
            throw new IllegalArgumentException("AgentSkill cannot be null");
        }

        String skillId = skill.getSkillId();

        // Create registered wrapper
        RegisteredSkill registered = new RegisteredSkill(skillId);

        // Register in skillRegistry
        skillRegistry.registerSkill(skillId, skill, registered);

        logger.info("Registered skill '{}'", skillId);
    }

    /**
     * Gets all skill IDs.
     * @return All skill IDs
     */
    public Set<String> getAllSkillIds() {
        return skillRegistry.getSkillIds();
    }

    /**
     * Gets a skill by ID (latest version).
     *
     * @param skillId The skill ID
     * @return The skill instance, or null if not found
     * @throws IllegalArgumentException if skillId is null
     */
    public AgentSkill getSkill(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.getSkill(skillId);
    }

    /**
     * Removes a skill completely.
     *
     * @param skillId The skill ID
     * @throws IllegalArgumentException if skillId is null
     */
    public void removeSkill(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        skillRegistry.removeSkill(skillId);
        logger.info("Removed skill '{}'", skillId);
    }

    /**
     * Checks if a skill exists.
     *
     * @param skillId The skill ID
     * @return true if the skill exists, false otherwise
     * @throws IllegalArgumentException if skillId is null
     */
    public boolean exists(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.exists(skillId);
    }

    /**
     * Deactivates all skills.
     *
     * <p>This method sets all registered skills to inactive state, which means their associated
     * tool groups will not be available to the agent until the skills are accessed again
     * via skill access tools.
     *
     * <p>This is typically called at the start of each agent call to ensure a clean state.
     */
    public void deactivateAllSkills() {
        skillRegistry.setAllSkillsActive(false);
        logger.debug("Deactivated all skills");
    }

    /**
     * Fluent builder for registering skills with optional configuration.
     *
     * <p>This builder provides a clear, type-safe way to register skills with various options
     * without method proliferation.
     */
    public static class SkillRegistration {
        private final SkillBox skillBox;
        private Toolkit toolkit;
        private AgentSkill skill;
        private Object toolObject;
        private AgentTool agentTool;
        private McpClientWrapper mcpClientWrapper;
        private SubAgentProvider<?> subAgentProvider;
        private SubAgentConfig subAgentConfig;
        private Map<String, Map<String, Object>> presetParameters;
        private ExtendedModel extendedModel;
        private List<String> enableTools;
        private List<String> disableTools;

        public SkillRegistration(SkillBox skillBox) {
            this.skillBox = skillBox;
        }

        /**
         * Set the skill to register.
         *
         * @param skill The skill to register
         * @return This builder for chaining
         */
        public SkillRegistration skill(AgentSkill skill) {
            this.skill = skill;
            return this;
        }

        public SkillRegistration toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Set the tool object to register (scans for @Tool methods).
         *
         * @param toolObject Object containing @Tool annotated methods
         * @return This builder for chaining
         */
        public SkillRegistration tool(Object toolObject) {
            this.toolObject = toolObject;
            return this;
        }

        /**
         * Set the AgentTool instance to register.
         *
         * @param agentTool The AgentTool instance
         * @return This builder for chaining
         */
        public SkillRegistration agentTool(AgentTool agentTool) {
            this.agentTool = agentTool;
            return this;
        }

        /**
         * Set the MCP client to register.
         *
         * @param mcpClientWrapper The MCP client wrapper
         * @return This builder for chaining
         */
        public SkillRegistration mcpClient(McpClientWrapper mcpClientWrapper) {
            this.mcpClientWrapper = mcpClientWrapper;
            return this;
        }

        /**
         * Register a sub-agent as a tool with default configuration.
         *
         * <p>The tool name and description are derived from the agent's properties. Uses a single
         * "task" string parameter by default.
         *
         * <p>Example:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(() -> ReActAgent.builder()
         *         .name("ResearchAgent")
         *         .model(model)
         *         .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each invocation)
         * @return This builder for chaining
         */
        public SkillRegistration subAgent(SubAgentProvider<?> provider) {
            return subAgent(provider, null);
        }

        /**
         * Register a sub-agent as a tool with custom configuration.
         *
         * <p>Sub-agents support multi-turn conversation with session-based state management. The
         * tool exposes two parameters: {@code message} (required) and {@code session_id} (optional,
         * for continuing existing conversations).
         *
         * <p>Example with custom tool name and description:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Expert").model(model).build(),
         *         SubAgentConfig.builder()
         *             .toolName("ask_expert")
         *             .description("Ask the domain expert a question")
         *             .build())
         *     .apply();
         * }</pre>
         *
         * <p>Example with persistent session for cross-process conversations:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Assistant").model(model).build(),
         *         SubAgentConfig.builder()
         *             .session(new JsonSession(Path.of("sessions")))
         *             .forwardEvents(true)
         *             .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each session)
         * @param config Configuration for the sub-agent tool, or null to use defaults (tool name
         *     derived from agent name, InMemorySession for state, events forwarded)
         * @return This builder for chaining
         * @see SubAgentConfig
         * @see SubAgentConfig#defaults()
         */
        public SkillRegistration subAgent(SubAgentProvider<?> provider, SubAgentConfig config) {
            if (this.toolObject != null
                    || this.agentTool != null
                    || this.mcpClientWrapper != null) {
                throw new IllegalStateException(
                        "Cannot set multiple registration types. Use only one of: tool(),"
                                + " agentTool(), mcpClient(), or subAgent().");
            }
            this.subAgentProvider = provider;
            this.subAgentConfig = config;
            return this;
        }

        /**
         * Set the list of tools to enable from the MCP client.
         *
         * <p>Only applicable when using mcpClient(). If not specified, all tools are enabled.
         *
         * @param enableTools List of tool names to enable
         * @return This builder for chaining
         */
        public SkillRegistration enableTools(List<String> enableTools) {
            this.enableTools = enableTools;
            return this;
        }

        /**
         * Set the list of tools to disable from the MCP client.
         *
         * <p>Only applicable when using mcpClient().
         *
         * @param disableTools List of tool names to disable
         * @return This builder for chaining
         */
        public SkillRegistration disableTools(List<String> disableTools) {
            this.disableTools = disableTools;
            return this;
        }

        /**
         * Set preset parameters that will be automatically injected during tool execution.
         *
         * <p>These parameters are not exposed in the JSON schema.
         *
         * <p>The map should have tool names as keys and parameter maps as values:
         * <pre>{@code
         * Map.of(
         *     "toolName1", Map.of("param1", "value1", "param2", "value2"),
         *     "toolName2", Map.of("param1", "value3")
         * )
         * }</pre>
         *
         * @param presetParameters Map from tool name to its preset parameters
         * @return This builder for chaining
         */
        public SkillRegistration presetParameters(
                Map<String, Map<String, Object>> presetParameters) {
            this.presetParameters = presetParameters;
            return this;
        }

        /**
         * Set the extended model for dynamic schema extension.
         *
         * @param extendedModel The extended model
         * @return This builder for chaining
         */
        public SkillRegistration extendedModel(ExtendedModel extendedModel) {
            this.extendedModel = extendedModel;
            return this;
        }

        /**
         * Apply the registration with all configured options.
         *
         * @throws IllegalStateException if none of skill() was set, or toolkit() is required but not set
         */
        public void apply() {
            if (skill == null) {
                throw new IllegalStateException("Must call skill() before apply()");
            }
            skillBox.registerSkill(skill);

            if (toolObject != null
                    || agentTool != null
                    || mcpClientWrapper != null
                    || subAgentProvider != null) {
                if (toolkit == null && (toolkit = skillBox.toolkit) == null) {
                    throw new IllegalStateException(
                            "Must bind toolkit or call toolkit() before apply()");
                }
                String skillToolGroup = skill.getSkillId() + "_skill_tools";
                if (toolkit.getToolGroup(skillToolGroup) == null) {
                    toolkit.createToolGroup(skillToolGroup, skillToolGroup, false);
                }
                toolkit.registration()
                        .group(skillToolGroup)
                        .presetParameters(presetParameters)
                        .extendedModel(extendedModel)
                        .enableTools(enableTools)
                        .disableTools(disableTools)
                        .agentTool(agentTool)
                        .tool(toolObject)
                        .mcpClient(mcpClientWrapper)
                        .subAgent(subAgentProvider, subAgentConfig)
                        .apply();
            }
        }
    }

    // ==================== Skill Build-In Tools ====================

    /**
     * Registers skill access tools to the provided toolkit.
     *
     * <p>This method registers the following tool:
     * <ul>
     *   <li>load_skill_through_path - Load skill resources or SKILL.md content. When a resource
     *       is not found, it automatically returns a list of available resources with SKILL.md
     *       as the first item.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if toolkit is null
     */
    public void registerSkillLoadTool() {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }

        if (toolkit.getToolGroup("skill-build-in-tools") == null) {
            toolkit.createToolGroup(
                    "skill-build-in-tools",
                    "skill build-in tools, could contain(load_skill_through_path)");
        }

        toolkit.registration()
                .agentTool(skillToolFactory.createSkillAccessToolAgentTool())
                .group("skill-build-in-tools")
                .apply();

        logger.info("Registered skill load tools to toolkit");
    }

    // ==================== Code Execution ====================

    /**
     * Create a fluent builder for configuring code execution with custom options.
     *
     * <p>This is the recommended way to enable code execution capabilities for skills.
     * The builder allows selective enabling of tools and customization of ShellCommandTool.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Simple - enable all tools with default configuration
     * skillBox.codeExecution()
     *     .withShell()
     *     .withRead()
     *     .withWrite()
     *     .enable();
     *
     * // Custom shell tool with approval callback
     * ShellCommandTool customShell = new ShellCommandTool(
     *     null,  // baseDir will be overridden
     *     Set.of("python3", "node", "npm"),
     *     command -> askUserApproval(command)
     * );
     *
     * skillBox.codeExecution()
     *     .workDir("/path/to/workdir")
     *     .withShell(customShell)  // Clone with workDir
     *     .withRead()
     *     .withWrite()
     *     .enable();
     *
     * // Only enable read and write tools
     * skillBox.codeExecution()
     *     .withRead()
     *     .withWrite()
     *     .enable();
     * }</pre>
     *
     * @return A new CodeExecutionBuilder for configuration
     */
    public CodeExecutionBuilder codeExecution() {
        return new CodeExecutionBuilder(this);
    }

    /**
     * Checks if code execution is enabled.
     *
     * @return true if code execution is enabled, false otherwise
     */
    public boolean isCodeExecutionEnabled() {
        return toolkit != null && toolkit.getToolGroup("skill_code_execution_tool_group") != null;
    }

    /**
     * Gets the working directory for code execution.
     *
     * @return The working directory path, or null if using temporary directory
     */
    public Path getCodeExecutionWorkDir() {
        return codeExecutionWorkDir;
    }

    /**
     * Ensures the working directory exists, creating it if necessary.
     *
     * @return The working directory path
     * @throws RuntimeException if failed to create the directory
     */
    private Path ensureWorkDirExists() {
        Path workDir;

        if (codeExecutionWorkDir == null) {
            // Create temporary directory
            try {
                workDir = Files.createTempDirectory("agentscope-code-execution-");

                // Register shutdown hook to clean up temporary directory
                Runtime.getRuntime()
                        .addShutdownHook(
                                new Thread(
                                        () -> {
                                            try {
                                                deleteTempDirectory(workDir);
                                                logger.info(
                                                        "Cleaned up temporary working directory:"
                                                                + " {}",
                                                        workDir);
                                            } catch (IOException e) {
                                                logger.warn(
                                                        "Failed to clean up temporary directory:"
                                                                + " {}",
                                                        e.getMessage());
                                            }
                                        }));

                logger.info("Created temporary working directory: {}", workDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temporary working directory", e);
            }
        } else {
            workDir = codeExecutionWorkDir;
            // Create directory if it doesn't exist
            if (!Files.exists(workDir)) {
                try {
                    Files.createDirectories(workDir);
                    logger.info("Created working directory: {}", workDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create working directory", e);
                }
            }
        }

        return workDir;
    }

    /**
     * Deletes the temporary working directory if it was created.
     *
     * <p>
     * This method only deletes directories that were created as temporary
     * directories
     * by this SkillBox instance. User-specified directories are never deleted.
     *
     * @throws IOException if deletion fails
     */
    private void deleteTempDirectory(Path temporaryWorkDir) throws IOException {
        if (temporaryWorkDir != null && Files.exists(temporaryWorkDir)) {
            Files.walk(temporaryWorkDir)
                    .sorted(
                            (a, b) ->
                                    -a.compareTo(
                                            b)) // Reverse order to delete files before directories
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    logger.warn("Failed to delete: {}", path);
                                }
                            });
        }
    }

    /**
     * Writes all skill scripts to the code execution working directory.
     *
     * <p>This method iterates through all registered skills and writes their script
     * resources to the working directory. Scripts are organized by skill ID:
     * <ul>
     *   <li>Scripts are written to workDir/skillId/relativePath</li>
     *   <li>Scripts are identified by being in "scripts/" directory OR having script extension (.py, .js, .sh)</li>
     * </ul>
     *
     * <p>If a script file already exists, it will be overwritten.
     *
     * @throws IllegalStateException if code execution is not enabled
     */
    public void writeSkillScriptsToWorkDir() {
        if (!isCodeExecutionEnabled()) {
            throw new IllegalStateException("Code execution is not enabled");
        }

        Path workDir = ensureWorkDirExists();
        int scriptCount = 0;

        for (String skillId : getAllSkillIds()) {
            AgentSkill skill = getSkill(skillId);
            Map<String, String> scripts = skill.getScriptResources();

            if (scripts.isEmpty()) {
                continue;
            }

            // Create skill-specific directory
            Path skillDir = workDir.resolve(skillId);

            for (Map.Entry<String, String> entry : scripts.entrySet()) {
                String relativePath = entry.getKey();
                String content = entry.getValue();
                Path targetPath = skillDir.resolve(relativePath).normalize();

                // Security check: Prevent path traversal attacks
                if (!targetPath.startsWith(skillDir)) {
                    logger.warn(
                            "Skipping script with invalid path (path traversal attempt): {}",
                            relativePath);
                    continue;
                }

                try {
                    // Create parent directories if they don't exist
                    if (targetPath.getParent() != null) {
                        Files.createDirectories(targetPath.getParent());
                    }
                    Files.writeString(targetPath, content, StandardCharsets.UTF_8);
                    logger.debug("Wrote script: {}", targetPath);
                    scriptCount++;
                } catch (IOException e) {
                    logger.error("Failed to write script {}: {}", relativePath, e.getMessage());
                }
            }
        }
        logger.info("Wrote {} skill scripts to workDir: {}", scriptCount, workDir);
    }

    // ==================== Code Execution Builder ====================

    /**
     * Fluent builder for configuring code execution with custom options.
     *
     * <p>This builder provides a flexible way to enable code execution capabilities
     * with selective tool enabling and custom ShellCommandTool configuration.
     *
     * <p>Key features:
     * <ul>
     *   <li>Selective tool enabling: choose which tools (shell/read/write) to enable</li>
     *   <li>Custom ShellCommandTool: provide your own tool with custom security policies</li>
     *   <li>WorkDir enforcement: all tools use the same working directory</li>
     *   <li>Tool cloning: custom ShellCommandTool is cloned with workDir override</li>
     * </ul>
     */
    public static class CodeExecutionBuilder {
        private final SkillBox skillBox;
        private String workDir;
        private ShellCommandTool customShellTool;
        private boolean withShellCalled = false;
        private boolean enableRead = false;
        private boolean enableWrite = false;

        CodeExecutionBuilder(SkillBox skillBox) {
            this.skillBox = skillBox;
        }

        /**
         * Set the working directory for code execution.
         *
         * <p>All code execution tools (shell, read, write) will use this directory.
         * If not set, a temporary directory will be created when scripts are written.
         *
         * @param workDir The working directory path (null or empty for temporary directory)
         * @return This builder for chaining
         */
        public CodeExecutionBuilder workDir(String workDir) {
            this.workDir = workDir;
            return this;
        }

        /**
         * Enable shell command execution with default configuration.
         *
         * <p>Default configuration:
         * <ul>
         *   <li>Allowed commands: python, python3, node, nodejs</li>
         *   <li>No approval callback</li>
         *   <li>Platform-specific validator (Unix or Windows)</li>
         * </ul>
         *
         * @return This builder for chaining
         */
        public CodeExecutionBuilder withShell() {
            this.withShellCalled = true;
            this.customShellTool = null;
            return this;
        }

        /**
         * Enable shell command execution with a custom ShellCommandTool.
         *
         * <p>The provided tool will be cloned with the following behavior:
         * <ul>
         *   <li>allowedCommands: copied from the source tool</li>
         *   <li>approvalCallback: copied from the source tool</li>
         *   <li>commandValidator: copied from the source tool</li>
         *   <li>baseDir: OVERRIDDEN with the builder's workDir</li>
         * </ul>
         *
         * <p>This ensures all code execution tools use the same working directory
         * while preserving your custom security policies.
         *
         * @param shellTool The custom ShellCommandTool to clone (must not be null)
         * @return This builder for chaining
         * @throws IllegalArgumentException if shellTool is null
         */
        public CodeExecutionBuilder withShell(ShellCommandTool shellTool) {
            if (shellTool == null) {
                throw new IllegalArgumentException("ShellCommandTool cannot be null");
            }
            this.withShellCalled = true;
            this.customShellTool = shellTool;
            return this;
        }

        /**
         * Enable file reading capabilities.
         *
         * <p>Registers ReadFileTool with the builder's workDir as base directory.
         *
         * @return This builder for chaining
         */
        public CodeExecutionBuilder withRead() {
            this.enableRead = true;
            return this;
        }

        /**
         * Enable file writing capabilities.
         *
         * <p>Registers WriteFileTool with the builder's workDir as base directory.
         *
         * @return This builder for chaining
         */
        public CodeExecutionBuilder withWrite() {
            this.enableWrite = true;
            return this;
        }

        /**
         * Apply the configuration and enable code execution.
         *
         * <p>This method:
         * <ul>
         *   <li>Validates toolkit is bound</li>
         *   <li>Removes existing code execution configuration if present</li>
         *   <li>Creates the code execution tool group</li>
         *   <li>Registers selected tools (shell, read, write)</li>
         * </ul>
         *
         * @throws IllegalStateException if toolkit is not bound
         */
        public void enable() {
            if (skillBox.toolkit == null) {
                throw new IllegalStateException("Must bind toolkit before enabling code execution");
            }

            // Handle replacement: remove existing tool group if present
            if (skillBox.isCodeExecutionEnabled()) {
                skillBox.toolkit.removeToolGroups(List.of("skill_code_execution_tool_group"));
                logger.info("Replacing existing code execution configuration");
            }

            // Set workDir
            if (workDir == null || workDir.isEmpty()) {
                skillBox.codeExecutionWorkDir = null;
            } else {
                skillBox.codeExecutionWorkDir = Paths.get(workDir).toAbsolutePath().normalize();
            }

            // Create tool group
            skillBox.toolkit.createToolGroup(
                    "skill_code_execution_tool_group", "Code execution tools for skills", true);

            String workDirStr =
                    skillBox.codeExecutionWorkDir != null
                            ? skillBox.codeExecutionWorkDir.toString()
                            : null;

            boolean shellEnabled = false;

            // Shell Tool - check if withShell() was called
            if (withShellCalled) {
                ShellCommandTool shellTool;
                if (customShellTool != null) {
                    // Clone custom tool with workDir override
                    shellTool = cloneShellToolWithWorkDir(customShellTool, workDirStr);
                } else {
                    // Create default shell tool
                    shellTool =
                            new ShellCommandTool(
                                    workDirStr,
                                    Set.of("python", "python3", "node", "nodejs"),
                                    null);
                }
                skillBox.toolkit
                        .registration()
                        .agentTool(shellTool)
                        .group("skill_code_execution_tool_group")
                        .apply();
                shellEnabled = true;
            }

            // Read Tool
            if (enableRead) {
                ReadFileTool readTool = new ReadFileTool(workDirStr);
                skillBox.toolkit
                        .registration()
                        .tool(readTool)
                        .group("skill_code_execution_tool_group")
                        .apply();
            }

            // Write Tool
            if (enableWrite) {
                WriteFileTool writeTool = new WriteFileTool(workDirStr);
                skillBox.toolkit
                        .registration()
                        .tool(writeTool)
                        .group("skill_code_execution_tool_group")
                        .apply();
            }

            logger.info(
                    "Code execution enabled with workDir: {}, tools: [shell={}, read={}, write={}]",
                    skillBox.codeExecutionWorkDir != null
                            ? skillBox.codeExecutionWorkDir
                            : "temporary",
                    shellEnabled,
                    enableRead,
                    enableWrite);
        }

        /**
         * Clone a ShellCommandTool with a new base directory.
         *
         * <p>This ensures all code execution tools use the same working directory
         * while preserving the custom security policies from the source tool.
         *
         * @param source The source ShellCommandTool to clone
         * @param workDir The new working directory (can be null for temporary)
         * @return A new ShellCommandTool with the same configuration but different baseDir
         */
        private ShellCommandTool cloneShellToolWithWorkDir(
                ShellCommandTool source, String workDir) {
            // Get configuration from source tool
            Set<String> allowedCommands = source.getAllowedCommands();
            Function<String, Boolean> approvalCallback = source.getApprovalCallback();
            CommandValidator validator = source.getCommandValidator();

            // Create new instance with workDir override
            return new ShellCommandTool(workDir, allowedCommands, approvalCallback, validator);
        }
    }
}
