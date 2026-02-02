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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.training.backend.TrinityClient;
import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.SelectionDecision;
import io.agentscope.core.training.strategy.TrainingSelectionStrategy;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * Tests for TrainingRouter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrainingRouter Tests")
class TrainingRouterTest {

    @Mock private TrinityClient trinityClient;

    @Mock private RewardCalculator rewardCalculator;

    @Mock private TrainingSelectionStrategy selectionStrategy;

    @Mock private Agent mockAgent;

    private TrainingConfig config;
    private TrainingRouter router;

    @BeforeEach
    void setUp() {
        config =
                TrainingConfig.builder()
                        .trinityEndpoint("http://localhost:8080")
                        .modelName("test-model")
                        .selectionStrategy(selectionStrategy)
                        .rewardCalculator(rewardCalculator)
                        .shadowPoolSize(2)
                        .shadowPoolCapacity(10)
                        .build();

        router = new TrainingRouter(config, trinityClient, rewardCalculator, selectionStrategy);
    }

    @Test
    @DisplayName("Should return correct priority")
    void shouldReturnCorrectPriority() {
        assertEquals(500, router.priority());
    }

    @Test
    @DisplayName("Should handle PreCallEvent and save input messages")
    void shouldHandlePreCallEventAndSaveInputMessages() {
        when(mockAgent.getAgentId()).thenReturn("agent-123");

        List<Msg> inputMessages =
                Collections.singletonList(
                        Msg.builder().role(MsgRole.USER).textContent("Hello").build());

        PreCallEvent event = new PreCallEvent(mockAgent, inputMessages);

        StepVerifier.create(router.onEvent(event)).expectNext(event).verifyComplete();
    }

    @Test
    @DisplayName("Should skip training for shadow agent")
    void shouldSkipTrainingForShadowAgent() {
        when(mockAgent.getAgentId()).thenReturn("agent-shadow-123");
        when(mockAgent.getName()).thenReturn("TestAgent-shadow");

        Msg outputMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("Response").build();
        PostCallEvent event = new PostCallEvent(mockAgent, outputMsg);

        StepVerifier.create(router.onEvent(event)).expectNext(event).verifyComplete();

        verify(selectionStrategy, never()).shouldSelect(any(), anyList(), any(), any());
    }

    @Test
    @DisplayName("Should skip training when selection decision is false")
    void shouldSkipTrainingWhenSelectionDecisionIsFalse() {
        when(mockAgent.getAgentId()).thenReturn("agent-123");
        when(mockAgent.getName()).thenReturn("TestAgent");

        List<Msg> inputMessages =
                Collections.singletonList(
                        Msg.builder().role(MsgRole.USER).textContent("Hello").build());
        PreCallEvent preEvent = new PreCallEvent(mockAgent, inputMessages);

        router.onEvent(preEvent).block();

        Msg outputMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("Response").build();
        PostCallEvent postEvent = new PostCallEvent(mockAgent, outputMsg);

        SelectionDecision rejectDecision = SelectionDecision.reject("Not selected");
        when(selectionStrategy.shouldSelect(any(), anyList(), any(), any()))
                .thenReturn(rejectDecision);

        StepVerifier.create(router.onEvent(postEvent).contextWrite(Context.empty()))
                .expectNext(postEvent)
                .verifyComplete();

        verify(trinityClient, never()).feedback(any());
    }

    @Test
    @DisplayName("Should return empty when no input messages found")
    void shouldReturnEmptyWhenNoInputMessagesFound() {
        when(mockAgent.getAgentId()).thenReturn("agent-no-input");
        when(mockAgent.getName()).thenReturn("TestAgent");

        Msg outputMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("Response").build();
        PostCallEvent event = new PostCallEvent(mockAgent, outputMsg);

        StepVerifier.create(router.onEvent(event).contextWrite(Context.empty()))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should pass through other event types like ErrorEvent")
    void shouldPassThroughOtherEventTypes() {
        ErrorEvent errorEvent = new ErrorEvent(mockAgent, new RuntimeException("Test error"));

        StepVerifier.create(router.onEvent(errorEvent)).expectNext(errorEvent).verifyComplete();
    }

    @Test
    @DisplayName("Should create router with valid config")
    void shouldCreateRouterWithValidConfig() {
        TrainingRouter newRouter =
                new TrainingRouter(config, trinityClient, rewardCalculator, selectionStrategy);

        assertNotNull(newRouter);
        assertEquals(500, newRouter.priority());
    }
}
