/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * Immutable configuration for retry behavior when calling LLM models.
 *
 * <p>Defines retry parameters including maximum attempts, backoff strategy, and error filtering.
 * This class uses exponential backoff by default, where each retry delay is multiplied by the
 * backoff multiplier up to the maximum backoff duration.
 *
 * <p><b>Default Retry Strategy:</b>
 * <ul>
 *   <li>Max attempts: 3 (original call + 2 retries)</li>
 *   <li>Initial backoff: 1 second</li>
 *   <li>Max backoff: 10 seconds</li>
 *   <li>Backoff multiplier: 2.0 (exponential)</li>
 *   <li>Retry on: All throwables (can be customized)</li>
 * </ul>
 *
 * <p><b>Backoff Calculation Example:</b>
 * <pre>
 * Attempt 1 fails → wait 1s (initial backoff)
 * Attempt 2 fails → wait 2s (1s * 2.0)
 * Attempt 3 fails → wait 4s (2s * 2.0)
 * </pre>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Use defaults (3 attempts, exponential backoff)
 * RetryConfig config = RetryConfig.builder().build();
 *
 * // Customize retry behavior
 * RetryConfig config = RetryConfig.builder()
 *     .maxAttempts(5)
 *     .initialBackoff(Duration.ofSeconds(2))
 *     .maxBackoff(Duration.ofSeconds(30))
 *     .backoffMultiplier(1.5)
 *     .retryOn(error -> error instanceof ModelException)
 *     .build();
 * }</pre>
 *
 * <p>Use the builder pattern to construct instances.
 *
 * @see GenerateOptions
 */
public class RetryConfig {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    private static final Predicate<Throwable> DEFAULT_RETRY_ON = throwable -> true;

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double backoffMultiplier;
    private final Predicate<Throwable> retryOn;

    /**
     * Creates a RetryConfig from the builder.
     *
     * @param builder the builder containing retry configuration
     */
    private RetryConfig(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.retryOn = builder.retryOn;
    }

    /**
     * Gets the maximum number of attempts (including the initial call).
     *
     * @return maximum attempts, must be at least 1
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Gets the initial backoff duration before the first retry.
     *
     * @return initial backoff duration
     */
    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    /**
     * Gets the maximum backoff duration between retries.
     *
     * @return maximum backoff duration
     */
    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    /**
     * Gets the multiplier for exponential backoff.
     *
     * <p>Each retry delay is calculated as: previous_delay * backoffMultiplier,
     * capped at maxBackoff.
     *
     * @return backoff multiplier, must be greater than 0
     */
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * Gets the predicate that determines if an error should trigger a retry.
     *
     * @return retry condition predicate
     */
    public Predicate<Throwable> getRetryOn() {
        return retryOn;
    }

    /**
     * Creates a new builder for RetryConfig.
     *
     * @return a new Builder instance with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating RetryConfig instances.
     */
    public static class Builder {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private double backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER;
        private Predicate<Throwable> retryOn = DEFAULT_RETRY_ON;

        /**
         * Sets the maximum number of attempts (including the initial call).
         *
         * <p>For example, maxAttempts=3 means 1 initial call + 2 retries.
         *
         * @param maxAttempts maximum attempts, must be at least 1
         * @return this builder instance
         * @throws IllegalArgumentException if maxAttempts is less than 1
         */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "maxAttempts must be at least 1, got: " + maxAttempts);
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the initial backoff duration before the first retry.
         *
         * @param initialBackoff initial backoff duration, must not be null or negative
         * @return this builder instance
         * @throws IllegalArgumentException if initialBackoff is null or negative
         */
        public Builder initialBackoff(Duration initialBackoff) {
            if (initialBackoff == null) {
                throw new IllegalArgumentException("initialBackoff must not be null");
            }
            if (initialBackoff.isNegative()) {
                throw new IllegalArgumentException("initialBackoff must not be negative");
            }
            this.initialBackoff = initialBackoff;
            return this;
        }

        /**
         * Sets the maximum backoff duration between retries.
         *
         * @param maxBackoff maximum backoff duration, must not be null or negative
         * @return this builder instance
         * @throws IllegalArgumentException if maxBackoff is null or negative
         */
        public Builder maxBackoff(Duration maxBackoff) {
            if (maxBackoff == null) {
                throw new IllegalArgumentException("maxBackoff must not be null");
            }
            if (maxBackoff.isNegative()) {
                throw new IllegalArgumentException("maxBackoff must not be negative");
            }
            this.maxBackoff = maxBackoff;
            return this;
        }

        /**
         * Sets the multiplier for exponential backoff.
         *
         * @param backoffMultiplier backoff multiplier, must be greater than 0
         * @return this builder instance
         * @throws IllegalArgumentException if backoffMultiplier is not positive
         */
        public Builder backoffMultiplier(double backoffMultiplier) {
            if (backoffMultiplier <= 0) {
                throw new IllegalArgumentException(
                        "backoffMultiplier must be positive, got: " + backoffMultiplier);
            }
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        /**
         * Sets the predicate that determines if an error should trigger a retry.
         *
         * <p>The predicate receives the throwable that caused the failure and should
         * return true if a retry should be attempted, false otherwise.
         *
         * <p>Example:
         * <pre>{@code
         * .retryOn(error -> error instanceof ModelException ||
         *                   error.getMessage().contains("rate limit"))
         * }</pre>
         *
         * @param retryOn retry condition predicate, must not be null
         * @return this builder instance
         * @throws IllegalArgumentException if retryOn is null
         */
        public Builder retryOn(Predicate<Throwable> retryOn) {
            if (retryOn == null) {
                throw new IllegalArgumentException("retryOn predicate must not be null");
            }
            this.retryOn = retryOn;
            return this;
        }

        /**
         * Builds a new RetryConfig instance with the configured values.
         *
         * @return a new RetryConfig instance
         */
        public RetryConfig build() {
            return new RetryConfig(this);
        }
    }
}
