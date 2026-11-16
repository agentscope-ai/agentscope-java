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
package io.agentscope.harness.agent.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rolls a {@link ContextManifest}'s disjoint segments up into the shares a context-usage panel
 * displays: system prompt, skills, MCP tools, framework tools, messages, and a catch-all.
 *
 * <p>Categories are ordered largest first and categories without content are omitted, so a caller
 * can render the result directly. Shares are computed from {@link
 * ContextManifest.Item#contentChars()}. Message and tool entries measure serialized content JSON;
 * material entries measure their source text, so the ratios are proportional estimates rather than
 * token counts.
 *
 * <p>Every item placed in the system channel — the base prompt and {@code SYSTEM} materials alike —
 * counts toward {@link #SYSTEM_PROMPT}, because all of it is merged into the single system message
 * the model receives.
 */
public final class ContextUsage {
    /** Content placed in the system channel: the base prompt and {@code SYSTEM} materials. */
    public static final String SYSTEM_PROMPT = "system_prompt";

    /** Skill listing injected as runtime material. */
    public static final String SKILLS = "skills";

    /** Tool schemas whose implementation lives in an MCP server. */
    public static final String MCP_TOOLS = "mcp_tool_schemas";

    /** Tool schemas registered by the framework or by the application. */
    public static final String SYSTEM_TOOLS = "system_tool_schemas";

    /** Conversation messages in any role, including compaction summaries. */
    public static final String MESSAGES = "messages";

    /** Everything else: runtime materials, injected state, resolved date. */
    public static final String OTHER = "other";

    private static final Set<String> MESSAGE_KINDS =
            Set.of("message_user", "message_assistant", "message_tool", "message_summary");

    private ContextUsage() {}

    /**
     * One category's share of the measured content.
     *
     * @param source stable category identifier, one of this class's constants
     * @param chars summed {@link ContextManifest.Item#contentChars()} of the category
     * @param ratio {@code chars} over the measured total
     */
    public record Share(String source, long chars, double ratio) {}

    /**
     * Groups {@code manifest} into category shares, largest first.
     *
     * @param manifest the manifest produced for one model request
     * @return category shares; empty when the manifest has no items or no recorded sizes
     */
    public static List<Share> breakdown(ContextManifest manifest) {
        if (manifest == null || manifest.items().isEmpty()) return List.of();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (ContextManifest.Item item : manifest.items()) {
            // A negative size cannot describe a contribution; treat it as unrecorded.
            long chars = Math.max(0, item.contentChars());
            totals.merge(category(item), chars, Long::sum);
        }
        long total = totals.values().stream().mapToLong(Long::longValue).sum();
        return totals.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(
                        entry ->
                                new Share(
                                        entry.getKey(),
                                        entry.getValue(),
                                        (double) entry.getValue() / total))
                .toList();
    }

    /** Maps a segment to its display category. */
    static String category(ContextManifest.Item item) {
        String sourceType = item.sourceType();
        if (SYSTEM_PROMPT.equals(sourceType)) return SYSTEM_PROMPT;
        if ("SYSTEM".equals(item.placement())) return SYSTEM_PROMPT;
        if (SKILLS.equals(sourceType)) return SKILLS;
        if ("tool_mcp".equals(sourceType)) return MCP_TOOLS;
        if ("tool_system".equals(sourceType)) return SYSTEM_TOOLS;
        if (sourceType != null && MESSAGE_KINDS.contains(sourceType)) return MESSAGES;
        return OTHER;
    }
}
