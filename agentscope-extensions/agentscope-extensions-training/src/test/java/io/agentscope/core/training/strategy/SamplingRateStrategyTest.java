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

package io.agentscope.core.training.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.training.util.TrainingTestConstants;
import io.agentscope.core.training.util.TrainingTestUtils;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

@DisplayName("SamplingRateStrategy Unit Tests")
class SamplingRateStrategyTest {

    private Agent mockAgent;
    private List<Msg> testInputs;
    private Msg testOutput;

    @BeforeEach
    void setUp() {
        mockAgent = TrainingTestUtils.createMockAgent(TrainingTestConstants.TEST_AGENT_NAME);
        testInputs = TrainingTestUtils.createTestMessages();
        testOutput =
                TrainingTestUtils.createTestMessage("assistant", MsgRole.ASSISTANT, "Response");
    }

    @Test
    @DisplayName("Should create strategy with valid sample rate")
    void shouldCreateStrategyWithValidSampleRate() {
        // Act
        SamplingRateStrategy strategy = SamplingRateStrategy.of(0.5);

        // Assert
        assertNotNull(strategy);
        assertEquals(0.5, strategy.getSampleRate(), 0.001);
    }

    @Test
    @DisplayName("Should create strategy with zero sample rate")
    void shouldCreateStrategyWithZeroSampleRate() {
        // Act
        SamplingRateStrategy strategy = SamplingRateStrategy.of(0.0);

        // Assert
        assertEquals(0.0, strategy.getSampleRate(), 0.001);
    }

    @Test
    @DisplayName("Should create strategy with full sample rate")
    void shouldCreateStrategyWithFullSampleRate() {
        // Act
        SamplingRateStrategy strategy = SamplingRateStrategy.of(1.0);

        // Assert
        assertEquals(1.0, strategy.getSampleRate(), 0.001);
    }

    @Test
    @DisplayName("Should throw exception for negative sample rate")
    void shouldThrowExceptionForNegativeSampleRate() {
        assertThrows(IllegalArgumentException.class, () -> SamplingRateStrategy.of(-0.1));
    }

    @Test
    @DisplayName("Should throw exception for sample rate greater than 1")
    void shouldThrowExceptionForSampleRateGreaterThanOne() {
        assertThrows(IllegalArgumentException.class, () -> SamplingRateStrategy.of(1.1));
    }

    @Test
    @DisplayName("Should accept all with 100% sample rate")
    void shouldAcceptAllWithFullSampleRate() {
        // Arrange
        SamplingRateStrategy strategy = SamplingRateStrategy.of(1.0);

        // Act & Assert - run multiple times to verify consistency
        for (int i = 0; i < 100; i++) {
            SelectionDecision decision =
                    strategy.shouldSelect(mockAgent, testInputs, testOutput, Context.empty());
            assertTrue(decision.shouldTrain(), "Should accept with 100% rate");
        }
    }

    @Test
    @DisplayName("Should reject all with 0% sample rate")
    void shouldRejectAllWithZeroSampleRate() {
        // Arrange
        SamplingRateStrategy strategy = SamplingRateStrategy.of(0.0);

        // Act & Assert - run multiple times to verify consistency
        for (int i = 0; i < 100; i++) {
            SelectionDecision decision =
                    strategy.shouldSelect(mockAgent, testInputs, testOutput, Context.empty());
            assertFalse(decision.shouldTrain(), "Should reject with 0% rate");
        }
    }

    @Test
    @DisplayName("Should return correct priority")
    void shouldReturnCorrectPriority() {
        // Arrange
        SamplingRateStrategy strategy = SamplingRateStrategy.of(0.5);

        // Assert
        assertEquals(200, strategy.priority());
    }

    @Test
    @DisplayName("Should return correct name")
    void shouldReturnCorrectName() {
        // Arrange
        SamplingRateStrategy strategy = SamplingRateStrategy.of(0.5);

        // Assert
        assertEquals("SamplingRate(0.5)", strategy.name());
    }

    @Test
    @DisplayName("Should produce accept decision with correct reason")
    void shouldProduceAcceptDecisionWithCorrectReason() {
        // Arrange
        SamplingRateStrategy strategy = SamplingRateStrategy.of(1.0);

        // Act
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, Context.empty());

        // Assert
        assertEquals("sampling-rate", decision.getReason());
        assertTrue(decision.getLabels().contains("sampled"));
    }

    @Test
    @DisplayName("Should produce reject decision with correct reason")
    void shouldProduceRejectDecisionWithCorrectReason() {
        // Arrange
        SamplingRateStrategy strategy = SamplingRateStrategy.of(0.0);

        // Act
        SelectionDecision decision =
                strategy.shouldSelect(mockAgent, testInputs, testOutput, Context.empty());

        // Assert
        assertEquals("not-sampled", decision.getReason());
    }
}
