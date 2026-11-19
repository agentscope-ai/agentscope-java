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
package io.agentscope.core.state;

import java.util.Objects;

/** One bounded check result. PASSED is not a verdict on overall task completion. */
public record TaskVerification(
        String id,
        String requirementId,
        String actionId,
        String verifierId,
        Outcome outcome,
        String reason,
        EvidenceBinding binding,
        long recordedAt)
        implements State {
    public enum Outcome {
        PASSED,
        FAILED,
        UNKNOWN,
        ERROR
    }

    public TaskVerification {
        TaskRequirement.requireText(id, "verification ID", 200);
        TaskRequirement.requireText(requirementId, "requirement ID", 200);
        TaskRequirement.requireText(actionId, "action ID", 200);
        TaskRequirement.requireText(verifierId, "verifier ID", 200);
        TaskRequirement.requireText(reason, "reason", 2000);
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(binding, "binding");
    }
}
