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
package io.agentscope.harness.agent.verification;

import io.agentscope.core.state.TaskVerification;
import io.agentscope.harness.agent.observation.StoredActionObservation;
import java.util.Objects;

/** Trusted, synchronous, bounded, side-effect-free check; never an LLM or command runner. */
public interface DeterministicVerifier {
    String id();

    Verdict verify(StoredActionObservation evidence);

    record Verdict(TaskVerification.Outcome outcome, String reason) {
        public Verdict {
            Objects.requireNonNull(outcome, "outcome");
            if (reason == null || reason.isBlank() || reason.length() > 2000) {
                throw new IllegalArgumentException("Bounded nonblank reason required");
            }
        }
    }
}
