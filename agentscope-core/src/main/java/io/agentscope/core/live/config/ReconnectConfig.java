/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.live.config;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reconnection configuration.
 *
 * <p>Note: Only Gemini and Doubao support automatic reconnection (native session resumption).
 * DashScope and OpenAI will end the stream on disconnection, leaving it to the user to decide
 * whether to rebuild the session.
 */
public final class ReconnectConfig {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double backoffMultiplier;
    private final double jitterFactor;

    private ReconnectConfig(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.jitterFactor = builder.jitterFactor;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public double getJitterFactor() {
        return jitterFactor;
    }

    /**
     * Calculate the delay for the nth reconnection attempt (exponential backoff + jitter).
     *
     * @param attempt the attempt number (1-based)
     * @return the delay duration
     */
    public Duration getDelayForAttempt(int attempt) {
        double baseDelay = initialDelay.toMillis() * Math.pow(backoffMultiplier, attempt - 1);
        double cappedDelay = Math.min(baseDelay, maxDelay.toMillis());
        double jitter =
                cappedDelay * jitterFactor * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Duration.ofMillis((long) Math.max(0, cappedDelay + jitter));
    }

    public static ReconnectConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(500);
        private Duration maxDelay = Duration.ofSeconds(10);
        private double backoffMultiplier = 2.0;
        private double jitterFactor = 0.2;

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        public ReconnectConfig build() {
            return new ReconnectConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReconnectConfig that = (ReconnectConfig) o;
        return maxAttempts == that.maxAttempts
                && Double.compare(that.backoffMultiplier, backoffMultiplier) == 0
                && Double.compare(that.jitterFactor, jitterFactor) == 0
                && Objects.equals(initialDelay, that.initialDelay)
                && Objects.equals(maxDelay, that.maxDelay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAttempts, initialDelay, maxDelay, backoffMultiplier, jitterFactor);
    }
}
