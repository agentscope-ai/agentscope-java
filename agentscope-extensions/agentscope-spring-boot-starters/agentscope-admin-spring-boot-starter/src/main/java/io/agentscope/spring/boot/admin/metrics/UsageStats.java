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
package io.agentscope.spring.boot.admin.metrics;

/**
 * Immutable snapshot of accumulated usage counters for a single bucket (agent or model).
 *
 * <p>{@code totalTokens} mirrors {@link io.agentscope.core.model.ChatUsage#getTotalTokens()} —
 * {@code inputTokens + outputTokens} when both are reported; some providers report only the sum.
 */
public record UsageStats(
        String key, long calls, long inputTokens, long outputTokens, long totalTokens) {

    public static UsageStats zero(String key) {
        return new UsageStats(key, 0L, 0L, 0L, 0L);
    }
}
