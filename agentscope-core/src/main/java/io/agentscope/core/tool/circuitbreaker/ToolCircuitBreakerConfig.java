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

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Policy for {@link ToolCircuitBreaker}: which tools are supervised, when a circuit trips, and how
 * long it stays open.
 *
 * <h2>Tools are supervised by opt-in, not by default</h2>
 *
 * <p>A breaker is only ever applied to tools named by {@link Builder#monitorTools(Collection)} (or
 * to every tool once {@link Builder#monitorAllTools(boolean)} is set). With neither configured the
 * breaker is inert.
 *
 * <p>That default is deliberate. Withholding a tool is the right response for a flaky external
 * dependency, but the wrong response for infrastructure the agent cannot work without: breaking a
 * database or filesystem tool does not degrade the agent gracefully, it cripples it. Requiring the
 * supervised set to be named means a breaker can never take down a tool its author never
 * considered. Prefer tools whose loss leaves the main flow viable.
 *
 * <h2>Cooldown grows with each trip</h2>
 *
 * <p>Cooldown is {@code min(initialCooldown * backoffMultiplier^(generation-1), maxCooldown)}. With
 * the defaults (60s, x2, capped at 600s) successive trips wait 60s, 120s, 240s, 480s, 600s, 600s...
 * A tool that keeps failing is isolated for longer, which cuts both the probe traffic aimed at a
 * struggling dependency and the tokens spent re-discovering that it is still down. The cap stops
 * backoff from isolating a tool for hours after a long outage.
 */
public final class ToolCircuitBreakerConfig {

    private final boolean enabled;
    private final Set<String> monitoredTools;
    private final Set<String> excludedTools;
    private final boolean monitorAllTools;
    private final int failureThreshold;
    private final Duration initialCooldown;
    private final double backoffMultiplier;
    private final Duration maxCooldown;

    private ToolCircuitBreakerConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.monitoredTools = Set.copyOf(builder.monitoredTools);
        this.excludedTools = Set.copyOf(builder.excludedTools);
        this.monitorAllTools = builder.monitorAllTools;
        this.failureThreshold = builder.failureThreshold;
        this.initialCooldown = builder.initialCooldown;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.maxCooldown = builder.maxCooldown;
    }

    /**
     * Create a builder carrying the documented defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether the breaker does anything at all.
     *
     * @return true when supervision is active
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Tools placed under supervision by name.
     *
     * @return unmodifiable set of tool names
     */
    public Set<String> getMonitoredTools() {
        return monitoredTools;
    }

    /**
     * Tools never supervised, which overrides both {@link #getMonitoredTools()} and {@link
     * #isMonitorAllTools()}.
     *
     * @return unmodifiable set of tool names
     */
    public Set<String> getExcludedTools() {
        return excludedTools;
    }

    /**
     * Whether every tool is supervised unless excluded.
     *
     * @return true when supervision is opt-out rather than opt-in
     */
    public boolean isMonitorAllTools() {
        return monitorAllTools;
    }

    /**
     * Consecutive failures that trip a closed circuit.
     *
     * @return failure threshold, at least 1
     */
    public int getFailureThreshold() {
        return failureThreshold;
    }

    /**
     * Cooldown applied on the first trip.
     *
     * @return initial cooldown
     */
    public Duration getInitialCooldown() {
        return initialCooldown;
    }

    /**
     * Factor the cooldown is multiplied by on each successive trip.
     *
     * @return backoff multiplier, at least 1.0
     */
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * Upper bound on the cooldown, whatever the generation.
     *
     * @return maximum cooldown
     */
    public Duration getMaxCooldown() {
        return maxCooldown;
    }

    /** Builder for {@link ToolCircuitBreakerConfig}. */
    public static final class Builder {

        private boolean enabled = true;
        private final Set<String> monitoredTools = new LinkedHashSet<>();
        private final Set<String> excludedTools = new LinkedHashSet<>();
        private boolean monitorAllTools = false;
        private int failureThreshold = 3;
        private Duration initialCooldown = Duration.ofSeconds(60);
        private double backoffMultiplier = 2.0;
        private Duration maxCooldown = Duration.ofSeconds(600);

        private Builder() {}

        /**
         * Turn supervision on or off, leaving the rest of the configuration in place.
         *
         * @param enabled false to make the breaker inert
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Place the named tools under supervision.
         *
         * @param toolNames tool names to supervise
         * @return this builder
         */
        public Builder monitorTools(Collection<String> toolNames) {
            if (toolNames != null) {
                this.monitoredTools.addAll(toolNames);
            }
            return this;
        }

        /**
         * Place the named tools under supervision.
         *
         * @param toolNames tool names to supervise
         * @return this builder
         */
        public Builder monitorTools(String... toolNames) {
            if (toolNames != null) {
                this.monitoredTools.addAll(Set.of(toolNames));
            }
            return this;
        }

        /**
         * Supervise every tool, subject to {@link #excludeTools(Collection)}.
         *
         * <p>Read the class-level note on infrastructure tools before enabling this. Opt-out
         * supervision also covers tools the framework adds per call, such as the structured-output
         * tool used to produce a final answer; withholding one of those does not degrade the agent,
         * it stops it completing. Name the unstable dependencies instead, or exclude the tools that
         * must always stay reachable.
         *
         * @param monitorAllTools true to switch to opt-out supervision
         * @return this builder
         */
        public Builder monitorAllTools(boolean monitorAllTools) {
            this.monitorAllTools = monitorAllTools;
            return this;
        }

        /**
         * Exempt the named tools from supervision, overriding every other setting.
         *
         * @param toolNames tool names to exempt
         * @return this builder
         */
        public Builder excludeTools(Collection<String> toolNames) {
            if (toolNames != null) {
                this.excludedTools.addAll(toolNames);
            }
            return this;
        }

        /**
         * Exempt the named tools from supervision, overriding every other setting.
         *
         * @param toolNames tool names to exempt
         * @return this builder
         */
        public Builder excludeTools(String... toolNames) {
            if (toolNames != null) {
                this.excludedTools.addAll(Set.of(toolNames));
            }
            return this;
        }

        /**
         * Set how many consecutive failures trip a closed circuit.
         *
         * @param failureThreshold threshold, at least 1
         * @return this builder
         */
        public Builder failureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
            return this;
        }

        /**
         * Set the cooldown applied on the first trip.
         *
         * @param initialCooldown positive duration
         * @return this builder
         */
        public Builder initialCooldown(Duration initialCooldown) {
            this.initialCooldown = initialCooldown;
            return this;
        }

        /**
         * Set the growth factor applied to the cooldown on each successive trip.
         *
         * @param backoffMultiplier factor, at least 1.0 (1.0 gives a fixed cooldown)
         * @return this builder
         */
        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        /**
         * Set the cooldown ceiling.
         *
         * @param maxCooldown duration, not shorter than the initial cooldown
         * @return this builder
         */
        public Builder maxCooldown(Duration maxCooldown) {
            this.maxCooldown = maxCooldown;
            return this;
        }

        /**
         * Validate and build the configuration.
         *
         * @return an immutable configuration
         * @throws IllegalArgumentException if any value is out of range or the cooldown bounds are
         *     inverted
         */
        public ToolCircuitBreakerConfig build() {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException(
                        "failureThreshold must be at least 1, got " + failureThreshold);
            }
            if (initialCooldown == null
                    || initialCooldown.isNegative()
                    || initialCooldown.isZero()) {
                throw new IllegalArgumentException(
                        "initialCooldown must be positive, got " + initialCooldown);
            }
            if (maxCooldown == null || maxCooldown.isNegative() || maxCooldown.isZero()) {
                throw new IllegalArgumentException(
                        "maxCooldown must be positive, got " + maxCooldown);
            }
            if (backoffMultiplier < 1.0 || !Double.isFinite(backoffMultiplier)) {
                throw new IllegalArgumentException(
                        "backoffMultiplier must be a finite value of at least 1.0, got "
                                + backoffMultiplier);
            }
            if (maxCooldown.compareTo(initialCooldown) < 0) {
                throw new IllegalArgumentException(
                        "maxCooldown ("
                                + maxCooldown
                                + ") must not be shorter than initialCooldown ("
                                + initialCooldown
                                + ")");
            }
            return new ToolCircuitBreakerConfig(this);
        }
    }
}
