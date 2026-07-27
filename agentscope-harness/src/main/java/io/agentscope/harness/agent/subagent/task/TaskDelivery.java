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

import java.time.Instant;

/**
 * Snapshot of a terminal-state subagent task awaiting (or undergoing) push delivery to the parent
 * agent's reasoning loop. Returned by {@link TaskRepository#findPendingDeliveries} so that
 * middleware can render it into a synthetic {@code <system-reminder>} message without re-querying
 * the underlying record.
 *
 * <p>Fields are denormalised from {@link TaskRecord} on purpose — the consumer
 * ({@code SubagentsMiddleware}) only needs the projection, and snapshotting up-front avoids racing
 * with a later status mutation.
 *
 * @param taskId        task identifier
 * @param agentId       which subagent type produced this result (may be {@code null} on legacy
 *                      records)
 * @param status        terminal status — one of COMPLETED / FAILED / CANCELLED / DENIED
 * @param result        completion payload; {@code null} for FAILED / CANCELLED
 * @param errorMessage  failure message; {@code null} for COMPLETED / CANCELLED / DENIED
 * @param completedAt   wall-clock instant the task reached its terminal status (best-effort —
 *                      typically {@link TaskRecord#getLastUpdatedAt()})
 */
public record TaskDelivery(
        String taskId,
        String agentId,
        TaskStatus status,
        String result,
        String errorMessage,
        Instant completedAt) {}
