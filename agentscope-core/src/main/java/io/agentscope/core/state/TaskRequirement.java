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

/** Immutable requirement. Confirmation establishes authority, not satisfaction or verification. */
public record TaskRequirement(
        String id,
        Kind kind,
        String text,
        String proposedSourceRef,
        Status status,
        Decision decision) {
    public enum Kind {
        CONSTRAINT,
        ACCEPTANCE_CRITERION
    }

    public enum Status {
        CANDIDATE,
        CONFIRMED,
        REJECTED
    }

    public enum Authority {
        USER,
        CALLER
    }

    /** Supplied by trusted application code after an explicit decision, never by a model tool. */
    public record Decision(Authority authority, String sourceRef) {
        public Decision {
            Objects.requireNonNull(authority, "authority");
            requireText(sourceRef, "decision source", 2000);
        }
    }

    public TaskRequirement {
        requireText(id, "id", 200);
        Objects.requireNonNull(kind, "kind");
        requireText(text, "requirement", 2000);
        requireText(proposedSourceRef, "proposed source", 2000);
        Objects.requireNonNull(status, "status");
        if ((status == Status.CANDIDATE) != (decision == null)) {
            throw new IllegalArgumentException(
                    "Only decided requirements must have a decision source");
        }
    }

    static void requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " must be nonblank and at most " + maxLength + " characters");
        }
    }
}
