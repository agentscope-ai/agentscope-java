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

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tool circuit breaker: decides when a repeatedly failing tool should stop being offered to the
 * model, and when it is worth offering again.
 *
 * <p>This class holds all policy and no transport. It is a plain object with no reactive or agent
 * dependencies, so the state machine can be tested directly against a fixed {@link Clock}. {@link
 * ToolCircuitBreakerMiddleware} is the adapter that wires it into an agent.
 *
 * <h2>Why withhold the tool instead of rejecting the call</h2>
 *
 * <p>A ReAct loop reasons, calls a tool, reads the result, and reasons again. When a tool returns an
 * error the model commonly retries it, because from the model's point of view a single failure looks
 * incidental. Each retry costs a model call, an outbound request and seconds of latency, and against
 * a dependency that is genuinely down it fails again.
 *
 * <p>A classic breaker sits between caller and dependency and fails fast once open. Here there is a
 * better option: stop advertising the tool. A tool absent from the schema list is a tool the model
 * cannot ask for, which removes the failure loop at the source rather than absorbing it.
 *
 * <h2>State machine</h2>
 *
 * <pre>
 *   CLOSED --failureThreshold consecutive failures--&gt; OPEN
 *   OPEN --cooldown elapsed--&gt; HALF_OPEN            (one turn may probe; see below)
 *   HALF_OPEN --probe succeeds--&gt; CLOSED            (failure streak cleared)
 *   HALF_OPEN --probe fails--&gt; OPEN                 (next generation, longer cooldown)
 * </pre>
 *
 * <p>Nothing runs in the background: {@link #state(String)} derives the state from the stored
 * snapshot and the current time, so a cooldown that elapsed while the agent was idle is recognised
 * on the next read. There is no timer to leak and no scheduler to configure.
 *
 * <h2>Concurrency</h2>
 *
 * <p>A breaker is shared by every concurrent turn, session and (with a distributed store) replica
 * that can reach the tool, so every transition is published as one compare-and-set against the exact
 * snapshot the caller observed. A caller that loses the race re-reads and recomputes, which is what
 * makes two guarantees hold rather than merely being documented:
 *
 * <ul>
 *   <li><b>The failure threshold really counts consecutive failures.</b> A failure can only trip the
 *       circuit by replacing the snapshot it counted, so a success that clears the streak in between
 *       invalidates that failure's decision and it recounts from the cleared state.
 *   <li><b>HALF_OPEN admits one probe.</b> Recovery is a claim, not merely an elapsed timestamp:
 *       {@link #tryAcquireProbe(String)} compare-and-sets a token plus lease into the snapshot, so
 *       exactly one caller may advertise the tool while the others keep withholding it. Without this
 *       every concurrent turn would probe at once and each failure would advance the backoff again,
 *       jumping straight to the cooldown ceiling and skipping the ramp.
 * </ul>
 *
 * <p>The lease bounds the damage when a probe never reports an outcome (a turn that never calls the
 * advertised tool, a lost execution, a crashed replica): once it expires another caller may claim the
 * probe. Results carrying a superseded token are ignored, so a late reply cannot close or re-open a
 * newer probe.
 */
public class ToolCircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(ToolCircuitBreaker.class);

    private final ToolCircuitBreakerConfig config;
    private final ToolCircuitBreakerStore store;
    private final Clock clock;

    /**
     * Create a breaker with the default in-process store and the system clock.
     *
     * @param config supervision and backoff policy
     */
    public ToolCircuitBreaker(ToolCircuitBreakerConfig config) {
        this(config, new InMemoryToolCircuitBreakerStore(), Clock.systemUTC());
    }

    /**
     * Create a breaker with a caller-supplied store and the system clock.
     *
     * @param config supervision and backoff policy
     * @param store state persistence
     */
    public ToolCircuitBreaker(ToolCircuitBreakerConfig config, ToolCircuitBreakerStore store) {
        this(config, store, Clock.systemUTC());
    }

    /**
     * Create a breaker with a caller-supplied store and clock.
     *
     * @param config supervision and backoff policy
     * @param store state persistence
     * @param clock time source; inject a fixed or adjustable clock in tests to step through
     *     cooldowns without sleeping. Cooldowns and probe leases compare stored wall-clock stamps
     *     against this clock, so a backwards jump (a manual correction, not NTP slew) can hold a
     *     tool back for up to the size of that jump. Use {@link #reset(String)} to clear it
     *     immediately.
     */
    public ToolCircuitBreaker(
            ToolCircuitBreakerConfig config, ToolCircuitBreakerStore store, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Whether this tool is under supervision.
     *
     * <p>Exclusions win over both the monitored set and {@code monitorAllTools}, so a tool can be
     * exempted without editing the supervised list.
     *
     * @param toolName tool to test; null is never supervised
     * @return true when failures of this tool count towards a trip
     */
    public boolean supervises(String toolName) {
        if (toolName == null || !config.isEnabled()) {
            return false;
        }
        if (config.getExcludedTools().contains(toolName)) {
            return false;
        }
        return config.isMonitorAllTools() || config.getMonitoredTools().contains(toolName);
    }

    /**
     * Current state of a tool's circuit, derived from stored state and the current time.
     *
     * <p>Unsupervised tools always report {@link ToolCircuitState#CLOSED}. This is a pure query: it
     * never claims the recovery probe, so it is safe to call for logging or metrics.
     *
     * @param toolName tool to inspect
     * @return the current state, never null
     */
    public ToolCircuitState state(String toolName) {
        if (!supervises(toolName)) {
            return ToolCircuitState.CLOSED;
        }
        return stateOf(store.snapshot(toolName), clock.millis());
    }

    /**
     * Whether the tool is inside a cooldown and must be kept out of the schema list.
     *
     * <p>A half-open circuit reports false here because the tool may be advertised again — but only
     * to the single turn that wins {@link #tryAcquireProbe(String)}. Callers deciding what to
     * advertise should therefore branch on {@link #state(String)} and claim the probe, not rely on
     * this method alone; it exists as a pure query for observability.
     *
     * @param toolName tool to test
     * @return true when the tool is in an unexpired cooldown
     */
    public boolean isWithheld(String toolName) {
        return state(toolName) == ToolCircuitState.OPEN;
    }

    /**
     * Cooldown for a given trip generation: {@code initial * multiplier^(generation-1)}, capped at
     * the configured maximum.
     *
     * @param generation trip count, 1 for the first trip
     * @return the cooldown, or {@link Duration#ZERO} for a circuit that has never tripped
     */
    public Duration cooldownFor(long generation) {
        if (generation <= 0L) {
            return Duration.ZERO;
        }
        double scaled =
                config.getInitialCooldown().toMillis()
                        * Math.pow(config.getBackoffMultiplier(), (double) generation - 1.0);
        // A large generation overflows to Infinity; both that and any value past the ceiling clamp
        // to maxCooldown, so backoff can never isolate a tool indefinitely.
        if (!Double.isFinite(scaled) || scaled >= config.getMaxCooldown().toMillis()) {
            return config.getMaxCooldown();
        }
        return Duration.ofMillis((long) Math.ceil(scaled));
    }

    /**
     * Try to claim the single recovery probe for a half-open circuit.
     *
     * <p>The winner may advertise the tool for one turn and must report the outcome through {@link
     * #recordSuccess(String, String)} / {@link #recordFailure(String, String)} with the returned
     * token, or hand the permit back with {@link #releaseProbe(String, String)} if the tool is never
     * called. Doing neither is not fatal: the claim carries a lease and expires.
     *
     * @param toolName tool to probe
     * @return the probe token when this caller owns the probe; empty when the circuit is not
     *     half-open or another caller already holds it
     */
    public Optional<String> tryAcquireProbe(String toolName) {
        if (!supervises(toolName)) {
            return Optional.empty();
        }
        while (true) {
            ToolCircuitSnapshot current = store.snapshot(toolName);
            long now = clock.millis();
            if (stateOf(current, now) != ToolCircuitState.HALF_OPEN
                    || current.hasActiveProbe(now)) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            ToolCircuitSnapshot update =
                    new ToolCircuitSnapshot(
                            current.failureCount(),
                            current.generation(),
                            current.openedAtEpochMilli(),
                            token,
                            now + config.getProbeTimeout().toMillis());
            if (store.compareAndSet(toolName, current, update)) {
                logger.debug(
                        "Recovery probe claimed: tool={}, generation={}",
                        toolName,
                        current.generation());
                return Optional.of(token);
            }
        }
    }

    /**
     * Hand back a probe claim without reporting an outcome, so another turn can retry immediately.
     *
     * <p>Used when the advertised tool was never called, or when the call was refused or cancelled
     * and therefore tested nothing. Does nothing if the claim has already expired or been replaced.
     *
     * @param toolName tool whose probe is being released
     * @param probeToken token returned by {@link #tryAcquireProbe(String)}
     */
    public void releaseProbe(String toolName, String probeToken) {
        if (probeToken == null || !supervises(toolName)) {
            return;
        }
        while (true) {
            ToolCircuitSnapshot current = store.snapshot(toolName);
            if (!probeToken.equals(current.probeToken())) {
                return;
            }
            ToolCircuitSnapshot update =
                    new ToolCircuitSnapshot(
                            current.failureCount(),
                            current.generation(),
                            current.openedAtEpochMilli(),
                            null,
                            0L);
            if (store.compareAndSet(toolName, current, update)) {
                logger.debug("Recovery probe released unused: tool={}", toolName);
                return;
            }
        }
    }

    /**
     * Record a successful tool execution that did not hold a recovery probe.
     *
     * @param toolName tool that succeeded
     */
    public void recordSuccess(String toolName) {
        recordSuccess(toolName, null);
    }

    /**
     * Record a successful tool execution.
     *
     * <p>A probe success closes the circuit and discards the accumulated backoff. A success while
     * closed clears any partial failure streak, which is what makes the threshold count consecutive
     * failures and stops an occasional blip from ever tripping a healthy tool.
     *
     * @param toolName tool that succeeded
     * @param probeToken token from {@link #tryAcquireProbe(String)}, or null when the call was not a
     *     recovery probe
     */
    public void recordSuccess(String toolName, String probeToken) {
        if (!supervises(toolName)) {
            return;
        }
        while (true) {
            ToolCircuitSnapshot current = store.snapshot(toolName);
            long now = clock.millis();
            ToolCircuitState state = stateOf(current, now);
            if (state == ToolCircuitState.OPEN) {
                // Withheld yet still executed: tolerated, see recordFailure.
                return;
            }
            if (state == ToolCircuitState.HALF_OPEN) {
                if (current.hasActiveProbe(now)
                        && !Objects.equals(probeToken, current.probeToken())) {
                    // Someone else owns the live probe; a superseded result must not close it.
                    return;
                }
                if (store.compareAndSet(toolName, current, ToolCircuitSnapshot.CLOSED)) {
                    logger.info("Tool circuit closed after successful probe: tool={}", toolName);
                    return;
                }
                continue;
            }
            if (current.failureCount() == 0L) {
                return;
            }
            if (store.compareAndSet(toolName, current, ToolCircuitSnapshot.CLOSED)) {
                logger.debug("Tool circuit failure streak cleared by success: tool={}", toolName);
                return;
            }
        }
    }

    /**
     * Record a failed tool execution that did not hold a recovery probe.
     *
     * @param toolName tool that failed
     */
    public void recordFailure(String toolName) {
        recordFailure(toolName, null);
    }

    /**
     * Record a failed tool execution, tripping the circuit once the threshold is reached.
     *
     * <p>Only genuine execution failures belong here. A call refused by permission rules or
     * cancelled by the user says nothing about the health of the dependency and must not count
     * towards a trip.
     *
     * @param toolName tool that failed
     * @param probeToken token from {@link #tryAcquireProbe(String)}, or null when the call was not a
     *     recovery probe
     */
    public void recordFailure(String toolName, String probeToken) {
        if (!supervises(toolName)) {
            return;
        }
        while (true) {
            ToolCircuitSnapshot current = store.snapshot(toolName);
            long now = clock.millis();
            ToolCircuitState state = stateOf(current, now);
            if (state == ToolCircuitState.OPEN) {
                // The tool was withheld, so the model should not have been able to call it. This is
                // still reachable: the model may have chosen the call in the same turn the circuit
                // tripped. Ignore it rather than counting a failure the policy never authorised.
                logger.debug(
                        "Ignoring failure of withheld tool, likely decided before the circuit"
                                + " opened: tool={}",
                        toolName);
                return;
            }
            if (state == ToolCircuitState.HALF_OPEN) {
                if (current.hasActiveProbe(now)
                        && !Objects.equals(probeToken, current.probeToken())) {
                    // Someone else owns the live probe; a superseded result must not re-open it.
                    return;
                }
                if (reopen(toolName, current, now)) {
                    return;
                }
                continue;
            }
            long failures = current.failureCount() + 1L;
            if (failures < config.getFailureThreshold()) {
                ToolCircuitSnapshot update =
                        new ToolCircuitSnapshot(failures, current.generation(), 0L, null, 0L);
                if (store.compareAndSet(toolName, current, update)) {
                    logger.debug(
                            "Tool failure recorded: tool={}, consecutiveFailures={}/{}",
                            toolName,
                            failures,
                            config.getFailureThreshold());
                    return;
                }
                continue;
            }
            // Trip in the same compare-and-set that counted the final failure, so no caller can
            // observe "threshold reached but not open" and act on it.
            if (reopen(toolName, current, now)) {
                logger.warn(
                        "Tool circuit opened: tool={}, consecutiveFailures={}. The tool will not be"
                                + " offered to the model until the cooldown elapses.",
                        toolName,
                        failures);
                return;
            }
        }
    }

    /**
     * Force a tool back to {@link ToolCircuitState#CLOSED}, discarding its failure streak,
     * accumulated backoff and any probe claim.
     *
     * <p>Intended for operators who know a dependency is healthy again and do not want to wait out
     * the cooldown.
     *
     * @param toolName tool to reset
     */
    public void reset(String toolName) {
        store.reset(toolName);
        logger.info("Tool circuit manually reset: tool={}", toolName);
    }

    /**
     * The policy in force.
     *
     * @return the configuration this breaker was built with
     */
    public ToolCircuitBreakerConfig getConfig() {
        return config;
    }

    private boolean reopen(String toolName, ToolCircuitSnapshot current, long now) {
        long generation = current.generation() + 1L;
        if (!store.compareAndSet(toolName, current, new ToolCircuitSnapshot(generation, now))) {
            return false;
        }
        logger.warn(
                "Tool circuit open: tool={}, generation={}, cooldown={}",
                toolName,
                generation,
                cooldownFor(generation));
        return true;
    }

    private ToolCircuitState stateOf(ToolCircuitSnapshot snapshot, long now) {
        if (!snapshot.isOpen()) {
            return ToolCircuitState.CLOSED;
        }
        Duration cooldown = cooldownFor(snapshot.generation());
        boolean elapsed = now >= snapshot.openedAtEpochMilli() + cooldown.toMillis();
        return elapsed ? ToolCircuitState.HALF_OPEN : ToolCircuitState.OPEN;
    }
}
