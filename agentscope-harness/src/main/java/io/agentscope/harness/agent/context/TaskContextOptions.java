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

/** Opt-in task projections and candidate tool. Does not run verifiers or enforce completion. */
public record TaskContextOptions(
        boolean requirements, boolean verificationResults, boolean requirementProposals) {
    public TaskContextOptions {
        if (requirementProposals && !requirements)
            throw new IllegalArgumentException(
                    "Requirement proposals require requirement projection");
    }

    public static TaskContextOptions disabled() {
        return new TaskContextOptions(false, false, false);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean requirements;
        private boolean verificationResults;
        private boolean requirementProposals;

        public Builder includeRequirements() {
            requirements = true;
            return this;
        }

        public Builder includeVerificationResults() {
            verificationResults = true;
            return this;
        }

        /** Also enables candidate requirement projection so proposals remain visible. */
        public Builder allowRequirementProposals() {
            requirementProposals = true;
            requirements = true;
            return this;
        }

        public TaskContextOptions build() {
            return new TaskContextOptions(requirements, verificationResults, requirementProposals);
        }
    }
}
