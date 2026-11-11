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

package io.agentscope.extensions.judge.jev.supervision;

import java.util.List;
import java.util.Objects;

/** Host-supplied evidence. The host enforces access, redaction and revision consistency. */
public record SupervisionEvidence(
        String revision,
        String repositoryStatus,
        String diff,
        List<String> changedFiles,
        String instructions,
        boolean documentationRequired,
        Verification verification) {
    public SupervisionEvidence {
        Objects.requireNonNull(revision);
        Objects.requireNonNull(repositoryStatus);
        Objects.requireNonNull(diff);
        changedFiles = List.copyOf(changedFiles);
        Objects.requireNonNull(instructions);
    }

    /** Scope and revision must come from the verifier, never a worker's completion claim. */
    public record Verification(
            String userId,
            String sessionId,
            String runId,
            String revision,
            String source,
            boolean passed,
            String summary) {
        public Verification {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(revision);
            Objects.requireNonNull(source);
            Objects.requireNonNull(summary);
        }
    }

    /** An absent provider is explicit missing evidence, not successful verification. */
    public static SupervisionEvidence missing() {
        return new SupervisionEvidence("", "", "", List.of(), "", false, null);
    }
}
