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
 * cannot ask for, which removes the failure loop at the source rather than absorbing it. The model
 * needs no prompt telling it to avoid the tool and cannot argue with the decision.
 *
 * <h2>State machine</h2>
 *
 * <pre>
 *   CLOSED --failureThreshold consecutive failures--&gt; OPEN
 *   OPEN --cooldown elapsed--&gt; HALF_OPEN            (tool advertised again, as a probe)
 *   HALF_OPEN --probe succeeds--&gt; CLOSED            (failure counter cleared)
 *   HALF_OPEN --probe fails--&gt; OPEN                 (next generation, longer cooldown)
 * </pre>
 *
 * <p>Nothing runs in the background: {@link #state(String)} derives the state from the stored
 * snapshot and the current time, so a cooldown that elapsed while the agent was idle is recognised
 * on the next read. There is no timer to leak and no scheduler to configure.
 *
 * <h2>Threading</h2>
 *
 * <p>Safe for concurrent use as long as the {@link ToolCircuitBreakerStore} is. Read-modify-write
 * sequences are not globally serialised: two tool calls failing at the same instant may both observe
 * the threshold and call {@link ToolCircuitBreakerStore#open(String, long)}. That is harmless —
 * opening is idempotent apart from advancing the generation, so the worst case is one extra backoff
 * step.
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
     *     cooldowns without sleeping. Cooldowns compare a stored wall-clock stamp against this
     *     clock, so a backwards jump (a manual correction, not NTP slew) can hold a tool back for
     *     up to the size of that jump. Use {@link #reset(String)} to clear it immediately.
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
     * <p>Unsupervised tools always report {@link ToolCircuitState#CLOSED}.
     *
     * @param toolName tool to inspect
     * @return the current state, never null
     */
    public ToolCircuitState state(String toolName) {
        if (!supervises(toolName)) {
            return ToolCircuitState.CLOSED;
        }
        ToolCircuitSnapshot snapshot = store.snapshot(toolName);
        if (!snapshot.isOpen()) {
            return ToolCircuitState.CLOSED;
        }
        return hasCooldownElapsed(snapshot) ? ToolCircuitState.HALF_OPEN : ToolCircuitState.OPEN;
    }

    /**
     * Whether the tool should be kept out of the schema list offered to the model.
     *
     * <p>True only in {@link ToolCircuitState#OPEN}: a half-open circuit deliberately advertises the
     * tool again so the model's next call doubles as the recovery probe.
     *
     * @param toolName tool to test
     * @return true when the tool must not be advertised
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
     * Record a successful tool execution.
     *
     * <p>In {@link ToolCircuitState#HALF_OPEN} this closes the circuit. In {@link
     * ToolCircuitState#CLOSED} it clears any partial failure streak, which is what makes the
     * threshold count consecutive failures and stops an occasional blip from ever tripping a
     * healthy tool.
     *
     * @param toolName tool that succeeded
     */
    public void recordSuccess(String toolName) {
        if (!supervises(toolName)) {
            return;
        }
        ToolCircuitState current = state(toolName);
        if (current == ToolCircuitState.OPEN) {
            // Withheld yet still executed: tolerated, see recordFailure.
            return;
        }
        if (current == ToolCircuitState.HALF_OPEN) {
            store.close(toolName);
            store.resetFailures(toolName);
            logger.info("Tool circuit closed after successful probe: tool={}", toolName);
            return;
        }
        if (store.failureCount(toolName) > 0L) {
            store.resetFailures(toolName);
            logger.debug("Tool circuit failure streak cleared by success: tool={}", toolName);
        }
    }

    /**
     * Record a failed tool execution, tripping the circuit once the threshold is reached.
     *
     * <p>Only genuine execution failures belong here. A call refused by permission rules or
     * cancelled by the user says nothing about the health of the dependency and must not count
     * towards a trip.
     *
     * @param toolName tool that failed
     */
    public void recordFailure(String toolName) {
        if (!supervises(toolName)) {
            return;
        }
        ToolCircuitState current = state(toolName);
        if (current == ToolCircuitState.OPEN) {
            // The tool was withheld, so the model should not have been able to call it. This is
            // still reachable: the model may have chosen the call in the same turn the circuit
            // tripped. Ignore it rather than counting a failure the policy never authorised.
            logger.debug(
                    "Ignoring failure of withheld tool, likely decided before the circuit opened:"
                            + " tool={}",
                    toolName);
            return;
        }
        if (current == ToolCircuitState.HALF_OPEN) {
            long generation = store.open(toolName, clock.millis());
            logger.warn(
                    "Tool circuit re-opened after failed probe: tool={}, generation={},"
                            + " cooldown={}",
                    toolName,
                    generation,
                    cooldownFor(generation));
            return;
        }
        long failures = store.recordFailure(toolName);
        if (failures < config.getFailureThreshold()) {
            logger.debug(
                    "Tool failure recorded: tool={}, consecutiveFailures={}/{}",
                    toolName,
                    failures,
                    config.getFailureThreshold());
            return;
        }
        long generation = store.open(toolName, clock.millis());
        // Clear the streak on trip so the counter always means "failures seen while closed".
        store.resetFailures(toolName);
        logger.warn(
                "Tool circuit opened: tool={}, consecutiveFailures={}, generation={}, cooldown={}."
                        + " The tool will not be offered to the model until the cooldown elapses.",
                toolName,
                failures,
                generation,
                cooldownFor(generation));
    }

    /**
     * Force a tool back to {@link ToolCircuitState#CLOSED}, discarding its failure streak and
     * accumulated backoff.
     *
     * <p>Intended for operators who know a dependency is healthy again and do not want to wait out
     * the cooldown.
     *
     * @param toolName tool to reset
     */
    public void reset(String toolName) {
        store.close(toolName);
        store.resetFailures(toolName);
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

    private boolean hasCooldownElapsed(ToolCircuitSnapshot snapshot) {
        Duration cooldown = cooldownFor(snapshot.generation());
        return clock.millis() >= snapshot.openedAtEpochMilli() + cooldown.toMillis();
    }
}
