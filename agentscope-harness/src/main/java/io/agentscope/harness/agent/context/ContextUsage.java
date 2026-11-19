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

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

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
 * the model receives. A category that is absent from {@link #breakdown(ContextManifest)} has no
 * measured content; it does not mean the framework failed to collect it (see below).
 *
 * <p><b>What is measured.</b> The manifest describes the request after preparation, so content that
 * pre-call hooks, system-prompt middleware, and model-call middleware injected is included: recalled
 * memory arrives as conversation messages, and the resolved date and the skill listing arrive as
 * materials when the application registers them. What is missing is content added after preparation.
 * Text a middleware appends to the system prompt is counted, but it merges into one item and cannot
 * be split back into its contributors. A category at zero therefore means the prepared request
 * carried nothing attributable to it: the source may be unused, its size may be unrecorded, or its
 * text may have been merged into a higher-priority category. Use {@link #summary(ContextManifest)}
 * for the measurement method and limits.
 */
public final class ContextUsage {
    /** Content placed in the system channel: the base prompt and {@code SYSTEM} materials. */
    public static final String SYSTEM_PROMPT = "system_prompt";

    /**
     * Skill listing injected as runtime material. Zero when no non-blank listing is registered as a
     * material — the builder skips a blank one — or when the listing is appended to the system
     * prompt; in that case the text counts toward {@link #SYSTEM_PROMPT} instead, so zero does not
     * mean the agent has no skills.
     */
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

    private static final List<String> CATEGORIES =
            List.of(SYSTEM_PROMPT, SKILLS, MCP_TOOLS, SYSTEM_TOOLS, MESSAGES, OTHER);

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
     * One source's attribution: the segment-level view of a category.
     *
     * <p>Items sharing the same category, source type, source id, and placement are merged, so
     * repeated materials report one row each. Zero-sized items are kept: a row with {@code chars}
     * of {@code 0} means the source was present without a recorded size, and its {@code items} count
     * still tells you the source exists.
     *
     * <p>{@code contentHash} identifies the content, not a logical source. Two rows from different
     * calls share a hash when their content is identical — which is how a caller correlates a source
     * whose {@code sourceId} is regenerated per call — but a source whose content grows also changes
     * its hash, so the hash cannot track a changing source across calls.
     *
     * <p>Record equality includes {@code contentHash}, so a row built with the convenience
     * constructor never equals a row from {@link #itemBreakdown(ContextManifest)} that carries a
     * hash.
     *
     * @param source display category, one of this class's constants
     * @param sourceType raw {@link ContextManifest.Item#sourceType()}
     * @param sourceId raw {@link ContextManifest.Item#sourceId()}
     * @param placement raw {@link ContextManifest.Item#placement()}, {@code null} when unknown
     * @param chars summed {@code contentChars} of the merged items
     * @param ratio {@code chars} over the measured total, {@code 0} when the total is {@code 0}
     * @param items how many manifest items were merged into this row
     * @param contentHash hash shared by every merged item; {@code null} when they disagree or when
     *     any merged item carries no hash
     */
    public record ItemShare(
            String source,
            String sourceType,
            String sourceId,
            String placement,
            long chars,
            double ratio,
            int items,
            String contentHash) {
        /** Convenience constructor for callers that do not read the hash. */
        public ItemShare(
                String source,
                String sourceType,
                String sourceId,
                String placement,
                long chars,
                double ratio,
                int items) {
            this(source, sourceType, sourceId, placement, chars, ratio, items, null);
        }
    }

    /**
     * Totals and provenance for one manifest, so a caller does not have to recompute them.
     *
     * <p>{@code totalChars} and {@code categoryChars} come from the segments; {@code
     * estimatedInputTokens} comes from the policy's estimator over the {@code ModelCallInput}. The
     * built-in approximate estimator counts per-message overhead and charges a fixed cost for media;
     * an adapter that plugs in a real tokenizer reports its own method and does not. The two are
     * different lenses on the same request and will not agree, and neither is provider usage.
     *
     * <p>A manifest from a failed build carries {@code -1} for both token figures and {@code
     * unavailable} as the counting method; render those as "not measured" rather than as sizes.
     *
     * @param totalChars measured content across every segment
     * @param estimatedInputTokens the policy estimator's estimate for this request, {@code -1} when
     *     the build failed
     * @param inputLimit input budget the policy computed for this request, {@code -1} when the build
     *     failed
     * @param countingMethod estimator identifier, {@code null} for a missing manifest
     * @param exact whether the estimate is exact; {@code false} for char-based estimators
     * @param validation validation status the framework recorded
     * @param categoryChars every category, in display order, {@code 0} when absent
     */
    public record Summary(
            long totalChars,
            long estimatedInputTokens,
            long inputLimit,
            String countingMethod,
            boolean exact,
            String validation,
            Map<String, Long> categoryChars) {
        public Summary {
            categoryChars =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    Objects.requireNonNull(categoryChars, "categoryChars")));
        }
    }

    /**
     * Groups {@code manifest} into category shares, largest first, omitting empty categories.
     *
     * @param manifest the manifest produced for one model request
     * @return category shares; empty when the manifest has no items or no recorded sizes
     */
    public static List<Share> breakdown(ContextManifest manifest) {
        return breakdown(manifest, false);
    }

    /**
     * Groups {@code manifest} into category shares, largest first.
     *
     * @param manifest the manifest produced for one model request
     * @param includeEmpty when {@code true}, every category is returned, absent ones with {@code
     *     chars} of {@code 0} and {@code ratio} of {@code 0}, so a panel can keep a stable row set;
     *     when {@code false}, empty categories are omitted
     * @return category shares; empty only when {@code manifest} is {@code null} and {@code
     *     includeEmpty} is {@code false}. Equal sizes fall back to this class's display order, so
     *     both overloads order a given manifest the same way
     */
    public static List<Share> breakdown(ContextManifest manifest, boolean includeEmpty) {
        if (manifest == null && !includeEmpty) return List.of();
        Map<String, Long> totals = new LinkedHashMap<>();
        if (includeEmpty) for (String category : CATEGORIES) totals.put(category, 0L);
        if (manifest != null) {
            for (ContextManifest.Item item : manifest.items()) {
                // A negative size cannot describe a contribution; treat it as unrecorded.
                totals.merge(category(item), Math.max(0L, item.contentChars()), Long::sum);
            }
        }
        long total = totals.values().stream().mapToLong(Long::longValue).sum();
        return totals.entrySet().stream()
                .filter(entry -> includeEmpty || entry.getValue() > 0)
                .sorted(
                        Map.Entry.<String, Long>comparingByValue()
                                .reversed()
                                .thenComparingInt(entry -> CATEGORIES.indexOf(entry.getKey())))
                .map(
                        entry ->
                                new Share(
                                        entry.getKey(),
                                        entry.getValue(),
                                        total == 0 ? 0 : (double) entry.getValue() / total))
                .toList();
    }

    /**
     * Attributes every segment to its source, largest first.
     *
     * <p>This is the item-level companion to {@link #breakdown(ContextManifest)}: it answers which
     * sources make up a category, for example how many distinct entries the system channel holds.
     * The shares sum to the same total as the category breakdown. Only metadata is exposed, never
     * prompt bodies.
     *
     * @param manifest the manifest produced for one model request
     * @return one row per distinct source; empty when {@code manifest} is {@code null} or empty
     */
    public static List<ItemShare> itemBreakdown(ContextManifest manifest) {
        return shareByItem(manifest, item -> true);
    }

    /**
     * Attributes the system channel alone, largest first.
     *
     * <p>The framework merges the base prompt and every {@code SYSTEM} material into one system
     * message before the model call. Because the merge happens before the manifest is built, the
     * items listed here are the pre-merge sources: when a middleware appends to the assembled system
     * string, that text is part of a single item and cannot be split further.
     *
     * @param manifest the manifest produced for one model request
     * @return one row per distinct system source; empty when the manifest has none. Ratios are over
     *     the system channel's own total, not over the whole request, so this view is not directly
     *     comparable with {@link #itemBreakdown(ContextManifest)}
     */
    public static List<ItemShare> systemBreakdown(ContextManifest manifest) {
        return shareByItem(manifest, item -> SYSTEM_PROMPT.equals(category(item)));
    }

    /**
     * Whether the request carried exactly one system source.
     *
     * <p>When this is {@code true}, the system channel holds a single source, so text appended to
     * it cannot be separated from it. That is the shape an application gets when it lets a
     * middleware append, for example, a skill listing to the prompt instead of registering a
     * material.
     *
     * <p>The default harness registers several {@code SYSTEM} materials — for example context
     * conventions, project rules, workspace guidance, and workspace environment — so this is
     * normally {@code false} there. A listing can still be appended to one of those items, so
     * either way a category at zero is a floor rather than proof the source is absent; use {@link
     * #systemBreakdown(ContextManifest)} to see how many blocks the channel holds.
     *
     * @param manifest the manifest produced for one model request
     * @return {@code true} when the manifest holds exactly one system source; {@code false} for a
     *     {@code null} manifest, no system source, or more than one
     */
    public static boolean systemChannelIsSingleBlock(ContextManifest manifest) {
        return systemBreakdown(manifest).size() == 1;
    }

    /**
     * Collects the totals a context panel needs, with the caveats that make them readable.
     *
     * @param manifest the manifest produced for one model request
     * @return totals; all zeros with a {@code null} counting method when {@code manifest} is {@code
     *     null}
     */
    public static Summary summary(ContextManifest manifest) {
        if (manifest == null) {
            Map<String, Long> empty = new LinkedHashMap<>();
            for (String category : CATEGORIES) empty.put(category, 0L);
            return new Summary(0, 0, 0, null, false, null, empty);
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String category : CATEGORIES) totals.put(category, 0L);
        for (ContextManifest.Item item : manifest.items()) {
            long chars = Math.max(0, item.contentChars());
            totals.merge(category(item), chars, Long::sum);
        }
        long total = totals.values().stream().mapToLong(Long::longValue).sum();
        return new Summary(
                total,
                manifest.estimatedInputTokens(),
                manifest.inputLimit(),
                manifest.countingMethod(),
                manifest.exact(),
                manifest.validation(),
                totals);
    }

    private static List<ItemShare> shareByItem(
            ContextManifest manifest, Predicate<ContextManifest.Item> filter) {
        if (manifest == null || manifest.items().isEmpty()) return List.of();
        Map<Key, Tally> tallies = new LinkedHashMap<>();
        for (ContextManifest.Item item : manifest.items()) {
            if (!filter.test(item)) continue;
            Key key = new Key(category(item), item.sourceType(), item.sourceId(), item.placement());
            tallies.merge(
                    key,
                    new Tally(Math.max(0, item.contentChars()), 1, item.contentHash()),
                    Tally::plus);
        }
        long total = tallies.values().stream().mapToLong(Tally::chars).sum();
        return tallies.entrySet().stream()
                .sorted(
                        Map.Entry.<Key, Tally>comparingByValue(
                                        Comparator.comparingLong(Tally::chars))
                                .reversed()
                                .thenComparingInt(
                                        entry -> CATEGORIES.indexOf(entry.getKey().source()))
                                .thenComparing(
                                        entry -> entry.getKey().sourceId(),
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparing(
                                        entry -> entry.getKey().sourceType(),
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparing(
                                        entry -> entry.getKey().placement(),
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(
                        entry ->
                                new ItemShare(
                                        entry.getKey().source(),
                                        entry.getKey().sourceType(),
                                        entry.getKey().sourceId(),
                                        entry.getKey().placement(),
                                        entry.getValue().chars(),
                                        total == 0 ? 0 : (double) entry.getValue().chars() / total,
                                        entry.getValue().items(),
                                        entry.getValue().contentHash()))
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

    private record Key(String source, String sourceType, String sourceId, String placement) {}

    private record Tally(long chars, int items, String contentHash) {
        Tally plus(Tally other) {
            // A row merges by identity, so the hash survives only while every item agrees on it.
            String hash = Objects.equals(contentHash, other.contentHash) ? contentHash : null;
            return new Tally(chars + other.chars, items + other.items, hash);
        }
    }
}
