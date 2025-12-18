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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Factory for creating skill access tools that allow agents to dynamically load and access skills.
 *
 * <p>This factory provides three tools for LLM to interact with skills:
 * <ul>
 *   <li><b>skill_md_load_tool:</b> Load skill markdown content by skillId
 *   <li><b>skill_resources_load_tool:</b> Load specific skill resource by skillId and path
 *   <li><b>get_all_resources_path_tool:</b> Get all resource paths for a skill
 * </ul>
 *
 * <p>When any of these tools is called, the corresponding skill will be automatically
 * set to active state, enabling its associated tools in the toolkit.
 */
class SkillLoaderToolFactory {

    private static final Logger log = LoggerFactory.getLogger(SkillLoaderToolFactory.class);

    private final SkillRegistry skillRegistry;

    SkillLoaderToolFactory(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * Create the skill_md_load_tool that loads skill markdown content.
     *
     * @return AgentTool for skill_md_load_tool
     */
    AgentTool createSkillMdLoadTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "skill_md_load_tool";
            }

            @Override
            public String getDescription() {
                return "Load the markdown content of a skill by its ID. "
                        + "This will activate the skill and return its full content including "
                        + "name, description, and implementation details.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> skillIdParam = new HashMap<>();
                skillIdParam.put("type", "string");
                skillIdParam.put("description", "The unique identifier of the skill to load.");

                // Generate enum from available skills
                Set<String> availableSkills = skillRegistry.getSkillIds();
                if (!availableSkills.isEmpty()) {
                    skillIdParam.put("enum", new ArrayList<>(availableSkills));
                }

                properties.put("skillId", skillIdParam);
                schema.put("properties", properties);
                schema.put("required", List.of("skillId"));

                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    String skillId = (String) param.getInput().get("skillId");

                    if (skillId == null || skillId.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Missing or empty required parameter: skillId"));
                    }

                    String result = loadSkillMdImpl(skillId);
                    return Mono.just(ToolResultBlock.text(result));
                } catch (Exception e) {
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * Create the skill_resources_load_tool that loads a specific skill resource.
     *
     * @return AgentTool for skill_resources_load_tool
     */
    AgentTool createSkillResourcesLoadTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "skill_resources_load_tool";
            }

            @Override
            public String getDescription() {
                return "Load a specific resource file from a skill by its ID and resource path."
                        + " This will activate the skill and return the content of the requested"
                        + " resource.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                Map<String, Object> properties = new HashMap<>();

                // skillId parameter
                Map<String, Object> skillIdParam = new HashMap<>();
                skillIdParam.put("type", "string");
                skillIdParam.put("description", "The unique identifier of the skill.");

                // Generate enum from available skills
                Set<String> availableSkills = skillRegistry.getSkillIds();
                if (!availableSkills.isEmpty()) {
                    skillIdParam.put("enum", new ArrayList<>(availableSkills));
                }

                // path parameter
                Map<String, Object> pathParam = new HashMap<>();
                pathParam.put("type", "string");
                pathParam.put(
                        "description",
                        "The path to the resource file within the skill (e.g., 'config.json').");

                properties.put("skillId", skillIdParam);
                properties.put("path", pathParam);
                schema.put("properties", properties);
                schema.put("required", List.of("skillId", "path"));

                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    String skillId = (String) param.getInput().get("skillId");
                    String path = (String) param.getInput().get("path");

                    if (skillId == null || skillId.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Missing or empty required parameter: skillId"));
                    }

                    if (path == null || path.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error("Missing or empty required parameter: path"));
                    }

                    String result = loadSkillResourceImpl(skillId, path);
                    return Mono.just(ToolResultBlock.text(result));
                } catch (Exception e) {
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * Create the get_all_resources_path_tool that lists all resource paths for a skill.
     *
     * @return AgentTool for get_all_resources_path_tool
     */
    AgentTool createGetAllResourcesPathTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "get_all_resources_path_tool";
            }

            @Override
            public String getDescription() {
                return "Get a list of all resource file paths available in a skill. "
                        + "This will activate the skill and return the paths of all its resources.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> skillIdParam = new HashMap<>();
                skillIdParam.put("type", "string");
                skillIdParam.put("description", "The unique identifier of the skill.");

                // Generate enum from available skills
                Set<String> availableSkills = skillRegistry.getSkillIds();
                if (!availableSkills.isEmpty()) {
                    skillIdParam.put("enum", new ArrayList<>(availableSkills));
                }

                properties.put("skillId", skillIdParam);
                schema.put("properties", properties);
                schema.put("required", List.of("skillId"));

                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    String skillId = (String) param.getInput().get("skillId");

                    if (skillId == null || skillId.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Missing or empty required parameter: skillId"));
                    }

                    List<String> result = getAllResourcesPathImpl(skillId);
                    return Mono.just(ToolResultBlock.text(formatResourcePaths(result)));
                } catch (Exception e) {
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * Implementation of skill_md_load_tool logic.
     *
     * @param skillId The skill ID to load
     * @return Skill markdown content
     * @throws IllegalArgumentException if skill doesn't exist
     */
    private String loadSkillMdImpl(String skillId) {
        // Validate skill exists
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException(
                    String.format("Skill not found: '%s'. Please check the skill ID.", skillId));
        }

        // Set skill as active
        skillRegistry.setSkillActive(skillId, true);
        log.debug("Activated skill: {}", skillId);

        // Get skill content
        AgentSkill skill = skillRegistry.getSkill(skillId);
        if (skill == null) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to load skill '%s' after validation. This is an internal"
                                    + " error.",
                            skillId));
        }

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

        return result.toString();
    }

    /**
     * Implementation of skill_resources_load_tool logic.
     *
     * @param skillId The skill ID
     * @param path The resource path
     * @return Resource content
     * @throws IllegalArgumentException if skill or resource doesn't exist
     */
    private String loadSkillResourceImpl(String skillId, String path) {
        // Validate skill exists
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException(
                    String.format("Skill not found: '%s'. Please check the skill ID.", skillId));
        }

        // Set skill as active
        skillRegistry.setSkillActive(skillId, true);
        log.debug("Activated skill: {}", skillId);

        // Get skill
        AgentSkill skill = skillRegistry.getSkill(skillId);
        if (skill == null) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to load skill '%s' after validation. This is an internal"
                                    + " error.",
                            skillId));
        }

        // Get resource
        Map<String, String> resources = skill.getResources();
        if (resources == null || !resources.containsKey(path)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Resource not found: '%s' in skill '%s'. "
                                    + "Use get_all_resources_path_tool to see available resources.",
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

        return result.toString();
    }

    /**
     * Implementation of get_all_resources_path_tool logic.
     *
     * @param skillId The skill ID
     * @return List of resource paths
     * @throws IllegalArgumentException if skill doesn't exist
     */
    private List<String> getAllResourcesPathImpl(String skillId) {
        // Validate skill exists
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException(
                    String.format("Skill not found: '%s'. Please check the skill ID.", skillId));
        }

        // Set skill as active
        skillRegistry.setSkillActive(skillId, true);
        log.debug("Activated skill: {}", skillId);

        // Get skill
        AgentSkill skill = skillRegistry.getSkill(skillId);
        if (skill == null) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to load skill '%s' after validation. This is an internal"
                                    + " error.",
                            skillId));
        }

        // Get resource paths
        Map<String, String> resources = skill.getResources();
        if (resources == null || resources.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(resources.keySet());
    }

    /**
     * Format resource paths for display.
     *
     * @param paths List of resource paths
     * @return Formatted string
     */
    private String formatResourcePaths(List<String> paths) {
        if (paths.isEmpty()) {
            return "No resources available for this skill.";
        }

        StringBuilder result = new StringBuilder();
        result.append("Available resource paths (").append(paths.size()).append(" total):\n\n");
        for (int i = 0; i < paths.size(); i++) {
            result.append(i + 1).append(". ").append(paths.get(i)).append("\n");
        }

        return result.toString();
    }
}
