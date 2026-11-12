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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer.Purpose;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionModels;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.context.ContextBudgetExceededException;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class ConversationCompactorFallbackTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MODEL_WINDOW = 1_000_000;
    private static final String PAD = " earlier conversation detail".repeat(100);
    private static final String FALLBACK_SUMMARY = "Earlier investigation completed.";
    private static final RuntimeException FAILURE =
            new IllegalStateException("provider unavailable");

    @ParameterizedTest
    @ValueSource(strings = {"before-response", "mid-stream", "synchronous", "wrapped"})
    void invocationFailuresUseAnIndependentCompleteFallbackSummary(String failureKind) {
        TestModel primary =
                new TestModel(
                        "primary",
                        () ->
                                switch (failureKind) {
                                    case "synchronous" -> throw FAILURE;
                                    case "wrapped" -> Flux.error(new CompletionException(FAILURE));
                                    case "mid-stream" ->
                                            Flux.concat(
                                                    chunk("UNFINISHED_PRIMARY"),
                                                    Flux.error(FAILURE));
                                    default -> Flux.error(FAILURE);
                                });
        TestModel fallback = successful("fallback");
        List<Msg> compacted = compact(primary, fallback).block(TIMEOUT).orElseThrow();

        assertEquals(1, primary.inputs.size());
        assertEquals(
                primary.inputs, fallback.inputs, "fallback regenerates the same summary input");
        assertEquals(2, compacted.size());
        assertTrue(compacted.get(0).getTextContent().contains(FALLBACK_SUMMARY));
        assertFalse(compacted.get(0).getTextContent().contains("UNFINISHED_PRIMARY"));
        assertEquals("fallback", compacted.get(0).getMetadata().get("context.summary_model"));
        assertEquals("latest request", compacted.get(1).getTextContent());
    }

    @Test
    void successfulPrimaryDoesNotCallFallback() {
        TestModel primary = successful("primary");
        TestModel fallback = successful("fallback");
        List<Msg> compacted = compact(primary, fallback).block(TIMEOUT).orElseThrow();

        assertEquals(1, primary.inputs.size());
        assertTrue(fallback.inputs.isEmpty());
        assertEquals("primary", compacted.get(0).getMetadata().get("context.summary_model"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "length", "tool-call"})
    void invalidPrimaryOutputDoesNotTriggerFailover(String outputKind) {
        TestModel primary = new TestModel("primary", () -> invalidOutput(outputKind));
        TestModel fallback = successful("fallback");

        assertTrue(compact(primary, fallback).block(TIMEOUT).isEmpty());
        assertEquals(1, primary.inputs.size());
        assertTrue(fallback.inputs.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "length", "tool-call"})
    void fallbackOutputMustPassTheExistingSummaryValidation(String outputKind) {
        TestModel primary = failing("primary");
        TestModel fallback = new TestModel("fallback", () -> invalidOutput(outputKind));

        assertTrue(compact(primary, fallback).block(TIMEOUT).isEmpty());
        assertEquals(1, primary.inputs.size());
        assertEquals(1, fallback.inputs.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrIdenticalFallbackDoesNotRepeatTheFailedModel(boolean identical) {
        TestModel primary = failing("primary");

        StepVerifier.create(compact(primary, identical ? primary : null))
                .expectErrorMatches(error -> error == FAILURE)
                .verify(TIMEOUT);
        assertEquals(1, primary.inputs.size());
    }

    @Test
    void differentInstancesWithTheSameModelNameCanFailOver() {
        TestModel primary = failing("same-name");
        TestModel fallback = successful("same-name");

        assertTrue(compact(primary, fallback).block(TIMEOUT).isPresent());
        assertEquals(1, primary.inputs.size());
        assertEquals(1, fallback.inputs.size());
    }

    @Test
    void primaryPreparationFailureDoesNotCallEitherModel() {
        TestModel primary = new TestModel("small-primary", 1, () -> Flux.error(FAILURE));
        TestModel fallback = successful("fallback");

        StepVerifier.create(compact(primary, fallback))
                .expectError(ContextBudgetExceededException.class)
                .verify(TIMEOUT);
        assertTrue(primary.inputs.isEmpty());
        assertTrue(fallback.inputs.isEmpty());
    }

    @Test
    void fallbackRequestUsesItsOwnContextWindowBeforeDispatch() {
        TestModel primary = failing("primary");
        TestModel fallback = new TestModel("small-fallback", 1, () -> chunk(FALLBACK_SUMMARY));

        StepVerifier.create(compact(primary, fallback))
                .expectError(ContextBudgetExceededException.class)
                .verify(TIMEOUT);
        assertEquals(1, primary.inputs.size());
        assertTrue(fallback.inputs.isEmpty());
    }

    @Test
    void eachSubscriptionStartsWithThePrimaryAndAFreshCollector() {
        TestModel primary = failing("primary");
        TestModel fallback = successful("fallback");
        Mono<Optional<List<Msg>>> request = compact(primary, fallback);

        String first = request.block(TIMEOUT).orElseThrow().get(0).getTextContent();
        String second = request.block(TIMEOUT).orElseThrow().get(0).getTextContent();

        assertEquals(first, second);
        assertEquals(2, primary.inputs.size());
        assertEquals(2, fallback.inputs.size());
    }

    @ParameterizedTest
    @MethodSource("terminalErrors")
    void terminationAndSessionLogErrorsPropagateThroughThePipeline(Throwable failure) {
        TestModel primary = new TestModel("primary", () -> Flux.error(failure));
        TestModel fallback = successful("fallback");
        AgentState state = state();
        List<Msg> original = List.copyOf(state.contextMutable());

        StepVerifier.create(prepare(primary, fallback, null, state, context(state), 10_000))
                .expectErrorMatches(error -> error == failure)
                .verify(TIMEOUT);
        assertEquals(original, state.contextMutable());
        assertEquals(1, primary.inputs.size());
        assertTrue(fallback.inputs.isEmpty());
    }

    private static Stream<Throwable> terminalErrors() {
        return Stream.of(
                new InterruptedException("stopped"),
                new IllegalStateException("wrapped", new InterruptedException("stopped")),
                new CancellationException("cancelled"),
                new IllegalStateException("wrapped", new CancellationException("cancelled")),
                new CompletionException(new CancellationException("cancelled")),
                new SessionLogException("journal failed"),
                new IllegalStateException("wrapped", new SessionLogException("journal failed")));
    }

    @ParameterizedTest
    @MethodSource("terminalErrors")
    void fallbackTerminationAndSessionLogErrorsPropagateWithoutAnotherAttempt(Throwable failure) {
        TestModel primary = failing("primary");
        TestModel fallback = new TestModel("fallback", () -> Flux.error(failure));
        AgentState state = state();
        List<Msg> original = List.copyOf(state.contextMutable());

        StepVerifier.create(prepare(primary, fallback, null, state, context(state), 10_000))
                .expectErrorMatches(error -> error == failure)
                .verify(TIMEOUT);
        assertEquals(original, state.contextMutable());
        assertEquals(1, primary.inputs.size());
        assertEquals(1, fallback.inputs.size());
    }

    @Test
    void cancellingAnIncompleteSummaryDoesNotStartFallbackOrReplaceHistory() {
        Sinks.One<Void> subscribed = Sinks.one();
        TestModel primary =
                new TestModel(
                        "primary",
                        () ->
                                Flux.concat(chunk("UNFINISHED_PRIMARY"), Flux.<ChatResponse>never())
                                        .doOnSubscribe(ignored -> subscribed.tryEmitEmpty()));
        TestModel fallback = successful("fallback");
        AgentState state = state();
        List<Msg> original = List.copyOf(state.contextMutable());

        StepVerifier.create(prepare(primary, fallback, null, state, context(state), 10_000))
                .then(() -> subscribed.asMono().block(TIMEOUT))
                .thenCancel()
                .verify(TIMEOUT);
        assertEquals(original, state.contextMutable());
        assertEquals(1, primary.inputs.size());
        assertTrue(fallback.inputs.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automaticCompactionUsesAgentFallbackForDefaultAndDedicatedModels(boolean dedicated) {
        TestModel primary = failing("summary-primary");
        TestModel fallback = successful("fallback");
        AgentState state = state();

        ModelCallInput prepared =
                prepare(
                                primary,
                                fallback,
                                dedicated ? primary : null,
                                state,
                                context(state),
                                10_000)
                        .block(TIMEOUT);

        assertNotNull(prepared);
        assertEquals(2, state.contextMutable().size());
        assertEquals(
                "fallback",
                state.contextMutable().get(0).getMetadata().get("context.summary_model"));
        assertTrue(prepared.messages().containsAll(state.contextMutable()));
        assertEquals(1, primary.inputs.size());
        assertEquals(1, fallback.inputs.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 10_000})
    void dualFailurePreservesHistoryAndStillValidatesTheFinalRequest(int inputLimit) {
        TestModel primary = failing("primary");
        TestModel fallback = failing("fallback");
        AgentState state = state();
        List<Msg> original = List.copyOf(state.contextMutable());
        Mono<ModelCallInput> request =
                prepare(primary, fallback, null, state, context(state), inputLimit);

        if (inputLimit == 100) {
            StepVerifier.create(request)
                    .expectError(ContextBudgetExceededException.class)
                    .verify(TIMEOUT);
        } else {
            assertEquals(original, request.block(TIMEOUT).messages());
        }
        assertEquals(original, state.contextMutable());
        assertEquals(1, primary.inputs.size());
        assertEquals(1, fallback.inputs.size());
    }

    @Test
    void successfulFallbackDoesNotCommitWhenTheFinalRequestStillExceedsBudget() {
        TestModel primary = failing("primary");
        TestModel fallback = successful("fallback");
        AgentState state = state();
        List<Msg> original = List.copyOf(state.contextMutable());
        RuntimeContext rc = context(state);
        WorkspaceContextMaterials.register(
                rc, List.of(ContextItem.instruction("project_rules", "AGENTS.md", PAD)));

        StepVerifier.create(prepare(primary, fallback, null, state, rc, 100))
                .expectError(ContextBudgetExceededException.class)
                .verify(TIMEOUT);
        assertEquals(original, state.contextMutable());
        assertEquals(1, primary.inputs.size());
        assertEquals(1, fallback.inputs.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeLogRecordsBothSummaryAttemptsAndTheActualFallbackModel(boolean alreadyManaged) {
        TestModel primary = failing("primary");
        TestModel fallback = successful("fallback");
        AgentState state = state();
        RuntimeContext rc = context(state);
        SessionLog log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "fallback-agent", "session"), rc);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        rc.put(SessionRecorder.CONTEXT_KEY, recorder);
        try {
            prepare(
                            alreadyManaged ? SessionModels.wrap(primary, rc, "SUMMARY") : primary,
                            fallback,
                            null,
                            state,
                            rc,
                            10_000)
                    .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, rc))
                    .block(TIMEOUT);
        } finally {
            recorder.close();
        }

        List<SessionEvent> events = log.readAfter(0, 100);
        List<Map<String, Object>> ends =
                events.stream()
                        .filter(event -> "model/end".equals(event.type()))
                        .map(SessionEvent::data)
                        .toList();
        assertEquals(2, ends.size());
        assertEquals(
                List.of("primary", "fallback"), ends.stream().map(e -> e.get("model")).toList());
        assertEquals(
                List.of("failed", "completed"), ends.stream().map(e -> e.get("status")).toList());
        assertTrue(ends.stream().allMatch(e -> "SUMMARY".equals(e.get("purpose"))));
        List<Map<String, Object>> retries =
                events.stream()
                        .filter(event -> "model/retry".equals(event.type()))
                        .map(SessionEvent::data)
                        .toList();
        assertEquals(
                List.of(
                        Map.of(
                                "reason", "fallback",
                                "purpose", "SUMMARY",
                                "from", "primary",
                                "to", "fallback")),
                retries);
    }

    @ParameterizedTest
    @CsvSource({
        "admission, false", "settlement, false", "error-settlement, false",
        "admission, true", "settlement, true", "error-settlement, true"
    })
    void localSessionPolicyFailuresDoNotTriggerFallbackOrReplaceHistory(
            String phase, boolean alreadyManaged) {
        TestModel primary =
                "error-settlement".equals(phase) ? failing("primary") : successful("primary");
        TestModel fallback = successful("fallback");
        AgentState state = state();
        List<Msg> original = List.copyOf(state.contextMutable());
        RuntimeContext rc = context(state);
        SessionLog log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "fallback-agent", "session"), rc);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        rc.put(SessionRecorder.CONTEXT_KEY, recorder);
        rc.put(
                SessionModelPolicy.CONTEXT_KEY,
                new SessionModelPolicy() {
                    @Override
                    public void beforeCall(String id, String model, RuntimeContext context) {
                        if ("admission".equals(phase)) {
                            throw new IllegalStateException("local budget admission failed");
                        }
                    }

                    @Override
                    public void afterCall(
                            String id,
                            String model,
                            String status,
                            ChatUsage usage,
                            RuntimeContext context) {
                        throw new IllegalStateException("local accounting failed");
                    }
                });
        try {
            ModelCallInput prepared =
                    prepare(
                                    alreadyManaged
                                            ? SessionModels.wrap(primary, rc, "SUMMARY")
                                            : primary,
                                    fallback,
                                    null,
                                    state,
                                    rc,
                                    10_000)
                            .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, rc))
                            .block(TIMEOUT);
            assertEquals(original, prepared.messages());
        } finally {
            recorder.close();
        }

        assertEquals(original, state.contextMutable());
        assertEquals("admission".equals(phase) ? 0 : 1, primary.inputs.size());
        assertTrue(fallback.inputs.isEmpty());
        assertTrue(
                log.readAfter(0, 100).stream()
                        .noneMatch(event -> "model/retry".equals(event.type())));
    }

    private static Mono<ModelCallInput> prepare(
            Model primary,
            Model fallback,
            Model dedicated,
            AgentState state,
            RuntimeContext rc,
            int inputLimit) {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("fallback-agent")
                        .model(dedicated == null ? primary : successful("reasoning-primary"))
                        .fallbackModel(fallback)
                        .failoverListener(
                                (failed, error) -> {
                                    throw new AssertionError(
                                            "compaction must not notify this listener");
                                })
                        .build();
        HarnessContextBuilder builder =
                new HarnessContextBuilder(
                        new ContextPolicy(
                                inputLimit,
                                1,
                                1,
                                ContextTokenEstimator.approximate(),
                                manifest -> {}),
                        new CompactionMiddleware(null, dedicated, config()),
                        null);
        return builder.prepare(
                agent,
                rc,
                new ModelCallInput(
                        state.contextMutable(), List.of(), null, successful("reasoning")),
                "call",
                Purpose.REASONING);
    }

    private static Mono<Optional<List<Msg>>> compact(Model primary, Model fallback) {
        return new ConversationCompactor(primary, new MemoryFlushManager(null, primary), fallback)
                .compactIfNeeded(RuntimeContext.empty(), history(), config(), "agent", "session");
    }

    private static CompactionConfig config() {
        return CompactionConfig.builder()
                .triggerMessages(2)
                .keepMessages(1)
                .keepTokens(0)
                .flushBeforeCompact(false)
                .truncateArgs(null)
                .prune(null)
                .build();
    }

    private static AgentState state() {
        return AgentState.builder().context(history()).build();
    }

    private static RuntimeContext context(AgentState state) {
        return RuntimeContext.builder()
                .userId("user")
                .sessionId("session")
                .agentState(state)
                .build();
    }

    private static List<Msg> history() {
        return List.of(
                Msg.builder().role(MsgRole.USER).textContent("first" + PAD).build(),
                Msg.builder().role(MsgRole.ASSISTANT).textContent("second" + PAD).build(),
                Msg.builder().role(MsgRole.USER).textContent("latest request").build());
    }

    private static TestModel successful(String name) {
        return new TestModel(name, () -> chunk(FALLBACK_SUMMARY));
    }

    private static TestModel failing(String name) {
        return new TestModel(name, () -> Flux.error(FAILURE));
    }

    private static Flux<ChatResponse> chunk(String text) {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(text).build()))
                        .build());
    }

    private static Flux<ChatResponse> invalidOutput(String kind) {
        return switch (kind) {
            case "empty" -> chunk("");
            case "length" ->
                    Flux.just(
                            ChatResponse.builder()
                                    .content(List.of(TextBlock.builder().text("truncated").build()))
                                    .finishReason("length")
                                    .build());
            default ->
                    Flux.just(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id("call")
                                                            .name("tool")
                                                            .input(Map.of())
                                                            .build()))
                                    .build());
        };
    }

    private static final class TestModel implements Model {
        private final String name;
        private final int window;
        private final Supplier<Flux<ChatResponse>> responses;
        private final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();

        private TestModel(String name, Supplier<Flux<ChatResponse>> responses) {
            this(name, MODEL_WINDOW, responses);
        }

        private TestModel(String name, int window, Supplier<Flux<ChatResponse>> responses) {
            this.name = name;
            this.window = window;
            this.responses = responses;
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public int getContextWindowSize() {
            return window;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            assertTrue(tools.isEmpty(), "summary must not expose tools");
            return responses.get();
        }
    }
}
