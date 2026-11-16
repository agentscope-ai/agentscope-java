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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
                                new ContextManifest.Item("tool_system", "a", "h", 10),
                                new ContextManifest.Item("message_user", "b", "h", 30),
                                new ContextManifest.Item("memory", "c", "h", -5)));
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

    private static String category(String sourceType, String placement) {
        return ContextUsage.category(
                new ContextManifest.Item(sourceType, "id", "hash", null, placement, false, 0, 1));
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
