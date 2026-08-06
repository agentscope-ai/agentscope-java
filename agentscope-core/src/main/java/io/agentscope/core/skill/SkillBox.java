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

import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ExtendedModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a collection of {@link AgentSkill} instances and exposes them as tools.
 *
 * Since 2.0.0, SkillBox is intended for internal use only; prefer skill repositories
 * ({@code AgentSkillRepository}) for defining and loading skills.
 */
public class SkillBox {
    private static final Logger logger = LoggerFactory.getLogger(SkillBox.class);
    private static final String BASE64_PREFIX = "base64:";

    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final AgentSkillPromptProvider skillPromptProvider;
    private final SkillToolFactory skillToolFactory;
    private Toolkit toolkit;
    private Path workDir;
    private Path uploadDir;
    private SkillFileFilter fileFilter;
    private boolean autoUploadSkill = true;

    private static final ConcurrentHashMap<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    public SkillBox(Toolkit toolkit) {
        this(toolkit, null);
    }

    /**
     * Creates a SkillBox with a toolkit and custom skill prompt instruction.
     *
     * @param toolkit The toolkit to bind
     * @param instruction Custom instruction header (null or blank uses default)
     */
    public SkillBox(Toolkit toolkit, String instruction) {
        this.skillPromptProvider = new AgentSkillPromptProvider(skillRegistry, instruction);
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
     * Gets the skill system prompt filtered by the given {@link SkillFilter}.
     *
     * @param filter the filter deciding which skills to include (null treated as all)
     * @return The skill system prompt, or empty string if no skills pass the filter
     */
    public String getSkillPrompt(SkillFilter filter) {
        return skillPromptProvider.getSkillSystemPrompt(filter);
    }

    /**
     * Controls whether the skill prompt exposes all metadata fields or only the core fields.
     *
     * <p>When disabled, only {@code name}, {@code description}, and {@code skill-id}
     * are included in the skill prompt.
     *
     * @param exposeAllMetadata {@code true} to expose all metadata, {@code false} to expose only
     *                          the core fields
     */
    public void setExposeAllSkillMetadata(boolean exposeAllMetadata) {
        skillPromptProvider.setExposeAllMetadata(exposeAllMetadata);
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
            // Name-convention path: skillId + "_skill_tools"
            if (toolkit.getToolGroup(registeredSkill.getToolsGroupName()) != null) {
                if (!registeredSkill.isActive()) {
                    inactiveSkillToolGroups.add(registeredSkill.getToolsGroupName());
                } else {
                    activeSkillToolGroups.add(registeredSkill.getToolsGroupName());
                }
            }

            // activateOnSkill path: scan SkillToolGroups bound to this skill's name
            AgentSkill agentSkill = skillRegistry.getSkill(registeredSkill.getSkillId());
            if (agentSkill != null) {
                List<String> boundGroups =
                        toolkit.findSkillToolGroupsByActivateOnSkill(agentSkill.getName());
                for (String group : boundGroups) {
                    if (!registeredSkill.isActive()) {
                        inactiveSkillToolGroups.add(group);
                    } else {
                        activeSkillToolGroups.add(group);
                    }
                }
            }
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
     * Sets the activation state of a specific skill.
     *
     * <p>When a skill is set to inactive, its associated tool group will be disabled
     * in the underlying toolkit, preventing the agent from accessing its tools until
     * it is activated again.
     *
     * <p>This method automatically synchronizes the state change with the bound toolkit.
     *
     * <p><b>Warning on Deactivation:</b> Setting a skill to inactive only unbinds its associated
     * tool group. It does not automatically remove the skill's context or prompt instructions
     * from the agent's memory. This is a risky operation, as the agent might still attempt to
     * invoke the inactive tool based on its retained memory context, leading to execution failures.
     * For a complete and ideal deactivation, it is recommended to implement custom hooks to unbind
     * both the tool group and its associated context from memory.
     *
     * @param skillId The ID of the skill to modify
     * @param active  true to activate the skill, false to deactivate
     * @throws IllegalArgumentException if skillId is null or the skill does not exist
     */
    public void setSkillActive(String skillId, boolean active) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }

        if (!exists(skillId)) {
            throw new IllegalArgumentException("Skill ID does not exist: " + skillId);
        }

        skillRegistry.setSkillActive(skillId, active);

        // sync ToolGroup state — name-convention path
        RegisteredSkill registeredSkill = skillRegistry.getRegisteredSkill(skillId);
        if (registeredSkill != null) {
            String toolGroupName = registeredSkill.getToolsGroupName();
            if (this.toolkit.getToolGroup(toolGroupName) != null) {
                this.toolkit.updateToolGroups(List.of(toolGroupName), active);
            }
        }

        // sync ToolGroup state — activateOnSkill path
        AgentSkill agentSkill = skillRegistry.getSkill(skillId);
        if (agentSkill != null && this.toolkit != null) {
            List<String> boundGroups =
                    this.toolkit.findSkillToolGroupsByActivateOnSkill(agentSkill.getName());
            if (!boundGroups.isEmpty()) {
                this.toolkit.updateToolGroups(boundGroups, active);
            }
        }

        logger.debug("Skill '{}' active state set to {}", skillId, active);
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
         *             .stateStore(new JsonFileAgentStateStore(Path.of("sessions")))
         *             .forwardEvents(true)
         *             .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each session)
         * @param config Configuration for the sub-agent tool, or null to use defaults (tool name
         *     derived from agent name, InMemoryAgentStateStore for state, events forwarded)
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

    /**
     * Sets whether skill files are automatically uploaded.
     *
     * @param autoUploadSkill true to automatically upload skill files
     */
    public void setAutoUploadSkill(boolean autoUploadSkill) {
        this.autoUploadSkill = autoUploadSkill;
    }

    /**
     * Checks whether skill files are automatically uploaded.
     *
     * @return true if skill files are automatically uploaded
     */
    public boolean isAutoUploadSkill() {
        return autoUploadSkill;
    }

    /**
     * Gets the working directory for code execution.
     *
     * @return The working directory path, or null if using temporary directory
     */
    public Path getCodeExecutionWorkDir() {
        return workDir;
    }

    /**
     * Sets a stable working directory for resource uploads and code execution. Callers can use
     * this to share one workDir across multiple SkillBox instances (e.g. {@code
     * DynamicSkillMiddleware} rebuilds the box every call, but the workDir lives for the agent's
     * lifetime so {@code uploadSkillFiles} no longer mints a fresh {@code agentscope-code-execution-*}
     * tempdir each round).
     *
     * @param workDir the directory under which {@code skills/<skillId>/} subtrees are written;
     *                {@code null} (the default) preserves the legacy per-instance tempdir behavior
     */
    public void setWorkDir(Path workDir) {
        this.workDir = workDir;
        // The uploadDir derives from workDir lazily in ensureUploadDirExists(); clear the
        // cached subdir so the next ensureUploadDirExists() recomputes against the new workDir.
        this.uploadDir = null;
    }

    /**
     * Exposes the skill prompt provider so callers (e.g. {@code DynamicSkillMiddleware}) can
     * toggle {@code codeExecutionEnable}, swap the instruction template, or query the resolved
     * uploadDir without re-walking the SkillBox internals.
     */
    public AgentSkillPromptProvider getSkillPromptProvider() {
        return skillPromptProvider;
    }

    /**
     * Gets the upload directory for skill files.
     *
     * @return The upload directory path, or null if not configured
     */
    public Path getUploadDir() {
        return uploadDir;
    }

    /**
     * Ensures the working directory exists, creating it if necessary.
     *
     * @return The working directory path
     * @throws RuntimeException if failed to create the directory
     */
    private Path ensureWorkDirExists() {
        if (this.workDir == null) {
            // Create temporary directory
            try {
                this.workDir = Files.createTempDirectory("agentscope-code-execution-");

                SkillFileSystemHelper.registerTempDirectoryCleanup(workDir);

                logger.info("Created temporary working directory: {}", workDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temporary working directory", e);
            }
        } else {
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

        return this.workDir;
    }

    /**
     * Ensures the upload directory exists, creating it if necessary.
     *
     * @return The upload directory path
     */
    private Path ensureUploadDirExists() {
        if (uploadDir == null) {
            Path resolvedWorkDir = ensureWorkDirExists();
            uploadDir = resolvedWorkDir.resolve("skills");
        }

        if (!Files.exists(uploadDir)) {
            try {
                Files.createDirectories(uploadDir);
                logger.info("Created upload directory: {}", uploadDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create upload directory", e);
            }
        }

        skillPromptProvider.setUploadDir(uploadDir);
        return uploadDir;
    }

    /**
     * Uploads skill files to the upload directory with the configured filter.
     *
     * <p>Upload directory resolution:
     * <ul>
     *   <li>If uploadDir is configured, use it.</li>
     *   <li>Otherwise, use workDir/skills (workDir may be a temporary directory).</li>
     * </ul>
     *
     * <p>If a file already exists, it will be overwritten.
     *
     */
    public void uploadSkillFiles() {
        Path targetDir = ensureUploadDirExists();
        SkillFileFilter filter = fileFilter != null ? fileFilter : SkillFileFilter.acceptAll();
        int fileCount = 0;

        for (String skillId : getAllSkillIds()) {
            AgentSkill skill = getSkill(skillId);

            // Skills with a populated originDir already live on disk at a known absolute path,
            // which the prompt provider exposes as <files-root>. Re-materialising them under
            // workDir would just be a redundant copy that the LLM never sees referenced.
            if (skill.getOriginDir().isPresent()) {
                continue;
            }

            Set<String> resourcePaths = skill.getResourcePaths();

            if (resourcePaths.isEmpty()) {
                continue;
            }

            Path skillDir = targetDir.resolve(skillId);

            for (String resourcePath : resourcePaths) {
                if (!filter.accept(resourcePath)) {
                    continue;
                }

                String content = skill.getResource(resourcePath);
                if (content == null) {
                    logger.warn("Resource not found: {} in skill {}", resourcePath, skillId);
                    continue;
                }

                Path targetPath = skillDir.resolve(resourcePath).normalize();

                // Security check: Prevent path traversal attacks
                if (!targetPath.startsWith(skillDir)) {
                    logger.warn("Skipping file with invalid path: {}", resourcePath);
                    continue;
                }

                try {
                    if (targetPath.getParent() != null) {
                        Files.createDirectories(targetPath.getParent());
                    }

                    Object lock =
                            FILE_LOCKS.computeIfAbsent(targetPath.toString(), k -> new Object());
                    synchronized (lock) {
                        if (content.startsWith(BASE64_PREFIX)) {
                            String encoded = content.substring(BASE64_PREFIX.length());
                            byte[] decoded = Base64.getDecoder().decode(encoded);
                            Files.write(targetPath, decoded);
                        } else {
                            Files.writeString(targetPath, content, StandardCharsets.UTF_8);
                        }
                    }

                    logger.debug("Uploaded file: {}", targetPath);
                    fileCount++;
                } catch (IOException | IllegalArgumentException e) {
                    logger.error("Failed to upload file {}: {}", resourcePath, e.getMessage());
                }
            }
        }

        logger.info("Uploaded {} skill files to: {}", fileCount, targetDir);
    }

    private static class DefaultSkillFileFilter implements SkillFileFilter {
        private final Set<String> includeFolders;
        private final Set<String> includeExtensions;

        private DefaultSkillFileFilter(Set<String> includeFolders, Set<String> includeExtensions) {
            this.includeFolders = includeFolders != null ? includeFolders : Set.of();
            this.includeExtensions = includeExtensions != null ? includeExtensions : Set.of();
        }

        @Override
        public boolean accept(String resourcePath) {
            if (resourcePath == null || resourcePath.isBlank()) {
                return false;
            }

            String normalizedPath = resourcePath.replace("\\", "/");

            if (!includeFolders.isEmpty()) {
                for (String folder : includeFolders) {
                    if (normalizedPath.startsWith(folder)) {
                        return true;
                    }
                }
            }

            if (!includeExtensions.isEmpty()) {
                for (String extension : includeExtensions) {
                    if (normalizedPath.endsWith(extension)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }
}
