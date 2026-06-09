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
package io.agentscope.harness.coding.middleware;

import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for {@link ThreadBudgetMiddleware}. */
class ThreadBudgetMiddlewareTest {

    private static final String TEST_THREAD = "test-thread-id";

    @BeforeEach
    void setThread() {
        MessageQueueMiddleware.CURRENT_THREAD_ID.set(TEST_THREAD);
    }

    @AfterEach
    void clearThread() {
        MessageQueueMiddleware.CURRENT_THREAD_ID.remove();
    }

    @Test
    void allowsCallsUnderBudget() {
        ThreadBudgetMiddleware mw = new ThreadBudgetMiddleware(3);
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(invoke(mw)).verifyComplete();
        }
    }

    @Test
    void terminatesWhenBudgetExceeded() {
        ThreadBudgetMiddleware mw = new ThreadBudgetMiddleware(2);
        invoke(mw).blockLast();
        invoke(mw).blockLast();

        StepVerifier.create(invoke(mw))
                .expectErrorMatches(
                        e ->
                                e instanceof RuntimeException
                                        && e.getMessage().contains("budget exceeded"))
                .verify();
    }

    @Test
    void noThreadId_doesNotEnforceBudget() {
        MessageQueueMiddleware.CURRENT_THREAD_ID.remove();
        ThreadBudgetMiddleware mw = new ThreadBudgetMiddleware(1);

        StepVerifier.create(invoke(mw)).verifyComplete();
        StepVerifier.create(invoke(mw)).verifyComplete();
    }

    @Test
    void resetThread_clearsBudget() {
        ThreadBudgetMiddleware mw = new ThreadBudgetMiddleware(1);
        invoke(mw).blockLast();

        StepVerifier.create(invoke(mw))
                .expectErrorMatches(e -> e.getMessage().contains("budget exceeded"))
                .verify();

        mw.resetThread(TEST_THREAD);
        StepVerifier.create(invoke(mw)).verifyComplete();
    }

    private static Flux<AgentEvent> invoke(ThreadBudgetMiddleware mw) {
        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
        Function<ReasoningInput, Flux<AgentEvent>> next = i -> Flux.empty();
        return mw.onReasoning(mock(Agent.class), null, input, next);
    }
}
