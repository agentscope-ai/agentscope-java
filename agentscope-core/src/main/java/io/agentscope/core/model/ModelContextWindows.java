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

import java.util.Map;

/**
 * Known context window sizes for popular models. Used by {@link ChatModelBase} subclasses
 * to infer {@link Model#getContextWindowSize()} from the model name when no explicit
 * value is provided via the builder.
 *
 * <p>Values are total context window (input + output) in tokens. Entries are matched
 * via prefix: a model name {@code "qwen-max-latest"} matches the {@code "qwen-max"} entry.
 * Longer prefixes are checked first so {@code "gpt-4.1-mini"} matches before {@code "gpt-4.1"}.
 */
public final class ModelContextWindows {

    private ModelContextWindows() {}

    // @formatter:off

    public static final Map<String, Integer> DASHSCOPE =
            Map.ofEntries(
                    // Qwen3 series
                    Map.entry("qwen3-235b", 131_072),
                    Map.entry("qwen3-32b", 131_072),
                    Map.entry("qwen3-30b", 131_072),
                    Map.entry("qwen3-14b", 131_072),
                    Map.entry("qwen3-8b", 131_072),
                    Map.entry("qwen3-4b", 131_072),
                    Map.entry("qwen3-1.7b", 32_768),
                    Map.entry("qwen3-0.6b", 32_768),
                    // Qwen3.7 series
                    Map.entry("qwen3.7", 131_072),
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

    public static final Map<String, Integer> ANTHROPIC =
            Map.ofEntries(
                    Map.entry("claude-opus-4", 200_000),
                    Map.entry("claude-sonnet-4", 200_000),
                    Map.entry("claude-haiku-4", 200_000),
                    Map.entry("claude-3-7", 200_000),
                    Map.entry("claude-3-5", 200_000),
                    Map.entry("claude-3", 200_000));

    public static final Map<String, Integer> GEMINI =
            Map.ofEntries(
                    Map.entry("gemini-2.5", 1_048_576),
                    Map.entry("gemini-2.0", 1_048_576),
                    Map.entry("gemini-1.5", 2_097_152));

    // @formatter:on

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
        String lower = modelName.toLowerCase();
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
}
