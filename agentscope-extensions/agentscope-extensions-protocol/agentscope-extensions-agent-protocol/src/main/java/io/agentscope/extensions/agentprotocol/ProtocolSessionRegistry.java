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
package io.agentscope.extensions.agentprotocol;

import java.util.Optional;

/**
 * Ownership record for client-named sessions.
 *
 * <p>A submission may carry {@code context.session_id}, which makes the conversation a resource the
 * caller owns rather than something the server derives per task. That is what lets a follow-up land
 * in the same conversation without the caller remembering a task id — but it also means the server
 * must remember who opened the session the first time: without that record, a caller that repeats
 * someone else's session id with a different {@code user_id} would silently start an empty
 * conversation in its own namespace instead of being told it is using an id it does not own.
 *
 * <p>The pair {@code (user_id, agent_id)} is the ownership triple together with the session id. Both
 * components already partition the agent's storage, so a mismatch never leaks another conversation's
 * content; the record exists to turn that silent divergence into a hard error. {@code created_by}
 * (the submitting agent id, a descriptive field) is kept for diagnostics only and is never used as a
 * security boundary.
 *
 * <p>The registry also answers a second question: which turn is currently using the session. That
 * pointer is written once per submission, after the session lease is claimed, and is never rewritten
 * while the turn runs or pauses. Whether a session may accept a new turn is decided by the task
 * record the pointer names — not by any pause-time flag — so a paused turn keeps its session, a
 * finished turn leaves a pointer to a terminal record that the next submission reads as "free", and
 * there is no marker to lose or to clean up on resume, cancel or timeout.
 *
 * <p>The shipped implementation is {@link BaseStoreProtocolSessionRegistry}, which keeps one record
 * per session under the protocol namespace of a {@link
 * io.agentscope.harness.agent.filesystem.remote.store.BaseStore}. Its {@code inMemory()} factory is
 * the process-local default, equivalent to a single-instance deployment; a deployment that runs
 * several protocol instances supplies one of the shipped store implementations (Redis, JDBC, MySQL,
 * PostgreSQL, OSS, COS, MongoDB) and the same record becomes visible to every instance.
 */
public interface ProtocolSessionRegistry {

    /**
     * Registers {@code sessionId} on first use, or verifies that it still belongs to the same
     * {@code (userId, agentId)}.
     *
     * @throws IllegalArgumentException when the session was opened by a different owner
     */
    void registerOrVerify(String sessionId, String userId, String agentId, String createdBy);

    /**
     * Records that {@code taskId} is the turn currently using {@code sessionId}.
     *
     * <p>Called once per submission, after the session lease is claimed. The value is a pointer, not
     * a lock: mutual exclusion comes from the session lease, and the task record it names is the
     * authority on whether that turn is running, paused or finished.
     */
    void bindCurrentTurn(String sessionId, String taskId);

    /**
     * Returns the task that most recently claimed {@code sessionId}, or empty.
     *
     * <p>The pointer is not filtered by state: naming a finished or vanished record is a normal
     * answer, and the caller decides what it means.
     */
    Optional<String> currentTurn(String sessionId);
}
