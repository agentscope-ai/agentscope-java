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

package io.agentscope.extensions.judge.jev.evaluation;

import java.util.Map;
import java.util.Objects;

/** Classification labels are preserved; passed is absent unless a metric defines a policy. */
public record JevMetricResult(
        Status status,
        Double score,
        Boolean passed,
        String label,
        String reason,
        Map<String, Object> evidence) {
    public enum Status {
        DECIDED,
        INCONCLUSIVE,
        SKIPPED,
        ERROR
    }

    public JevMetricResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(reason);
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 1))
            throw new IllegalArgumentException("score must be in [0,1]");
        if (status != Status.DECIDED && passed != null)
            throw new IllegalArgumentException("non-decisions cannot pass or fail");
        evidence = TraceData.snapshot(evidence);
    }

    public static JevMetricResult skipped(String reason) {
        return new JevMetricResult(Status.SKIPPED, null, null, null, reason, Map.of());
    }

    public static JevMetricResult error(String reason) {
        return new JevMetricResult(Status.ERROR, null, null, null, reason, Map.of());
    }
}
