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
package io.agentscope.core.formatter;

import java.util.function.Consumer;

/**
 * Policy controlling the generate-validate-correct retry loop for structured
 * outputs, as applied by structured-output-aware components (for example
 * {@code StructuredOutputValidationMiddleware}).
 *
 * <p>All knobs are optional and carry safe defaults:
 *
 * <ul>
 *   <li>{@code maxAttempts} defaults to {@link #DEFAULT_MAX_ATTEMPTS} — one
 *       initial generation plus up to two error-feedback corrections.</li>
 *   <li>{@code tokenBudget} defaults to {@code null} (unlimited). When set,
 *       generation stops as soon as the cumulative token usage across attempts
 *       reaches the budget; the policy is passed per call via
 *       {@code GenerateOptions.builder().structuredOutputPolicy(...)}.</li>
 *   <li>{@code onFailedAttempt} observes every failed attempt for logging,
 *       metrics or user-facing progress events.</li>
 * </ul>
 */
public final class StructuredOutputRetryPolicy {

    /** Default maximum number of generation attempts when none is configured. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final int maxAttempts;
    private final Long tokenBudget;
    private final Consumer<FailedAttempt> onFailedAttempt;
    private final boolean emitAttemptEvents;

    private StructuredOutputRetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.tokenBudget = builder.tokenBudget;
        this.onFailedAttempt = builder.onFailedAttempt;
        this.emitAttemptEvents = builder.emitAttemptEvents;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A policy with framework defaults: 3 attempts, unlimited tokens, no listeners. */
    public static StructuredOutputRetryPolicy defaults() {
        return builder().build();
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Cumulative token budget across all attempts of one structured output
     * generation; {@code null} means unlimited (the default).
     */
    public Long tokenBudget() {
        return tokenBudget;
    }

    public Consumer<FailedAttempt> onFailedAttempt() {
        return onFailedAttempt;
    }

    /**
     * Whether failed-attempt observability events should be emitted into the
     * agent event stream (as custom events) in addition to listener callbacks.
     */
    public boolean emitAttemptEvents() {
        return emitAttemptEvents;
    }

    /** Builder following the framework's fluent options style. */
    public static final class Builder {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Long tokenBudget;
        private Consumer<FailedAttempt> onFailedAttempt;
        private boolean emitAttemptEvents = true;

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** Cumulative token budget across attempts; {@code null} disables the guard. */
        public Builder tokenBudget(Long tokenBudget) {
            if (tokenBudget != null && tokenBudget <= 0) {
                throw new IllegalArgumentException("tokenBudget must be positive when set");
            }
            this.tokenBudget = tokenBudget;
            return this;
        }

        public Builder onFailedAttempt(Consumer<FailedAttempt> listener) {
            this.onFailedAttempt = listener;
            return this;
        }

        public Builder emitAttemptEvents(boolean emit) {
            this.emitAttemptEvents = emit;
            return this;
        }

        public StructuredOutputRetryPolicy build() {
            return new StructuredOutputRetryPolicy(this);
        }
    }
}
