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
package io.agentcore.managed.server.runtime.protocol;

import java.util.Objects;

/** Requested only after the runtime acquires its exclusive Session execution lease. */
public record RuntimeExecutionRequest(
        RuntimeRequestContext context,
        String inputDigest,
        long admissionSequence,
        long executionGeneration,
        String executionFence,
        boolean renewal,
        String activationCommandId) {
    public RuntimeExecutionRequest(
            RuntimeRequestContext context,
            String inputDigest,
            long admissionSequence,
            long executionGeneration,
            String executionFence,
            boolean renewal) {
        this(
                context,
                inputDigest,
                admissionSequence,
                executionGeneration,
                executionFence,
                renewal,
                context.commandId());
    }

    public RuntimeExecutionRequest {
        Objects.requireNonNull(context, "context");
        RuntimeProtocol.requireId(context.turnId(), "turnId");
        RuntimeProtocol.requireId(inputDigest, "inputDigest");
        RuntimeProtocol.requireId(executionFence, "executionFence");
        RuntimeProtocol.requireId(activationCommandId, "activationCommandId");
        if (admissionSequence <= 0 || executionGeneration <= 0)
            throw new IllegalArgumentException(
                    "Admission and execution generations must be positive");
    }
}
