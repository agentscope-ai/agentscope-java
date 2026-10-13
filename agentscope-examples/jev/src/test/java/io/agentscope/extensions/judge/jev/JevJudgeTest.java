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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.jev.JevCustomerSupportExample;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevJudgeTest {
    private JevJudge.Definition definition() {
        return JevCustomerSupportExample.definition();
    }

    private SystemOneResult response(double coverage, double risk) {
        return new SystemOneResult(
                "test",
                Map.of(
                        "covers_request",
                        new NoulAnswer(coverage),
                        "unsupported_commitment",
                        new NoulAnswer(risk)),
                null);
    }

    private JevJudge.Result evaluate(double coverage, double risk) {
        return new JevJudge(r -> Mono.just(response(coverage, risk)), Duration.ofSeconds(1))
                .judge("state", definition())
                .block();
    }

    @Test
    void invertsNegativeCriterionAndDoesNotAverageAwayFailure() {
        var result = evaluate(0.99, 0.99);
        assertEquals(JevJudge.Status.FAIL, result.status());
        assertEquals(
                0.01, result.findings().get("unsupported_commitment").expectedProbability(), 1e-9);
        assertEquals("support-draft-v1", result.definitionVersion());
        assertEquals("test", result.model());
        assertThrows(UnsupportedOperationException.class, () -> result.findings().clear());
    }

    @Test
    void thresholdsAndUncertainty() {
        assertEquals(JevJudge.Status.PASS, evaluate(0.8, 0.1).status());
        assertEquals(JevJudge.Status.FAIL, evaluate(0.2, 0.1).status());
        assertEquals(JevJudge.Status.INCONCLUSIVE, evaluate(0.5, 0.1).status());
        assertEquals(JevJudge.Status.FAIL, evaluate(0.5, 0.99).status());
    }

    @Test
    void validatesDefinitions() {
        var criterion = definition().criteria().get(0);
        assertThrows(
                IllegalArgumentException.class, () -> new JevJudge.Definition("v1", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevJudge.Definition("v1", List.of(criterion, criterion)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevJudge.Criterion("x", criterion.question(), true, 0.8, 0.2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevJudge.Criterion("x", criterion.question(), true, 0, Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevJudge(r -> Mono.empty(), Duration.ZERO));
    }

    @Test
    void invalidOrMissingAnswersNeverPass() {
        for (Double value : new Double[] {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1, null}) {
            var judge =
                    new JevJudge(
                            r ->
                                    Mono.just(
                                            new SystemOneResult(
                                                    "test",
                                                    Map.of(
                                                            "covers_request",
                                                            new NoulAnswer(value),
                                                            "unsupported_commitment",
                                                            new NoulAnswer(0.1)),
                                                    null)),
                            Duration.ofSeconds(1));
            assertEquals(
                    JevJudge.ErrorCode.INVALID_RESPONSE,
                    judge.judge("state", definition()).block().error());
        }
        var judge =
                new JevJudge(
                        r -> Mono.just(new SystemOneResult("test", Map.of(), null)),
                        Duration.ofSeconds(1));
        assertEquals(JevJudge.Status.ERROR, judge.judge("state", definition()).block().status());
    }

    @Test
    void emptyAndSynchronousBackendFailuresAreExplicit() {
        assertEquals(
                JevJudge.ErrorCode.INVALID_RESPONSE,
                new JevJudge(r -> Mono.empty(), Duration.ofSeconds(1))
                        .judge("state", definition())
                        .block()
                        .error());
        var result =
                new JevJudge(
                                r -> {
                                    throw new IllegalStateException("secret response");
                                },
                                Duration.ofSeconds(1))
                        .judge("state", definition())
                        .block();
        assertEquals(JevJudge.ErrorCode.BACKEND, result.error());
        assertFalse(result.toString().contains("secret response"));
    }

    @Test
    void budgetCancelsSource() {
        AtomicBoolean cancelled = new AtomicBoolean();
        StepVerifier.withVirtualTime(
                        () ->
                                new JevJudge(
                                                r ->
                                                        Mono.<SystemOneResult>never()
                                                                .doOnCancel(
                                                                        () -> cancelled.set(true)),
                                                Duration.ofSeconds(2))
                                        .judge("state", definition()))
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(r -> assertEquals(JevJudge.ErrorCode.TIMEOUT, r.error()))
                .verifyComplete();
        assertTrue(cancelled.get());
    }

    @Test
    void totalBudgetIncludesRetryBackoff() {
        AtomicInteger attempts = new AtomicInteger();
        StepVerifier.withVirtualTime(
                        () ->
                                new JevJudge(
                                                r ->
                                                        Mono.defer(
                                                                        () -> {
                                                                            attempts
                                                                                    .incrementAndGet();
                                                                            return Mono
                                                                                    .<SystemOneResult>
                                                                                            error(
                                                                                                    new IllegalStateException(
                                                                                                            "transient"));
                                                                        })
                                                                .retryWhen(
                                                                        reactor.util.retry.Retry
                                                                                .fixedDelay(
                                                                                        5,
                                                                                        Duration
                                                                                                .ofSeconds(
                                                                                                        1))),
                                                Duration.ofMillis(1500))
                                        .judge("state", definition()))
                .thenAwait(Duration.ofMillis(1500))
                .assertNext(r -> assertEquals(JevJudge.ErrorCode.TIMEOUT, r.error()))
                .verifyComplete();
        assertEquals(2, attempts.get());
    }

    @Test
    void callerCancellationPropagatesWithoutFallbackResult() {
        AtomicBoolean cancelled = new AtomicBoolean();
        StepVerifier.create(
                        new JevJudge(
                                        r ->
                                                Mono.<SystemOneResult>never()
                                                        .doOnCancel(() -> cancelled.set(true)),
                                        Duration.ofSeconds(2))
                                .judge("state", definition()))
                .thenCancel()
                .verify();
        assertTrue(cancelled.get());
    }

    @Test
    void lazySubscriptionAndRequestContainsOnlyQuestions() {
        AtomicInteger count = new AtomicInteger();
        var publisher =
                new JevJudge(
                                r -> {
                                    count.incrementAndGet();
                                    assertEquals("state", r.state());
                                    assertEquals(2, r.questions().size());
                                    assertInstanceOf(
                                            NoulQuestion.class,
                                            r.questions().get("covers_request"));
                                    return Mono.just(response(0.9, 0.1));
                                },
                                Duration.ofSeconds(1))
                        .judge("state", definition());
        assertEquals(0, count.get());
        publisher.block();
        publisher.block();
        assertEquals(2, count.get());
    }
}
