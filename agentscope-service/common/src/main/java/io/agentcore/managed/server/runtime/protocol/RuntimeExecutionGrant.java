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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Permission lease for one activation; it neither starts execution nor changes public Turn state. */
public record RuntimeExecutionGrant(
        RuntimeRequestContext context,
        long executionGeneration,
        String executionFence,
        long validUntil,
        @JsonInclude(JsonInclude.Include.NON_NULL) RuntimeConfigurationOverlay configurationOverlay,
        @JsonInclude(JsonInclude.Include.NON_NULL) RuntimeBudgetSettings budgetSettings) {
    public RuntimeExecutionGrant(
            RuntimeRequestContext context,
            long executionGeneration,
            String executionFence,
            long validUntil,
            RuntimeConfigurationOverlay configurationOverlay) {
        this(context, executionGeneration, executionFence, validUntil, configurationOverlay, null);
    }

    public RuntimeExecutionGrant(
            RuntimeRequestContext context,
            long executionGeneration,
            String executionFence,
            long validUntil) {
        this(context, executionGeneration, executionFence, validUntil, null, null);
    }

    public RuntimeExecutionGrant {
        Objects.requireNonNull(context, "context");
        RuntimeProtocol.requireId(context.turnId(), "turnId");
        RuntimeProtocol.requireId(executionFence, "executionFence");
        if (executionGeneration <= 0
                || validUntil <= 0
                || validUntil > context.permissionExpiresAt())
            throw new IllegalArgumentException("Invalid execution grant validity or generation");
    }
}
