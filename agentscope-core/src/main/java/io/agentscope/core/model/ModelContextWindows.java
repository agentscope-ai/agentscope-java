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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Known context window sizes for popular models. Used by {@link ChatModelBase} subclasses
 * to infer {@link Model#getContextWindowSize()} from the model name when no explicit
 * value is provided via the builder.
 *
 * <p>Values are total context window (input + output) in tokens and follow the
 * vendor-published figure, so a vendor that advertises "1M tokens" is recorded as
 * {@code 1_000_000} rather than the binary approximation {@code 1_048_576}. Entries are
 * matched via prefix: a model name {@code "qwen3.8-max-latest"} matches the
 * {@code "qwen3.8"} entry. Longer prefixes are checked first so {@code "gpt-5.4-mini"}
 * matches before {@code "gpt-5"}.
 *
 * <p>{@link #resolve(String, Map)} additionally tolerates provider namespaces such as
 * {@code "deepseek/deepseek-v4.1-flash"}, {@code "anthropic.claude-opus-5-5"} or
 * {@code "claude-sonnet-4-6@20260301"}, and falls back to the other tables when the
 * preferred one has no match, so a model served through a compatible endpoint still
 * resolves.
 */
public final class ModelContextWindows {

    private ModelContextWindows() {}

    // @formatter:off

    public static final Map<String, Integer> DASHSCOPE =
            Map.ofEntries(
                    // Qwen3.8 series
                    Map.entry("qwen3.8", 1_000_000),
                    // Qwen3.7 series
                    Map.entry("qwen3.7", 1_000_000),
                    // Qwen3.6 series
                    Map.entry("qwen3.6", 1_000_000),
                    // Qwen3.5 series
                    Map.entry("qwen3.5", 1_000_000),
                    // Qwen3 open-source series
                    Map.entry("qwen3-235b", 131_072),
                    Map.entry("qwen3-32b", 131_072),
                    Map.entry("qwen3-30b", 131_072),
                    Map.entry("qwen3-14b", 131_072),
                    Map.entry("qwen3-8b", 131_072),
                    Map.entry("qwen3-4b", 131_072),
                    Map.entry("qwen3-1.7b", 32_768),
                    Map.entry("qwen3-0.6b", 32_768),
                    // Qwen-Max / Plus / Turbo / Long
                    Map.entry("qwen-max", 32_768),
                    Map.entry("qwen-plus", 131_072),
                    Map.entry("qwen-turbo", 1_000_000),
                    Map.entry("qwen-long", 10_000_000),
                    // Qwen2.5 series
                    Map.entry("qwen2.5", 131_072),
                    // QVQ / QwQ
                    Map.entry("qvq", 131_072),
                    Map.entry("qwq", 131_072));

    public static final Map<String, Integer> OPENAI =
            Map.ofEntries(
                    Map.entry("gpt-6", 1_050_000),
                    Map.entry("gpt-5.6", 1_050_000),
                    Map.entry("gpt-5.5", 1_050_000),
                    Map.entry("gpt-5.4-mini", 400_000),
                    Map.entry("gpt-5.4", 1_050_000),
                    Map.entry("gpt-5.2", 400_000),
                    Map.entry("gpt-5.1", 400_000),
                    Map.entry("gpt-4.1-mini", 1_047_576),
                    Map.entry("gpt-4.1-nano", 1_047_576),
                    Map.entry("gpt-4.1", 1_047_576),
                    Map.entry("gpt-4o-mini", 128_000),
                    Map.entry("gpt-4o", 128_000),
                    Map.entry("gpt-4-turbo", 128_000),
                    Map.entry("o4-mini", 200_000),
                    Map.entry("o3-mini", 200_000),
                    Map.entry("o3", 200_000),
                    Map.entry("o1-mini", 128_000),
                    Map.entry("o1", 200_000));

    public static final Map<String, Integer> DEEPSEEK =
            Map.ofEntries(
                    Map.entry("deepseek-v4.1", 1_000_000),
                    Map.entry("deepseek-v4-pro", 1_000_000),
                    Map.entry("deepseek-v4-flash", 1_000_000),
                    Map.entry("deepseek-flash", 1_000_000));

    public static final Map<String, Integer> GLM =
            Map.ofEntries(
                    Map.entry("glm-5.3", 1_000_000),
                    Map.entry("glm-5.2", 1_000_000),
                    Map.entry("glm-5.1", 200_000),
                    Map.entry("glm-5-turbo", 200_000),
                    Map.entry("glm-5", 200_000),
                    Map.entry("glm-4.7-flashx", 200_000),
                    Map.entry("glm-4.7-flash", 200_000),
                    Map.entry("glm-4.7", 200_000),
                    Map.entry("glm-4.6", 200_000),
                    Map.entry("glm-4.5-airx", 128_000),
                    Map.entry("glm-4.5-air", 128_000),
                    Map.entry("glm-4-flashx-250414", 128_000),
                    Map.entry("glm-4-flash-250414", 128_000),
                    Map.entry("glm-4-long", 1_000_000));

    public static final Map<String, Integer> MINIMAX =
            Map.ofEntries(
                    Map.entry("minimax-m3", 1_000_000),
                    Map.entry("minimax-m2.7-highspeed", 204_800),
                    Map.entry("minimax-m2.7", 204_800),
                    Map.entry("minimax-m2.5-highspeed", 204_800),
                    Map.entry("minimax-m2.5", 204_800),
                    Map.entry("minimax-m2.1-highspeed", 204_800),
                    Map.entry("minimax-m2.1", 204_800),
                    Map.entry("minimax-m2", 204_800),
                    Map.entry("m2-her", 65_536));

    public static final Map<String, Integer> KIMI =
            Map.ofEntries(
                    Map.entry("kimi-k3", 1_048_576),
                    Map.entry("kimi-k2.7-code-highspeed", 262_144),
                    Map.entry("kimi-k2.7-code", 262_144),
                    Map.entry("kimi-k2.6", 262_144),
                    Map.entry("kimi-k2.5", 262_144),
                    Map.entry("moonshot-v1-128k-vision-preview", 131_072),
                    Map.entry("moonshot-v1-128k", 131_072),
                    Map.entry("moonshot-v1-32k-vision-preview", 32_768),
                    Map.entry("moonshot-v1-32k", 32_768),
                    Map.entry("moonshot-v1-8k-vision-preview", 8_192),
                    Map.entry("moonshot-v1-8k", 8_192));

    public static final Map<String, Integer> ANTHROPIC =
            Map.ofEntries(
                    Map.entry("claude-fable-5", 1_000_000),
                    Map.entry("claude-mythos-5", 1_000_000),
                    Map.entry("claude-opus-5", 1_000_000),
                    Map.entry("claude-sonnet-5", 1_000_000),
                    Map.entry("claude-haiku-5", 1_000_000),
                    Map.entry("claude-opus-4-8", 1_000_000),
                    Map.entry("claude-opus-4-7", 1_000_000),
                    Map.entry("claude-opus-4-6", 1_000_000),
                    Map.entry("claude-sonnet-4-6", 1_000_000),
                    Map.entry("claude-opus-4", 200_000),
                    Map.entry("claude-sonnet-4", 200_000),
                    Map.entry("claude-haiku-4", 200_000),
                    Map.entry("claude-3-7", 200_000),
                    Map.entry("claude-3-5", 200_000),
                    Map.entry("claude-3", 200_000));

    public static final Map<String, Integer> GEMINI =
            Map.ofEntries(
                    Map.entry("gemini-4", 1_048_576),
                    Map.entry("gemini-3", 1_048_576),
                    Map.entry("gemini-2.5", 1_048_576),
                    Map.entry("gemini-2.0", 1_048_576),
                    Map.entry("gemini-1.5", 2_097_152));

    public static final Map<String, Integer> GROK =
            Map.ofEntries(
                    Map.entry("grok-4.7", 500_000),
                    Map.entry("grok-4.6", 500_000),
                    Map.entry("grok-4.5", 500_000),
                    Map.entry("grok-4.3", 1_000_000),
                    Map.entry("grok-4.20", 1_000_000),
                    Map.entry("grok-4-fast", 2_000_000),
                    Map.entry("grok-build-0.1", 256_000));

    // @formatter:on

    private static final List<Map<String, Integer>> ALL =
            List.of(DASHSCOPE, OPENAI, DEEPSEEK, GLM, MINIMAX, KIMI, ANTHROPIC, GEMINI, GROK);

    /**
     * Finds the context window size for the given model name from the provided map.
     * Uses prefix matching: longest matching prefix wins.
     *
     * @return context window size, or 0 if no match
     */
    public static int lookup(String modelName, Map<String, Integer> knownModels) {
        if (modelName == null || modelName.isBlank()) {
            return 0;
        }
        String lower = modelName.toLowerCase(Locale.ROOT).trim();
        int bestLen = 0;
        int bestValue = 0;
        for (Map.Entry<String, Integer> entry : knownModels.entrySet()) {
            String prefix = entry.getKey();
            if (lower.startsWith(prefix) && prefix.length() > bestLen) {
                bestLen = prefix.length();
                bestValue = entry.getValue();
            }
        }
        return bestValue;
    }

    /**
     * Resolves the context window size for a model name, tolerating provider namespaces
     * and models served outside their own vendor table.
     *
     * <p>The preferred table is consulted first, then the remaining tables. For every
     * table, the full normalized name is tried first, followed by the suffixes that start
     * after a {@code '/'}, {@code '.'}, {@code '@'} or {@code ':'} separator so that
     * {@code "deepseek/deepseek-v4.1-flash"}, {@code "anthropic.claude-opus-5-5"} and
     * {@code "claude-sonnet-4-6@20260301"} all match.
     *
     * @return context window size, or 0 if no table matches
     */
    public static int resolve(String modelName, Map<String, Integer> preferred) {
        if (modelName == null || modelName.isBlank()) {
            return 0;
        }
        List<String> names = nameVariants(modelName);
        if (preferred != null) {
            for (String name : names) {
                int size = lookup(name, preferred);
                if (size > 0) {
                    return size;
                }
            }
        }
        for (Map<String, Integer> table : ALL) {
            if (table == preferred) {
                continue;
            }
            for (String name : names) {
                int size = lookup(name, table);
                if (size > 0) {
                    return size;
                }
            }
        }
        return 0;
    }

    private static List<String> nameVariants(String modelName) {
        String lower = modelName.toLowerCase(Locale.ROOT).trim();
        List<String> names = new ArrayList<>();
        names.add(lower);
        for (int i = 1; i < lower.length(); i++) {
            char previous = lower.charAt(i - 1);
            if (previous == '/' || previous == '.' || previous == '@' || previous == ':') {
                names.add(lower.substring(i));
            }
        }
        return names;
    }
}
