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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.SamplingRateStrategy;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for TrainingRunner.
 */
@DisplayName("TrainingRunner Tests")
class TrainingRunnerTest {

    private MockWebServer mockServer;
    private String mockEndpoint;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        mockEndpoint = mockServer.url("/").toString().replaceAll("/$", "");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Should build TrainingRunner with config")
    void shouldBuildTrainingRunnerWithConfig() {
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(mockEndpoint)
                        .modelName("test-model")
                        .selectionStrategy(SamplingRateStrategy.of(0.1))
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        TrainingRunner runner = TrainingRunner.builder().config(config).build();

        assertNotNull(runner);
        assertFalse(runner.isRunning());
        assertEquals(config, runner.getConfig());
    }

    @Test
    @DisplayName("Should build TrainingRunner with builder methods")
    void shouldBuildTrainingRunnerWithBuilderMethods() {
        TrainingRunner runner =
                TrainingRunner.builder()
                        .trinityEndpoint(mockEndpoint)
                        .modelName("test-model")
                        .selectionStrategy(SamplingRateStrategy.of(0.1))
                        .rewardCalculator(mock(RewardCalculator.class))
                        .commitIntervalSeconds(300)
                        .repeatTime(3)
                        .build();

        assertNotNull(runner);
        assertFalse(runner.isRunning());
    }

    @Test
    @DisplayName("Should throw when config is set and builder methods are used")
    void shouldThrowWhenConfigSetAndBuilderMethodsUsed() {
        TrainingConfig config =
                TrainingConfig.builder()
                        .trinityEndpoint(mockEndpoint)
                        .modelName("test-model")
                        .selectionStrategy(SamplingRateStrategy.of(0.1))
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        assertThrows(
                IllegalStateException.class,
                () ->
                        TrainingRunner.builder()
                                .config(config)
                                .trinityEndpoint("http://other-endpoint")
                                .build());
    }

    @Test
    @DisplayName("Should throw when no config provided")
    void shouldThrowWhenNoConfigProvided() {
        assertThrows(IllegalStateException.class, () -> TrainingRunner.builder().build());
    }

    @Test
    @DisplayName("Should start and stop runner")
    void shouldStartAndStopRunner() throws InterruptedException {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\": \"success\", \"message\": \"OK\"}")
                        .addHeader("Content-Type", "application/json"));

        TrainingRunner runner =
                TrainingRunner.builder()
                        .trinityEndpoint(mockEndpoint)
                        .modelName("test-model")
                        .selectionStrategy(SamplingRateStrategy.of(0.1))
                        .rewardCalculator(mock(RewardCalculator.class))
                        .commitIntervalSeconds(0)
                        .build();

        assertFalse(runner.isRunning());

        runner.start();
        assertTrue(runner.isRunning());

        runner.start();
        assertTrue(runner.isRunning());

        runner.stop();
        assertFalse(runner.isRunning());

        runner.stop();
        assertFalse(runner.isRunning());
    }

    @Test
    @DisplayName("Should execute commit")
    void shouldExecuteCommit() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\": \"success\", \"message\": \"OK\"}")
                        .addHeader("Content-Type", "application/json"));

        TrainingRunner runner =
                TrainingRunner.builder()
                        .trinityEndpoint(mockEndpoint)
                        .modelName("test-model")
                        .selectionStrategy(SamplingRateStrategy.of(0.1))
                        .rewardCalculator(mock(RewardCalculator.class))
                        .build();

        runner.commit().block();

        assertEquals(1, mockServer.getRequestCount());
    }

    @Test
    @DisplayName("Should schedule periodic commits when interval > 0")
    void shouldSchedulePeriodicCommits() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\": \"success\", \"message\": \"OK\"}")
                        .addHeader("Content-Type", "application/json"));

        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\": \"success\", \"message\": \"OK\"}")
                        .addHeader("Content-Type", "application/json"));

        TrainingRunner runner =
                TrainingRunner.builder()
                        .trinityEndpoint(mockEndpoint)
                        .modelName("test-model")
                        .selectionStrategy(SamplingRateStrategy.of(0.1))
                        .rewardCalculator(mock(RewardCalculator.class))
                        .commitIntervalSeconds(1)
                        .build();

        runner.start();
        assertTrue(runner.isRunning());

        Thread.sleep(1500);

        runner.stop();

        assertTrue(mockServer.getRequestCount() >= 1);
    }
}
