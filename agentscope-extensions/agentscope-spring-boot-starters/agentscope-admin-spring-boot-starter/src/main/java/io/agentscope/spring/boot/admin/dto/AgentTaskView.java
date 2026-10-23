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
package io.agentscope.spring.boot.admin.dto;

import io.agentscope.core.state.Task;
import java.util.List;
import java.util.Map;

/**
 * Read-only wire view of {@link Task} (the per-{@link io.agentscope.core.state.AgentState} task
 * record — the TodoWrite-style work-item list that an agent maintains for itself).
 *
 * <p>Distinct from {@link SubagentTaskView}, which describes a {@code BackgroundTask} dispatched
 * to a subagent via {@code TaskRepository}.
 */
public record AgentTaskView(
        String id,
        String subject,
        String description,
        String state,
        String owner,
        String createdAt,
        List<String> blocks,
        List<String> blockedBy,
        Map<String, Object> metadata) {

    public static AgentTaskView of(Task t) {
        if (t == null) return null;
        return new AgentTaskView(
                t.getId(),
                t.getSubject(),
                t.getDescription(),
                t.getState() == null ? null : t.getState().name(),
                t.getOwner(),
                t.getCreatedAt(),
                t.getBlocks(),
                t.getBlockedBy(),
                t.getMetadata());
    }
}
