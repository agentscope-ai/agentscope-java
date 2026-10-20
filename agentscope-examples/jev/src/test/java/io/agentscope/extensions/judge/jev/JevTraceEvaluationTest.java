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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.evaluation.JevMetricResult;
import io.agentscope.extensions.judge.jev.evaluation.JevTrace;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetric;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevTraceEvaluationTest {
    static final JevTraceMetrics.Thresholds THRESHOLDS = new JevTraceMetrics.Thresholds(.2, .8);
    static final JevTraceEvaluator.Limits LIMITS = JevTraceEvaluator.Limits.defaults();

    static JevTrace sample(String id) {
        return JevTrace.fromMessages(
                id,
                List.of(
                        new UserMessage("Where is order 42?"),
                        new AssistantMessage(
                                new ToolUseBlock(
                                        "call1", "lookup", Map.of("id", "42"), null, null)),
                        new ToolResultMessage("call1", "lookup", "Order 42 shipped yesterday."),
                        new AssistantMessage("Order 42 shipped yesterday.")),
                List.of(
                        ToolSchema.builder()
                                .name("lookup")
                                .description("Look up an order")
                                .build()));
    }

    static SystemOneResult answer(SystemOneRequest r, double probability) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        r.questions()
                .forEach(
                        (id, q) -> {
                            if (q instanceof ChoiceQuestion choice) {
                                Map<String, Double> ps = new LinkedHashMap<>();
                                choice.criteria()
                                        .keySet()
                                        .forEach(k -> ps.put(k, k.equals("correct") ? 1d : 0d));
                                answers.put(id, new ChoiceAnswer("correct", ps, 1d));
                            } else if (q instanceof ScoreQuestion score) {
                                Map<String, String> legend = new LinkedHashMap<>();
                                Map<String, Double> ps = new LinkedHashMap<>();
                                for (int i = 0; i < score.criteria().size(); i++) {
                                    legend.put(
                                            Integer.toString(i),
                                            score.criteria().get(i).toString());
                                    ps.put(
                                            Integer.toString(i),
                                            i == score.criteria().size() - 1 ? 1d : 0d);
                                }
                                answers.put(
                                        id,
                                        new ScoreAnswer(
                                                (double) score.criteria().size() - 1,
                                                legend,
                                                ps,
                                                1d));
                            } else
                                answers.put(
                                        id,
                                        new NoulAnswer(
                                                id.startsWith("indirect_injection.")
                                                                || id.endsWith(".noncommittal")
                                                        ? 1 - probability
                                                        : probability));
                        });
        return new SystemOneResult("offline", answers, new Usage(100, 0));
    }

    static JevTraceEvaluator evaluator(
            Function<SystemOneRequest, Mono<SystemOneResult>> caller, JevTraceMetric... metrics) {
        return new JevTraceEvaluator(caller, List.of(metrics), LIMITS);
    }

    static JevTraceMetric custom(String id, Map<String, Object> state) {
        return new JevTraceMetric() {
            public String id() {
                return id;
            }

            public String version() {
                return "test-v1";
            }

            public Map<String, Object> state(JevTrace t) {
                return state;
            }

            public Map<String, Question> questions(JevTrace t) {
                return Map.of("q", new NoulQuestion("check state", null));
            }

            public JevMetricResult reduce(Map<String, Answer> a, JevTrace t) {
                return new JevMetricResult(
                        JevMetricResult.Status.DECIDED,
                        ((NoulAnswer) a.get("q")).noul(),
                        true,
                        null,
                        "OK",
                        Map.of());
            }
        };
    }

    @Test
    void compatibleMetricsUseOneRequestAndCountUsageOnce() {
        AtomicInteger calls = new AtomicInteger();
        var metrics = new ArrayList<>(JevTraceMetrics.agentMetrics(THRESHOLDS));
        metrics.add(JevTraceMetrics.trajectoryMatch(JevTraceMetrics.MatchMode.STRICT, false));
        var e =
                new JevTraceEvaluator(
                        r -> {
                            calls.incrementAndGet();
                            return Mono.just(answer(r, .95));
                        },
                        metrics,
                        LIMITS);
        var result =
                e.evaluate(sample("one").with("expected_tool_calls", List.of("lookup"))).block();
        assertEquals(1, calls.get());
        assertEquals(1, result.requests());
        assertEquals(1, result.usage().size());
        assertEquals(8, result.results().size());
        assertTrue(result.passed());
        assertEquals("correct", result.result("tool_choice").label());
        assertEquals(100, result.usage().get(0).usage().inputTokens());
    }

    @Test
    void incompatibleStatesSplitAndLocalQuestionIdsRemainScoped() {
        List<SystemOneRequest> seen = new ArrayList<>();
        var e =
                evaluator(
                        r -> {
                            seen.add(r);
                            return Mono.just(answer(r, .9));
                        },
                        custom("first", Map.of("evidence", "A")),
                        custom("second", Map.of("evidence", "B")),
                        custom("third", Map.of("evidence", "A")));
        var result = e.evaluate(sample("one")).block();
        assertEquals(2, seen.size());
        assertEquals(java.util.Set.of("first.q", "third.q"), seen.get(0).questions().keySet());
        assertEquals(Map.of("evidence", "A"), seen.get(0).state());
        assertEquals(
                List.of("first", "second", "third"),
                result.results().stream().map(JevTraceEvaluator.Entry::id).toList());
    }

    @Test
    void packingRespectsQuestionLimitWithoutDroppingMetrics() {
        var e =
                new JevTraceEvaluator(
                        r -> Mono.just(answer(r, .9)),
                        List.of(custom("a", Map.of()), custom("b", Map.of())),
                        new JevTraceEvaluator.Limits(Duration.ofSeconds(1), 1, 1000));
        var r = e.evaluate(sample("one")).block();
        assertEquals(2, r.requests());
        assertTrue(r.passed());
    }

    @Test
    void overLimitMetricSkipsWithoutPartialScoring() {
        var e =
                new JevTraceEvaluator(
                        r -> {
                            fail("must not call");
                            return Mono.empty();
                        },
                        List.of(JevTraceMetrics.grounded(THRESHOLDS)),
                        new JevTraceEvaluator.Limits(Duration.ofSeconds(1), 1, 1000));
        var r =
                e.evaluate(sample("one").with("claims", List.of("First claim", "Second claim")))
                        .block();
        assertEquals("EVALUATION_LIMIT", r.result("grounded").reason());
        assertFalse(r.passed());
    }

    @Test
    void missingEvidenceAndPendingCallsCannotPass() {
        var e =
                evaluator(
                        r -> {
                            fail("must not call");
                            return Mono.empty();
                        },
                        JevTraceMetrics.grounded(THRESHOLDS));
        assertEquals(
                JevMetricResult.Status.SKIPPED,
                e.evaluate(new JevTrace("empty", Map.of("final_answer", "Unverified claim")))
                        .block()
                        .result("grounded")
                        .status());
        var pending =
                JevTrace.fromMessages(
                        "pending",
                        List.of(
                                new UserMessage("lookup"),
                                new AssistantMessage(
                                        new ToolUseBlock("p", "lookup", Map.of(), null, null))),
                        List.of());
        assertEquals("INCOMPLETE_TRACE", e.evaluate(pending).block().result("grounded").reason());
    }

    @Test
    void duplicateAndOrphanResultsAreExplicitIssues() {
        var duplicate =
                JevTrace.fromMessages(
                        "d",
                        List.of(
                                new AssistantMessage(
                                        new ToolUseBlock("same", "one", Map.of(), null, null),
                                        new ToolUseBlock("same", "two", Map.of(), null, null))),
                        List.of());
        assertTrue(duplicate.issues().contains("AMBIGUOUS_CALL_ID"));
        var orphan =
                JevTrace.fromMessages(
                        "o",
                        List.of(new ToolResultMessage("missing", "lookup", "done")),
                        List.of());
        assertTrue(orphan.issues().contains("UNPAIRED_TOOL_RESULT"));
    }

    @Test
    void argumentsAreDetachedAndMetadataAndThinkingExcluded() {
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("order", "42"));
        var msg =
                new AssistantMessage(
                                new ToolUseBlock(
                                        "one", "lookup", Map.of("data", nested), null, null),
                                ThinkingBlock.builder().thinking("PRIVATE_THINKING").build())
                        .withMetadata(Map.of("secret", "PRIVATE_METADATA"));
        var trace =
                JevTrace.fromMessages(
                        "one",
                        List.of(
                                new UserMessage("lookup"),
                                msg,
                                new ToolResultMessage("one", "lookup", "shipped")),
                        List.of());
        nested.put("order", "changed");
        assertFalse(trace.fields().toString().contains("changed"));
        assertFalse(trace.fields().toString().contains("PRIVATE"));
        assertThrows(UnsupportedOperationException.class, () -> trace.fields().put("x", "y"));
    }

    @Test
    void lowProbabilityAndUncertaintyAreDifferentFromErrors() {
        var metric = JevTraceMetrics.usedToolResult(THRESHOLDS);
        var fail =
                evaluator(r -> Mono.just(answer(r, .1)), metric)
                        .evaluate(sample("f"))
                        .block()
                        .result(metric.id());
        assertEquals(JevMetricResult.Status.DECIDED, fail.status());
        assertEquals(false, fail.passed());
        var uncertain =
                evaluator(r -> Mono.just(answer(r, .5)), metric)
                        .evaluate(sample("u"))
                        .block()
                        .result(metric.id());
        assertEquals(JevMetricResult.Status.INCONCLUSIVE, uncertain.status());
        assertNull(uncertain.passed());
    }

    @Test
    void choiceRetainsLabelWhenUncertainAndDoesNotFakePassFail() {
        var e =
                evaluator(
                        r ->
                                Mono.just(
                                        new SystemOneResult(
                                                "fake",
                                                Map.of(
                                                        "tool_choice.q",
                                                        new ChoiceAnswer(
                                                                "missing",
                                                                Map.of(
                                                                        "correct",
                                                                        .2,
                                                                        "missing",
                                                                        .4,
                                                                        "wrong_tool",
                                                                        .2,
                                                                        "unnecessary",
                                                                        .2),
                                                                .4)),
                                                new Usage(1, 0))),
                        JevTraceMetrics.toolChoice(THRESHOLDS));
        var r = e.evaluate(sample("one")).block().result("tool_choice");
        assertEquals("missing", r.label());
        assertNull(r.passed());
        assertEquals(JevMetricResult.Status.INCONCLUSIVE, r.status());
    }

    @Test
    void missingNaNAndWrongTypeAnswersAreInvalid() {
        for (Map<String, Answer> answers :
                List.<Map<String, Answer>>of(
                        Map.of(),
                        Map.of("used_tool_result.q", new NoulAnswer(Double.NaN)),
                        Map.of("used_tool_result.q", new ChoiceAnswer("x", Map.of("x", 1d), 1d)))) {
            var r =
                    evaluator(
                                    q ->
                                            Mono.just(
                                                    new SystemOneResult(
                                                            "offline", answers, new Usage(7, 0))),
                                    JevTraceMetrics.usedToolResult(THRESHOLDS))
                            .evaluate(sample("one"))
                            .block();
            assertEquals("INVALID_RESPONSE", r.result("used_tool_result").reason());
            assertEquals(7, r.usage().get(0).usage().inputTokens());
        }
    }

    @Test
    void emptyAndThrowingBackendsProduceErrors() {
        var metric = JevTraceMetrics.completeness(THRESHOLDS);
        assertEquals(
                "INVALID_RESPONSE",
                evaluator(r -> Mono.empty(), metric)
                        .evaluate(sample("one"))
                        .block()
                        .result(metric.id())
                        .reason());
        assertEquals(
                "BACKEND_ERROR",
                evaluator(
                                r -> {
                                    throw new IllegalStateException("SECRET");
                                },
                                metric)
                        .evaluate(sample("one"))
                        .block()
                        .result(metric.id())
                        .reason());
    }

    @Test
    void totalBudgetCoversAllGroupsAndCancelsBackend() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        var e =
                new JevTraceEvaluator(
                        r -> {
                            calls.incrementAndGet();
                            return Mono.just(answer(r, .9))
                                    .delayElement(Duration.ofSeconds(2))
                                    .doOnCancel(() -> cancelled.set(true));
                        },
                        List.of(custom("a", Map.of("v", 1)), custom("b", Map.of("v", 2))),
                        new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 20, 1000));
        StepVerifier.withVirtualTime(() -> e.evaluate(sample("time")))
                .thenAwait(Duration.ofSeconds(3))
                .assertNext(
                        r -> {
                            assertEquals(JevMetricResult.Status.DECIDED, r.result("a").status());
                            assertEquals("TIMEOUT", r.result("b").reason());
                            assertEquals(2, r.requests());
                        })
                .verifyComplete();
        assertEquals(2, calls.get());
        assertTrue(cancelled.get());
    }

    @Test
    void cancellationDoesNotCreateAReportOrStartAnotherGroup() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        var e =
                evaluator(
                        r -> {
                            calls.incrementAndGet();
                            return Mono.<SystemOneResult>never()
                                    .doOnCancel(() -> cancelled.set(true));
                        },
                        custom("a", Map.of("v", 1)),
                        custom("b", Map.of("v", 2)));
        StepVerifier.create(e.evaluate(sample("cancel")))
                .thenAwait(Duration.ofMillis(1))
                .thenCancel()
                .verify();
        assertEquals(1, calls.get());
        assertTrue(cancelled.get());
    }

    @Test
    void metricFailuresAreIsolatedAndDuplicateIdsRejected() {
        var broken = custom("broken", null);
        var e = evaluator(r -> Mono.just(answer(r, .9)), broken, custom("good", Map.of()));
        var r = e.evaluate(sample("one")).block();
        assertEquals("DEFINITION_ERROR", r.result("broken").reason());
        assertTrue(r.result("good").passed());
        assertThrows(
                IllegalArgumentException.class, () -> evaluator(q -> Mono.empty(), broken, broken));
    }

    @Test
    void groundedUsesAllClaimsIncludingChineseAndDoesNotDropMissingAnswers() {
        var t = sample("cn").with("final_answer", "订单已发货。退款已经完成。");
        assertEquals(2, JevTraceMetrics.claims(t).size());
        var e =
                evaluator(
                        r -> {
                            var a = answer(r, .99);
                            Map<String, Answer> answers = new LinkedHashMap<>(a.answers());
                            answers.put("grounded.c1", new NoulAnswer(.01));
                            return Mono.just(new SystemOneResult(a.model(), answers, a.usage()));
                        },
                        JevTraceMetrics.grounded(THRESHOLDS));
        var r = e.evaluate(t).block().result("grounded");
        assertEquals(.5, r.score());
        assertFalse(r.passed());
    }

    @Test
    void trajectoryMatchingCountsDuplicatesAndHonorsArgumentOrderIndependence() {
        var calls =
                List.of(
                        Map.of("name", "lookup", "arguments", Map.of("a", 1, "b", 2)),
                        Map.of("name", "lookup", "arguments", Map.of("b", 2, "a", 1)));
        var trace =
                new JevTrace(
                        "t",
                        Map.of("tool_calls", calls, "expected_tool_calls", List.of(calls.get(0))));
        for (var mode :
                List.of(
                        JevTraceMetrics.MatchMode.STRICT,
                        JevTraceMetrics.MatchMode.UNORDERED,
                        JevTraceMetrics.MatchMode.SUBSET)) {
            var e =
                    evaluator(
                            r -> {
                                fail("deterministic");
                                return Mono.empty();
                            },
                            JevTraceMetrics.trajectoryMatch(mode, true));
            assertFalse(e.evaluate(trace).block().passed());
        }
        assertTrue(
                evaluator(
                                r -> Mono.empty(),
                                JevTraceMetrics.trajectoryMatch(
                                        JevTraceMetrics.MatchMode.SUPERSET, true))
                        .evaluate(trace)
                        .block()
                        .passed());
        assertTrue(
                evaluator(
                                r -> Mono.empty(),
                                JevTraceMetrics.trajectoryMatch(
                                        JevTraceMetrics.MatchMode.STRICT, false))
                        .evaluate(
                                new JevTrace(
                                        "none",
                                        Map.of(
                                                "tool_calls",
                                                List.of(),
                                                "expected_tool_calls",
                                                List.of())))
                        .block()
                        .passed());
    }

    @Test
    void runnerPreservesOrderAndIncludesAbstentionsAndErrorsInAccuracyDenominator() {
        var e =
                evaluator(
                        r -> {
                            String request = ((Map<?, ?>) r.state()).get("request").toString();
                            if (request.equals("error"))
                                return Mono.error(new IllegalStateException());
                            return Mono.just(answer(r, request.equals("uncertain") ? .5 : .99))
                                    .delayElement(
                                            Duration.ofMillis(request.equals("good") ? 20 : 1));
                        },
                        JevTraceMetrics.usedToolResult(THRESHOLDS));
        var cases =
                List.of(
                        new JevTraceRunner.Case(
                                "orders",
                                sample("good").with("request", "good"),
                                Map.of(
                                        "used_tool_result",
                                        new JevTraceRunner.Expected(true, null))),
                        new JevTraceRunner.Case(
                                "orders",
                                sample("uncertain").with("request", "uncertain"),
                                Map.of(
                                        "used_tool_result",
                                        new JevTraceRunner.Expected(true, null))),
                        new JevTraceRunner.Case(
                                "orders",
                                sample("error").with("request", "error"),
                                Map.of(
                                        "used_tool_result",
                                        new JevTraceRunner.Expected(false, null))));
        var r = new JevTraceRunner().run(e, cases, 3).block();
        assertEquals(
                List.of("good", "uncertain", "error"),
                r.rows().stream().map(JevTraceRunner.Row::traceId).toList());
        var summary = r.metrics().get("used_tool_result");
        assertEquals(3, summary.labelled());
        assertEquals(1, summary.correct());
        assertEquals(1, summary.errors());
        assertEquals(1, summary.inconclusive());
        assertEquals(1d / 3, summary.accuracy());
        assertEquals(3, r.usage().requests());
        assertEquals(2, r.usage().responsesWithUsage());
        assertEquals(200, r.usage().inputTokens());
        assertTrue(r.latency().p95Millis() >= r.latency().p50Millis());
        assertEquals(summary, r.scenarios().get("orders").get("used_tool_result"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevTraceRunner().run(e, List.of(cases.get(0), cases.get(0)), 2));
    }
}
