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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.legacy.hook.PreCallEvent;
import io.agentscope.core.legacy.hook.PreReasoningEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Unit tests for {@link ModelCallLimitHook}. */
class ModelCallLimitHookTest {

    @Test
    void allowsCallsUnderGlobalLimit() {
        ModelCallLimitHook hook = new ModelCallLimitHook(3);
        for (int i = 0; i < 3; i++) {
            PreReasoningEvent event = buildEvent();
            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
        }
        assertEquals(3, hook.getTotalCalls());
    }

    @Test
    void terminatesOnLimitExceeded() {
        ModelCallLimitHook hook = new ModelCallLimitHook(2);
        hook.onEvent(buildEvent()).block();
        hook.onEvent(buildEvent()).block();

        StepVerifier.create(hook.onEvent(buildEvent()))
                .expectErrorMatches(
                        e ->
                                e instanceof RuntimeException
                                        && e.getMessage().contains("Global model call limit"))
                .verify();
    }

    @Test
    void passesThrough_nonPreReasoningEvent() {
        ModelCallLimitHook hook = new ModelCallLimitHook(1);
        PreCallEvent other = new PreCallEvent(mock(Agent.class), List.of());

        StepVerifier.create(hook.onEvent(other)).expectNext(other).verifyComplete();
        assertEquals(0, hook.getTotalCalls(), "Non-PRE_REASONING events should not count");
    }

    private static PreReasoningEvent buildEvent() {
        return new PreReasoningEvent(mock(Agent.class), "stub-model", null, List.of());
    }
}
