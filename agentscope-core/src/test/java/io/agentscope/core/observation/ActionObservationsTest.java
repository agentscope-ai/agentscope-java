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
package io.agentscope.core.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ActionObservationsTest {
    @Test
    void externalRequestCommitsBeforeItsSuspendedActionAndIsNotRecordedTwice() {
        var use = ToolUseBlock.builder().id("external").name("execute").input(Map.of()).build();
        var state =
                AgentState.builder()
                        .context(List.of(AssistantMessage.builder().content(use).build()))
                        .build();
        var context = RuntimeContext.builder().userId("u").sessionId("s").agentState(state).build();
        var log =
                new InMemoryThreadJournalStore()
                        .open(new ThreadKey("u", "a", "s", "main"), context);
        var recorder = new InvocationRecorder(log, "turn");
        context.put(InvocationRecorder.CONTEXT_KEY, recorder);
        try {
            var param = ToolCallParam.builder().toolUseBlock(use).runtimeContext(context).build();
            ActionObservations.observe(param, () -> Mono.just(ToolResultBlock.suspended(use)))
                    .block(Duration.ofSeconds(5));
            recorder.checkpointNow(state, "after_tool");
            var types = log.readAfter(0, 100).stream().map(e -> e.type()).toList();
            assertEquals(1, types.stream().filter("tool/requested"::equals).count());
            assertTrue(types.indexOf("tool/requested") < types.indexOf("action/start"));
            assertTrue(types.indexOf("action/start") < types.indexOf("action/end"));
        } finally {
            recorder.close();
        }
    }

    @Test
    void toolkitDoesNotRetryFailedSettlement() {
        AtomicInteger calls = new AtomicInteger();
        var toolkit = toolkit(calls);
        ActionObserver observer =
                (observation, result) ->
                        observation.status() == ActionObservation.Status.STARTED
                                ? Mono.empty()
                                : Mono.error(new IllegalStateException("store"));
        var param = param(observer);
        var config =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(1))
                        .build();
        StepVerifier.create(
                        toolkit.callTools(
                                List.of(param.getToolUseBlock()),
                                config,
                                null,
                                param.getRuntimeContext()))
                .expectErrorMatches(ActionObservationException::causedBy)
                .verify(Duration.ofSeconds(5));
        assertEquals(1, calls.get());
    }

    @Test
    void slowPersistenceDoesNotConsumeToolTimeoutOrReplayExecution() {
        AtomicInteger calls = new AtomicInteger();
        var toolkit = toolkit(calls);
        ActionObserver observer =
                (observation, result) -> Mono.delay(Duration.ofMillis(150)).then();
        var param = param(observer);
        var config =
                ExecutionConfig.builder().maxAttempts(3).timeout(Duration.ofMillis(100)).build();
        StepVerifier.create(
                        toolkit.callTools(
                                List.of(param.getToolUseBlock()),
                                config,
                                null,
                                param.getRuntimeContext()))
                .assertNext(results -> assertEquals(1, calls.get(), results.toString()))
                .verifyComplete();
        assertEquals(1, calls.get());
    }

    private Toolkit toolkit(AtomicInteger calls) {
        var toolkit = new Toolkit();
        var tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn("test");
        when(tool.getDescription()).thenReturn("test");
        when(tool.getParameters()).thenReturn(Map.of("type", "object", "properties", Map.of()));
        when(tool.callAsync(any()))
                .thenAnswer(
                        invocation ->
                                Mono.fromSupplier(
                                        () -> {
                                            calls.incrementAndGet();
                                            return ToolResultBlock.text("ok");
                                        }));
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private ToolCallParam param(ActionObserver observer) {
        return ToolCallParam.builder()
                .toolUseBlock(
                        ToolUseBlock.builder()
                                .id("call")
                                .name("test")
                                .input(Map.of())
                                .content("{}")
                                .build())
                .runtimeContext(
                        RuntimeContext.builder()
                                .sessionId("session")
                                .put(ActionObserver.CONTEXT_KEY, observer)
                                .build())
                .build();
    }

    @Test
    void eachSubscriptionHasItsOwnAttemptAndNoRawOutputInEvents() {
        List<ActionObservation> recorded = new ArrayList<>();
        ActionObserver observer =
                (observation, result) -> Mono.fromRunnable(() -> recorded.add(observation));
        var execution =
                ActionObservations.observe(
                        param(observer), () -> Mono.just(ToolResultBlock.text("secret")));
        assertEquals("call", execution.block().getId());
        execution.block();
        assertEquals(4, recorded.size());
        assertEquals(recorded.get(0).actionId(), recorded.get(1).actionId());
        assertNotEquals(recorded.get(0).actionId(), recorded.get(2).actionId());
        assertEquals(ActionObservation.Status.RETURNED, recorded.get(1).status());
        assertFalse(recorded.get(1).eventPayload().toString().contains("secret"));
    }

    @Test
    void failedStartPreventsExecution() {
        AtomicInteger calls = new AtomicInteger();
        ActionObserver observer =
                (observation, result) -> Mono.error(new IllegalStateException("store"));
        StepVerifier.create(
                        ActionObservations.observe(
                                param(observer),
                                () -> {
                                    calls.incrementAndGet();
                                    return Mono.just(ToolResultBlock.text("ok"));
                                }))
                .expectErrorMatches(ActionObservationException::causedBy)
                .verify();
        assertEquals(0, calls.get());
    }

    @Test
    void failedCommitIsNotRecordedAsToolFailure() {
        List<ActionObservation> recorded = new ArrayList<>();
        ActionObserver observer =
                (observation, result) -> {
                    recorded.add(observation);
                    return observation.status() == ActionObservation.Status.STARTED
                            ? Mono.empty()
                            : Mono.error(new IllegalStateException("store"));
                };
        StepVerifier.create(
                        ActionObservations.observe(
                                param(observer), () -> Mono.just(ToolResultBlock.text("ok"))))
                .expectErrorMatches(ActionObservationException::causedBy)
                .verify();
        assertEquals(2, recorded.size());
        assertEquals(ActionObservation.Status.RETURNED, recorded.get(1).status());
    }

    @Test
    void cancellationRecordsInterruption() {
        List<ActionObservation> recorded = new ArrayList<>();
        ActionObserver observer =
                (observation, result) -> Mono.fromRunnable(() -> recorded.add(observation));
        StepVerifier.create(ActionObservations.observe(param(observer), Mono::never))
                .then(() -> assertEquals(1, recorded.size()))
                .thenCancel()
                .verify();
        assertEquals(ActionObservation.Status.INTERRUPTED, recorded.get(1).status());
    }

    @Test
    void recordsExecutionFailureAndProducerDetails() {
        List<ActionObservation> recorded = new ArrayList<>();
        ActionObserver observer =
                (observation, result) -> Mono.fromRunnable(() -> recorded.add(observation));
        var details =
                new ToolExecutionDetails("shell", ToolExecutionDetails.Outcome.FAILED, 1, true);
        ActionObservations.observe(
                        param(observer),
                        () ->
                                Mono.just(
                                        ToolResultBlock.error("failed")
                                                .withExecutionDetails(details)))
                .block();
        assertEquals(ActionObservation.Status.FAILED, recorded.get(1).status());
        assertEquals(details, recorded.get(1).executionDetails());
        assertEquals(
                details,
                ToolResultBlock.text("ok")
                        .withExecutionDetails(details)
                        .withState(ToolResultState.ERROR)
                        .withIdAndName("id", "name")
                        .getExecutionDetails());
    }

    @Test
    void eventFailureCannotInvalidateDurableCommit() {
        List<ActionObservation> recorded = new ArrayList<>();
        ActionObserver observer =
                (observation, result) -> Mono.fromRunnable(() -> recorded.add(observation));
        AgentEventEmitter emitter =
                event -> {
                    throw new IllegalStateException("closed");
                };
        StepVerifier.create(
                        ActionObservations.observe(
                                        param(observer),
                                        () -> Mono.just(ToolResultBlock.text("ok")))
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        AgentEventEmitter.CONTEXT_KEY, emitter)))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(2, recorded.size());
        assertTrue(recorded.get(1).endedAt() != null);
    }
}
