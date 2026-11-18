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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class RecordedModelRequestsTest {
    private static final GenerateOptions OPTIONS =
            GenerateOptions.builder()
                    .executionConfig(
                            ExecutionConfig.builder()
                                    .maxAttempts(2)
                                    .initialBackoff(Duration.ofMillis(1))
                                    .maxBackoff(Duration.ofMillis(1))
                                    .build())
                    .build();

    private static ChatResponse usage(int input, int output) {
        return ChatResponse.builder()
                .content(List.of())
                .usage(new ChatUsage(input, output, 0))
                .build();
    }

    private static ChatResponse answer() {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("answer").build()))
                .build();
    }

    private static Model model(Supplier<Flux<ChatResponse>> source, boolean observed) {
        return new Model() {
            public String getModelName() {
                return "fixture";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                var response = Flux.defer(source);
                return observed
                        ? ModelUtils.applyTimeoutAndRetry(
                                response, options, options, "fixture", "test")
                        : response;
            }
        };
    }

    private static final class Fixture implements AutoCloseable {
        final ThreadJournal log;
        final InvocationRecorder recorder;
        final RuntimeContext context;

        Fixture(boolean spyLog) {
            var stored =
                    new InMemoryThreadJournalStore()
                            .open(new ThreadKey("u", "a", "s", "main"), null);
            log = spyLog ? spy(stored) : stored;
            recorder = new InvocationRecorder(log, "turn", "run", null);
            var state = AgentState.builder().sessionId("s").threadId("main").build();
            context =
                    RuntimeContext.builder().put(InvocationRecorder.CONTEXT_KEY, recorder).build();
            context.setAgentState(state);
            recorder.start(List.of(), state).block();
            recorder.step(3);
        }

        void call(Model model) {
            RecordedModels.wrap(model, context, "REASONING").stream(List.of(), List.of(), OPTIONS)
                    .blockLast(Duration.ofSeconds(5));
        }

        List<JournalEvent> facts(String type) {
            return log.readAfter(0, 200).stream().filter(e -> type.equals(e.type())).toList();
        }

        public void close() {
            recorder.close();
        }
    }

    @Test
    void retriesRecordDistinctRequestsAndRetainUsageBeforeAnEmptyTrailer() {
        try (var fixture = new Fixture(false)) {
            var calls = new AtomicInteger();
            fixture.call(
                    model(
                            () -> {
                                int attempt = calls.incrementAndGet();
                                assertEquals(
                                        attempt, fixture.facts("model/request_started").size());
                                return attempt == 1
                                        ? Flux.concat(
                                                Flux.just(usage(2, 0)),
                                                Flux.error(new IllegalStateException("retry")))
                                        : Flux.just(usage(10, 5), answer());
                            },
                            true));
            var starts = fixture.facts("model/request_started");
            var ends = fixture.facts("model/request_ended");
            assertEquals(2, starts.size());
            assertEquals(2, ends.size());
            assertNotEquals(
                    starts.get(0).data().get("requestId"), starts.get(1).data().get("requestId"));
            assertEquals(List.of(1, 2), starts.stream().map(e -> e.data().get("attempt")).toList());
            assertEquals("run", starts.get(0).executionRunId());
            assertEquals(fixture.recorder.stepId(), starts.get(0).data().get("stepId"));
            assertEquals(3, starts.get(0).data().get("iteration"));
            assertEquals("failed", ends.get(0).data().get("status"));
            assertEquals(false, ends.get(0).data().get("usageComplete"));
            assertEquals(true, ends.get(1).data().get("usageComplete"));
            assertEquals(10, ((Map<?, ?>) ends.get(1).data().get("usage")).get("inputTokens"));
            assertTrue(((Number) ends.get(1).data().get("durationMillis")).longValue() >= 0);
            var logical = fixture.facts("model/end").get(0).data();
            assertEquals("provider_attempts", logical.get("requestObservation"));
            assertEquals(2, logical.get("observedRequests"));
            assertEquals(5, ((Map<?, ?>) logical.get("usage")).get("outputTokens"));
        }
    }

    @Test
    void unknownFinalAttemptUsageDoesNotInheritFailedAttemptUsage() {
        try (var fixture = new Fixture(false)) {
            var calls = new AtomicInteger();
            fixture.call(
                    model(
                            () ->
                                    calls.incrementAndGet() == 1
                                            ? Flux.concat(
                                                    Flux.just(usage(9, 1)),
                                                    Flux.error(new IllegalStateException("retry")))
                                            : Flux.just(answer()),
                            true));
            var end = fixture.facts("model/request_ended").get(1).data();
            assertNull(end.get("usage"));
            assertEquals(false, end.get("usageComplete"));
            assertNull(fixture.facts("model/end").get(0).data().get("usage"));
        }
    }

    @Test
    void uninstrumentedModelsReportCallVisibilityWithoutInventingProviderRequests() {
        try (var fixture = new Fixture(false)) {
            fixture.call(model(() -> Flux.just(usage(7, 4), answer()), false));
            assertTrue(fixture.facts("model/request_started").isEmpty());
            var end = fixture.facts("model/end").get(0).data();
            assertEquals("model_call", end.get("requestObservation"));
            assertEquals(0, end.get("observedRequests"));
            assertEquals(7, ((Map<?, ?>) end.get("usage")).get("inputTokens"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model/request_started", "model/request_ended"})
    void requestRecordingFailureDoesNotRetryProviderEffects(String rejectedType) {
        try (var fixture = new Fixture(true)) {
            var rejected = new AtomicInteger();
            var calls = new AtomicInteger();
            doAnswer(
                            invocation -> {
                                List<JournalEvent> events = invocation.getArgument(3);
                                if (events.stream().anyMatch(e -> rejectedType.equals(e.type()))) {
                                    rejected.incrementAndGet();
                                    throw new JournalException("request evidence unavailable");
                                }
                                return invocation.callRealMethod();
                            })
                    .when(fixture.log)
                    .commit(any(), anyString(), anyLong(), anyList());
            var failure =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    fixture.call(
                                            model(
                                                    () -> {
                                                        calls.incrementAndGet();
                                                        return Flux.just(usage(3, 1));
                                                    },
                                                    true)));
            assertTrue(JournalException.causedBy(failure));
            assertEquals("model/request_started".equals(rejectedType) ? 0 : 1, calls.get());
            assertEquals(1, rejected.get());
        }
    }
}
