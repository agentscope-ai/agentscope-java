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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/**
 * Opt-in, in-memory circuit breaker around one tool.
 *
 * <p>State belongs to this decorator instance. Share it only between callers that share the same
 * dependency and credentials. Open circuits reject calls before invoking the delegate. After the
 * cooldown, exactly one subscription may probe recovery; cancellation releases that probe.
 * Calls already running when the circuit opens are not cancelled, and their stale outcomes cannot
 * change the new circuit generation. No background timers or threads are created.
 *
 * <p>Pair with {@link io.agentscope.core.middleware.CircuitBreakerMiddleware} to also withhold
 * unavailable tools from the model. The execution guard works without that middleware.
 */
public final class CircuitBreakerTool implements AgentTool {
    private final AgentTool delegate;
    private final int failureThreshold;
    private final Duration initialCooldown;
    private final Duration maxCooldown;
    private final double multiplier;
    private final Predicate<ToolResultBlock> failurePredicate;
    private final Clock clock;
    private int failures;
    private long epoch;
    private Duration cooldown;
    private Instant retryAt;
    private boolean probing;

    private CircuitBreakerTool(Builder builder) {
        delegate = builder.delegate;
        failureThreshold = builder.failureThreshold;
        initialCooldown = builder.initialCooldown;
        maxCooldown = builder.maxCooldown;
        multiplier = builder.multiplier;
        failurePredicate = builder.failurePredicate;
        clock = builder.clock;
    }

    /** @param delegate the tool to protect; wrapping is explicitly opt-in
     * @return a builder with three failures and a 60-second initial cooldown
     */
    public static Builder builder(AgentTool delegate) {
        return new Builder(delegate);
    }

    /**
     * Whether a new call could be admitted now. This does not reserve a recovery probe.
     * @return false during cooldown or while a recovery probe is running
     */
    public synchronized boolean isAvailable() {
        return retryAt == null || (!probing && !clock.instant().isBefore(retryAt));
    }

    private synchronized long acquire() {
        if (!isAvailable()) {
            return -1;
        }
        if (retryAt != null) {
            probing = true;
        }
        return epoch;
    }

    private synchronized void complete(long admittedEpoch, Boolean failed) {
        if (admittedEpoch != epoch) {
            return;
        }
        if (retryAt != null) {
            probing = false;
            if (failed == null) {
                return;
            }
            if (failed) {
                open();
            } else {
                retryAt = null;
                cooldown = null;
                failures = 0;
                epoch++;
            }
        } else if (failed != null) {
            if (!failed) {
                failures = 0;
            } else if (++failures >= failureThreshold) {
                open();
            }
        }
    }

    private void open() {
        long millis =
                cooldown == null
                        ? initialCooldown.toMillis()
                        : (long)
                                Math.min(
                                        maxCooldown.toMillis(),
                                        Math.ceil(cooldown.toMillis() * multiplier));
        cooldown = Duration.ofMillis(millis);
        retryAt = clock.instant().plus(cooldown);
        probing = false;
        failures = 0;
        epoch++;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(
                () -> {
                    long admittedEpoch = acquire();
                    if (admittedEpoch < 0) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Tool temporarily unavailable: circuit breaker open for "
                                                + getName()));
                    }
                    // Each subscription has one outcome, including cancellation and an empty
                    // publisher.
                    AtomicBoolean settled = new AtomicBoolean();
                    Consumer<Boolean> settle =
                            failed -> {
                                if (settled.compareAndSet(false, true)) {
                                    complete(admittedEpoch, failed);
                                }
                            };
                    return Mono.defer(() -> delegate.callAsync(param))
                            .doOnNext(
                                    result -> {
                                        ToolResultState state = result.getState();
                                        if (state == ToolResultState.DENIED
                                                || state == ToolResultState.INTERRUPTED
                                                || result.isSuspended()) {
                                            settle.accept(null);
                                        } else {
                                            settle.accept(failurePredicate.test(result));
                                        }
                                    })
                            .doOnError(
                                    error ->
                                            settle.accept(
                                                    error instanceof ToolSuspendException
                                                            ? null
                                                            : true))
                            .doFinally(signal -> settle.accept(null));
                });
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    /** Configuration for a single decorated tool. */
    public static final class Builder {
        private final AgentTool delegate;
        private int failureThreshold = 3;
        private Duration initialCooldown = Duration.ofSeconds(60);
        private Duration maxCooldown = Duration.ofSeconds(600);
        private double multiplier = 2;
        private Predicate<ToolResultBlock> failurePredicate =
                result -> result.getState() == ToolResultState.ERROR;
        private Clock clock = Clock.systemUTC();

        private Builder(AgentTool delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        /** @param value consecutive failures required to open; must be positive
         * @return this builder
         */
        public Builder failureThreshold(int value) {
            failureThreshold = value;
            return this;
        }

        /** @param value first cooldown, at least one millisecond
         * @return this builder
         */
        public Builder initialCooldown(Duration value) {
            initialCooldown = Objects.requireNonNull(value);
            return this;
        }

        /** @param value cooldown ceiling, at least the initial cooldown
         * @return this builder
         */
        public Builder maxCooldown(Duration value) {
            maxCooldown = Objects.requireNonNull(value);
            return this;
        }

        /** @param value finite multiplier, at least one
         * @return this builder
         */
        public Builder backoffMultiplier(double value) {
            multiplier = value;
            return this;
        }

        /**
         * Classifies terminal results. Exceptions always count as failures; denied, interrupted,
         * and suspended results never count. The predicate must be thread-safe and must not throw.
         * @param value result classifier, defaulting to ERROR state
         * @return this builder
         */
        public Builder failurePredicate(Predicate<ToolResultBlock> value) {
            failurePredicate = Objects.requireNonNull(value);
            return this;
        }

        Builder clock(Clock value) {
            clock = Objects.requireNonNull(value);
            return this;
        }

        /** @return the configured decorator
         * @throws IllegalArgumentException if the threshold or cooldown policy is invalid
         */
        public CircuitBreakerTool build() {
            if (failureThreshold < 1
                    || initialCooldown.isNegative()
                    || initialCooldown.toMillis() < 1
                    || maxCooldown.compareTo(initialCooldown) < 0
                    || !Double.isFinite(multiplier)
                    || multiplier < 1) {
                throw new IllegalArgumentException(
                        "Invalid circuit breaker threshold or cooldown policy");
            }
            maxCooldown.toMillis(); // Validate that the configured duration fits the arithmetic.
            return new CircuitBreakerTool(this);
        }
    }
}
