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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.skill.SkillResources;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The harness-native {@code load_skill_through_path} {@link AgentTool}.
 *
 * <p>Query order for any {@code path} other than {@code SKILL.md}:
 *
 * <ol>
 *   <li>{@code skill.getResources().get(path)} — in-memory map (Layer 1/3 host repositories and
 *       marketplace repositories that preload everything)
 *   <li>{@code entry.lazyResources().read(path)} — filesystem fallback for sources that
 *       implement {@link io.agentscope.harness.agent.skill.LazyResourceCapable}
 *       (e.g. {@code WorkspaceSkillRepository})
 *   <li>Not found → error with a deduped enumeration of {@code SKILL.md} + in-memory keys +
 *       lazy listing
 * </ol>
 *
 * <p>The tool's catalog reference is held via an {@link AtomicReference} supplied by
 * {@link SkillRuntime} so the registered tool instance can be reused across {@code call()}
 * rounds without re-registering, while the catalog itself is rebuilt every round.
 */
@SuppressWarnings("deprecation")
public final class SkillLoadTool implements AgentTool {

    public static final String TOOL_NAME = "load_skill_through_path";
    private static final String SKILL_FILE = "SKILL.md";

    private static final Logger log = LoggerFactory.getLogger(SkillLoadTool.class);

    private final AtomicReference<SkillCatalog> catalogRef;

    public SkillLoadTool(AtomicReference<SkillCatalog> catalogRef) {
        if (catalogRef == null) {
            throw new IllegalArgumentException("catalogRef must not be null");
        }
        this.catalogRef = catalogRef;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Load and return a skill resource by its skill id and a path relative to the"
                + " skill root.\n\n"
                + "Path rules:\n"
                + "- Use path=\"SKILL.md\" to load the skill's markdown documentation.\n"
                + "- Use exact resource paths listed by the skill, e.g."
                + " \"references/guide.md\" or \"scripts/run.py\".\n"
                + "- Do not use '.', './', the skill directory itself, or an absolute path.";
    }

    @Override
    public Map<String, Object> getParameters() {
        SkillCatalog snapshot = catalogRef.get();
        List<String> ids = snapshot == null ? List.of() : snapshot.ids();
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "skillId",
                                        Map.of(
                                                "type", "string",
                                                "description", "Unique skill identifier.",
                                                "enum", ids),
                                "path",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Exact resource path within the skill."
                                                        + " Use 'SKILL.md' for the skill's"
                                                        + " instructions; do not use '.',"
                                                        + " './', directories, or absolute"
                                                        + " paths.")),
                "required", List.of("skillId", "path"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            Map<String, Object> input = param.getInput();
            String skillId = stringOrNull(input.get("skillId"));
            String path = stringOrNull(input.get("path"));
            if (skillId == null || skillId.isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: skillId"));
            }
            if (path == null || path.isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: path"));
            }

            SkillCatalog catalog = catalogRef.get();
            HarnessSkillEntry entry = catalog == null ? null : catalog.get(skillId);
            if (entry == null) {
                return Mono.just(
                        ToolResultBlock.error(
                                "Skill not found: '"
                                        + skillId
                                        + "'. Check the available skills list."));
            }

            return Mono.just(loadOne(entry, path));
        } catch (Exception e) {
            log.error("load_skill_through_path failed", e);
            return Mono.just(ToolResultBlock.error(e.getMessage()));
        }
    }

    private ToolResultBlock loadOne(HarnessSkillEntry entry, String path) {
        AgentSkill skill = entry.skill();

        // 1. Special case: SKILL.md returns the parsed body text.
        if (SKILL_FILE.equals(path)) {
            return ToolResultBlock.text(formatSkillMarkdown(entry));
        }

        // 2. In-memory map (Layer 1/3 host repos + marketplace fully-loaded resources).
        Map<String, String> mem = skill.getResources();
        if (mem != null && mem.containsKey(path)) {
            return ToolResultBlock.text(formatResource(entry, path, mem.get(path)));
        }

        // 3. Lazy fallback (WorkspaceSkillRepository and other LazyResourceCapable sources).
        if (entry.lazyResources() != null) {
            Optional<String> lazy = entry.lazyResources().read(path);
            if (lazy.isPresent()) {
                return ToolResultBlock.text(formatResource(entry, path, lazy.get()));
            }
        }

        // 4. Not found: enumerate both sources so the model sees real options.
        return ToolResultBlock.error(formatNotFound(entry, path));
    }

    // ---------------------------------------------------------------------
    //  Formatting helpers
    // ---------------------------------------------------------------------

    /**
     * Wrapping fence used to demarcate file content from the surrounding tool-result header.
     * Four backticks so it stays unambiguous even when the wrapped body contains the more common
     * triple-backtick code fences.
     */
    private static final String CONTENT_FENCE = "````";

    private String formatSkillMarkdown(HarnessSkillEntry entry) {
        AgentSkill skill = entry.skill();
        StringBuilder sb = new StringBuilder();
        sb.append("Successfully loaded skill: ").append(skill.getSkillId()).append("\n\n");
        sb.append("Name: ").append(skill.getName()).append("\n");
        sb.append("Description: ").append(skill.getDescription()).append("\n");
        sb.append("Source: ").append(skill.getSource()).append("\n");
        if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
            sb.append("Files root: ").append(entry.filesRoot()).append("\n");
        }
        // Emit the full SKILL.md document (YAML frontmatter + body) so the view the LLM reads
        // matches the bytes that skill_manage(action=patch) operates on. Returning body-only
        // (the previous behaviour) drove patch failures whenever the LLM tried to old_string
        // anything near the frontmatter boundary, since the parser eats that whitespace.
        String fullMarkdown =
                MarkdownSkillParser.generate(skill.getMetadata(), skill.getSkillContent());
        sb.append("\nContent (").append("SKILL.md").append("):\n");
        sb.append(CONTENT_FENCE).append("markdown\n");
        sb.append(fullMarkdown);
        if (!fullMarkdown.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append(CONTENT_FENCE).append("\n");
        return sb.toString();
    }

    private String formatResource(HarnessSkillEntry entry, String path, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("Successfully loaded resource from skill: ")
                .append(entry.skill().getSkillId())
                .append("\n");
        sb.append("Resource path: ").append(path).append("\n");
        if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
            sb.append("Files root: ").append(entry.filesRoot()).append("\n");
        }
        sb.append("\nContent (").append(path).append("):\n");
        sb.append(CONTENT_FENCE).append("\n");
        sb.append(content);
        if (content == null || !content.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append(CONTENT_FENCE).append("\n");
        return sb.toString();
    }

    private String formatNotFound(HarnessSkillEntry entry, String missingPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("Resource not found: '")
                .append(missingPath)
                .append("' in skill '")
                .append(entry.skill().getSkillId())
                .append("'.\n\n");

        LinkedHashSet<String> available = new LinkedHashSet<>();
        available.add(SKILL_FILE);

        Map<String, String> mem = entry.skill().getResources();
        if (mem != null) {
            available.addAll(mem.keySet());
        }

        SkillResources lazy = entry.lazyResources();
        if (lazy != null) {
            try {
                available.addAll(lazy.list());
            } catch (Exception e) {
                log.debug(
                        "lazyResources.list() failed for '{}': {}",
                        entry.skill().getSkillId(),
                        e.getMessage());
            }
        }

        sb.append("Available resources:\n");
        int i = 1;
        List<String> ordered = new ArrayList<>(available);
        for (String p : ordered) {
            sb.append(i++).append(". ").append(p).append("\n");
        }
        return sb.toString();
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
