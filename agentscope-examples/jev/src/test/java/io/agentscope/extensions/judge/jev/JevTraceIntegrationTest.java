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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.examples.jev.JevTraceEvaluationExample;
import io.agentscope.extensions.judge.jev.evaluation.JevMetricResult;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluationMiddleware;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevTraceIntegrationTest {
    static JevExecution.Options options(JevExecution.Mode mode) {
        return new JevExecution.Options(
                mode, Duration.ofSeconds(2), "integration-v1", (ctx, r) -> {});
    }

    static JevTraceEvaluator evaluator(AtomicInteger calls) {
        return new JevTraceEvaluator(
                r -> {
                    calls.incrementAndGet();
                    return JevTraceEvaluationExample.syntheticAnswers(r);
                },
                List.of(JevTraceMetrics.completeness(JevTraceEvaluationTest.THRESHOLDS)),
                JevTraceEvaluator.Limits.defaults());
    }

    static Flux<AgentEvent> invocation(
            JevTraceEvaluationMiddleware m, String session, String text) {
        Msg user = new UserMessage(text);
        var ctx = RuntimeContext.builder().sessionId(session).build();
        return m.onAgent(
                null,
                ctx,
                new AgentInput(List.of(user)),
                input ->
                        m.onModelCall(
                                null,
                                ctx,
                                new ModelCallInput(List.of(user), List.of(), null, null),
                                ignored ->
                                        Flux.<AgentEvent>just(
                                                        new AgentResultEvent(
                                                                new AssistantMessage(text)))
                                                .delayElements(Duration.ofMillis(2))));
    }

    @Test
    void realAgentExecutesToolOnceAndEmitsOnePackedEvaluation() {
        var run = JevTraceEvaluationExample.runOffline();
        assertEquals(1, run.executedTools());
        assertEquals(1, run.report().requests());
        assertTrue(run.report().passed());
        assertEquals("Order 42 shipped yesterday.", run.answer());
    }

    @Test
    void disabledDoesNotCaptureOrCallAndShadowKeepsEventsEvenIfObserverThrows() {
        AtomicInteger calls = new AtomicInteger();
        var off = new JevTraceEvaluationMiddleware(evaluator(calls));
        assertEquals(1, invocation(off, "off", "hello").collectList().block().size());
        assertEquals(0, calls.get());
        var shadow =
                new JevTraceEvaluationMiddleware(
                        evaluator(calls),
                        options(JevExecution.Mode.SHADOW),
                        10000,
                        (ctx, r) -> {
                            throw new IllegalStateException("observer unavailable");
                        });
        var events = invocation(shadow, "s", "hello").collectList().block();
        assertEquals("hello", ((AgentResultEvent) events.get(0)).getResult().getTextContent());
        assertEquals(1, calls.get());
    }

    @Test
    void sameMiddlewareHasIndependentConcurrentCaptures() {
        Map<String, String> requests = new ConcurrentHashMap<>();
        var evaluator =
                new JevTraceEvaluator(
                        r ->
                                Mono.deferContextual(
                                        c -> {
                                            String request =
                                                    ((Map<?, ?>) r.state())
                                                            .get("request")
                                                            .toString();
                                            requests.put(
                                                    request,
                                                    ((Map<?, ?>) r.state())
                                                            .get("final_answer")
                                                            .toString());
                                            return JevTraceEvaluationExample.syntheticAnswers(r)
                                                    .delayElement(Duration.ofMillis(5));
                                        }),
                        List.of(JevTraceMetrics.completeness(JevTraceEvaluationTest.THRESHOLDS)),
                        JevTraceEvaluator.Limits.defaults());
        Map<String, String> reports = new ConcurrentHashMap<>();
        var middleware =
                new JevTraceEvaluationMiddleware(
                        evaluator,
                        options(JevExecution.Mode.SHADOW),
                        10000,
                        (ctx, r) -> reports.put(ctx.getSessionId(), r.traceId()));
        Flux.merge(invocation(middleware, "one", "first"), invocation(middleware, "two", "second"))
                .blockLast();
        assertEquals(Map.of("first", "first", "second", "second"), requests);
        assertEquals(2, reports.size());
        assertFalse(reports.get("one").equals(reports.get("two")));
    }

    @Test
    void captureOverflowSkipsWithoutChangingAnswer() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JevTraceEvaluator.Report> seen = new AtomicReference<>();
        var middleware =
                new JevTraceEvaluationMiddleware(
                        evaluator(calls),
                        options(JevExecution.Mode.SHADOW),
                        1,
                        (ctx, r) -> seen.set(r));
        assertEquals(1, invocation(middleware, "s", "hello").collectList().block().size());
        assertEquals(0, calls.get());
        assertEquals(JevMetricResult.Status.SKIPPED, seen.get().result("completeness").status());
    }

    @Test
    void agentFailureAndCancellationDoNotStartEvaluation() {
        AtomicInteger calls = new AtomicInteger();
        var m =
                new JevTraceEvaluationMiddleware(
                        evaluator(calls), options(JevExecution.Mode.SHADOW), 10000, (c, r) -> {});
        var ctx = RuntimeContext.builder().build();
        var input = new AgentInput(List.of(new UserMessage("hello")));
        StepVerifier.create(
                        m.onAgent(
                                null,
                                ctx,
                                input,
                                i -> Flux.error(new IllegalStateException("agent failed"))))
                .expectError(IllegalStateException.class)
                .verify();
        StepVerifier.create(m.onAgent(null, ctx, input, i -> Flux.never())).thenCancel().verify();
        assertEquals(0, calls.get());
    }

    @Test
    void postRunObserverCannotPretendToEnforceToolPermissions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JevTraceEvaluationMiddleware(
                                evaluator(new AtomicInteger()),
                                options(JevExecution.Mode.ENFORCE),
                                10000,
                                (c, r) -> {}));
    }
}
