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

package io.agentscope.core.training.util;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.training.backend.TrinityClient;
import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.runner.RunExecutionContext;
import io.agentscope.core.training.runner.TrainingConfig;
import io.agentscope.core.training.strategy.SamplingRateStrategy;
import java.util.Arrays;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Test utilities for Training module unit tests.
 */
public final class TrainingTestUtils {

    private TrainingTestUtils() {}

    /**
     * Creates a mock Agent with the given name.
     */
    public static Agent createMockAgent(String name) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        when(agent.getAgentId()).thenReturn("id-" + name);
        when(agent.getDescription()).thenReturn("Mock agent: " + name);
        return agent;
    }

    /**
     * Creates a mock RewardCalculator that returns the specified reward.
     */
    public static RewardCalculator createMockRewardCalculator(double reward) {
        RewardCalculator calculator = mock(RewardCalculator.class);
        when(calculator.calculate(org.mockito.ArgumentMatchers.any())).thenReturn(reward);
        return calculator;
    }

    /**
     * Creates a mock TrinityClient.
     */
    public static TrinityClient createMockTrinityClient() {
        TrinityClient client = mock(TrinityClient.class);
        when(client.getEndpoint()).thenReturn(TrainingTestConstants.TEST_TRINITY_ENDPOINT);
        when(client.feedback(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(client.commit(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        return client;
    }

    /**
     * Creates a default TrainingConfig for testing.
     */
    public static TrainingConfig createTestConfig() {
        return TrainingConfig.builder()
                .trinityEndpoint(TrainingTestConstants.TEST_TRINITY_ENDPOINT)
                .modelName(TrainingTestConstants.TEST_MODEL_NAME)
                .selectionStrategy(
                        SamplingRateStrategy.of(TrainingTestConstants.DEFAULT_SAMPLE_RATE))
                .rewardCalculator(createMockRewardCalculator(0.5))
                .commitIntervalSeconds(TrainingTestConstants.DEFAULT_COMMIT_INTERVAL)
                .build();
    }

    /**
     * Creates a RunExecutionContext for testing.
     */
    public static RunExecutionContext createTestContext(String taskId, String runId) {
        return RunExecutionContext.create(taskId, runId);
    }

    /**
     * Creates a test Msg list.
     */
    public static List<Msg> createTestMessages() {
        Msg msg1 = Msg.builder().name("user").role(MsgRole.USER).textContent("Hello").build();
        Msg msg2 =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .textContent("Hi there!")
                        .build();
        return Arrays.asList(msg1, msg2);
    }

    /**
     * Creates a single test Msg.
     */
    public static Msg createTestMessage(String name, MsgRole role, String content) {
        return Msg.builder().name(name).role(role).textContent(content).build();
    }

    /**
     * Creates a list of test message IDs.
     */
    public static List<String> createTestMsgIds() {
        return Arrays.asList(
                TrainingTestConstants.TEST_MSG_ID_1,
                TrainingTestConstants.TEST_MSG_ID_2,
                TrainingTestConstants.TEST_MSG_ID_3);
    }

    /**
     * Pauses execution for the specified milliseconds.
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
