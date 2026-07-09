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
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Factory for creating skill access tools that allow agents to dynamically load and access skills.
 *
 */
class SkillToolFactory {

    private static final Logger logger = LoggerFactory.getLogger(SkillToolFactory.class);

    private final SkillRegistry skillRegistry;
    private Toolkit toolkit;

    SkillToolFactory(SkillRegistry skillRegistry, Toolkit toolkit) {
        this.skillRegistry = skillRegistry;
        this.toolkit = toolkit;
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
                        + "\n"
                        + "**Path rules:**\n"
                        + "- Use path=\"SKILL.md\" to load the skill's markdown documentation"
                        + " (name, description, usage instructions).\n"
                        + "- Use exact resource paths listed by the skill, such as"
                        + " \"references/guide.md\" or \"scripts/run.py\".\n"
                        + "- Do not use '.', './', the skill directory, or an absolute path.";
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
                                                        "The exact resource path within the skill."
                                                                + " Use 'SKILL.md' to load the"
                                                                + " skill instructions. Do not use"
                                                                + " '.', './', directories, or"
                                                                + " absolute paths.")),
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
        AgentSkill skill = validateSkillExists(skillId);

        // Special handling for SKILL.md - return the skill's markdown content
        if ("SKILL.md".equals(path)) {
            activateSkill(skillId);
            return buildSkillMarkdownResponse(skillId, skill);
        }

        // 1. In-memory map (eager FS repos, classpath repos, marketplace prefetch).
        Map<String, String> resources = skill.getResources();
        if (resources != null && resources.containsKey(path)) {
            activateSkill(skillId);
            return buildResourceResponse(skillId, path, resources.get(path));
        }

        // 2. Disk fallback via skill.originDir (lazy FS repos, or any FS-backed skill whose
        //    in-memory map didn't preload the requested path). Same sanitisation rules as the
        //    advertised path schema: no '..', no absolute path, no directories.
        Optional<String> sanitized = sanitizeRelativePath(path);
        if (sanitized.isPresent() && skill.getOriginDir().isPresent()) {
            Optional<String> diskContent =
                    readFromOriginDir(skill.getOriginDir().get(), sanitized.get());
            if (diskContent.isPresent()) {
                activateSkill(skillId);
                return buildResourceResponse(skillId, path, diskContent.get());
            }
        }

        // 3. Not found — enumerate from both sources so the model gets real options.
        throw new IllegalArgumentException(
                buildResourceNotFoundMessage(
                        skillId, path, resources, skill.getOriginDir().orElse(null)));
    }

    private static Optional<String> sanitizeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            return Optional.empty();
        }
        if (".".equals(normalized) || "./".equals(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * Reads a single file under {@code originDir} for the disk-fallback path. Text content is
     * returned as-is; binary content uses the {@code base64:} prefix convention that matches
     * {@code SkillFileSystemHelper.readAndPutResource}, so the load tool's contract is identical
     * whether the resource came from memory or disk.
     */
    private static Optional<String> readFromOriginDir(Path originDir, String relPath) {
        Path target = originDir.resolve(relPath).normalize();
        if (!target.startsWith(originDir)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (MalformedInputException e) {
            try {
                byte[] bytes = Files.readAllBytes(target);
                return Optional.of("base64:" + Base64.getEncoder().encodeToString(bytes));
            } catch (IOException ex) {
                logger.warn("Failed to read binary resource {}: {}", target, ex.getMessage());
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.warn("Failed to read resource {}: {}", target, e.getMessage());
            return Optional.empty();
        }
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
            String skillId, String path, Map<String, String> resources, Path originDir) {
        StringBuilder message = new StringBuilder();
        message.append("Resource not found: '")
                .append(path)
                .append("' in skill '")
                .append(skillId)
                .append("'.\n\n");

        // Build a deduped list spanning in-memory keys and on-disk entries so the model can
        // see both classes of resources in one place.
        Set<String> resourcePaths = new LinkedHashSet<>();
        resourcePaths.add("SKILL.md");
        if (resources != null) {
            resourcePaths.addAll(resources.keySet());
        }
        if (originDir != null) {
            resourcePaths.addAll(listOriginDirEntries(originDir));
        }

        message.append("Available resources:\n");
        int i = 1;
        for (String entry : resourcePaths) {
            message.append(i++).append(". ").append(entry).append("\n");
        }

        return message.toString();
    }

    private static List<String> listOriginDirEntries(Path originDir) {
        List<String> entries = new ArrayList<>();
        try (var stream = Files.walk(originDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(
                            p -> {
                                String rel = originDir.relativize(p).toString().replace('\\', '/');
                                if (!rel.isBlank() && !"SKILL.md".equals(rel)) {
                                    entries.add(rel);
                                }
                            });
        } catch (IOException e) {
            logger.debug(
                    "Failed to enumerate {} for not-found listing: {}", originDir, e.getMessage());
        }
        return entries;
    }

    /**
     * Validates that a skill is registered and returns its instance.
     *
     * @param skillId The unique identifier of the skill
     * @return The registered skill instance
     * @throws IllegalArgumentException if the skill is not registered
     * @throws IllegalStateException if the skill cannot be loaded after validation
     */
    private AgentSkill validateSkillExists(String skillId) {
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
        return skill;
    }

    private void activateSkill(String skillId) {
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

        // Also activate any SkillToolGroup bound to this skill via activateOnSkill
        AgentSkill agentSkill = skillRegistry.getSkill(skillId);
        if (agentSkill != null) {
            List<String> boundGroups =
                    toolkit.findSkillToolGroupsByActivateOnSkill(agentSkill.getName());
            for (String group : boundGroups) {
                if (!group.equals(toolsGroupName)
                        && toolkit.getToolGroup(group) != null
                        && !toolkit.getToolGroup(group).isActive()) {
                    toolkit.updateToolGroups(List.of(group), true);
                    logger.info(
                            "Activated skill-bound tool group: {} for skill: {}",
                            group,
                            agentSkill.getName());
                }
            }
        }
    }
}
