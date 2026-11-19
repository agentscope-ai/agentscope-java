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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ContextUsageTest {
    @Test
    void mapsEverySegmentToItsCategory() {
        assertEquals(ContextUsage.SYSTEM_PROMPT, category("system_prompt", null));
        assertEquals(ContextUsage.SYSTEM_PROMPT, category("project_rules", "SYSTEM"));
        assertEquals(ContextUsage.SYSTEM_PROMPT, category("business_instruction", "SYSTEM"));
        assertEquals(ContextUsage.SKILLS, category("skills", "RUNTIME"));
        assertEquals(ContextUsage.MCP_TOOLS, category("tool_mcp", null));
        assertEquals(ContextUsage.SYSTEM_TOOLS, category("tool_system", null));
        assertEquals(ContextUsage.MESSAGES, category("message_user", null));
        assertEquals(ContextUsage.MESSAGES, category("message_assistant", null));
        assertEquals(ContextUsage.MESSAGES, category("message_tool", null));
        assertEquals(ContextUsage.MESSAGES, category("message_summary", null));
        assertEquals(ContextUsage.OTHER, category("runtime_projection", null));
        assertEquals(ContextUsage.OTHER, category("memory", "REFERENCE"));
        assertEquals(ContextUsage.OTHER, category("date", "RUNTIME"));
        assertEquals(ContextUsage.OTHER, category("unrecognised", null));
        assertEquals(ContextUsage.OTHER, category(null, null));
    }

    @Test
    void breakdownClampsNegativeSizesAndOrdersLargestFirst() {
        var manifest =
                manifest(
                        List.of(
                                item("tool_system", "a", null, 10),
                                item("message_user", "b", null, 30),
                                item("memory", "c", null, -5)));
        var shares = ContextUsage.breakdown(manifest);
        assertEquals(2, shares.size());
        assertEquals(ContextUsage.MESSAGES, shares.get(0).source());
        assertEquals(30, shares.get(0).chars());
        assertEquals(ContextUsage.SYSTEM_TOOLS, shares.get(1).source());
        assertEquals(10, shares.get(1).chars());
        assertEquals(0.75, shares.get(0).ratio(), 1e-12);
    }

    @Test
    void breakdownOfAnEmptyManifestIsEmpty() {
        assertTrue(ContextUsage.breakdown(manifest(List.of())).isEmpty());
    }

    @Test
    void itemBreakdownSumsToTheCategoryTotal() {
        var manifest =
                manifest(
                        List.of(
                                item("system_prompt", "harness:system", "SYSTEM", 100),
                                item("tool_system", "shell", null, 60),
                                item("tool_mcp", "mcp__fs__read", null, 40),
                                item("message_user", "u1", null, 20),
                                item("memory", "workspace:MEMORY.md", "REFERENCE", 10),
                                item("memory", "negative", "REFERENCE", -5)));
        var rows = ContextUsage.itemBreakdown(manifest);
        var shares = ContextUsage.breakdown(manifest);
        long itemTotal = rows.stream().mapToLong(ContextUsage.ItemShare::chars).sum();
        assertEquals(shares.stream().mapToLong(ContextUsage.Share::chars).sum(), itemTotal);
        // The negative size contributes nothing, and its row stays visible with chars 0.
        assertEquals(230, itemTotal);
        assertEquals(6, rows.size());
        assertEquals(0, rows.get(rows.size() - 1).chars());
        assertEquals("harness:system", rows.get(0).sourceId());
        assertEquals(ContextUsage.SYSTEM_PROMPT, rows.get(0).source());
        assertEquals(1.0, rows.stream().mapToDouble(ContextUsage.ItemShare::ratio).sum(), 1e-9);
        for (int index = 1; index < rows.size(); index++) {
            assertTrue(rows.get(index - 1).chars() >= rows.get(index).chars());
        }
    }

    @Test
    void itemBreakdownMergesRepeatedSources() {
        var manifest =
                manifest(
                        List.of(
                                item("message_user", "dup", null, 5),
                                item("message_user", "dup", null, 7),
                                item("message_user", "other", null, 1)));
        var rows = ContextUsage.itemBreakdown(manifest);
        assertEquals(2, rows.size());
        assertEquals("dup", rows.get(0).sourceId());
        assertEquals(12, rows.get(0).chars());
        assertEquals(2, rows.get(0).items());
        assertEquals(1, rows.get(1).items());
    }

    @Test
    void itemBreakdownKeepsZeroSizedSourcesVisible() {
        var manifest =
                manifest(
                        List.of(
                                item("tool_system", "sized", null, 5),
                                item("tool_system", "unsized", null, 0)));
        var rows = ContextUsage.itemBreakdown(manifest);
        assertEquals(2, rows.size());
        assertEquals("sized", rows.get(0).sourceId());
        assertEquals("unsized", rows.get(1).sourceId());
        assertEquals(0, rows.get(1).chars());
        assertEquals(0.0, rows.get(1).ratio(), 1e-12);
        assertEquals(1, rows.get(1).items());
    }

    @Test
    void includeEmptyKeepsAFullRowSet() {
        var manifest = manifest(List.of(item("message_user", "u", null, 30)));
        var full = ContextUsage.breakdown(manifest, true);
        assertEquals(6, full.size());
        assertEquals(
                Set.of(
                        ContextUsage.SYSTEM_PROMPT,
                        ContextUsage.SKILLS,
                        ContextUsage.MCP_TOOLS,
                        ContextUsage.SYSTEM_TOOLS,
                        ContextUsage.MESSAGES,
                        ContextUsage.OTHER),
                full.stream().map(ContextUsage.Share::source).collect(Collectors.toSet()));
        assertEquals(ContextUsage.MESSAGES, full.get(0).source());
        assertEquals(1.0, full.stream().mapToDouble(ContextUsage.Share::ratio).sum(), 1e-9);

        var empty = ContextUsage.breakdown(manifest(List.of()), true);
        assertEquals(6, empty.size());
        assertTrue(empty.stream().allMatch(share -> share.chars() == 0));
        assertEquals(0.0, empty.stream().mapToDouble(ContextUsage.Share::ratio).sum(), 1e-12);

        // A missing manifest still yields a full row set, matching summary(null).
        var missing = ContextUsage.breakdown(null, true);
        assertEquals(6, missing.size());
        assertTrue(missing.stream().allMatch(share -> share.chars() == 0));
    }

    @Test
    void systemBreakdownSeparatesOneSystemSourceFromMany() {
        var single = manifest(List.of(item("system_prompt", "harness:system", "SYSTEM", 9127)));
        var one = ContextUsage.systemBreakdown(single);
        assertEquals(1, one.size());
        assertEquals(ContextUsage.SYSTEM_PROMPT, one.get(0).source());
        assertEquals("harness:system", one.get(0).sourceId());
        assertEquals(9127, one.get(0).chars());
        assertEquals(1.0, one.get(0).ratio(), 1e-12);

        var many =
                manifest(
                        List.of(
                                // Production base prompts carry no placement; sourceType decides.
                                item("system_prompt", "base", null, 4473),
                                item("project_rules", "harness:rules", "SYSTEM", 613),
                                item("message_user", "u", null, 100)));
        var rows = ContextUsage.systemBreakdown(many);
        assertEquals(2, rows.size());
        assertEquals("base", rows.get(0).sourceId());
        assertEquals(613, rows.get(1).chars());
        // Only the system channel is attributed, and it matches the category total.
        assertEquals(5086, rows.stream().mapToLong(ContextUsage.ItemShare::chars).sum());
        assertEquals(
                5086,
                ContextUsage.breakdown(many).stream()
                        .filter(share -> ContextUsage.SYSTEM_PROMPT.equals(share.source()))
                        .findFirst()
                        .orElseThrow()
                        .chars());
    }

    @Test
    void systemBreakdownOfAManifestWithoutASystemChannelIsEmpty() {
        var manifest = manifest(List.of(item("message_user", "u", null, 10)));
        assertTrue(ContextUsage.systemBreakdown(manifest).isEmpty());
        assertTrue(ContextUsage.systemBreakdown(null).isEmpty());
        assertTrue(ContextUsage.itemBreakdown(null).isEmpty());
    }

    @Test
    void summaryCarriesMethodLimitsAndEveryCategory() {
        var manifest =
                new ContextManifest(
                        "c",
                        "REASONING",
                        "m",
                        "v",
                        5000,
                        60000,
                        "mixed-text-2.5chars-media-2048-v1",
                        false,
                        0,
                        List.of(
                                item("tool_system", "shell", null, 60),
                                item("message_user", "u", null, 40),
                                item("memory", "negative", null, -5)),
                        List.of(),
                        "passed");
        var summary = ContextUsage.summary(manifest);
        assertEquals(100, summary.totalChars());
        assertEquals(5000, summary.estimatedInputTokens());
        assertEquals(60000, summary.inputLimit());
        assertEquals("mixed-text-2.5chars-media-2048-v1", summary.countingMethod());
        assertFalse(summary.exact());
        assertEquals("passed", summary.validation());
        assertEquals(
                List.of(
                        ContextUsage.SYSTEM_PROMPT,
                        ContextUsage.SKILLS,
                        ContextUsage.MCP_TOOLS,
                        ContextUsage.SYSTEM_TOOLS,
                        ContextUsage.MESSAGES,
                        ContextUsage.OTHER),
                List.copyOf(summary.categoryChars().keySet()));
        assertEquals(60L, summary.categoryChars().get(ContextUsage.SYSTEM_TOOLS));
        assertEquals(40L, summary.categoryChars().get(ContextUsage.MESSAGES));
        assertEquals(0L, summary.categoryChars().get(ContextUsage.SKILLS));
        assertEquals(
                100, summary.categoryChars().values().stream().mapToLong(Long::longValue).sum());
        assertThrows(
                UnsupportedOperationException.class,
                () -> summary.categoryChars().put(ContextUsage.SKILLS, 1L));
    }

    @Test
    void equalSizedCategoriesKeepTheDisplayOrder() {
        var manifest =
                manifest(
                        List.of(
                                item("message_user", "u", null, 10),
                                item("tool_system", "t", null, 10)));
        var filtered = ContextUsage.breakdown(manifest);
        var full = ContextUsage.breakdown(manifest, true);
        assertEquals(
                List.of(ContextUsage.SYSTEM_TOOLS, ContextUsage.MESSAGES),
                filtered.stream().map(ContextUsage.Share::source).toList());
        assertEquals(ContextUsage.SYSTEM_TOOLS, full.get(0).source());
        assertEquals(ContextUsage.MESSAGES, full.get(1).source());
    }

    @Test
    void summaryOfAMissingManifestIsZeroed() {
        var summary = ContextUsage.summary(null);
        assertEquals(0, summary.totalChars());
        assertEquals(0, summary.estimatedInputTokens());
        assertEquals(0, summary.inputLimit());
        assertNull(summary.countingMethod());
        assertFalse(summary.exact());
        assertNull(summary.validation());
        assertEquals(6, summary.categoryChars().size());
        assertEquals(0, summary.categoryChars().values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void itemBreakdownCarriesTheSharedContentHash() {
        var manifest =
                manifest(
                        List.of(
                                itemWithHash("message_user", "dup", null, 5, "h1"),
                                itemWithHash("message_user", "dup", null, 7, "h1"),
                                itemWithHash("tool_system", "shell", null, 3, "h2")));
        var rows = ContextUsage.itemBreakdown(manifest);
        assertEquals(2, rows.size());
        assertEquals("h1", rows.get(0).contentHash());
        assertEquals(2, rows.get(0).items());
        assertEquals("h2", rows.get(1).contentHash());
    }

    @Test
    void itemBreakdownDropsTheHashWhenMergedItemsDisagree() {
        var manifest =
                manifest(
                        List.of(
                                itemWithHash("message_user", "dup", null, 5, "h1"),
                                itemWithHash("message_user", "dup", null, 7, "h2")));
        var rows = ContextUsage.itemBreakdown(manifest);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).contentHash());
        assertEquals(12, rows.get(0).chars());
    }

    @Test
    void itemBreakdownDropsTheHashWhenOneItemHasNone() {
        var manifest =
                manifest(
                        List.of(
                                itemWithHash("message_user", "dup", null, 5, "h1"),
                                itemWithHash("message_user", "dup", null, 7, null)));
        assertNull(ContextUsage.itemBreakdown(manifest).get(0).contentHash());
    }

    @Test
    void itemBreakdownDropsTheHashWhenThreeItemsDisagree() {
        var manifest =
                manifest(
                        List.of(
                                itemWithHash("message_user", "dup", null, 5, "h1"),
                                itemWithHash("message_user", "dup", null, 7, "h1"),
                                itemWithHash("message_user", "dup", null, 9, "h2")));
        var rows = ContextUsage.itemBreakdown(manifest);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).contentHash());
        assertEquals(3, rows.get(0).items());
        assertEquals(21, rows.get(0).chars());
    }

    @Test
    void itemBreakdownDropsTheHashWhateverOrderTheItemsArriveIn() {
        var manifest =
                manifest(
                        List.of(
                                itemWithHash("message_user", "dup", null, 5, "h1"),
                                itemWithHash("message_user", "dup", null, 7, null),
                                itemWithHash("message_user", "dup", null, 9, "h1")));
        assertNull(ContextUsage.itemBreakdown(manifest).get(0).contentHash());
    }

    @Test
    void systemBreakdownCarriesTheHashSoCallsCanBeCorrelated() {
        var manifest =
                manifest(List.of(itemWithHash("system_prompt", "call-1", null, 8991, "stable")));
        var rows = ContextUsage.systemBreakdown(manifest);
        assertEquals(1, rows.size());
        assertEquals("stable", rows.get(0).contentHash());
    }

    @Test
    void legacyItemShareConstructorLeavesTheHashUnknown() {
        var legacy =
                new ContextUsage.ItemShare(
                        "system_prompt", "system_prompt", "sys", "SYSTEM", 10, 1.0, 2);
        assertEquals("system_prompt", legacy.source());
        assertEquals("sys", legacy.sourceId());
        assertEquals("SYSTEM", legacy.placement());
        assertEquals(10, legacy.chars());
        assertEquals(2, legacy.items());
        assertNull(legacy.contentHash());
    }

    @Test
    void systemChannelIsSingleBlockTracksTheSourceCount() {
        assertTrue(
                ContextUsage.systemChannelIsSingleBlock(
                        manifest(List.of(item("system_prompt", "a", null, 100)))));
        assertTrue(
                ContextUsage.systemChannelIsSingleBlock(
                        manifest(List.of(item("project_rules", "b", "SYSTEM", 20)))));
        assertTrue(
                ContextUsage.systemChannelIsSingleBlock(
                        manifest(List.of(item("system_prompt", "a", null, 0)))));
        assertFalse(
                ContextUsage.systemChannelIsSingleBlock(
                        manifest(
                                List.of(
                                        item("system_prompt", "a", null, 100),
                                        item("project_rules", "b", "SYSTEM", 20)))));
        assertFalse(
                ContextUsage.systemChannelIsSingleBlock(
                        manifest(List.of(item("message_user", "u", null, 10)))));
        assertFalse(ContextUsage.systemChannelIsSingleBlock(null));
    }

    private static String category(String sourceType, String placement) {
        return ContextUsage.category(
                new ContextManifest.Item(sourceType, "id", "hash", null, placement, false, 0, 1));
    }

    private static ContextManifest.Item item(
            String sourceType, String sourceId, String placement, int contentChars) {
        return itemWithHash(sourceType, sourceId, placement, contentChars, "hash");
    }

    private static ContextManifest.Item itemWithHash(
            String sourceType, String sourceId, String placement, int contentChars, String hash) {
        return new ContextManifest.Item(
                sourceType, sourceId, hash, null, placement, false, 0, contentChars);
    }

    private static ContextManifest manifest(List<ContextManifest.Item> items) {
        return new ContextManifest(
                "c",
                "REASONING",
                "test",
                "v",
                0,
                0,
                "approximate",
                false,
                0,
                items,
                List.of(),
                "passed");
    }
}
