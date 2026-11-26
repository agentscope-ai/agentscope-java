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

import io.agentscope.core.model.Model;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers context windows learned from provider overflow errors.
 *
 * <p>Models whose window is unknown ({@link Model#getContextWindowSize()} returns {@code 0})
 * fall back to conservative budgets. When such a model overflows anyway, the provider error
 * usually states the real limit; {@link #recordOverflow(Model, Throwable)} parses it and
 * {@link #effectiveWindow(Model)} feeds it back to the compaction and input budgets, so the
 * same model does not keep overflowing.
 *
 * <p>A declared window always wins: learning only applies while the model reports {@code 0}.
 * Repeated overflows keep the smaller parsed value, and at most {@value #MAX_ENTRIES} models
 * are remembered per instance, evicting the least recently used one.
 */
public final class LearnedContextWindows {

    private static final Logger log = LoggerFactory.getLogger(LearnedContextWindows.class);

    /** Upper bound on remembered models, so a long-lived agent cannot grow without limit. */
    static final int MAX_ENTRIES = 64;

    /** Below this, the number is a sub-limit (max_tokens, article counts), not a window. */
    private static final int MIN_PLAUSIBLE_TOKENS = 1_024;

    private static final List<Pattern> WINDOW_PATTERNS =
            List.of(
                    // Anthropic: "210000 tokens > 200000 maximum", "210000 + 8192 > 200000"
                    Pattern.compile(">\\s*([\\d,_]+)"),
                    // OpenAI, DeepSeek: "maximum context length is 128000 tokens"
                    Pattern.compile("maximum context (?:length|window|size)[^\\d]{0,12}([\\d,_]+)"),
                    // Gemini: "maximum number of tokens allowed (1048576)"
                    Pattern.compile(
                            "maximum (?:number of )?tokens? (?:allowed|per (?:request|prompt|call))"
                                    + "[^\\d]{0,24}([\\d,_]+)"),
                    // Generic: "context window: 200000", "context limit of 131072"
                    Pattern.compile("context (?:window|length|limit)[^\\d]{0,12}([\\d,_]+)"),
                    // Generic: "131072 tokens maximum"
                    Pattern.compile("([\\d,_]+)\\s*tokens?\\s*(?:maximum|allowed|limit)"),
                    // Generic: "maximum input length 131072 tokens"
                    Pattern.compile(
                            "(?:max(?:imum)?|limit|allowed)[^\\d\\n]{0,32}([\\d,_]+)\\s*tokens?"),
                    // DashScope: "Range of input length should be [1, 131072]"
                    Pattern.compile("\\[\\s*\\d+\\s*,\\s*([\\d,_]+)\\s*\\]"),
                    // "input length 200000 exceeds max input length 131072"
                    Pattern.compile("exceeds[^\\d\\n]{0,40}([\\d,_]+)"));

    private final Map<String, Integer> learned =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /**
     * Returns the window the model declares, else the one learned from a previous overflow.
     *
     * @return window in tokens, or 0 when neither is known
     */
    public synchronized int effectiveWindow(Model model) {
        if (model == null) {
            return 0;
        }
        int declared = model.getContextWindowSize();
        if (declared > 0) {
            return declared;
        }
        Integer remembered = learned.get(key(model.getModelName()));
        return remembered != null ? remembered : 0;
    }

    /**
     * Learns the model's context window from an overflow error, when the error states it.
     * Ignored when the model already declares a window or the message carries no usable
     * number.
     */
    public void recordOverflow(Model model, Throwable error) {
        if (model == null || error == null || model.getContextWindowSize() > 0) {
            return;
        }
        String name = model.getModelName();
        if (name == null || name.isBlank()) {
            return;
        }
        String message = describe(error);
        int window = parseWindow(message);
        if (window <= 0) {
            log.warn(
                    "Context overflow for model '{}' but the provider error states no token"
                            + " window; keeping the fallback budget. Error: {}",
                    name,
                    abbreviate(message));
            return;
        }
        String key = key(name);
        synchronized (this) {
            Integer existing = learned.get(key);
            int known = existing != null ? existing : 0;
            int next = known > 0 ? Math.min(known, window) : window;
            if (known == next) {
                return;
            }
            learned.put(key, next);
            if (known == 0) {
                log.info(
                        "Learned a context window of {} tokens for model '{}' from a provider"
                                + " overflow error",
                        next,
                        name);
            } else {
                log.info(
                        "Lowered the learned context window for model '{}' from {} to {} tokens",
                        name,
                        known,
                        next);
            }
        }
    }

    /** Remembered window for a model name, 0 when absent. Test/diagnostic visibility. */
    synchronized int remembered(String modelName) {
        Integer value = learned.get(key(modelName));
        return value != null ? value : 0;
    }

    /** Drops everything learned. Test visibility. */
    synchronized void clear() {
        learned.clear();
    }

    static int parseWindow(String message) {
        if (message == null || message.isBlank()) {
            return 0;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (Pattern pattern : WINDOW_PATTERNS) {
            Matcher matcher = pattern.matcher(lower);
            while (matcher.find()) {
                int value = toTokens(matcher.group(1));
                if (value >= MIN_PLAUSIBLE_TOKENS) {
                    return value;
                }
            }
        }
        return 0;
    }

    private static int toTokens(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            long value = Long.parseLong(raw.replace(",", "").replace("_", ""));
            return value > 0 && value <= Integer.MAX_VALUE ? (int) value : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String key(String modelName) {
        return modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
    }

    /** Joins the messages along the cause chain; providers often wrap the API error. */
    private static String describe(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                if (messages.length() > 0) {
                    messages.append(" | ");
                }
                messages.append(message);
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return messages.toString();
    }

    private static String abbreviate(String message) {
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }
}
