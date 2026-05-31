/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.model.RunAgentInput;
import java.util.Objects;

/**
 * Context for AG-UI snapshot requests.
 *
 * @param agentId The resolved agent ID
 * @param threadId The thread ID to inspect
 * @param runId The run ID for the snapshot request
 * @param input The original AG-UI input
 */
public record AguiSnapshotRequest(
        String agentId, String threadId, String runId, RunAgentInput input) {

    public AguiSnapshotRequest {
        agentId = Objects.requireNonNull(agentId, "agentId cannot be null");
        threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        runId = Objects.requireNonNull(runId, "runId cannot be null");
        input = Objects.requireNonNull(input, "input cannot be null");
    }
}
