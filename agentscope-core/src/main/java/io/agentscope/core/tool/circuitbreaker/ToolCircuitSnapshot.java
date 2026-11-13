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
 * Immutable point-in-time view of all state for one tool, read in a single store round trip.
 *
 * <p>The cooldown duration is deliberately <em>not</em> part of the snapshot: it is derived from
 * {@code generation} by {@link ToolCircuitBreaker#cooldownFor(long)}, so changing the backoff
 * policy takes effect immediately and never has to be migrated in the store.
 *
 * @param failureCount consecutive failures recorded while the circuit is closed
 * @param generation number of times the circuit has tripped, driving exponential backoff;
 *     {@code 0} means it has never tripped
 * @param openedAtEpochMilli wall-clock instant the circuit was last opened, or {@code 0} when
 *     the circuit is not open — a positive value is the sole marker of the OPEN state, so no
 *     separate boolean has to be kept consistent with it
 * @param probeToken opaque owner token for the current half-open probe, or {@code null} when no
 *     probe is claimed
 * @param probeLeaseUntilEpochMilli wall-clock instant at which the current probe claim expires, or
 *     {@code 0} when no probe is claimed
 */
public record ToolCircuitSnapshot(
        long failureCount,
        long generation,
        long openedAtEpochMilli,
        String probeToken,
        long probeLeaseUntilEpochMilli) {

    /** Snapshot of a healthy tool with no partial failure streak. */
    public static final ToolCircuitSnapshot CLOSED = new ToolCircuitSnapshot(0L, 0L, 0L, null, 0L);

    /**
     * Create an open snapshot without a failure streak or claimed probe.
     *
     * @param generation trip generation
     * @param openedAtEpochMilli instant the circuit opened
     */
    public ToolCircuitSnapshot(long generation, long openedAtEpochMilli) {
        this(0L, generation, openedAtEpochMilli, null, 0L);
    }

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

    /**
     * Whether a recovery probe currently owns an unexpired lease.
     *
     * @param nowEpochMilli current wall-clock instant
     * @return true when another caller must not acquire the probe
     */
    public boolean hasActiveProbe(long nowEpochMilli) {
        return probeToken != null
                && !probeToken.isEmpty()
                && probeLeaseUntilEpochMilli > nowEpochMilli;
    }
}
