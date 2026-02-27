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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.ContextSharingMode;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentContext;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Factory for creating skill access tools that allow agents to dynamically load and access skills.
 */
class SkillToolFactory {

    private static final Logger logger = LoggerFactory.getLogger(SkillToolFactory.class);

    private final SkillRegistry skillRegistry;
    private final SkillBox skillBox;
    private Toolkit toolkit;

    /** Tracks which skills have already had their sub-agent tools created. */
    private final Set<String> skillsWithSubAgentCreated = new HashSet<>();

    SkillToolFactory(SkillRegistry skillRegistry, Toolkit toolkit, SkillBox skillBox) {
        this.skillRegistry = skillRegistry;
        this.toolkit = toolkit;
        this.skillBox = skillBox;
    }

    /**
     * Binds a toolkit to the skill tool factory.
     *
     * <p>
     * This method binds the toolkit to skill tool factory.
     * Since ReActAgent uses a deep copy of the Toolkit, rebinding is necessary to
     * ensure the
     * skill tool factory references the correct toolkit instance.
     *
     * @param toolkit The toolkit to bind to the skill tool factory
     * @throws IllegalArgumentException if the toolkit is null
     */
    void bindToolkit(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    /**
     * Creates the load_skill_through_path agent tool.
     *
     * <p>This tool allows agents to load and activate skills by their ID and resource path.
     * It supports loading SKILL.md for skill documentation or other resources like scripts,
     * configs, and templates.
     *
     * @return AgentTool for loading skill resources (including SKILL.md)
     */
    AgentTool createSkillAccessToolAgentTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "load_skill_through_path";
            }

            @Override
            public String getDescription() {
                return "Load and activate a skill resource by its ID and resource path.\n\n"
                        + "**Functionality:**\n"
                        + "1. Activates the specified skill (making its tools available)\n"
                        + "2. Returns the requested resource content\n"
                        + " usage instructions)\n"
                        + "- 'SKILL.md': The skill's markdown documentation (name, description,"
                        + "- Other paths: Additional resources like scripts, configs, templates, or"
                        + " data files";
            }

            @Override
            public Map<String, Object> getParameters() {
                // Get all available skill IDs
                List<String> availableSkillIds =
                        new ArrayList<>(skillRegistry.getAllRegisteredSkills().keySet());

                return Map.of(
                        "type", "object",
                        "properties",
                                Map.of(
                                        "skillId",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The unique identifier of the" + " skill.",
                                                        "enum",
                                                        availableSkillIds),
                                        "path",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The path to the resource file within the"
                                                                + " skill (e.g., 'SKILL.md,"
                                                                + " references/references.md')")),
                        "required", List.of("skillId", "path"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    Map<String, Object> input = param.getInput();

                    // Validate parameters
                    String skillId = (String) input.get("skillId");
                    if (skillId == null || skillId.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Missing or empty required parameter: skillId"));
                    }

                    String path = (String) input.get("path");
                    if (path == null || path.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error("Missing or empty required parameter: path"));
                    }

                    String result = loadSkillResourceImpl(skillId, path);
                    return Mono.just(ToolResultBlock.text(result));
                } catch (IllegalArgumentException e) {
                    logger.error("Error loading skill resource", e);
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                } catch (Exception e) {
                    logger.error("Unexpected error loading skill resource", e);
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * Implementation of skill resource loading logic.
     *
     * @param skillId The unique identifier of the skill
     * @param path The path to the resource file
     * @return The formatted resource content or error message with available resources
     * @throws IllegalArgumentException if skill doesn't exist or resource not found
     */
    private String loadSkillResourceImpl(String skillId, String path) {
        AgentSkill skill = validatedActiveSkill(skillId);

        // Special handling for SKILL.md - return the skill's markdown content
        if ("SKILL.md".equals(path)) {
            return buildSkillMarkdownResponse(skillId, skill);
        }

        // Get resource
        Map<String, String> resources = skill.getResources();
        if (resources == null || !resources.containsKey(path)) {
            // Resource not found, return available resource paths
            throw new IllegalArgumentException(
                    buildResourceNotFoundMessage(skillId, path, resources));
        }

        String resourceContent = resources.get(path);
        return buildResourceResponse(skillId, path, resourceContent);
    }

    /**
     * Build response for SKILL.md content.
     *
     * @param skillId The skill ID
     * @param skill The skill instance
     * @return Formatted skill markdown response
     */
    private String buildSkillMarkdownResponse(String skillId, AgentSkill skill) {
        StringBuilder result = new StringBuilder();
        result.append("Successfully loaded skill: ").append(skillId).append("\n\n");
        result.append("Name: ").append(skill.getName()).append("\n");
        result.append("Description: ").append(skill.getDescription()).append("\n");

        // Add model info if present
        if (skill.getModel() != null && !skill.getModel().isBlank()) {
            result.append("Model: ").append(skill.getModel()).append("\n");
        }

        result.append("Source: ").append(skill.getSource()).append("\n\n");
        result.append("Content:\n");
        result.append("---\n");
        result.append(skill.getSkillContent());
        result.append("\n---\n");

        // Add hint about sub-agent tool if skill has a configured model
        if (skill.getModel() != null && !skill.getModel().isBlank()) {
            String toolName = "call_" + skill.getName();
            result.append("\n**Note:** This skill is configured to use model '")
                    .append(skill.getModel())
                    .append("'.\n");
            result.append("Use the '**")
                    .append(toolName)
                    .append("**' tool to execute tasks with this skill's configured model.\n");
        }

        return result.toString();
    }

    /**
     * Build response for regular resource content.
     *
     * @param skillId The skill ID
     * @param path The resource path
     * @param resourceContent The resource content
     * @return Formatted resource response
     */
    private String buildResourceResponse(String skillId, String path, String resourceContent) {
        StringBuilder result = new StringBuilder();
        result.append("Successfully loaded resource from skill: ").append(skillId).append("\n");
        result.append("Resource path: ").append(path).append("\n\n");
        result.append("Content:\n");
        result.append("---\n");
        result.append(resourceContent);
        result.append("\n---\n");
        return result.toString();
    }

    /**
     * Build error message with available resource paths when resource is not found.
     *
     * @param skillId The skill ID
     * @param path The requested path that was not found
     * @param resources The available resources map
     * @return Formatted error message with available resources
     */
    private String buildResourceNotFoundMessage(
            String skillId, String path, Map<String, String> resources) {
        StringBuilder message = new StringBuilder();
        message.append("Resource not found: '")
                .append(path)
                .append("' in skill '")
                .append(skillId)
                .append("'.\n\n");

        // Build available resources list with SKILL.md as the first item
        List<String> resourcePaths = new ArrayList<>();
        resourcePaths.add("SKILL.md"); // Always add SKILL.md as the first resource

        if (resources != null && !resources.isEmpty()) {
            resourcePaths.addAll(resources.keySet());
        }

        message.append("Available resources:\n");
        for (int i = 0; i < resourcePaths.size(); i++) {
            message.append(i + 1).append(". ").append(resourcePaths.get(i)).append("\n");
        }

        return message.toString();
    }

    /**
     * Validate skill exists and activate it and its tool group.
     *
     * <p>This method also creates a sub-agent tool if the skill has a model configured
     * and the sub-agent tool hasn't been created yet.
     *
     * @param skillId The unique identifier of the skill
     * @return The skill instance
     * @throws IllegalArgumentException if skill doesn't exist
     */
    private AgentSkill validatedActiveSkill(String skillId) {
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException(
                    String.format("Skill not found: '%s'. Please check the skill ID.", skillId));
        }
        // Get skill
        AgentSkill skill = skillRegistry.getSkill(skillId);
        if (skill == null) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to load skill '%s' after validation. This is an internal"
                                    + " error.",
                            skillId));
        }
        // Set skill as active
        skillRegistry.setSkillActive(skillId, true);
        logger.info("Activated skill: {}", skillId);

        String toolsGroupName = skillRegistry.getRegisteredSkill(skillId).getToolsGroupName();
        if (toolkit.getToolGroup(toolsGroupName) != null) {
            toolkit.updateToolGroups(List.of(toolsGroupName), true);
            logger.info(
                    "Activated skill tool group: {} and its tools: {}",
                    toolsGroupName,
                    toolkit.getToolGroup(toolsGroupName).getTools());
        }

        // Create sub-agent tool if skill has model and not already created
        createSubAgentIfHasModel(skill, skillId);

        return skill;
    }

    /**
     * Create a SubAgentTool if the skill has a model configured and not already created.
     *
     * <p>This method is called when a skill is dynamically loaded via load_skill_through_path.
     * It ensures that skills with models get their sub-agent tools created on-demand.
     *
     * @param skill The skill to check for model configuration
     * @param skillId The skill ID for tracking creation status
     */
    private void createSubAgentIfHasModel(AgentSkill skill, String skillId) {
        // Check if skill has a model configured
        String modelRef = skill.getModel();
        logger.debug(
                "createSubAgentIfHasModel called for skill '{}', modelRef='{}', "
                        + "toolkit={}, skillBox={}, modelProvider={}",
                skill.getName(),
                modelRef,
                toolkit != null ? "present" : "null",
                skillBox != null ? "present" : "null",
                skillBox != null && skillBox.getModelProvider() != null ? "present" : "null");

        if (modelRef == null || modelRef.isBlank()) {
            logger.debug(
                    "Skill '{}' has no model configured, skipping sub-agent creation",
                    skill.getName());
            return; // No model specified
        }

        // Check if sub-agent tool already created for this skill
        if (skillsWithSubAgentCreated.contains(skillId)) {
            logger.debug("Sub-agent tool already exists for skill '{}'", skillId);
            return;
        }

        // Check prerequisites
        if (toolkit == null) {
            logger.warn(
                    "No toolkit available for skill '{}', cannot create sub-agent with model '{}'",
                    skill.getName(),
                    modelRef);
            return;
        }

        if (skillBox == null || skillBox.getModelProvider() == null) {
            logger.warn(
                    "No SkillModelProvider configured for skill '{}', "
                            + "cannot create sub-agent with model '{}'",
                    skill.getName(),
                    modelRef);
            return;
        }

        // Resolve model
        Model model = skillBox.getModelProvider().getModel(modelRef);
        if (model == null) {
            logger.warn(
                    "Model '{}' not found for skill '{}', skipping sub-agent creation",
                    modelRef,
                    skill.getName());
            return;
        }

        // Create tool group if needed
        String skillToolGroup = skillId + "_skill_tools";
        if (toolkit.getToolGroup(skillToolGroup) == null) {
            toolkit.createToolGroup(skillToolGroup, skillToolGroup, false);
        }

        // Parse context sharing mode from skill
        ContextSharingMode contextMode = parseContextSharingMode(skill.getContext());

        // Build system prompt using SkillSubagentPromptBuilder (only used for NEW mode)
        final Model resolvedModel = model;
        final Toolkit toolkitCopy = toolkit.copy();
        final String systemPrompt =
                SkillSubagentPromptBuilder.builder()
                        .skill(skill)
                        .modelName(resolvedModel.getModelName())
                        .build();

        // Create SubAgentProvider - context-aware for memory sharing
        // For SHARED and FORK modes, the context will contain the memory to use
        // For NEW mode, the context will have null memory, so we use our own
        SubAgentProvider<ReActAgent> provider =
                new SubAgentProvider<>() {
                    @Override
                    public ReActAgent provideWithContext(SubAgentContext context) {
                        ReActAgent.Builder agentBuilder =
                                ReActAgent.builder()
                                        .name(skill.getName() + "_agent")
                                        .description(skill.getDescription())
                                        .model(resolvedModel)
                                        .toolkit(toolkitCopy);

                        // Check if context provides a memory to use (SHARED or FORK mode)
                        Memory memoryToUse = context.getMemoryToUse();
                        if (memoryToUse != null) {
                            // Use the provided memory (shared or forked from parent)
                            agentBuilder.memory(memoryToUse);
                            logger.debug(
                                    "Sub-agent '{}' using {} memory from context",
                                    skill.getName(),
                                    context.getContextSharingMode());
                        } else {
                            // No memory provided - use independent memory with our system prompt
                            // This is the NEW mode case
                            agentBuilder.sysPrompt(systemPrompt).memory(new InMemoryMemory());
                            logger.debug(
                                    "Sub-agent '{}' using independent memory with skill system"
                                            + " prompt",
                                    skill.getName());
                        }

                        return agentBuilder.build();
                    }
                };

        // Register sub-agent tool with context sharing mode from skill
        String toolName = "call_" + skill.getName();
        toolkit.registration()
                .group(skillToolGroup)
                .subAgent(
                        provider,
                        SubAgentConfig.builder()
                                .toolName(toolName)
                                .description(
                                        "Execute "
                                                + skill.getName()
                                                + " skill task using model '"
                                                + model.getModelName()
                                                + "'")
                                .contextSharingMode(contextMode)
                                .build())
                .apply();

        // Mark as created
        skillsWithSubAgentCreated.add(skillId);

        // Activate the tool group
        toolkit.updateToolGroups(List.of(skillToolGroup), true);

        logger.info(
                "Created sub-agent tool '{}' for skill '{}' with model '{}' (dynamic loading)",
                toolName,
                skill.getName(),
                model.getModelName());
    }

    /**
     * Parses the context sharing mode from skill's context field.
     *
     * <p>Supported values:
     *
     * <ul>
     *   <li>null, empty, "shared" - SHARED (default)
     *   <li>"fork" - FORK
     *   <li>"new" - NEW
     * </ul>
     *
     * @param context The context string from skill
     * @return The corresponding ContextSharingMode
     */
    private ContextSharingMode parseContextSharingMode(String context) {
        if (context == null || context.isEmpty() || "shared".equalsIgnoreCase(context)) {
            return ContextSharingMode.SHARED;
        } else if ("fork".equalsIgnoreCase(context)) {
            return ContextSharingMode.FORK;
        } else if ("new".equalsIgnoreCase(context)) {
            return ContextSharingMode.NEW;
        } else {
            logger.warn(
                    "Unknown context mode '{}', defaulting to SHARED. "
                            + "Supported values: shared, fork, new",
                    context);
            return ContextSharingMode.SHARED;
        }
    }
}
