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
package io.agentcore.managed.server.runtime.managed;

import java.util.List;
import java.util.Map;

/** Immutable, private model outcome. Ordered responses include structured content and tool calls. */
public record ModelCallResult(
        int schemaVersion,
        String scope,
        String turnId,
        String modelCallId,
        String model,
        String purpose,
        String requestHash,
        RuntimeObjectStore.Reference request,
        long requestSequence,
        long completionSequence,
        long entryCheckpointSequence,
        String status,
        List<Map<String, Object>> responses,
        Map<String, Object> completion) {
    public ModelCallResult {
        if (schemaVersion != 1
                || requestSequence < 1
                || completionSequence <= requestSequence
                || entryCheckpointSequence < 0
                || !List.of("completed", "failed", "cancelled").contains(status)
                || !scope.equals(request.scope())
                || !"model-request".equals(request.kind()))
            throw new IllegalArgumentException("Invalid model result");
        ManagedScope.requireText(turnId, "turn id");
        ManagedScope.requireText(modelCallId, "model call id");
        if (requestHash == null || !requestHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid model request hash");
        responses = List.copyOf(responses);
        completion = Map.copyOf(completion);
    }
}
