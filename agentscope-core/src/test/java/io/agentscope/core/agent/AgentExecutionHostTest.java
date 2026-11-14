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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.JournalException;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AgentExecutionHostTest {
    @ParameterizedTest
    @CsvSource({
        "REASONING,false",
        "REASONING,true",
        "MODEL,false",
        "MODEL,true",
        "FINALIZATION,false",
        "FINALIZATION,true"
    })
    void cooperativePausePreservesInvocationOutcomeAndResumesWithoutRepeatingAProducedReply(
            AgentExecutionHost.Boundary boundary, boolean streaming) {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var pausing = new AtomicBoolean(true);
        var reply = new AtomicReference<Msg>();
        var host =
                new AgentExecutionHost() {
                    public Mono<Void> beforeBoundary(
                            Boundary current, RuntimeContext runtime, AgentState state) {
                        return current == boundary && pausing.get()
                                ? Mono.error(new Paused())
                                : Mono.empty();
                    }

                    public Msg resumeResult() {
                        return reply.get();
                    }

                    public Mono<Void> finish(
                            RuntimeContext runtime, Msg result, Supplier<Mono<Void>> next) {
                        reply.compareAndSet(null, result);
                        return next.get();
                    }
                };
        try (var agent = agent(calls, finishes)) {
            var context =
                    RuntimeContext.builder()
                            .sessionId("paused")
                            .turnId("task")
                            .put(AgentExecutionHost.class, host)
                            .build();
            var input = List.<Msg>of(new UserMessage("original input"));
            AgentRun<?> first =
                    streaming
                            ? agent.prepareRun(input, context)
                            : agent.prepareCall(input, context);
            var output = first.stream().collectList().block(Duration.ofSeconds(5));
            assertNotNull(output);
            Msg paused =
                    streaming
                            ? output.stream()
                                    .filter(AgentResultEvent.class::isInstance)
                                    .map(AgentResultEvent.class::cast)
                                    .findFirst()
                                    .orElseThrow()
                                    .getResult()
                            : (Msg) output.get(0);
            assertEquals(GenerateReason.HOST_PAUSED, paused.getGenerateReason());
            assertEquals("", paused.getTextContent());
            assertEquals(
                    new RunOutcome(RunOutcome.Status.SUSPENDED, "HOST_PAUSED"),
                    first.outcome().block(Duration.ofSeconds(5)));
            assertEquals(boundary == AgentExecutionHost.Boundary.FINALIZATION ? 1 : 0, calls.get());
            assertEquals(0, finishes.get());
            var log = agent.threadJournal(context);
            var pausedEvents =
                    StreamSupport.stream(log.scan(0, log.head().seq()).spliterator(), false)
                            .toList();
            assertFalse(pausedEvents.stream().anyMatch(e -> "run/output".equals(e.type())));
            assertTrue(
                    pausedEvents.stream()
                            .anyMatch(
                                    e ->
                                            "run/end".equals(e.type())
                                                    && "suspended".equals(e.data().get("status"))
                                                    && "HOST_PAUSED"
                                                            .equals(e.data().get("reason"))));

            pausing.set(false);
            var resumed =
                    RuntimeContext.builder()
                            .sessionId("paused")
                            .turnId("task")
                            .put(AgentExecutionHost.class, host)
                            .build();
            var second = agent.prepareCall(List.of(), resumed);
            assertEquals(
                    "new reply",
                    second.stream().single().block(Duration.ofSeconds(5)).getTextContent());
            assertEquals(
                    RunOutcome.Status.COMPLETED,
                    second.outcome().block(Duration.ofSeconds(5)).status());
            assertEquals(1, calls.get(), "A generated final reply must survive pause");
            assertEquals(1, finishes.get());
            assertEquals(
                    2,
                    StreamSupport.stream(log.scan(0, log.head().seq()).spliterator(), false)
                            .filter(e -> "run/start".equals(e.type()))
                            .map(e -> e.executionRunId())
                            .distinct()
                            .count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hostAppliesInputBeforeModelAndChecksFinalizationForCallAndStream(boolean streaming) {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var finalizations = new AtomicInteger();
        var host =
                new AgentExecutionHost() {
                    public Mono<Void> beforeBoundary(
                            Boundary boundary, RuntimeContext runtime, AgentState state) {
                        return Mono.fromRunnable(
                                () -> {
                                    assertNotNull(runtime.getRunId());
                                    if (boundary == Boundary.REASONING)
                                        state.contextMutable().add(new UserMessage("host input"));
                                    if (boundary == Boundary.FINALIZATION)
                                        finalizations.incrementAndGet();
                                });
                    }

                    public Mono<Void> finish(
                            RuntimeContext runtime, Msg result, Supplier<Mono<Void>> next) {
                        assertEquals(0, finalizations.get());
                        return next.get()
                                .then(
                                        Mono.fromRunnable(
                                                () -> assertEquals(1, finalizations.get())));
                    }
                };
        var context =
                RuntimeContext.builder()
                        .sessionId("hosted")
                        .put(AgentExecutionHost.class, host)
                        .build();
        try (var agent =
                agent(
                        calls,
                        finishes,
                        messages ->
                                assertTrue(
                                        messages.stream()
                                                .anyMatch(
                                                        message ->
                                                                "host input"
                                                                        .equals(
                                                                                message
                                                                                        .getTextContent()))))) {
            var input = List.<Msg>of(new UserMessage("original input"));
            var actual =
                    streaming
                            ? agent.streamEvents(input, context)
                                    .ofType(AgentResultEvent.class)
                                    .map(AgentResultEvent::getResult)
                                    .single()
                                    .block(Duration.ofSeconds(5))
                            : agent.call(input, context).block(Duration.ofSeconds(5));
            assertEquals("new reply", actual.getTextContent());
            assertEquals(1, calls.get());
            assertEquals(1, finishes.get());
        }
    }

    @Test
    void rejectedModelBoundaryDoesNotDispatchOrReportACompletedReply() {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var host =
                new AgentExecutionHost() {
                    public Mono<Void> beforeBoundary(
                            Boundary boundary, RuntimeContext runtime, AgentState state) {
                        return boundary == Boundary.MODEL
                                ? Mono.error(new JournalException("host no longer owns execution"))
                                : Mono.empty();
                    }

                    public Mono<Void> finish(
                            RuntimeContext runtime, Msg result, Supplier<Mono<Void>> next) {
                        return next.get();
                    }
                };
        var context =
                RuntimeContext.builder()
                        .sessionId("fenced")
                        .put(AgentExecutionHost.class, host)
                        .build();
        try (var agent = agent(calls, finishes)) {
            StepVerifier.create(agent.call(List.of(new UserMessage("hello")), context))
                    .expectError(JournalException.class)
                    .verify(Duration.ofSeconds(5));
            assertEquals(0, calls.get());
            assertEquals(0, finishes.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumedFinalizationPreservesReplyAndFinishHooksWithoutPrimaryInference(boolean streaming) {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var agent = agent(calls, finishes);
        Msg original = Msg.builder().name("agent").textContent("already produced").build();
        var context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .put(
                                AgentExecutionHost.class,
                                new AgentExecutionHost() {
                                    public Msg resumeResult() {
                                        return original;
                                    }

                                    public Mono<Void> finish(
                                            RuntimeContext runtime,
                                            Msg result,
                                            Supplier<Mono<Void>> next) {
                                        assertNotNull(runtime.getAgentState());
                                        assertEquals(original, result);
                                        return next.get();
                                    }
                                })
                        .build();
        Msg actual =
                streaming
                        ? agent.streamEvents(List.of(new UserMessage("resume")), context)
                                .ofType(AgentResultEvent.class)
                                .map(AgentResultEvent::getResult)
                                .single()
                                .block(Duration.ofSeconds(5))
                        : agent.call(List.of(new UserMessage("resume")), context)
                                .block(Duration.ofSeconds(5));
        assertEquals(original, actual);
        assertEquals(0, calls.get());
        assertEquals(1, finishes.get());
    }

    @Test
    void observerKeepsOrdinaryModelAndFinishBehavior() {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var observed = new AtomicInteger();
        var context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .put(
                                AgentExecutionHost.class,
                                (rc, result, next) -> {
                                    observed.incrementAndGet();
                                    return next.get();
                                })
                        .build();
        assertEquals(
                "new reply",
                agent(calls, finishes)
                        .call(List.of(new UserMessage("hello")), context)
                        .block(Duration.ofSeconds(5))
                        .getTextContent());
        assertEquals(1, calls.get());
        assertEquals(1, finishes.get());
        assertEquals(1, observed.get());
    }

    @Test
    void persistenceFailureInFinalizationIsNotConvertedToAReply() {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .put(
                                AgentExecutionHost.class,
                                (rc, result, next) ->
                                        Mono.error(
                                                new JournalException("unconfirmed finalization")))
                        .build();
        StepVerifier.create(agent(calls, finishes).call(List.of(new UserMessage("hello")), context))
                .expectError(JournalException.class)
                .verify(Duration.ofSeconds(5));
        assertEquals(1, calls.get());
        assertEquals(0, finishes.get());
    }

    private ReActAgent agent(AtomicInteger calls, AtomicInteger finishes) {
        return agent(calls, finishes, messages -> {});
    }

    private ReActAgent agent(
            AtomicInteger calls, AtomicInteger finishes, Consumer<List<Msg>> inputs) {
        Model model =
                new Model() {
                    public String getModelName() {
                        return "finalization-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        inputs.accept(messages);
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("new reply")
                                                                .build()))
                                        .build());
                    }
                };
        return ReActAgent.builder()
                .name("agent")
                .model(model)
                .threadJournalStore(new InMemoryThreadJournalStore())
                .middleware(
                        new MiddlewareBase() {
                            public Set<ExtensionPoint> activePoints() {
                                return EnumSet.of(ExtensionPoint.ON_AGENT_FINISH);
                            }

                            public Mono<Void> onAgentFinish(
                                    Agent agent, RuntimeContext rc, Msg result) {
                                finishes.incrementAndGet();
                                return Mono.empty();
                            }
                        })
                .build();
    }
}
