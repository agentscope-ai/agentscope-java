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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelContextWindowsTest {

    private static final Map<String, Map<String, Integer>> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("DASHSCOPE", ModelContextWindows.DASHSCOPE);
        TABLES.put("OPENAI", ModelContextWindows.OPENAI);
        TABLES.put("DEEPSEEK", ModelContextWindows.DEEPSEEK);
        TABLES.put("GLM", ModelContextWindows.GLM);
        TABLES.put("MINIMAX", ModelContextWindows.MINIMAX);
        TABLES.put("KIMI", ModelContextWindows.KIMI);
        TABLES.put("ANTHROPIC", ModelContextWindows.ANTHROPIC);
        TABLES.put("GEMINI", ModelContextWindows.GEMINI);
        TABLES.put("GROK", ModelContextWindows.GROK);
    }

    @Test
    void resolvesQwenFamilies() {
        assertEquals(1_000_000, resolve("qwen3.8-max"));
        assertEquals(1_000_000, resolve("qwen3.8-flash-1101"));
        // Regression: qwen3.7 was recorded as 131_072 before the series reached 1M.
        assertEquals(1_000_000, resolve("qwen3.7-plus"));
        assertEquals(1_000_000, resolve("qwen3.6-plus-2026-04-02"));
        assertEquals(1_000_000, resolve("qwen3.5-plus-2026-04-20"));
        assertEquals(131_072, resolve("qwen3-235b-a22b"));
        assertEquals(32_768, resolve("qwen3-0.6b"));
    }

    @Test
    void resolvesOpenAiFamilies() {
        assertEquals(1_050_000, resolve("gpt-6-astra"));
        assertEquals(1_050_000, resolve("gpt-6.1-terra"));
        assertEquals(1_050_000, resolve("gpt-6-luna"));
        assertEquals(400_000, resolve("gpt-5.2"));
        assertEquals(400_000, resolve("gpt-5.1-2025-11-13"));
        assertEquals(400_000, resolve("gpt-5.4-mini"));
        assertEquals(1_050_000, resolve("gpt-5.4"));
    }

    @Test
    void resolvesAnthropicFamilies() {
        assertEquals(1_000_000, resolve("claude-opus-5-5"));
        assertEquals(1_000_000, resolve("claude-fable-5-2"));
        assertEquals(1_000_000, resolve("claude-haiku-5-5"));
        // Regression: claude-sonnet-4-6 gained 1M while claude-sonnet-4-5 stayed at 200k.
        assertEquals(1_000_000, resolve("claude-sonnet-4-6"));
        assertEquals(200_000, resolve("claude-sonnet-4-5"));
        assertEquals(200_000, resolve("claude-opus-4-5"));
        assertEquals(200_000, resolve("claude-haiku-4-5"));
    }

    @Test
    void resolvesGeminiAndGrokFamilies() {
        assertEquals(1_048_576, resolve("gemini-4-carbon"));
        assertEquals(1_048_576, resolve("gemini-3.1-pro-preview"));
        assertEquals(2_097_152, resolve("gemini-1.5-pro"));
        assertEquals(500_000, resolve("grok-4.7"));
        assertEquals(1_000_000, resolve("grok-4.3"));
        assertEquals(2_000_000, resolve("grok-4-fast-reasoning"));
    }

    @Test
    void resolvesDeepSeekAtVendorPublishedWindow() {
        assertEquals(1_000_000, resolve("deepseek-v4.1-flash"));
        assertEquals(1_000_000, resolve("deepseek-v4-pro"));
        assertEquals(1_000_000, resolve("deepseek-flash"));
    }

    @Test
    void resolvesChineseCompatProviders() {
        assertEquals(1_000_000, resolve("glm-5.3"));
        assertEquals(1_000_000, resolve("minimax-m3"));
        assertEquals(1_048_576, resolve("kimi-k3"));
    }

    @Test
    void longestPrefixWins() {
        assertEquals(400_000, resolve("gpt-5.4-mini"));
        assertEquals(1_050_000, resolve("gpt-5.4"));
        assertEquals(1_050_000, ModelContextWindows.resolve("gpt-5.4", ModelContextWindows.OPENAI));
    }

    @Test
    void resolveStripsProviderNamespaces() {
        assertEquals(
                1_000_000,
                ModelContextWindows.resolve(
                        "deepseek/deepseek-v4.1-flash", ModelContextWindows.DEEPSEEK));
        assertEquals(
                1_000_000,
                ModelContextWindows.resolve(
                        "global.anthropic.claude-opus-5-5", ModelContextWindows.ANTHROPIC));
        assertEquals(
                1_000_000,
                ModelContextWindows.resolve(
                        "claude-sonnet-4-6@20260301", ModelContextWindows.ANTHROPIC));
        assertEquals(
                1_050_000,
                ModelContextWindows.resolve("openai/gpt-6-astra", ModelContextWindows.DASHSCOPE));
    }

    @Test
    void resolveFallsBackToOtherTables() {
        // A model served through a different vendor's compatible endpoint still resolves.
        assertEquals(1_048_576, ModelContextWindows.resolve("kimi-k3", ModelContextWindows.GLM));
        assertEquals(
                500_000, ModelContextWindows.resolve("xai/grok-4.7", ModelContextWindows.OPENAI));
    }

    @Test
    void resolveMatchesVendorPrefixedIdsPublishedByDashScope() {
        // DashScope lists third-party models as "<VENDOR>/<model-id>"; the vendor prefix is
        // ignored so the model-id prefix in its own table decides the window.
        assertEquals(
                1_048_576,
                ModelContextWindows.resolve("kimi/kimi-k3", ModelContextWindows.DASHSCOPE));
        assertEquals(
                262_144,
                ModelContextWindows.resolve("moonshotai/kimi-k2.5", ModelContextWindows.DASHSCOPE));
        assertEquals(
                1_000_000,
                ModelContextWindows.resolve("ZHIPU/GLM-5.3", ModelContextWindows.DASHSCOPE));
        assertEquals(
                1_000_000,
                ModelContextWindows.resolve("MiniMax/MiniMax-M3", ModelContextWindows.DASHSCOPE));
        assertEquals(
                1_000_000,
                ModelContextWindows.resolve(
                        "deepseek/deepseek-v4.1-flash", ModelContextWindows.DASHSCOPE));
        assertEquals(
                1_000_000,
                ModelContextWindows.resolve(
                        "Qwen/Qwen3.5-397B-A17B", ModelContextWindows.DASHSCOPE));
    }

    @Test
    void unknownNameStaysUnknown() {
        assertEquals(0, resolve("some-unreleased-model"));
        assertEquals(
                0,
                ModelContextWindows.resolve("some-unreleased-model", ModelContextWindows.OPENAI));
        assertEquals(0, ModelContextWindows.lookup(null, ModelContextWindows.OPENAI));
        assertEquals(0, ModelContextWindows.lookup("  ", ModelContextWindows.OPENAI));
        assertEquals(0, ModelContextWindows.resolve(null, ModelContextWindows.OPENAI));
        assertEquals(0, ModelContextWindows.resolve("   ", ModelContextWindows.OPENAI));
    }

    @Test
    void noPrefixInOneTableShadowsAPrefixInAnother() {
        for (Map.Entry<String, Map<String, Integer>> left : TABLES.entrySet()) {
            for (Map.Entry<String, Map<String, Integer>> right : TABLES.entrySet()) {
                if (left.getKey().equals(right.getKey())) {
                    continue;
                }
                for (String leftPrefix : left.getValue().keySet()) {
                    for (String rightPrefix : right.getValue().keySet()) {
                        assertFalse(
                                rightPrefix.startsWith(leftPrefix),
                                leftPrefix
                                        + " from "
                                        + left.getKey()
                                        + " shadows "
                                        + rightPrefix
                                        + " from "
                                        + right.getKey());
                    }
                }
            }
        }
    }

    @Test
    void everyTableEntryResolves() {
        for (Map.Entry<String, Map<String, Integer>> table : TABLES.entrySet()) {
            for (Map.Entry<String, Integer> entry : table.getValue().entrySet()) {
                assertTrue(
                        resolve(entry.getKey()) > 0,
                        table.getKey() + " entry " + entry.getKey() + " does not resolve");
            }
        }
    }

    /** Resolves through the cross-table path, i.e. no preferred table. */
    private static int resolve(String modelName) {
        return ModelContextWindows.resolve(modelName, null);
    }
}
