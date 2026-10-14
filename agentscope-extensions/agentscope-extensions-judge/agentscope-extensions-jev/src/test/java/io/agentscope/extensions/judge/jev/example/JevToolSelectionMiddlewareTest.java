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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JevToolSelectionMiddlewareTest {
    private ReasoningInput input(String... names) {
        return new ReasoningInput(
                List.of(new UserMessage("request")),
                java.util.Arrays.stream(names)
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
        String[] names =
                java.util.stream.IntStream.range(0, 130)
                        .mapToObj(i -> "tool" + i)
                        .toArray(String[]::new);
        assertEquals("tool64", run(middleware, input(names)).tools().get(0).getName());
        assertEquals(3, calls.get());
    }
}
