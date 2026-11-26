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
package io.agentscope.extensions.model.openai.formatter;

import java.util.regex.Pattern;

/**
 * Shared rules for names that OpenAI-compatible APIs constrain to {@code
 * ^[a-zA-Z0-9_-]{1,64}$} (message names and function/tool names).
 *
 * <p>The character set and length limit are defined here so that the one-way message-name
 * sanitizer and the reversible tool-name codec cannot drift apart. The two callers keep their
 * own truncation policy: message names are simply truncated, while tool names get a
 * deterministic hash suffix so that different long names stay distinguishable.
 */
final class OpenAINameRules {

    static final int MAX_LENGTH = 64;

    /** Fallback for a name that contains nothing but illegal characters. */
    static final String EMPTY_FALLBACK = "agent";

    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");

    private OpenAINameRules() {}

    /** Returns whether the name already satisfies the pattern (null is invalid). */
    static boolean isValid(String name) {
        return name != null
                && !name.isEmpty()
                && name.length() <= MAX_LENGTH
                && !ILLEGAL_CHARS.matcher(name).find();
    }

    /** Replaces every illegal character with {@code '_'}, falling back for an empty result. */
    static String replaceIllegalChars(String name) {
        String replaced = ILLEGAL_CHARS.matcher(name).replaceAll("_");
        return replaced.isEmpty() ? EMPTY_FALLBACK : replaced;
    }

    /** Truncates to the maximum length. */
    static String truncate(String name) {
        return name.length() > MAX_LENGTH ? name.substring(0, MAX_LENGTH) : name;
    }

    /**
     * Truncates to the maximum length while appending a deterministic suffix derived from the
     * original name, so two different long names keep distinct wire names.
     */
    static String truncateWithHash(String name, String hashSource) {
        String suffix = String.format("%08x", hashSource.hashCode());
        return name.substring(0, MAX_LENGTH - suffix.length() - 1) + "_" + suffix;
    }
}
