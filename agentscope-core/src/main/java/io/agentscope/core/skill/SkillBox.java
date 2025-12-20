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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.StateModuleBase;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ExtendedModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class SkillBox extends StateModuleBase {
    private static final Logger logger = LoggerFactory.getLogger(SkillBox.class);

    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final SkillLoaderToolFactory skillLoaderToolFactory;
    private final AgentSkillPromptProvider skillPromptProvider;
    private Toolkit toolkit;

    public SkillBox(Toolkit toolkit) {
        this.skillPromptProvider = new AgentSkillPromptProvider(skillRegistry);
        this.skillLoaderToolFactory = new SkillLoaderToolFactory(skillRegistry);
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
    private String getSkillPrompt() {
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
     * Activate the tool group for the accessed skill.
     */
    private void activateSkillToolGroup() {
        if (toolkit == null) {
            return;
        }
        List<String> inactiveSkillToolGroups = new ArrayList<>();
        List<String> activeSkillToolGroups = new ArrayList<>();

        // Dynamically update active/inactive tool groups based on skills' states
        for (RegisteredSkill registeredSkill : skillRegistry.getAllRegisteredSkills().values()) {
            if (!registeredSkill.isActive()) {
                inactiveSkillToolGroups.add(registeredSkill.getToolsGroupName());
                continue; // Skip inactive skill's tools, its tools won't be included
            }
            activeSkillToolGroups.add(registeredSkill.getToolsGroupName());
        }
        toolkit.updateToolGroups(inactiveSkillToolGroups, false);
        toolkit.updateToolGroups(activeSkillToolGroups, true);
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

    /**
     * Creates a Hook that automatically resets all skills to inactive state before each agent call.
     *
     * <p>This hook listens to PreCallEvent and resets skills for ReActAgent instances,
     * ensuring that skills start in an inactive state and will be activated only when
     * explicitly accessed via skill loading tools during execution.
     *
     * <p><b>Priority:</b> 10 (high priority) to ensure skills are reset before
     * other hooks that might depend on skill state.
     *
     * <p><b>Usage:</b>
     * <pre>{@code
     * SkillBox skillBox = new SkillBox();
     * ReActAgent agent = ReActAgent.builder()
     *     .name("Assistant")
     *     .skillBox(skillBox)
     *     .hooks(List.of(skillBox.getSkillHook()))
     *     .build();
     * }</pre>
     *
     * @return A Hook that resets skills on PreCallEvent
     */
    public Hook getSkillHook() {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PreCallEvent preCallEvent) {
                    resetAllSkillsActive();
                    activateSkillToolGroup();
                    return Mono.just(event);
                }

                if (event instanceof PreReasoningEvent preReasoningEvent) {
                    activateSkillToolGroup();
                    String skillPrompt = getSkillPrompt();
                    if (skillPrompt != null && !skillPrompt.isEmpty()) {
                        List<Msg> inputMessages =
                                new ArrayList<>(preReasoningEvent.getInputMessages());
                        inputMessages.add(
                                Msg.builder()
                                        .role(MsgRole.SYSTEM)
                                        .content(TextBlock.builder().text(skillPrompt).build())
                                        .build());
                        preReasoningEvent.setInputMessages(inputMessages);
                    }
                    return Mono.just(event);
                }

                if (event instanceof PostCallEvent postCallEvent) {
                    resetAllSkillsActive();
                    activateSkillToolGroup();
                    return Mono.just(event);
                }

                return Mono.just(event);
            }

            @Override
            public int priority() {
                // High priority (10) to ensure skills system prompt is added early
                // before other hooks that might depend on skill system prompt
                return 10;
            }
        };
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
     * skillBox.registerAgentSkill(mySkill);
     * skillBox.registerAgentSkill(my_skill); // do nothing
     * }</pre>
     *
     * @param skill The agent skill to register
     * @throws IllegalArgumentException if skill is null
     * @see #registerSkillVersion(String, AgentSkill, String) for explicit version management
     */
    public void registerAgentSkill(AgentSkill skill) {

        if (skill == null) {
            throw new IllegalArgumentException("AgentSkill cannot be null");
        }

        String skillId = skill.getSkillId();

        // Create registered wrapper
        RegisteredSkill registered = new RegisteredSkill(skillId);

        // Register in skillRegistry
        skillRegistry.registerSkill(skillId, skill, registered);

        logger.info("Registered skill '{}'", skillId);

        if (toolkit == null) {
            return;
        }
        // Create the tool group for this skill's tools
        String toolsGroupName = registered.getToolsGroupName();
        if (toolkit.getToolGroup(toolsGroupName) == null) {
            toolkit.createToolGroup(toolsGroupName, "Tools for skill: " + skillId, false);
        }
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
     * Gets a specific version of a skill.
     *
     * @param skillId The skill ID
     * @param versionId The version ID ("latest" for current version)
     * @return The skill version, or null if not found
     * @throws IllegalArgumentException if skillId or versionId is null
     */
    public AgentSkill getSkillVersion(String skillId, String versionId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        if (versionId == null) {
            throw new IllegalArgumentException("Version ID cannot be null");
        }
        return skillRegistry.getSkillVersion(skillId, versionId);
    }

    /**
     * Registers a new version of an existing skill.
     *
     * <p>This method is typically used after modifying a skill with AgentSkill.Builder.
     *
     * <p><b>Usage example:</b>
     * <pre>{@code
     * // Get current skill
     * AgentSkill current = toolkit.getSkill("my_skill");
     *
     * // Create modified version
     * AgentSkill modified = current.toBuilder()
     *     .description("Updated description")
     *     .skillContent("New instructions")
     *     .addResource("config.json", "{\"key\": \"value\"}")
     *     .build();
     *
     * // Register as new version
     * toolkit.registerSkillVersion("my_skill", modified, "v2.0");
     * }</pre>
     *
     * @param skillId The skill ID
     * @param skill The modified skill
     * @param versionId The version ID (null to auto-generate with timestamp)
     * @throws IllegalArgumentException if skillId or skill is null, or skill doesn't exist
     */
    public void registerSkillVersion(String skillId, AgentSkill skill, String versionId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        if (skill == null) {
            throw new IllegalArgumentException("Skill cannot be null");
        }
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException("Skill not found: " + skillId);
        }

        // versionId can be null (will be auto-generated)
        skillRegistry.addNewVersion(skillId, skill, versionId);
        logger.info(
                "Registered new version of skill '{}' with version ID '{}'",
                skillId,
                versionId != null ? versionId : "auto-generated");
    }

    /**
     * Rolls back to a previous skill version by promoting it to latest.
     *
     * <p>This allows users to revert to a previous snapshot of the skill.
     *
     * @param skillId The skill ID
     * @param versionId The version ID to roll back to
     * @throws IllegalArgumentException if skillId or versionId is null, or skill doesn't exist
     */
    public void rollbackSkillVersion(String skillId, String versionId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        if (versionId == null) {
            throw new IllegalArgumentException("Version ID cannot be null");
        }
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException("Skill not found: " + skillId);
        }

        skillRegistry.promoteVersionToLatest(skillId, versionId);
        logger.info("Rolled back skill '{}' to version '{}'", skillId, versionId);
    }

    /**
     * Lists all version IDs for a skill.
     *
     * @param skillId The skill ID
     * @return List of version IDs (includes "latest" alias)
     * @throws IllegalArgumentException if skillId is null
     */
    public List<String> listSkillVersions(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.listVersionIds(skillId);
    }

    /**
     * Gets the version ID of the current (latest) version.
     *
     * @param skillId The skill ID
     * @return The latest version ID, or null if skill not found
     * @throws IllegalArgumentException if skillId is null
     */
    public String getLatestSkillVersionId(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.getLatestVersionId(skillId);
    }

    /**
     * Removes a specific version of a skill.
     *
     * <p><b>Note:</b> The latest version cannot be removed.
     * To remove the latest version, first promote another version to latest.
     *
     * @param skillId The skill ID
     * @param versionId The version ID to remove
     * @throws IllegalArgumentException if skillId or versionId is null
     */
    public void removeSkillVersion(String skillId, String versionId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        if (versionId == null) {
            throw new IllegalArgumentException("Version ID cannot be null");
        }

        skillRegistry.removeVersion(skillId, versionId);
        logger.info("Removed version '{}' of skill '{}'", versionId, skillId);
    }

    /**
     * Removes all old versions of a skill, keeping only the latest.
     *
     * <p>This is useful for cleaning up version history.
     *
     * @param skillId The skill ID
     * @throws IllegalArgumentException if skillId is null
     */
    public void clearSkillOldVersions(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }

        skillRegistry.clearOldVersions(skillId);
        logger.info("Cleared all old versions of skill '{}'", skillId);
    }

    /**
     * Removes a skill completely.
     *
     * <p>If the skill has old versions, the removal will fail silently.
     *
     * @param skillId The skill ID
     * @throws IllegalArgumentException if skillId is null
     */
    public void removeSkill(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        skillRegistry.removeSkill(skillId, false);
        logger.info("Removed skill '{}' (force={})", skillId);
    }

    /**
     * Checks if a skill exists.
     *
     * @param skillId The skill ID
     * @return true if the skill exists, false otherwise
     * @throws IllegalArgumentException if skillId is null
     */
    public boolean skillExists(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.exists(skillId);
    }

    // ==================== Skill Access Tool Registration ====================

    /**
     * Register skill load tools with the current bound toolkit.
     *
     * @throws IllegalArgumentException if not bind toolkit
     */
    public void registerSkillLoadTools() {
        this.registerSkillLoadTools(this.toolkit);
    }

    /**
     * Register skill access tools that allow agents to dynamically load and access skills.
     *
     * <p>This creates three tools:
     * <ul>
     *   <li><b>skill_md_load_tool:</b> Load skill markdown content by skillId
     *   <li><b>skill_resources_load_tool:</b> Load specific skill resource by skillId and path
     *   <li><b>get_all_resources_path_tool:</b> Get all resource paths for a skill
     * </ul>
     *
     * <p>When any of these tools is called, the corresponding skill will be automatically
     * set to active state, enabling its associated tools in the toolkit.
     *
     * @throws IllegalArgumentException if toolkit is null
     */
    public void registerSkillLoadTools(Toolkit toolkit) {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }
        AgentTool skillMdLoadTool = skillLoaderToolFactory.createSkillMdLoadTool();
        AgentTool skillResourcesLoadTool = skillLoaderToolFactory.createSkillResourcesLoadTool();
        AgentTool getAllResourcesPathTool = skillLoaderToolFactory.createGetAllResourcesPathTool();

        // Register without group (skill access tools are always available)
        toolkit.registerAgentTool(skillMdLoadTool);
        toolkit.registerAgentTool(skillResourcesLoadTool);
        toolkit.registerAgentTool(getAllResourcesPathTool);

        logger.info(
                "Registered skill access tools: skill_md_load_tool, skill_resources_load_tool,"
                        + " get_all_resources_path_tool");
    }

    /**
     * Reset all skills to inactive state.
     *
     * <p>This method sets all registered skills to inactive, which means their associated
     * tool groups will not be available to the agent until the skills are accessed again
     * via skill access tools.
     *
     * <p>This is typically called at the start of each agent call to ensure a clean state.
     */
    private void resetAllSkillsActive() {
        skillRegistry.setAllSkillsActive(false);
        logger.debug("Reset all skills to inactive state");
    }

    /**
     * Fluent builder for registering skills with optional configuration.
     *
     * <p>This builder provides a clear, type-safe way to register skills with various options
     * without method proliferation.
     */
    public static class SkillRegistration {
        private final SkillBox skillBox;
        private final Toolkit.ToolRegistration toolkitRegistration;
        private AgentSkill skill;
        private boolean withTool;
        private String toolGroup;

        public SkillRegistration(SkillBox skillBox) {
            this.skillBox = skillBox;
            if (skillBox.toolkit != null) {
                toolkitRegistration = skillBox.toolkit.registration();
            } else {
                toolkitRegistration = null;
            }
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

        /**
         * Set the tool object to register (scans for @Tool methods).
         *
         * @param toolObject Object containing @Tool annotated methods
         * @return This builder for chaining
         */
        public SkillRegistration tool(Object toolObject) {
            toolkitRegistration.tool(toolObject);
            this.withTool = true;
            return this;
        }

        /**
         * Set the AgentTool instance to register.
         *
         * @param agentTool The AgentTool instance
         * @return This builder for chaining
         */
        public SkillRegistration agentTool(AgentTool agentTool) {
            toolkitRegistration.agentTool(agentTool);
            this.withTool = true;
            return this;
        }

        /**
         * Set the MCP client to register.
         *
         * @param mcpClientWrapper The MCP client wrapper
         * @return This builder for chaining
         */
        public SkillRegistration mcpClient(McpClientWrapper mcpClientWrapper) {
            toolkitRegistration.mcpClient(mcpClientWrapper);
            this.withTool = true;
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
            toolkitRegistration.enableTools(enableTools);
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
            toolkitRegistration.disableTools(disableTools);
            return this;
        }

        /**
         * Set the tool group name.
         *
         * @param groupName The group name (null for ungrouped)
         * @return This builder for chaining
         */
        public SkillRegistration toolGroup(String groupName) {
            this.toolGroup = groupName;
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
            toolkitRegistration.presetParameters(presetParameters);
            return this;
        }

        /**
         * Set the extended model for dynamic schema extension.
         *
         * @param extendedModel The extended model
         * @return This builder for chaining
         */
        public SkillRegistration extendedModel(ExtendedModel extendedModel) {
            toolkitRegistration.extendedModel(extendedModel);
            return this;
        }

        /**
         * Apply the registration with all configured options.
         *
         * @throws IllegalStateException if none of skill() was set
         */
        public void apply() {
            if (skill == null) {
                throw new IllegalStateException("Must call skill() before apply()");
            }
            skillBox.registerAgentSkill(skill);

            if (toolkitRegistration == null || !withTool) {
                return;
            }
            withTool = false; // reset flag

            String skillToolGroup = skill.getSkillId() + "_skill_tools";
            if (skillBox.toolkit.getToolGroup(skillToolGroup) == null) {
                skillBox.toolkit.createToolGroup(skillToolGroup, skillToolGroup);
            }
            toolkitRegistration.group(toolGroup);
            toolkitRegistration.apply();
        }
    }
}
