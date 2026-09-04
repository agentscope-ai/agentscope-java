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
 * Immutable point-in-time view of a tool's trip state, read in a single store round trip.
 *
 * <p>The cooldown duration is deliberately <em>not</em> part of the snapshot: it is derived from
 * {@code generation} by {@link ToolCircuitBreaker#cooldownFor(long)}, so changing the backoff
 * policy takes effect immediately and never has to be migrated in the store.
 *
 * @param generation number of times the circuit has tripped, driving exponential backoff;
 *     {@code 0} means it has never tripped
 * @param openedAtEpochMilli wall-clock instant the circuit was last opened, or {@code 0} when
 *     the circuit is not open — a positive value is the sole marker of the OPEN state, so no
 *     separate boolean has to be kept consistent with it
 */
public record ToolCircuitSnapshot(long generation, long openedAtEpochMilli) {

    /** Snapshot of a tool that has never tripped. */
    public static final ToolCircuitSnapshot CLOSED = new ToolCircuitSnapshot(0L, 0L);

    /**
     * Whether the circuit is currently open, ignoring whether its cooldown has elapsed.
     *
     * <p>An open circuit whose cooldown has elapsed is reported as
     * {@link ToolCircuitState#HALF_OPEN} by {@link ToolCircuitBreaker#state(String)}; this method
     * only reports the persisted flag.
     *
     * @return true when an open timestamp is recorded
     */
    public boolean isOpen() {
        return openedAtEpochMilli > 0L;
    }
}
