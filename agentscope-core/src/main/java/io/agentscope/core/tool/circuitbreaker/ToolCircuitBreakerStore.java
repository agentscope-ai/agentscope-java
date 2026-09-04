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
 * circuit trips, or consult a clock. All policy lives in {@link ToolCircuitBreaker}, which keeps
 * this SPI stable when the policy evolves and makes the policy unit-testable without a store.
 *
 * <p>{@link InMemoryToolCircuitBreakerStore} is the default and is sufficient for a single
 * process. A distributed implementation (for example the Redis-backed store in
 * {@code agentscope-extensions-redis}) lets every replica share one view of a broken tool, so a
 * dependency that node A found down is not re-probed by nodes B and C in parallel.
 *
 * <h2>Threading</h2>
 *
 * <p>Implementations must be safe for concurrent use from multiple threads. {@link
 * #recordFailure(String)} and {@link #open(String, long)} must be atomic, since concurrent tool
 * calls in one ReAct turn race on both.
 */
public interface ToolCircuitBreakerStore {

    /**
     * Atomically increment the consecutive-failure counter and return the new value.
     *
     * @param toolName tool being counted
     * @return the counter value after incrementing, starting at 1
     */
    long recordFailure(String toolName);

    /**
     * Clear the consecutive-failure counter.
     *
     * <p>Called whenever a tool succeeds, which is what makes the threshold count
     * <em>consecutive</em> failures rather than lifetime failures.
     *
     * @param toolName tool to reset
     */
    void resetFailures(String toolName);

    /**
     * Read the consecutive-failure counter without modifying it.
     *
     * @param toolName tool to read
     * @return current count, or {@code 0} when nothing is recorded
     */
    long failureCount(String toolName);

    /**
     * Atomically move the circuit to OPEN: increment the backoff generation and stamp the open
     * instant, returning the new generation.
     *
     * <p>The caller supplies the timestamp so that it shares a clock with the cooldown comparison
     * in {@link ToolCircuitBreaker}; a store must never substitute its own clock.
     *
     * @param toolName tool to trip
     * @param openedAtEpochMilli instant the circuit opened, in epoch milliseconds
     * @return the backoff generation after incrementing, starting at 1
     */
    long open(String toolName, long openedAtEpochMilli);

    /**
     * Reset the circuit to CLOSED, discarding both the open timestamp and the backoff generation.
     *
     * <p>Dropping the generation means a tool that recovers starts its next incident from the
     * initial cooldown instead of inheriting an old, long backoff.
     *
     * @param toolName tool to close
     */
    void close(String toolName);

    /**
     * Read the trip state in a single round trip.
     *
     * @param toolName tool to read
     * @return current snapshot, never null; {@link ToolCircuitSnapshot#CLOSED} when no state exists
     */
    ToolCircuitSnapshot snapshot(String toolName);
}
