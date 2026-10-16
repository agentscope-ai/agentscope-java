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

package io.agentscope.extensions.judge.jev.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware.ContextStrategy;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JevToolSelectionMiddlewareTest {
    private ReasoningInput input(String... names) {
        return new ReasoningInput(
                List.of(new UserMessage("request")),
                Arrays.stream(names)
                        .map(n -> ToolSchema.builder().name(n).description(n).build())
                        .toList(),
                null);
    }

    private ReasoningInput run(JevToolSelectionMiddleware middleware, ReasoningInput input) {
        AtomicReference<ReasoningInput> next = new AtomicReference<>();
        middleware
                .onReasoning(
                        null,
                        RuntimeContext.empty(),
                        input,
                        i -> {
                            next.set(i);
                            return Flux.empty();
                        })
                .blockLast();
        return next.get();
    }

    private JevExecution.Options options(JevExecution.Mode mode) {
        return new JevExecution.Options(mode, Duration.ofSeconds(2), "test", (c, r) -> {});
    }

    private JevToolSelectionMiddleware middleware(JevExecution.Mode mode, double... scores) {
        return JevToolSelectionMiddleware.builder(
                        r -> {
                            Map<String, Answer> answers = new LinkedHashMap<>();
                            for (int i = 0; i < scores.length; i++)
                                answers.put("tool_" + i, new NoulAnswer(scores[i]));
                            return Mono.just(new SystemOneResult("fake", answers, null));
                        })
                .execution(options(mode))
                .build();
    }

    @Test
    void defaultOffNeverCalls() {
        var input = input("refund");
        assertSame(
                input,
                run(
                        JevToolSelectionMiddleware.builder(
                                        r -> {
                                            throw new AssertionError();
                                        })
                                .build(),
                        input));
    }

    @Test
    void shadowDoesNotChangeInput() {
        var input = input("refund");
        assertSame(input, run(middleware(JevExecution.Mode.SHADOW, 0.01), input));
    }

    @Test
    void singleCandidateCanBeRejected() {
        assertEquals(
                0,
                run(middleware(JevExecution.Mode.ENFORCE, 0.01), input("refund")).tools().size());
    }

    @Test
    void nonePreservesNecessaryToolsOnly() {
        assertEquals(
                List.of("generate_response"),
                run(
                                middleware(JevExecution.Mode.ENFORCE, 0.01),
                                input("refund", "generate_response"))
                        .tools()
                        .stream()
                        .map(ToolSchema::getName)
                        .toList());
    }

    @Test
    void multipleUsefulToolsSurvive() {
        assertEquals(
                2,
                run(
                                middleware(JevExecution.Mode.ENFORCE, 0.9, 0.95, 0.01),
                                input("order", "refund", "email"))
                        .tools()
                        .size());
    }

    @Test
    void uncertaintyAndInvalidAnswerRestoreOriginal() {
        var input = input("refund");
        assertSame(input, run(middleware(JevExecution.Mode.ENFORCE, 0.5), input));
        assertSame(input, run(middleware(JevExecution.Mode.ENFORCE, Double.NaN), input));
        assertSame(input, run(middleware(JevExecution.Mode.ENFORCE), input));
    }

    @Test
    void failureRestoresOriginal() {
        var input = input("refund");
        assertSame(
                input,
                run(
                        JevToolSelectionMiddleware.builder(
                                        r -> Mono.error(new IllegalStateException()))
                                .execution(options(JevExecution.Mode.ENFORCE))
                                .build(),
                        input));
    }

    @Test
    void emptyCandidateSkips() {
        run(
                JevToolSelectionMiddleware.builder(
                                r -> {
                                    throw new AssertionError();
                                })
                        .execution(options(JevExecution.Mode.ENFORCE))
                        .build(),
                input());
    }

    @Test
    void batchesKeepUsefulCandidatesAcrossChunksAndLimitByScore() {
        AtomicInteger calls = new AtomicInteger();
        var middleware =
                JevToolSelectionMiddleware.builder(
                                r -> {
                                    int batch = calls.getAndIncrement();
                                    Map<String, Answer> answers = new LinkedHashMap<>();
                                    r.questions()
                                            .keySet()
                                            .forEach(
                                                    id ->
                                                            answers.put(
                                                                    id,
                                                                    new NoulAnswer(
                                                                            id.equals("tool_0")
                                                                                    ? (batch == 0
                                                                                            ? 0.9
                                                                                            : 0.99)
                                                                                    : 0.01)));
                                    return Mono.just(new SystemOneResult("fake", answers, null));
                                })
                        .execution(options(JevExecution.Mode.ENFORCE))
                        .maxTools(1)
                        .alwaysIncludeTools(Set.of())
                        .build();
        String[] names = IntStream.range(0, 130).mapToObj(i -> "tool" + i).toArray(String[]::new);
        assertEquals("tool64", run(middleware, input(names)).tools().get(0).getName());
        assertEquals(3, calls.get());
    }

    @Test
    void contextStrategiesPreserveIndependentSelection() {
        for (ContextStrategy strategy : ContextStrategy.values()) {
            AtomicReference<SystemOneRequest> request = new AtomicReference<>();
            List<Msg> messages = List.of(new UserMessage("old"), new UserMessage("latest"));
            var middleware =
                    JevToolSelectionMiddleware.builder(
                                    r -> {
                                        request.set(r);
                                        return Mono.just(
                                                new SystemOneResult(
                                                        "fake",
                                                        Map.of("tool_0", new NoulAnswer(0.9)),
                                                        null));
                                    })
                            .execution(options(JevExecution.Mode.ENFORCE))
                            .contextStrategy(strategy)
                            .maxContextMessages(1)
                            .maxContextChars(3)
                            .build();
            var input = new ReasoningInput(messages, input("refund").tools(), null);
            assertEquals(1, run(middleware, input).tools().size());
            Object expected =
                    switch (strategy) {
                        case RECENT_WINDOW ->
                                Map.of("messages", List.of(Map.of("role", "user", "text", "lat")));
                        case LATEST_USER_MESSAGE -> Map.of("userRequest", "latest");
                        case FULL_CONVERSATION -> Map.of("messages", messages);
                    };
            assertEquals(expected, request.get().state());
        }
    }

    @Test
    void defaultWindowIsBoundedAcrossEveryBatch() {
        AtomicInteger calls = new AtomicInteger();
        List<Msg> messages =
                IntStream.range(0, 10)
                        .mapToObj(i -> (Msg) new UserMessage("x".repeat(1100)))
                        .toList();
        var middleware =
                JevToolSelectionMiddleware.builder(
                                r -> {
                                    calls.incrementAndGet();
                                    assertEquals(
                                            JevSelectionSupport.recentWindowState(
                                                    messages, 8, 8000),
                                            r.state());
                                    Map<String, Answer> answers = new LinkedHashMap<>();
                                    r.questions()
                                            .keySet()
                                            .forEach(id -> answers.put(id, new NoulAnswer(0.9)));
                                    return Mono.just(new SystemOneResult("fake", answers, null));
                                })
                        .execution(options(JevExecution.Mode.ENFORCE))
                        .build();
        String[] names = IntStream.range(0, 130).mapToObj(i -> "tool" + i).toArray(String[]::new);
        assertEquals(
                3,
                run(middleware, new ReasoningInput(messages, input(names).tools(), null))
                        .tools()
                        .size());
        assertEquals(3, calls.get());
    }

    @Test
    void contextFailureIsObservedAndRestoresInput() {
        Msg poison = mock(Msg.class);
        when(poison.getTextContent()).thenReturn("bad");
        AtomicReference<JevExecution.Record> record = new AtomicReference<>();
        var middleware =
                JevToolSelectionMiddleware.builder(
                                r -> {
                                    throw new AssertionError(
                                            "invalid context must not reach backend");
                                })
                        .execution(
                                new JevExecution.Options(
                                        JevExecution.Mode.ENFORCE,
                                        Duration.ofSeconds(2),
                                        "test",
                                        (ctx, value) -> record.set(value)))
                        .build();
        var input =
                new ReasoningInput(
                        List.of(poison, new UserMessage("request")), input("refund").tools(), null);
        assertSame(input, run(middleware, input));
        assertEquals(JevExecution.Status.ERROR, record.get().status());
    }

    @Test
    void disabledSkipsContextConstruction() {
        Msg poison = mock(Msg.class);
        when(poison.getTextContent()).thenThrow(new IllegalStateException());
        var middleware =
                JevToolSelectionMiddleware.builder(
                                r -> {
                                    throw new AssertionError();
                                })
                        .build();
        var input =
                new ReasoningInput(
                        List.of(poison, new UserMessage("request")), input("refund").tools(), null);
        assertSame(input, run(middleware, input));
    }

    @Test
    void invalidContextConfigurationRejected() {
        assertThrows(
                NullPointerException.class,
                () ->
                        JevToolSelectionMiddleware.builder(r -> Mono.empty())
                                .contextStrategy(null)
                                .build());
        for (int value : new int[] {0, -1}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            JevToolSelectionMiddleware.builder(r -> Mono.empty())
                                    .maxContextMessages(value)
                                    .build());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            JevToolSelectionMiddleware.builder(r -> Mono.empty())
                                    .maxContextChars(value)
                                    .build());
        }
    }
}
