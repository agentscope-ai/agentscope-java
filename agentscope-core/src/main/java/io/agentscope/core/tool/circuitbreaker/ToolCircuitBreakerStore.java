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
package io.agentscope.core.tool.circuitbreaker;

/**
 * Persistence contract for tool circuit-breaker state.
 *
 * <p>Implementations are pure state holders: they must not apply the backoff policy, decide when a
 * circuit trips, or consult a clock. All policy lives in {@link ToolCircuitBreaker}. The breaker
 * computes immutable snapshots and commits them with {@link #compareAndSet}; this makes a complete
 * state transition atomic without moving policy into the persistence layer.
 *
 * <p>{@link InMemoryToolCircuitBreakerStore} is the default and is sufficient for a single
 * process. A distributed implementation (for example the Redis-backed store in
 * {@code agentscope-extensions-redis}) lets every replica share one view of a broken tool, so a
 * dependency that node A found down is not re-probed by nodes B and C in parallel.
 *
 * <h2>Threading</h2>
 *
 * <p>Implementations must be safe for concurrent use from multiple threads and processes. {@link
 * #compareAndSet(String, ToolCircuitSnapshot, ToolCircuitSnapshot)} must compare and replace the
 * complete snapshot atomically.
 */
public interface ToolCircuitBreakerStore {

    /**
     * Read all state for one tool in a single round trip.
     *
     * @param toolName tool to read
     * @return current snapshot, never null; {@link ToolCircuitSnapshot#CLOSED} when no state exists
     */
    ToolCircuitSnapshot snapshot(String toolName);

    /**
     * Atomically replace the current snapshot if it still equals {@code expected}.
     *
     * <p>{@link ToolCircuitSnapshot#CLOSED} is the logical value of a missing entry. Implementations
     * should remove storage when {@code update} is CLOSED so healthy tools do not accumulate state.
     *
     * @param toolName tool to update
     * @param expected snapshot the caller observed
     * @param update complete replacement snapshot
     * @return true when the replacement was committed; false when another caller changed the state
     */
    boolean compareAndSet(
            String toolName, ToolCircuitSnapshot expected, ToolCircuitSnapshot update);

    /**
     * Unconditionally discard all state for one tool.
     *
     * @param toolName tool to reset
     */
    void reset(String toolName);
}
