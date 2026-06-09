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

import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import java.time.Instant;

/**
 * Wire view of a {@link BackgroundTask} dispatched to a subagent. Distinct from
 * {@link AgentTaskView}, which is the in-{@code AgentState} TodoWrite-style record.
 *
 * @param taskId backend-assigned task identifier
 * @param subagentId the subagent type executing the task
 * @param status one of the {@link io.agentscope.harness.agent.subagent.task.TaskStatus} names
 * @param createdAt task submission time
 * @param lastCheckedAt last status read time
 * @param completed convenience flag
 * @param resultPreview first 500 chars of the result when {@code completed}, otherwise null
 * @param errorMessage non-null when the task failed
 */
public record SubagentTaskView(
        String taskId,
        String subagentId,
        String status,
        Instant createdAt,
        Instant lastCheckedAt,
        boolean completed,
        String resultPreview,
        String errorMessage) {

    public static SubagentTaskView of(BackgroundTask t) {
        if (t == null) return null;
        String preview = null;
        String error = null;
        if (t.isCompleted()) {
            String r = t.getResult();
            if (r != null && !r.isEmpty()) {
                preview = r.length() > 500 ? r.substring(0, 500) + "…" : r;
            }
            Exception e = t.getError();
            if (e != null) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        return new SubagentTaskView(
                t.getTaskId(),
                t.getAgentId(),
                t.getTaskStatus() == null ? "UNKNOWN" : t.getTaskStatus().name(),
                t.getCreatedAt(),
                t.getLastCheckedAt(),
                t.isCompleted(),
                preview,
                error);
    }
}
