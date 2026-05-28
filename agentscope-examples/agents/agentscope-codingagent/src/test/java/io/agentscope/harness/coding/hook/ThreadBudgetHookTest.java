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
package io.agentscope.harness.coding.hook;

import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.legacy.hook.PreCallEvent;
import io.agentscope.core.legacy.hook.PreReasoningEvent;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Unit tests for {@link ThreadBudgetHook}. */
class ThreadBudgetHookTest {

    private static final String TEST_THREAD = "test-thread-id";

    @BeforeEach
    void setThread() {
        MessageQueueHook.CURRENT_THREAD_ID.set(TEST_THREAD);
    }

    @AfterEach
    void clearThread() {
        MessageQueueHook.CURRENT_THREAD_ID.remove();
    }

    @Test
    void allowsCallsUnderBudget() {
        ThreadBudgetHook hook = new ThreadBudgetHook(3);

        for (int i = 0; i < 3; i++) {
            PreReasoningEvent event = buildPreReasoningEvent();
            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
        }
    }

    @Test
    void terminatesWhenBudgetExceeded() {
        ThreadBudgetHook hook = new ThreadBudgetHook(2);

        hook.onEvent(buildPreReasoningEvent()).block();
        hook.onEvent(buildPreReasoningEvent()).block();

        StepVerifier.create(hook.onEvent(buildPreReasoningEvent()))
                .expectErrorMatches(
                        e ->
                                e instanceof RuntimeException
                                        && e.getMessage().contains("budget exceeded"))
                .verify();
    }

    @Test
    void passesThrough_nonPreReasoningEvent() {
        ThreadBudgetHook hook = new ThreadBudgetHook(1);
        PreCallEvent other = new PreCallEvent(mock(Agent.class), List.of());

        StepVerifier.create(hook.onEvent(other)).expectNext(other).verifyComplete();
    }

    @Test
    void noThreadId_doesNotEnforceBudget() {
        MessageQueueHook.CURRENT_THREAD_ID.remove();
        ThreadBudgetHook hook = new ThreadBudgetHook(1);

        PreReasoningEvent e1 = buildPreReasoningEvent();
        PreReasoningEvent e2 = buildPreReasoningEvent();

        StepVerifier.create(hook.onEvent(e1)).expectNext(e1).verifyComplete();
        StepVerifier.create(hook.onEvent(e2)).expectNext(e2).verifyComplete();
    }

    @Test
    void resetThread_clearsBudget() {
        ThreadBudgetHook hook = new ThreadBudgetHook(1);

        hook.onEvent(buildPreReasoningEvent()).block();

        StepVerifier.create(hook.onEvent(buildPreReasoningEvent()))
                .expectErrorMatches(e -> e.getMessage().contains("budget exceeded"))
                .verify();

        hook.resetThread(TEST_THREAD);

        PreReasoningEvent e = buildPreReasoningEvent();
        StepVerifier.create(hook.onEvent(e)).expectNext(e).verifyComplete();
    }

    private static PreReasoningEvent buildPreReasoningEvent() {
        Agent agent = mock(Agent.class);
        return new PreReasoningEvent(agent, "stub-model", null, List.of());
    }
}
