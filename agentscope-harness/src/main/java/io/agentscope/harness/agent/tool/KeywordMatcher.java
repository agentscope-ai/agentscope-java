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
package io.agentscope.harness.agent.tool;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Combines literal matchers without changing each tool's existing case-matching semantics. */
final class KeywordMatcher {
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private KeywordMatcher() {}

    static Predicate<String> compile(
            String query, String matchMode, Function<String, Predicate<String>> literalMatcher) {
        String mode = matchMode == null ? "phrase" : matchMode;
        if (mode.equals("phrase")) {
            return literalMatcher.apply(query);
        }
        if (!mode.equals("all") && !mode.equals("any")) {
            throw new IllegalArgumentException("matchMode must be one of: phrase, all, any");
        }
        List<Predicate<String>> terms =
                Arrays.stream(WHITESPACE.split(query))
                        .filter(term -> !term.isEmpty())
                        .distinct()
                        .map(literalMatcher)
                        .toList();
        // Do not let an empty ALL query match every record.
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("query must contain at least one keyword");
        }
        return mode.equals("all")
                ? text -> terms.stream().allMatch(term -> term.test(text))
                : text -> terms.stream().anyMatch(term -> term.test(text));
    }
}
