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

import java.util.Map;
import java.util.Set;

/** A tool source has its own sequence; it never allocates a Core SessionLog sequence. */
public record ManagedToolEvent(
        ManagedScope scope,
        String taskId,
        String executionId,
        long sourceSeq,
        String type,
        Map<String, Object> data) {
    public ManagedToolEvent {
        if (scope == null || sourceSeq < 1 || data == null)
            throw new IllegalArgumentException("Tool source identity required");
        ManagedScope.requireText(taskId, "task id");
        ManagedScope.requireText(executionId, "execution id");
        if (!Set.of("tool.delta", "tool.progress", "tool.output_gap", "tool.result").contains(type))
            throw new IllegalArgumentException("Invalid tool source event type");
        data = Map.copyOf(data);
    }

    public String eventId() {
        return "tool_event_"
                + ManagedScope.fingerprint(
                        scope.messageGroup(), taskId, executionId, String.valueOf(sourceSeq));
    }
}
