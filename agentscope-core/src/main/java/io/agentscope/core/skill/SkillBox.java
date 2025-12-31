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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ExtendedModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class SkillBox implements StateModule {
    private static final Logger logger = LoggerFactory.getLogger(SkillBox.class);

    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final AgentSkillPromptProvider skillPromptProvider;
    private Toolkit toolkit;

    public SkillBox() {
        this(null);
    }

    public SkillBox(Toolkit toolkit) {
        this.skillPromptProvider = new AgentSkillPromptProvider(skillRegistry);
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
     * @param toolkit The toolkit to bind to the skill box
     * @throws IllegalArgumentException if the toolkit is null
     */
    public void bindToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }
        this.toolkit = toolkit;
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

    // ==================== Skill Access Tools ====================

    /**
     * Load the markdown content of a skill by its ID.
     *
     * <p>This will activate the skill and return its full content including
     * name, description, and implementation details.
     *
     * @param skillId The unique identifier of the skill to load
     * @return Skill markdown content with metadata
     * @throws IllegalArgumentException if skill doesn't exist
     */
    @Tool(
            name = "skill_md_load_tool",
            description =
                    "Load the markdown content of a skill by its ID. "
                            + "This will activate the skill and return its full content including "
                            + "name, description, and implementation details.")
    public Mono<ToolResultBlock> loadSkillMd(
            @ToolParam(
                            name = "skillId",
                            description = "The unique identifier of the skill to load.")
                    String skillId) {
        try {
            // Validate parameter
            if (skillId == null || skillId.trim().isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: skillId"));
            }

            AgentSkill skill = validatedActiveSkill(skillId);

            // Build response
            StringBuilder result = new StringBuilder();
            result.append("Successfully loaded skill: ").append(skillId).append("\n\n");
            result.append("Name: ").append(skill.getName()).append("\n");
            result.append("Description: ").append(skill.getDescription()).append("\n");
            result.append("Source: ").append(skill.getSource()).append("\n\n");
            result.append("Content:\n");
            result.append("---\n");
            result.append(skill.getSkillContent());
            result.append("\n---\n");

            return Mono.just(ToolResultBlock.text(result.toString()));
        } catch (Exception e) {
            logger.error("Error loading skill markdown: {}", skillId, e);
            return Mono.just(ToolResultBlock.error(e.getMessage()));
        }
    }

    /**
     * Load a specific resource file from a skill by its ID and resource path.
     *
     * <p>This will activate the skill and return the content of the requested resource.
     *
     * @param skillId The unique identifier of the skill
     * @param path The path to the resource file within the skill (e.g., 'config.json')
     * @return Resource content
     * @throws IllegalArgumentException if skill or resource doesn't exist
     */
    @Tool(
            name = "skill_resources_load_tool",
            description =
                    "Load a specific resource file from a skill by its ID and resource path. This"
                            + " will activate the skill and return the content of the requested"
                            + " resource.")
    public Mono<ToolResultBlock> loadSkillResource(
            @ToolParam(name = "skillId", description = "The unique identifier of the skill.")
                    String skillId,
            @ToolParam(
                            name = "path",
                            description =
                                    "The path to the resource file within the skill (e.g.,"
                                            + " 'config.json').")
                    String path) {
        try {
            // Validate parameters
            if (skillId == null || skillId.trim().isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: skillId"));
            }

            if (path == null || path.trim().isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: path"));
            }

            // Get resource
            Map<String, String> resources = validatedActiveSkill(skillId).getResources();
            if (resources == null || !resources.containsKey(path)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Resource not found: '%s' in skill '%s'. "
                                        + "Use get_all_resources_path_tool to see available"
                                        + " resources.",
                                path, skillId));
            }

            String resourceContent = resources.get(path);

            // Build response
            StringBuilder result = new StringBuilder();
            result.append("Successfully loaded resource from skill: ").append(skillId).append("\n");
            result.append("Resource path: ").append(path).append("\n\n");
            result.append("Content:\n");
            result.append("---\n");
            result.append(resourceContent);
            result.append("\n---\n");

            return Mono.just(ToolResultBlock.text(result.toString()));
        } catch (Exception e) {
            logger.error("Error loading skill resource: {} from {}", path, skillId, e);
            return Mono.just(ToolResultBlock.error(e.getMessage()));
        }
    }

    /**
     * Get a list of all resource file paths available in a skill.
     *
     * <p>This will activate the skill and return the paths of all its resources.
     *
     * @param skillId The unique identifier of the skill
     * @return List of resource paths formatted as a string
     * @throws IllegalArgumentException if skill doesn't exist
     */
    @Tool(
            name = "get_all_resources_path_tool",
            description =
                    "Get a list of all resource file paths available in a skill. "
                            + "This will activate the skill and return the paths of all its"
                            + " resources.")
    public Mono<ToolResultBlock> getAllResourcesPath(
            @ToolParam(name = "skillId", description = "The unique identifier of the skill.")
                    String skillId) {
        try {
            // Validate parameter
            if (skillId == null || skillId.trim().isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: skillId"));
            }

            // Get resource paths
            Map<String, String> resources = validatedActiveSkill(skillId).getResources();
            if (resources == null || resources.isEmpty()) {
                return Mono.just(ToolResultBlock.text("No resources available for this skill."));
            }

            List<String> resourcePaths = new ArrayList<>(resources.keySet());

            // Format resource paths
            StringBuilder result = new StringBuilder();
            result.append(
                    String.format(
                            "Available resource paths (%d total):\n\n", resourcePaths.size()));

            for (int i = 0; i < resourcePaths.size(); i++) {
                result.append(i + 1).append(". ").append(resourcePaths.get(i)).append("\n");
            }

            return Mono.just(ToolResultBlock.text(result.toString()));
        } catch (Exception e) {
            logger.error("Error getting resources for skill: {}", skillId, e);
            return Mono.just(ToolResultBlock.error(e.getMessage()));
        }
    }

    /**
     * validate skill is not null and can get successfully, and set skill as active.
     * @param skillId The unique identifier of the skill
     * @return The skill instance get by skill ID
     */
    private AgentSkill validatedActiveSkill(String skillId) {
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException(
                    String.format("Skill not found: '%s'. Please check the skill ID.", skillId));
        }

        // Set skill as active
        skillRegistry.setSkillActive(skillId, true);
        logger.debug("Activated skill: {}", skillId);

        // Get skill
        AgentSkill skill = skillRegistry.getSkill(skillId);
        if (skill == null) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to load skill '%s' after validation. This is an internal"
                                    + " error.",
                            skillId));
        }
        return skill;
    }
}
