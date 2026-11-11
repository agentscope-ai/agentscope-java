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
package io.agentscope.core.agent;

import java.util.Objects;

/** Immutable correlation shared by a live execution and its committed session facts. */
public record ExecutionIdentity(String agentId, String sessionId, String turnId, String runId) {
    public static final String CONTEXT_KEY = "agentscope.execution.identity";

    public ExecutionIdentity {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(runId, "runId");
        if (agentId.isBlank() || sessionId.isBlank() || turnId.isBlank() || runId.isBlank())
            throw new IllegalArgumentException("Execution identity must not be blank");
    }
}
