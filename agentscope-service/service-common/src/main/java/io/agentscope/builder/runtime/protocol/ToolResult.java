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
package io.agentscope.builder.runtime.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Durable Worker result; unknown non-idempotent side effects require explicit reconciliation. */
public record ToolResult(
        RuntimeRequestContext context,
        String taskId,
        String authorizationGeneration,
        String claimToken,
        String status,
        Map<String, Object> output,
        String artifactRef) {
    private static final Set<String> STATUSES =
            Set.of("succeeded", "failed", "cancelled", "unknown");

    public ToolResult {
        Objects.requireNonNull(context, "context");
        RuntimeProtocol.requireId(taskId, "taskId");
        RuntimeProtocol.requireId(authorizationGeneration, "authorizationGeneration");
        RuntimeProtocol.requireId(claimToken, "claimToken");
        if (!STATUSES.contains(status))
            throw new IllegalArgumentException("Unsupported result status");
        output = RuntimeProtocol.freeze(output);
    }
}
