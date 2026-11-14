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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class RunOutcomeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    static Stream<Arguments> results() {
        return Stream.of(
                Arguments.of(GenerateReason.MODEL_STOP, RunOutcome.Status.COMPLETED),
                Arguments.of(GenerateReason.TOOL_SUSPENDED, RunOutcome.Status.SUSPENDED),
                Arguments.of(GenerateReason.HOST_PAUSED, RunOutcome.Status.SUSPENDED),
                Arguments.of(GenerateReason.PERMISSION_ASKING, RunOutcome.Status.SUSPENDED),
                Arguments.of(GenerateReason.INTERRUPTED, RunOutcome.Status.INTERRUPTED),
                Arguments.of(GenerateReason.MAX_ITERATIONS, RunOutcome.Status.FAILED));
    }

    @ParameterizedTest
    @MethodSource("results")
    void semanticOutcomeIsIndependentOfPublisherCompletion(
            GenerateReason reason, RunOutcome.Status expected) {
        var cleanup = Sinks.<Void>empty();
        var calls = new AtomicInteger();
        var agent = agent(reason, cleanup.asMono(), calls);
        var run = AgentRun.create(agent.getAgentId(), () -> agent.call(new UserMessage("go")));
        var observed = run.outcome().toFuture();
        assertEquals(0, calls.get());
        assertFalse(observed.isDone());
        var output = run.stream().collectList().toFuture();
        assertEquals(1, calls.get());
        assertFalse(observed.isDone(), "Outcome must wait for persisted cleanup");
        cleanup.tryEmitEmpty();
        assertFalse(output.join().isEmpty());
        assertEquals(AgentRun.Status.COMPLETED, run.termination().block(TIMEOUT));
        var outcome = observed.join();
        assertEquals(expected, outcome.status());
        assertEquals(reason.name(), outcome.reason());
        assertEquals(outcome, run.outcome().block(TIMEOUT));
        var codec = JsonUtils.getJsonCodec();
        assertEquals(outcome, codec.fromJson(codec.toJson(outcome), RunOutcome.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reactCallAndStreamExposeTheSameInvocationOutcome(boolean streaming) {
        try (var agent =
                ReActAgent.builder().name("outcome").model(new MockModel("done")).build()) {
            AgentRun<?> run =
                    streaming
                            ? agent.prepareRun(List.of(new UserMessage("go")), null)
                            : agent.prepareCall(List.of(new UserMessage("go")), null);
            assertFalse(run.stream().collectList().block(TIMEOUT).isEmpty());
            assertEquals(RunOutcome.Status.COMPLETED, run.outcome().block(TIMEOUT).status());
            assertEquals(GenerateReason.MODEL_STOP.name(), run.outcome().block(TIMEOUT).reason());
        }
    }

    @Test
    void failedCleanupOverridesAnAlreadyProducedSuccessfulResult() {
        var cleanup = Sinks.<Void>empty();
        var agent = agent(GenerateReason.MODEL_STOP, cleanup.asMono(), new AtomicInteger());
        var run = AgentRun.create(agent.getAgentId(), () -> agent.call(new UserMessage("go")));
        var output = run.stream().collectList().toFuture();
        cleanup.tryEmitError(new IllegalStateException("checkpoint unavailable"));
        assertThrows(RuntimeException.class, output::join);
        assertEquals(AgentRun.Status.FAILED, run.status());
        assertEquals(RunOutcome.Status.FAILED, run.outcome().block(TIMEOUT).status());
    }

    @Test
    void cancellationBeforeSubscriptionHasOutcomeWithoutRunningAgent() {
        var calls = new AtomicInteger();
        var agent = agent(GenerateReason.MODEL_STOP, Mono.empty(), calls);
        var run = AgentRun.create(agent.getAgentId(), () -> agent.call(new UserMessage("go")));
        var outcome = run.outcome().toFuture();
        assertTrue(run.cancel());
        assertEquals(RunOutcome.Status.CANCELLED, outcome.join().status());
        assertEquals(0, calls.get());
    }

    private static AgentBase agent(GenerateReason reason, Mono<Void> cleanup, AtomicInteger calls) {
        return new AgentBase("outcome") {
            protected Mono<Msg> doCall(List<Msg> input) {
                calls.incrementAndGet();
                return Mono.just(
                        AssistantMessage.builder()
                                .textContent("result")
                                .generateReason(reason)
                                .build());
            }

            protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... input) {
                return Mono.just(
                        AssistantMessage.builder()
                                .textContent("interrupted")
                                .generateReason(GenerateReason.INTERRUPTED)
                                .build());
            }

            protected Mono<Void> doObserve(Msg message) {
                return Mono.empty();
            }

            protected Mono<Void> releaseCallExecution(Object scope, String status) {
                return cleanup;
            }
        };
    }
}
