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
package io.agentscope.core.tool.search;

import io.agentscope.core.model.ToolSchema;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** Dependency-free, case-insensitive matching of query terms against names and descriptions. */
public final class KeywordToolSearchStrategy implements ToolSearchStrategy {
    @Override
    public Mono<ToolSearchResult> search(ToolSearchRequest request) {
        return Mono.fromSupplier(
                () -> {
                    var terms =
                            Arrays.stream(
                                            request.query()
                                                    .toLowerCase(Locale.ROOT)
                                                    .split("[^\\p{L}\\p{N}]+"))
                                    .filter(term -> !term.isBlank())
                                    .distinct()
                                    .toList();
                    List<String> expandedTerms = expandCjkBigrams(terms);
                    var matches =
                            request.candidates().stream()
                                    .map(tool -> new Match(tool, score(tool, expandedTerms)))
                                    .filter(match -> match.score() > 0)
                                    .sorted(
                                            Comparator.comparingInt(Match::score)
                                                    .reversed()
                                                    .thenComparing(match -> match.tool().getName()))
                                    .limit(request.limit())
                                    .map(match -> match.tool().getName())
                                    .toList();
                    return new ToolSearchResult(matches, null, Map.of());
                });
    }

    /**
     * Adds the Han-character bigrams of every term carrying three or more Han code points. The
     * splitter treats a natural Chinese query as one long term (CJK letters are not separators),
     * so whole-phrase {@code contains()} never matches a catalog entry described with a shorter
     * phrase; overlapping bigrams give those queries substring recall without a tokenizer
     * dependency. Windows are built from code points (supplementary-plane Han spans two UTF-16
     * chars) and only between two adjacent Han code points, so Latin fragments of a mixed term
     * never become matching units. Two-Han terms are already the smallest matching unit, and
     * non-CJK terms keep their exact-substring semantics.
     */
    private static List<String> expandCjkBigrams(List<String> terms) {
        if (terms.stream().noneMatch(KeywordToolSearchStrategy::containsHan)) {
            return terms;
        }
        Set<String> expanded = new LinkedHashSet<>(terms);
        for (String term : terms) {
            int[] codePoints = term.codePoints().toArray();
            long hanCount =
                    Arrays.stream(codePoints).filter(KeywordToolSearchStrategy::isHan).count();
            if (hanCount < 3) {
                continue;
            }
            for (int i = 0; i + 1 < codePoints.length; i++) {
                if (isHan(codePoints[i]) && isHan(codePoints[i + 1])) {
                    expanded.add(new String(codePoints, i, 2));
                }
            }
        }
        return List.copyOf(expanded);
    }

    private static boolean containsHan(String term) {
        return term.codePoints().anyMatch(KeywordToolSearchStrategy::isHan);
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static int score(ToolSchema tool, List<String> terms) {
        String name = tool.getName().toLowerCase(Locale.ROOT);
        String description = tool.getDescription().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (name.contains(term)) score += 3;
            if (description.contains(term)) score++;
        }
        return score;
    }

    private record Match(ToolSchema tool, int score) {}
}
