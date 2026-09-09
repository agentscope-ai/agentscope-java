/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Matching modes shared by the memory and session search tools. */
enum SearchMatchMode {
    PHRASE,
    ALL,
    ANY;

    static SearchMatchMode parse(String value) {
        if (value == null || value.isBlank()) {
            return PHRASE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PHRASE;
        }
    }

    boolean matches(String text, String query) {
        if (text == null || query == null) {
            return false;
        }
        if (this == PHRASE) {
            return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE)
                    .matcher(text)
                    .find();
        }
        List<String> keywords =
                Arrays.stream(query.trim().split("\\s+"))
                        .filter(keyword -> !keyword.isEmpty())
                        .toList();
        if (keywords.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        return this == ALL
                ? keywords.stream()
                        .allMatch(keyword -> lowerText.contains(keyword.toLowerCase(Locale.ROOT)))
                : keywords.stream()
                        .anyMatch(keyword -> lowerText.contains(keyword.toLowerCase(Locale.ROOT)));
    }
}
