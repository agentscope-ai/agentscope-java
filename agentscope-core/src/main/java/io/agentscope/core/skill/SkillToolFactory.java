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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
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

    static final String LOAD_TOOL_NAME = "load_skill_through_path";
    private static final String LOAD_TOOL_DESCRIPTION =
            "Load and activate a skill resource by its ID and resource path.\n\n"
                + "**Functionality:**\n"
                + "1. Activates the specified skill for this session (adding its tool groups to"
                + " this session's active set)\n"
                + "2. Returns the requested resource content\n"
                + "\n"
                + "**Path rules:**\n"
                + "- Use path=\"SKILL.md\" to load the skill's markdown documentation (name,"
                + " description, usage instructions).\n"
                + "- Use exact resource paths listed by the skill, such as \"references/guide.md\""
                + " or \"scripts/run.py\".\n"
                + "- Set reload=true on a SKILL.md load to receive the full document again, e.g."
                + " after context compaction removed it from the conversation.\n"
                + "- Do not use '.', './', the skill directory, or an absolute path.";

    private final SkillRegistry skillRegistry;
    private volatile Toolkit toolkit;

    /**
     * Entry-delivery tracking, scoped per conversation (see {@link EntryDeliveryTracker}).
     *
     * <p>One agent (and therefore one {@code SkillBox} / factory) can serve multiple
     * {@code (userId, sessionId)} pairs, so "the model has already seen this entry" must not be
     * agent-global state: session A's load must not suppress the first entry load of session B,
     * whose context never received it. The tracker bounds the number of remembered scopes (LRU)
     * and claims atomically, so overlapping same-batch loads dedup correctly.
     */
    private final EntryDeliveryTracker entryDelivery = new EntryDeliveryTracker();

    /**
     * Fallback scope for tool calls that carry no runtime context at all (direct tool use).
     * A context with a user but no session id gets {@code null} instead — an unidentifiable
     * conversation never dedups, so its sessions cannot suppress each other.
     */
    private static final String NO_CONTEXT_SCOPE = "<no-context>";

    SkillToolFactory(SkillRegistry skillRegistry, Toolkit toolkit) {
        this.skillRegistry = skillRegistry;
        this.toolkit = toolkit;
    }

    /**
     * Binds the shared toolkit to this skill tool factory (read-only reference).
     *
     * @param toolkit The toolkit to bind to the skill tool factory
     * @throws IllegalArgumentException if the toolkit is null
     */
    void bindToolkit(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    /**
     * Registers the shared, runtime-resolving {@code load_skill_through_path} tool on the given
     * toolkit. Used by the dynamic-skill path: the tool is registered once at build time and
     * resolves the current per-call {@link SkillBox} from the {@link RuntimeContext} at invocation
     * time, so the shared toolkit is never mutated per call.
     *
     * @param toolkit the shared toolkit to register the load tool on
     */
    static void registerRuntimeLoadTool(Toolkit toolkit) {
        if (toolkit.getTool(LOAD_TOOL_NAME) != null) {
            // Already registered by another path (e.g. the static skillBox); never overwrite.
            // Mixing static and dynamic skill configuration is unsupported, so surface it.
            logger.warn(
                    "load_skill_through_path already registered; skipping (a same-named tool is"
                            + " already present on this toolkit)");
            return;
        }
        // Registered ungrouped so it is always visible/callable and is never dropped by the
        // META-scoped reset_equipped_tools replacement (like reset_equipped_tools itself).
        toolkit.registration().agentTool(createRuntimeLoadTool()).apply();
    }

    /**
     * Creates the shared {@code load_skill_through_path} tool that resolves the current per-call
     * {@link SkillBox} at invocation time. Unlike {@link #createSkillAccessToolAgentTool()}, this
     * tool carries no per-box state, so a single instance is safe to share across concurrent
     * sessions.
     */
    static AgentTool createRuntimeLoadTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return LOAD_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return LOAD_TOOL_DESCRIPTION;
            }

            @Override
            public Map<String, Object> getParameters() {
                // Dynamic skills are not known at build time, so the parameter enum is omitted;
                // the skill catalog is advertised via the system prompt instead.
                return Map.of(
                        "type", "object",
                        "properties",
                                Map.of(
                                        "skillId",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The unique identifier of the skill."),
                                        "path",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The exact resource path within the"
                                                                + " skill. Use 'SKILL.md' to load"
                                                                + " the skill instructions. Do not"
                                                                + " use '.', './', directories, or"
                                                                + " absolute paths."),
                                        "reload",
                                                Map.of(
                                                        "type",
                                                        "boolean",
                                                        "description",
                                                        "Optional. Set true to re-receive the"
                                                                + " full SKILL.md even if it was"
                                                                + " already delivered to this"
                                                                + " session (e.g. after context"
                                                                + " compaction removed it).")),
                        "required", List.of("skillId", "path"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    SkillBox box = resolveSkillBox(param);
                    if (box == null) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "load_skill_through_path: no skill context for this"
                                                + " call"));
                    }
                    return Mono.just(ToolResultBlock.text(box.loadSkillResource(param)));
                } catch (IllegalArgumentException e) {
                    logger.warn("Error loading skill resource: {}", e.getMessage());
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                } catch (Exception e) {
                    logger.error("Unexpected error loading skill resource", e);
                    return Mono.just(
                            ToolResultBlock.error(
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : "Unexpected error: " + e.getClass().getSimpleName()));
                }
            }
        };
    }

    private static SkillBox resolveSkillBox(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        return rc != null ? rc.get(SkillBox.class) : null;
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
                return LOAD_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return LOAD_TOOL_DESCRIPTION;
            }

            @Override
            public Map<String, Object> getParameters() {
                // Get all available skill IDs
                List<String> availableSkillIds = new ArrayList<>(skillRegistry.getSkillIds());

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
                                                                + " absolute paths."),
                                        "reload",
                                                Map.of(
                                                        "type",
                                                        "boolean",
                                                        "description",
                                                        "Optional. Set true to re-receive the full"
                                                            + " SKILL.md even if it was already"
                                                            + " delivered to this session (e.g."
                                                            + " after context compaction removed"
                                                            + " it).")),
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

                    boolean reload = lenientBoolean(input.get("reload"));
                    String result =
                            loadSkillResourceImpl(
                                    skillId,
                                    path,
                                    resolveToolContext(param),
                                    scopeOf(param),
                                    reload);
                    return Mono.just(ToolResultBlock.text(result));
                } catch (IllegalArgumentException e) {
                    logger.warn("Error loading skill resource: {}", e.getMessage());
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                } catch (Exception e) {
                    logger.error("Unexpected error loading skill resource", e);
                    return Mono.just(
                            ToolResultBlock.error(
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : "Unexpected error: " + e.getClass().getSimpleName()));
                }
            }
        };
    }

    /**
     * Parses an optional boolean tool argument leniently.
     *
     * <p>Models frequently send booleans as strings ({@code "true"}) or numbers ({@code 1}) when
     * a parameter is optional; a strict {@code Boolean.TRUE.equals} would silently drop the
     * reload lever exactly when the model asked for it.
     */
    static boolean lenientBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return value != null && Boolean.parseBoolean(value.toString().trim());
    }

    /**
     * Derives the entry-delivery scope from a tool call's runtime context, or null when the
     * conversation cannot be identified precisely enough to dedup safely.
     *
     * <ul>
     *   <li>No context at all (direct tool use) — the shared {@link #NO_CONTEXT_SCOPE} bucket.
     *   <li>A user without a session id — {@code null}: a null session must not collapse a
     *       whole user's conversations into one dedup bucket, so such calls never dedup.
     *   <li>A session without a user id (gateway shared rooms) — {@code *::sessionId}: one
     *       stable scope per shared conversation, matching the state-slot identity.
     * </ul>
     */
    static String scopeOf(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        if (context == null) {
            return NO_CONTEXT_SCOPE;
        }
        String userId = context.getUserId();
        String sessionId = context.getSessionId();
        if (sessionId == null) {
            return userId == null ? NO_CONTEXT_SCOPE : null;
        }
        return (userId == null ? "*" : userId) + "::" + sessionId;
    }

    /**
     * Implementation of skill resource loading logic.
     *
     * @param skillId The unique identifier of the skill
     * @param path The path to the resource file
     * @param tcs the per-call tool context state; skill activation targets this session's
     *     activation set instead of the shared group manager
     * @param scope the per-call (userId, sessionId) scope of this load, used to track entry
     *     delivery without leaking state across sessions
     * @param reload whether the caller explicitly asked to re-receive an already-delivered
     *     SKILL.md (e.g. after context compaction removed it)
     * @return The formatted resource content or error message with available resources
     * @throws IllegalArgumentException if skill doesn't exist or resource not found
     */
    String loadSkillResourceImpl(
            String skillId, String path, ToolContextState tcs, String scope, boolean reload) {
        AgentSkill skill = validateSkillExists(skillId);

        // Special handling for SKILL.md - return the skill's markdown content
        if ("SKILL.md".equals(path)) {
            // An LLM often calls load_skill_through_path for the same skill several
            // times in one batch; re-sending the full SKILL.md burns tokens for
            // content already in context (#1569). Key the short-circuit on the entry
            // content having actually been delivered *in this session's context* —
            // NOT on the skill being active, because loading any resource activates
            // the skill without ever serving SKILL.md (the not-found message also
            // enumerates resource paths, so a model can reach a resource first), and
            // NOT on agent-global state, because one agent serves many sessions.
            // scope == null means the conversation could not be identified precisely
            // enough to dedup safely (user without a session): always deliver.
            // tryClaim is atomic, so overlapping same-batch loads (parallel tool
            // execution) still dedup: the first claim wins, the rest see the notice.
            if (scope != null) {
                if (reload) {
                    // Explicit in-session recovery lever for content lost to compaction.
                    entryDelivery.mark(scope, skillId);
                } else if (!entryDelivery.tryClaim(scope, skillId)) {
                    // Still reconcile activation: a repeat load must keep this session's
                    // tool-group state in sync (a host can disable groups independently),
                    // skipping only the re-send of the markdown.
                    activateSkill(skillId, tcs);
                    return buildAlreadyLoadedNotice(skillId, skill);
                }
            }
            activateSkill(skillId, tcs);
            return buildSkillMarkdownResponse(skillId, skill);
        }

        // 1. In-memory map (eager FS repos, classpath repos, marketplace prefetch).
        Map<String, String> resources = skill.getResources();
        if (resources != null && resources.containsKey(path)) {
            activateSkill(skillId, tcs);
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
                activateSkill(skillId, tcs);
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
     * Builds the deduplication notice for a repeat {@code SKILL.md} load of an already-delivered
     * skill in the same session scope.
     *
     * <p>The notice is actionable rather than terminal: it enumerates the skill's resources (each
     * still returns full content) so a model that lost parts of the skill to context compaction
     * can re-fetch what it needs. Delivery tracking is per (userId, sessionId) scope, so a fresh
     * session always receives the full entry document on its first load.
     */
    private String buildAlreadyLoadedNotice(String skillId, AgentSkill skill) {
        StringBuilder notice = new StringBuilder();
        notice.append("Skill '")
                .append(skillId)
                .append("' is already loaded and active; its SKILL.md was delivered to this")
                .append(" session by the earlier load and is not re-sent to save context.\n\n");
        appendAvailableResources(notice, skill.getResources(), skill.getOriginDir().orElse(null));
        notice.append("\nEach listed resource returns its full content on request. Pass")
                .append(" reload=true to receive the full SKILL.md again (e.g. after context")
                .append(" compaction removed it); a new session receives it on first load.");
        return notice.toString();
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

        appendAvailableResources(message, resources, originDir);

        return message.toString();
    }

    /**
     * Appends a numbered, deduped listing of the skill's resources spanning in-memory keys and
     * on-disk entries, so the model can see both classes of resources in one place.
     */
    private static void appendAvailableResources(
            StringBuilder message, Map<String, String> resources, Path originDir) {
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

    /**
     * Resolves the per-call {@link ToolContextState} from the tool call's runtime context, so
     * skill activation targets the active session's activation set instead of the shared group
     * manager.
     *
     * @param param the tool call parameters
     * @return the resolved tool context state, or {@code null} when unavailable
     */
    ToolContextState resolveToolContext(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        AgentState state = rc != null ? rc.getAgentState() : null;
        return state != null ? state.getToolContext() : null;
    }

    private void activateSkill(String skillId, ToolContextState tcs) {
        // No per-session context: skip the tool-group gate without mutating shared state.
        if (tcs == null) {
            logger.warn(
                    "load_skill_through_path: no per-session runtime context; tool-group activation"
                            + " skipped");
            return;
        }

        // Read-only discovery of the groups owned by this skill: the name-convention group
        // (skillId_skill_tools) plus any SkillToolGroup bound via activateOnSkill. Shared group
        // definitions are never mutated.
        List<String> groups = new ArrayList<>();
        String toolsGroupName = skillId + "_skill_tools";
        if (toolkit != null && toolkit.getToolGroup(toolsGroupName) != null) {
            groups.add(toolsGroupName);
        }
        AgentSkill agentSkill = skillRegistry.getSkill(skillId);
        if (agentSkill != null && toolkit != null) {
            for (String group :
                    toolkit.findSkillToolGroupsByActivateOnSkill(agentSkill.getName())) {
                if (!groups.contains(group)) {
                    groups.add(group);
                }
            }
        }

        // Atomic union, so parallel loads within a session cannot lose one another's activation.
        tcs.addActivatedGroups(groups);
    }
}
