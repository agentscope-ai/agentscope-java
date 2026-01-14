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
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Factory for creating skill access tools that allow agents to dynamically load and access skills.
 */
class SkillToolFactory {

    private static final Logger logger = LoggerFactory.getLogger(SkillToolFactory.class);

    private final SkillRegistry skillRegistry;

    SkillToolFactory(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
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
        result.append("Source: ").append(skill.getSource()).append("\n\n");
        result.append("Content:\n");
        result.append("---\n");
        result.append(skill.getSkillContent());
        result.append("\n---\n");
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
     * Validate skill exists and activate it.
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
