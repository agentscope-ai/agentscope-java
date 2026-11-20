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
package io.agentscope.harness.agent.memory;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Optional, snapshot-scoped fact admission. Errors leave source progress unchanged. */
@FunctionalInterface
public interface MemoryAdmission {
    Mono<Decision> assess(Input input);

    /**
     * Owner is the user ID, or an anonymous session scope. Source is the new batch,
     * not the read-only context or a per-sentence citation.
     */
    record Input(String owner, String candidate, String source, List<String> existing) {
        public Input {
            if (owner == null
                    || owner.isBlank()
                    || candidate == null
                    || candidate.isBlank()
                    || source == null
                    || source.isBlank())
                throw new IllegalArgumentException(
                        "Memory admission requires owner, candidate and source");
            existing = List.copyOf(existing);
        }
    }

    /** A rejection is a completed decision; uncertainty must be an error, not a rejection. */
    record Decision(boolean accepted, String policyVersion, Map<String, Double> probabilities) {
        public Decision {
            if (policyVersion == null || policyVersion.isBlank())
                throw new IllegalArgumentException("Memory admission policy version is required");
            probabilities = Map.copyOf(probabilities);
            if (probabilities.values().stream()
                    .anyMatch(p -> !Double.isFinite(p) || p < 0 || p > 1))
                throw new IllegalArgumentException("Invalid admission probability");
        }
    }
}
