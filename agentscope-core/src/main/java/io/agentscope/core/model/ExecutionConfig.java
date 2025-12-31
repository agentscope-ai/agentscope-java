/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.model;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Unified execution configuration for timeout and retry behavior.
 *
 * <p>This class replaces the previous TimeoutConfig and RetryConfig classes, providing a single
 * unified configuration for controlling execution behavior of both model API calls and tool
 * executions.
 *
 * <p>Use the builder pattern to construct instances. All fields are optional and nullable.
 *
 * <h2>Standard Defaults</h2>
 *
 * <ul>
 *   <li>{@link #MODEL_DEFAULTS}: 5 minutes timeout, 3 retry attempts with exponential backoff
 *   <li>{@link #TOOL_DEFAULTS}: 5 minutes timeout, no retry (1 attempt only)
 * </ul>
 *
 * <h2>Configuration Merging</h2>
 *
 * <p>Use {@link #mergeConfigs(ExecutionConfig, ExecutionConfig)} to combine configurations with
 * parameter-by-parameter precedence. This allows layering configs from different sources:
 *
 * <pre>{@code
 * // Priority: per-request > agent-level > component-defaults > system-defaults
 * ExecutionConfig effective = ExecutionConfig.mergeConfigs(
 *     perRequestConfig,
 *     ExecutionConfig.mergeConfigs(agentConfig, ExecutionConfig.MODEL_DEFAULTS)
 * );
 * }</pre>
 */
public class ExecutionConfig {
    /** Timeout duration for a single execution (model request or tool call). */
    private final Duration timeout;

    /** Maximum number of attempts (including the initial attempt). */
    private final Integer maxAttempts;

    /** Initial backoff duration for the first retry. */
    private final Duration initialBackoff;

    /** Maximum backoff duration between retries. */
    private final Duration maxBackoff;

    /** Multiplier applied to backoff duration after each retry. */
    private final Double backoffMultiplier;

    /** Predicate to determine if an error should trigger a retry. */
    private final Predicate<Throwable> retryOn;

    /**
     * Standard defaults for model API calls.
     *
     * <ul>
     *   <li>Timeout: 5 minutes
     *   <li>Max attempts: 3 (initial + 2 retries)
     *   <li>Initial backoff: 1 second
     *   <li>Max backoff: 10 seconds
     *   <li>Backoff multiplier: 2.0 (exponential)
     *   <li>Retry on: all errors
     * </ul>
     */
    public static final ExecutionConfig MODEL_DEFAULTS =
            builder()
                    .timeout(Duration.ofMinutes(5))
                    .maxAttempts(3)
                    .initialBackoff(Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofSeconds(10))
                    .backoffMultiplier(2.0)
                    .retryOn(error -> true)
                    .build();

    /**
     * Standard defaults for tool executions.
     *
     * <ul>
     *   <li>Timeout: 5 minutes
     *   <li>Max attempts: 1 (no retry)
     * </ul>
     */
    public static final ExecutionConfig TOOL_DEFAULTS =
            builder().timeout(Duration.ofMinutes(5)).maxAttempts(1).build();

    private ExecutionConfig(Builder builder) {
        this.timeout = builder.timeout;
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.retryOn = builder.retryOn;
    }

    /**
     * Gets the timeout duration.
     *
     * @return the timeout duration, or null if not set
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Gets the maximum number of attempts.
     *
     * @return the max attempts (including initial attempt), or null if not set
     */
    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Gets the initial backoff duration.
     *
     * @return the initial backoff duration, or null if not set
     */
    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    /**
     * Gets the maximum backoff duration.
     *
     * @return the max backoff duration, or null if not set
     */
    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    /**
     * Gets the backoff multiplier.
     *
     * @return the backoff multiplier, or null if not set
     */
    public Double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * Gets the retry predicate.
     *
     * @return the predicate to determine if an error should be retried, or null if not set
     */
    public Predicate<Throwable> getRetryOn() {
        return retryOn;
    }

    /**
     * Creates a new builder for ExecutionConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Merges two ExecutionConfig instances, with primary config taking precedence.
     *
     * <p>This method performs parameter-by-parameter merging: for each parameter, if the primary
     * value is non-null, it is used; otherwise, the fallback value is used. This allows proper
     * layering of configs from different sources.
     *
     * <p><b>Merge Behavior:</b>
     *
     * <ul>
     *   <li>Each field: primary != null ? primary : fallback
     *   <li>If primary is null, returns fallback directly
     *   <li>If fallback is null, returns primary directly
     * </ul>
     *
     * <p><b>Example:</b>
     *
     * <pre>{@code
     * ExecutionConfig defaults = ExecutionConfig.MODEL_DEFAULTS;
     * ExecutionConfig agentLevel = ExecutionConfig.builder()
     *     .timeout(Duration.ofMinutes(2))
     *     .build();
     *
     * // Result: timeout=2min, maxAttempts=3 (from defaults), ...
     * ExecutionConfig merged = ExecutionConfig.mergeConfigs(agentLevel, defaults);
     * }</pre>
     *
     * @param primary the primary config (higher priority)
     * @param fallback the fallback config (lower priority)
     * @return merged config, or null if both are null
     */
    public static ExecutionConfig mergeConfigs(ExecutionConfig primary, ExecutionConfig fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }

        Builder builder = builder();
        builder.timeout(primary.timeout != null ? primary.timeout : fallback.timeout);
        builder.maxAttempts(
                primary.maxAttempts != null ? primary.maxAttempts : fallback.maxAttempts);
        builder.initialBackoff(
                primary.initialBackoff != null ? primary.initialBackoff : fallback.initialBackoff);
        builder.maxBackoff(primary.maxBackoff != null ? primary.maxBackoff : fallback.maxBackoff);
        builder.backoffMultiplier(
                primary.backoffMultiplier != null
                        ? primary.backoffMultiplier
                        : fallback.backoffMultiplier);
        builder.retryOn(primary.retryOn != null ? primary.retryOn : fallback.retryOn);

        return builder.build();
    }

    /** Builder for ExecutionConfig. */
    public static class Builder {
        private Duration timeout;
        private Integer maxAttempts;
        private Duration initialBackoff;
        private Duration maxBackoff;
        private Double backoffMultiplier;
        private Predicate<Throwable> retryOn;

        /**
         * Sets the timeout duration for a single execution.
         *
         * @param timeout the timeout duration, or null for no timeout
         * @return this builder instance
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the maximum number of attempts (including the initial attempt).
         *
         * <p>For example, maxAttempts=3 means: 1 initial attempt + 2 retries.
         *
         * @param maxAttempts the max attempts (must be >= 1), or null
         * @return this builder instance
         */
        public Builder maxAttempts(Integer maxAttempts) {
            if (maxAttempts != null && maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the initial backoff duration for the first retry.
         *
         * @param initialBackoff the initial backoff duration, or null
         * @return this builder instance
         */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        /**
         * Sets the maximum backoff duration between retries.
         *
         * @param maxBackoff the max backoff duration, or null
         * @return this builder instance
         */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /**
         * Sets the backoff multiplier applied after each retry.
         *
         * <p>For example, with initialBackoff=1s, maxBackoff=10s, and backoffMultiplier=2.0, the
         * retry delays will be: 1s, 2s, 4s, 8s, 10s (capped), 10s, ...
         *
         * @param backoffMultiplier the backoff multiplier (must be >= 1.0), or null
         * @return this builder instance
         */
        public Builder backoffMultiplier(Double backoffMultiplier) {
            if (backoffMultiplier != null && backoffMultiplier < 1.0) {
                throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
            }
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        /**
         * Sets the predicate to determine if an error should trigger a retry.
         *
         * @param retryOn the retry predicate (returns true to retry), or null
         * @return this builder instance
         */
        public Builder retryOn(Predicate<Throwable> retryOn) {
            this.retryOn = retryOn;
            return this;
        }

        /**
         * Builds a new ExecutionConfig instance.
         *
         * @return a new ExecutionConfig instance
         */
        public ExecutionConfig build() {
            return new ExecutionConfig(this);
        }
    }
}
