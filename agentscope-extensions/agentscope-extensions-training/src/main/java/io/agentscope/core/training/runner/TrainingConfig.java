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

package io.agentscope.core.training.runner;

import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.SamplingRateStrategy;
import io.agentscope.core.training.strategy.TrainingSelectionStrategy;
import java.time.Duration;

/**
 * Configuration for Training Runner.
 *
 * <p>Configures various parameters for the training process, including Trinity service address, training model, selection strategy, reward calculator, etc.
 *
 * <p><b>Automated design:</b>
 * <ul>
 *   <li>Task ID and Run ID are fully managed automatically by the system</li>
 *   <li>Users don't need to worry about task tracking details</li>
 *   <li>Supports flexible training request filtering strategies</li>
 * </ul>
 */
public class TrainingConfig {
    private final String trinityEndpoint; // Trinity service address, e.g. http://47.252.36.19:8010
    private final String trinityApiKey; // Trinity API Key (optional, some deployments require it)
    private final String
            modelName; // Training model path, e.g. /home/ecs-user/models/Qwen2.5-0.5B-Instruct
    private final TrainingSelectionStrategy
            selectionStrategy; // Training request filtering strategy
    private final RewardCalculator rewardCalculator;
    private final long commitIntervalSeconds;
    private final Duration httpTimeout;
    private final boolean enableAutoCommit;
    private final int shadowPoolSize;
    private final int shadowPoolCapacity;
    private final int repeatTime; // Number of times each task runs repeatedly, defaults to 1

    private TrainingConfig(Builder builder) {
        this.trinityEndpoint = builder.trinityEndpoint;
        this.trinityApiKey = builder.trinityApiKey;
        this.modelName = builder.modelName;
        this.selectionStrategy = builder.selectionStrategy;
        this.rewardCalculator = builder.rewardCalculator;
        this.commitIntervalSeconds = builder.commitIntervalSeconds;
        this.httpTimeout = builder.httpTimeout;
        this.enableAutoCommit = builder.enableAutoCommit;
        this.shadowPoolSize = builder.shadowPoolSize;
        this.shadowPoolCapacity = builder.shadowPoolCapacity;
        this.repeatTime = builder.repeatTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTrinityEndpoint() {
        return trinityEndpoint;
    }

    public String getTrinityApiKey() {
        return trinityApiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public TrainingSelectionStrategy getSelectionStrategy() {
        return selectionStrategy;
    }

    public RewardCalculator getRewardCalculator() {
        return rewardCalculator;
    }

    public long getCommitIntervalSeconds() {
        return commitIntervalSeconds;
    }

    public Duration getHttpTimeout() {
        return httpTimeout;
    }

    public boolean isEnableAutoCommit() {
        return enableAutoCommit;
    }

    public int getShadowPoolSize() {
        return shadowPoolSize;
    }

    public int getShadowPoolCapacity() {
        return shadowPoolCapacity;
    }

    public int getRepeatTime() {
        return repeatTime;
    }

    /**
     * Get current sampling rate
     *
     * @return Sampling rate (0.0 ~ 1.0), returns -1 if using non-sampling strategy
     */
    public double getSampleRate() {
        if (selectionStrategy instanceof SamplingRateStrategy) {
            return ((SamplingRateStrategy) selectionStrategy).getSampleRate();
        }
        return -1;
    }

    public static class Builder {
        private String trinityEndpoint;
        private String trinityApiKey =
                "dummy"; // Default value, some Trinity deployments don't need real key
        private String modelName = "training-model";
        private TrainingSelectionStrategy selectionStrategy; // Optional, defaults to 10% sampling
        private RewardCalculator rewardCalculator;
        private long commitIntervalSeconds = 300; // Default 5 minutes
        private Duration httpTimeout = Duration.ofSeconds(300); // Default 5 minutes timeout
        private boolean enableAutoCommit = true;
        private int shadowPoolSize = 10; // Default shadow Agent thread pool size
        private int shadowPoolCapacity = 1000; // Default shadow Agent queue capacity
        private int repeatTime = 1; // Default each task runs 1 time

        public Builder trinityEndpoint(String endpoint) {
            this.trinityEndpoint = endpoint;
            return this;
        }

        public Builder trinityApiKey(String apiKey) {
            this.trinityApiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Set training request filtering strategy
         *
         * <p>Supports multiple strategies:
         * <ul>
         *   <li>{@link SamplingRateStrategy} - Based on sampling rate</li>
         *   <li>{@link io.agentscope.core.training.strategy.ExplicitMarkingStrategy} - Based on user explicit marking</li>
         *   <li>Custom strategy - Implement {@link TrainingSelectionStrategy}</li>
         * </ul>
         *
         * @param strategy Filtering strategy
         * @return this
         */
        public Builder selectionStrategy(TrainingSelectionStrategy strategy) {
            this.selectionStrategy = strategy;
            return this;
        }

        /**
         * Shortcut method: Set simple sampling rate strategy
         *
         * @param sampleRate Sampling rate (0.0 ~ 1.0)
         * @return this
         * @deprecated Recommend using {@link #selectionStrategy(TrainingSelectionStrategy)}
         */
        @Deprecated
        public Builder sampleRate(double sampleRate) {
            if (sampleRate < 0 || sampleRate > 1) {
                throw new IllegalArgumentException("sampleRate must be between 0 and 1");
            }
            this.selectionStrategy = SamplingRateStrategy.of(sampleRate);
            return this;
        }

        public Builder rewardCalculator(RewardCalculator calculator) {
            this.rewardCalculator = calculator;
            return this;
        }

        public Builder commitIntervalSeconds(long seconds) {
            this.commitIntervalSeconds = seconds;
            return this;
        }

        public Builder httpTimeout(Duration timeout) {
            this.httpTimeout = timeout;
            return this;
        }

        public Builder enableAutoCommit(boolean enable) {
            this.enableAutoCommit = enable;
            return this;
        }

        public Builder shadowPoolSize(int size) {
            this.shadowPoolSize = size;
            return this;
        }

        public Builder shadowPoolCapacity(int capacity) {
            this.shadowPoolCapacity = capacity;
            return this;
        }

        /**
         * Set repeat execution count for each task
         *
         * <p>When a request is selected for training, it will run repeatTime times with the same taskId,
         * each run will be assigned an incrementing runId (0, 1, 2, ...).
         *
         * <p><b>Use cases:</b>
         * <ul>
         *   <li>A/B/C/D testing: Compare different strategy effects</li>
         *   <li>Diversity training: Generate multiple training samples for the same task</li>
         *   <li>Stability assessment: Evaluate model stability with same input</li>
         * </ul>
         *
         * @param repeatTime Repeat execution count, must be >= 1, defaults to 1
         * @return this
         * @throws IllegalArgumentException if repeatTime &lt; 1
         */
        public Builder repeatTime(int repeatTime) {
            if (repeatTime < 1) {
                throw new IllegalArgumentException("repeatTime must be >= 1");
            }
            this.repeatTime = repeatTime;
            return this;
        }

        public TrainingConfig build() {
            if (trinityEndpoint == null || trinityEndpoint.isEmpty()) {
                throw new IllegalArgumentException("Trinity endpoint must be specified");
            }
            if (rewardCalculator == null) {
                throw new IllegalArgumentException("RewardCalculator must be specified");
            }

            // If no strategy specified, use default 10% sampling rate
            if (selectionStrategy == null) {
                selectionStrategy = SamplingRateStrategy.of(0.1);
            }

            return new TrainingConfig(this);
        }
    }
}
