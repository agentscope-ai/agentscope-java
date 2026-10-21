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

/** Task authorization outlives a paused Harness activation and is fenced independently. */
public record ToolTask(
        RuntimeRequestContext context,
        String taskId,
        String toolCallId,
        String environmentId,
        String sandboxScope,
        String workspaceRef,
        String authorizationGeneration,
        String name,
        Map<String, Object> input,
        long timeoutMs,
        String retryKind) {
    private static final Set<String> RETRY_KINDS =
            Set.of("read_only", "idempotent", "non_idempotent");

    public ToolTask {
        Objects.requireNonNull(context, "context");
        RuntimeProtocol.requireId(context.turnId(), "turnId");
        RuntimeProtocol.requireId(taskId, "taskId");
        RuntimeProtocol.requireId(toolCallId, "toolCallId");
        RuntimeProtocol.requireId(environmentId, "environmentId");
        RuntimeProtocol.requireId(sandboxScope, "sandboxScope");
        RuntimeProtocol.requireId(workspaceRef, "workspaceRef");
        RuntimeProtocol.requireId(authorizationGeneration, "authorizationGeneration");
        RuntimeProtocol.requireId(name, "name");
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        if (!RETRY_KINDS.contains(retryKind))
            throw new IllegalArgumentException("Unsupported retryKind");
        input = RuntimeProtocol.freeze(input);
    }
}
