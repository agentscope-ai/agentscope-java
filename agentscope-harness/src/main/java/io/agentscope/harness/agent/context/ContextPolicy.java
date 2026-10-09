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

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Per-agent context policy. Zero limits use the actual model's window; no fixed layer quotas. */
public record ContextPolicy(
        int maxInputTokens,
        int reservedOutputTokens,
        int safetyMarginTokens,
        ContextTokenEstimator estimator,
        Consumer<ContextManifest> observer) {
    public ContextPolicy {
        if (maxInputTokens < 0 || reservedOutputTokens < 0 || safetyMarginTokens < 0) {
            throw new IllegalArgumentException("Context limits must be nonnegative");
        }
        Objects.requireNonNull(estimator);
        Objects.requireNonNull(observer);
    }

    public static ContextPolicy defaults() {
        return new ContextPolicy(0, 0, 0, ContextTokenEstimator.approximate(), manifest -> {});
    }

    /** Serializable numeric configuration; executable estimators/observers remain application-owned. */
    public static ContextPolicy fromMap(Map<?, ?> values) {
        for (Object key : values.keySet()) {
            if (!Set.of("maxInputTokens", "reservedOutputTokens", "safetyMarginTokens")
                    .contains(key)) {
                throw new IllegalArgumentException("Unknown context policy field: " + key);
            }
        }
        return new ContextPolicy(
                limit(values, "maxInputTokens"),
                limit(values, "reservedOutputTokens"),
                limit(values, "safetyMarginTokens"),
                ContextTokenEstimator.approximate(),
                manifest -> {});
    }

    private static int limit(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) return 0;
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()
                || number.longValue() < 0
                || number.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be a nonnegative integer");
        }
        return ((Number) value).intValue();
    }
}
