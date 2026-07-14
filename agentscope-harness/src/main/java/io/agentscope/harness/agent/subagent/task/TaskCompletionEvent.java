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
package io.agentscope.harness.agent.subagent.task;

import io.agentscope.core.agent.RuntimeContext;

/**
 * Immutable snapshot delivered to {@link WorkspaceTaskRepository.TaskCompletionCallback} when a
 * background subagent task reaches a terminal state ({@link TaskStatus#COMPLETED},
 * {@link TaskStatus#FAILED}, or {@link TaskStatus#CANCELLED}).
 *
 * <p>Replaces the former bare-parameter callback so that consumers receive the authoritative
 * terminal status and error message without needing to re-read the persisted {@link TaskRecord}.
 *
 * @param rc            runtime context of the originating call; may be {@code null}
 * @param taskId        task identifier
 * @param subAgentId    which subagent type executed this task
 * @param sessionId     parent session scope
 * @param status        terminal status — one of COMPLETED / FAILED / CANCELLED
 * @param result        completion payload; {@code null} for FAILED / CANCELLED
 * @param errorMessage  failure reason; {@code null} for COMPLETED / CANCELLED
 */
public record TaskCompletionEvent(
        RuntimeContext rc,
        String taskId,
        String subAgentId,
        String sessionId,
        TaskStatus status,
        String result,
        String errorMessage) {}
