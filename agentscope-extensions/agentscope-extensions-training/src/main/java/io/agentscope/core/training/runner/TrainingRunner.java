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

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.training.backend.TrinityClient;
import io.agentscope.core.training.backend.dto.CommitRequest;
import io.agentscope.core.training.reward.RewardCalculator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Training Runner - Training Process Controller
 *
 * <p>Responsible for:
 * <ul>
 *   <li>Intercepting Agent requests and determining if they should be used for training</li>
 *   <li>Replacing ChatClient to Trinity backend</li>
 *   <li>Collecting trace data</li>
 *   <li>Calculating rewards and calling feedback API</li>
 *   <li>Periodically calling commit API to trigger training</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * // Method 1: Using sampling rate strategy
 * TrainingRunner runner = TrainingRunner.builder()
 *     .trinityEndpoint("http://localhost:8080")
 *     .modelName("/path/to/model")
 *     .selectionStrategy(SamplingRateStrategy.of(0.1))  // 10% sampling rate
 *     .rewardCalculator(agent -> 0.0)  // Implement custom reward logic
 *     .commitIntervalSeconds(300)  // Commit every 5 minutes
 *     .build();
 *
 * // Method 2: Using explicit marking strategy
 * TrainingRunner runner = TrainingRunner.builder()
 *     .trinityEndpoint("http://localhost:8080")
 *     .modelName("/path/to/model")
 *     .selectionStrategy(ExplicitMarkingStrategy.create())  // User manually marks
 *     .rewardCalculator(agent -> {
 *         // Custom reward calculation logic
 *         return 1.0;
 *     })
 *     .build();
 *
 * // Method 3: Using custom RewardCalculator class
 * TrainingRunner runner = TrainingRunner.builder()
 *     .trinityEndpoint("http://localhost:8080")
 *     .modelName("/path/to/model")
 *     .selectionStrategy(SamplingRateStrategy.of(0.1))
 *     .rewardCalculator(new CustomRewardCalculator())  // Implement RewardCalculator interface
 *     .build();
 *
 * // Start training runner
 * runner.start();
 *
 * // Users use Agent normally (transparent)
 * agent.call(msg).block();
 *
 * // Stop training
 * runner.stop();
 * }</pre>
 */
public class TrainingRunner {
    private static final Logger logger = LoggerFactory.getLogger(TrainingRunner.class);

    private final TrainingConfig config;
    private final TrinityClient trinityClient;
    private final TrainingRouter router;

    private ScheduledExecutorService commitScheduler;
    private volatile boolean running = false;

    private TrainingRunner(Builder builder) {
        this.config = builder.config;

        // Initialize Trinity client
        this.trinityClient =
                TrinityClient.builder()
                        .endpoint(config.getTrinityEndpoint())
                        .timeout(config.getHttpTimeout())
                        .build();

        // Initialize router
        this.router =
                new TrainingRouter(
                        config,
                        trinityClient,
                        config.getRewardCalculator(),
                        config.getSelectionStrategy());
    }

    /**
     * Start training runner
     */
    public synchronized void start() {
        if (running) {
            logger.warn("TrainingRunner already running");
            return;
        }

        logger.info(
                "Starting TrainingRunner: strategy={}, model={}",
                config.getSelectionStrategy().getClass().getSimpleName(),
                config.getModelName());

        // Register TrainingRouter as system hook
        AgentBase.addSystemHook(router);

        // Start periodic commit scheduler
        startCommitScheduler();

        running = true;
        logger.info("TrainingRunner started successfully");
    }

    /**
     * Stop training runner
     */
    public synchronized void stop() {
        if (!running) {
            logger.warn("TrainingRunner not running");
            return;
        }

        logger.info("Stopping TrainingRunner...");

        // Stop commit scheduler
        if (commitScheduler != null) {
            commitScheduler.shutdown();
            try {
                if (!commitScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    commitScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                commitScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Final commit
        commit().block();

        // Remove hook
        AgentBase.removeSystemHook(router);
        logger.debug("Removed training router hook from AgentBase");

        running = false;
        logger.info("TrainingRunner stopped");
    }

    /**
     * Manually trigger commit
     */
    public Mono<Void> commit() {
        return trinityClient
                .commit(CommitRequest.builder().build())
                .doOnSuccess(v -> logger.info("Training commit successful"))
                .doOnError(e -> logger.error("Failed to commit training", e))
                .then();
    }

    /**
     * Start periodic commit scheduler
     */
    private void startCommitScheduler() {
        if (config.getCommitIntervalSeconds() <= 0) {
            logger.info("Commit scheduler disabled (interval <= 0)");
            return;
        }

        commitScheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "training-commit-scheduler");
                            thread.setDaemon(true);
                            return thread;
                        });

        commitScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        commit().block();
                    } catch (Exception e) {
                        logger.error("Error in scheduled commit", e);
                    }
                },
                config.getCommitIntervalSeconds(),
                config.getCommitIntervalSeconds(),
                TimeUnit.SECONDS);

        logger.info(
                "Commit scheduler started with interval: {} seconds",
                config.getCommitIntervalSeconds());
    }

    public boolean isRunning() {
        return running;
    }

    public TrainingConfig getConfig() {
        return config;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for TrainingRunner
     */
    public static class Builder {
        private TrainingConfig config;
        private TrainingConfig.Builder configBuilder;

        public Builder config(TrainingConfig config) {
            this.config = config;
            return this;
        }

        public Builder trinityEndpoint(String endpoint) {
            getConfigBuilder().trinityEndpoint(endpoint);
            return this;
        }

        public Builder modelName(String modelName) {
            getConfigBuilder().modelName(modelName);
            return this;
        }

        public Builder selectionStrategy(
                io.agentscope.core.training.strategy.TrainingSelectionStrategy strategy) {
            getConfigBuilder().selectionStrategy(strategy);
            return this;
        }

        public Builder rewardCalculator(RewardCalculator calculator) {
            getConfigBuilder().rewardCalculator(calculator);
            return this;
        }

        public Builder commitIntervalSeconds(long seconds) {
            getConfigBuilder().commitIntervalSeconds(seconds);
            return this;
        }

        public Builder repeatTime(int repeatTime) {
            getConfigBuilder().repeatTime(repeatTime);
            return this;
        }

        private TrainingConfig.Builder getConfigBuilder() {
            if (config != null) {
                throw new IllegalStateException(
                        "Cannot use builder methods when config is already set");
            }
            if (configBuilder == null) {
                configBuilder = TrainingConfig.builder();
            }
            return configBuilder;
        }

        public TrainingRunner build() {
            if (config == null) {
                if (configBuilder == null) {
                    throw new IllegalStateException(
                            "TrainingConfig must be set. Use config() or builder methods.");
                }
                config = configBuilder.build();
            }
            return new TrainingRunner(this);
        }
    }
}
