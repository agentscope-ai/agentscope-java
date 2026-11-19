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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewTool;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewer;
import io.agentscope.extensions.judge.jev.review.JevReviewInput;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevCodeReviewTest {
    static JevReviewInput input(String revision) {
        return new JevReviewInput(
                revision,
                JevReviewInput.Mode.CHANGES,
                List.of(
                        new JevReviewInput.File(
                                "src/auth.py",
                                "@@ -1,2 +1,1 @@\n"
                                        + "-if not authorized: raise Forbidden()\n"
                                        + "-return record\n"
                                        + "+return record",
                                List.of())),
                List.of());
    }

    static JevExecution.Options options(JevExecution.Mode mode) {
        return new JevExecution.Options(mode, Duration.ofSeconds(2), "review-test", (ctx, r) -> {});
    }

    static JevCodeReviewer reviewer(Function<SystemOneRequest, Mono<SystemOneResult>> caller) {
        return new JevCodeReviewer(
                caller,
                JevCodeReviewer.Config.referencePolicy(),
                options(JevExecution.Mode.SHADOW));
    }

    static Mono<SystemOneResult> answer(SystemOneRequest request) {
        return answer(request, .99, Map.of(), Map.of());
    }

    static Mono<SystemOneResult> answer(
            SystemOneRequest request,
            double risk,
            Map<String, String> choices,
            Map<String, Double> confidence) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        request.questions()
                .forEach(
                        (id, question) -> {
                            if (question instanceof NoulQuestion)
                                answers.put(id, new NoulAnswer(id.equals("security") ? risk : .01));
                            else if (question instanceof ChoiceQuestion choice) {
                                String selected = choices.get(id);
                                if (selected == null)
                                    selected =
                                            switch (id) {
                                                case "category" ->
                                                        choice.criteria().containsKey("behavior")
                                                                ? "behavior"
                                                                : "domain";
                                                case "mechanism" -> "authorization";
                                                case "owner" -> "security";
                                                default ->
                                                        choice.criteria().keySet().stream()
                                                                .filter(k -> !k.equals("noMatch"))
                                                                .sorted()
                                                                .findFirst()
                                                                .orElseThrow();
                                            };
                                String selection = selected;
                                Map<String, Double> probabilities = new LinkedHashMap<>();
                                choice.criteria()
                                        .keySet()
                                        .forEach(
                                                k ->
                                                        probabilities.put(
                                                                k, k.equals(selection) ? 1d : 0d));
                                answers.put(
                                        id,
                                        new ChoiceAnswer(
                                                selection,
                                                probabilities,
                                                confidence.getOrDefault(id, .99)));
                            } else if (question instanceof ScoreQuestion score) {
                                Map<String, Double> probabilities = new LinkedHashMap<>();
                                Map<String, String> legend = new LinkedHashMap<>();
                                for (int i = 0; i < score.criteria().size(); i++) {
                                    probabilities.put(Integer.toString(i), i == 2 ? 1d : 0d);
                                    legend.put(
                                            Integer.toString(i),
                                            score.criteria().get(i).toString());
                                }
                                answers.put(
                                        id,
                                        new ScoreAnswer(
                                                2d,
                                                legend,
                                                probabilities,
                                                confidence.getOrDefault(id, .99)));
                            }
                        });
        return Mono.just(new SystemOneResult("synthetic-review", answers, new Usage(10, 2)));
    }

    @Test
    void completesSixStagesWithEvidenceBoundLineAndNoAutomaticReviewAction() {
        var decision = reviewer(JevCodeReviewTest::answer).review(null, input("r1")).block();
        assertEquals(JevExecution.Status.DECIDED, decision.status());
        assertEquals("REVIEW_FINISHED_NOT_APPROVAL", decision.reason());
        var report = decision.value();
        assertEquals(
                List.of("screen", "profile", "locate", "mechanism", "severity", "route"),
                report.calls().stream().map(JevCodeReviewer.Call::stage).toList());
        assertEquals(1, report.findings().size());
        assertEquals(1, report.findings().get(0).evidence().startLine());
        assertEquals("authorization", report.findings().get(0).mechanism());
        assertEquals("security", report.findings().get(0).owner());
        assertEquals(
                JevCodeReviewer.Advice.REQUEST_CHANGES_REVIEW, report.findings().get(0).advice());
    }

    @Test
    void noMatchNoIssueAndLowConfidenceAreDistinctNotEmptyApprovals() {
        var noMatch =
                reviewer(r -> answer(r, .99, Map.of("evidence", "noMatch"), Map.of()))
                        .review(null, input("r"))
                        .block()
                        .value();
        assertEquals(
                JevCodeReviewer.Disposition.NO_MATCH, noMatch.followUps().get(0).disposition());
        assertEquals(3, noMatch.calls().size());
        var noIssue =
                reviewer(r -> answer(r, .99, Map.of("mechanism", "noIssue"), Map.of()))
                        .review(null, input("r"))
                        .block()
                        .value();
        assertEquals(
                JevCodeReviewer.Disposition.NO_ISSUE, noIssue.followUps().get(0).disposition());
        assertEquals(4, noIssue.calls().size());
        for (String key : List.of("evidence", "mechanism", "severity", "owner")) {
            var report =
                    reviewer(r -> answer(r, .99, Map.of(), Map.of(key, .1)))
                            .review(null, input("r"))
                            .block()
                            .value();
            assertFalse(report.complete());
            assertEquals(
                    JevCodeReviewer.Disposition.LOW_CONFIDENCE,
                    report.followUps().get(0).disposition());
            if (key.equals("owner")) {
                assertEquals(1, report.findings().size());
                assertEquals(null, report.findings().get(0).owner());
            }
        }
    }

    @Test
    void lowScreeningRisksDoNotTriggerFollowupsButUncertaintyIsVisible() {
        var low =
                reviewer(r -> answer(r, .01, Map.of(), Map.of()))
                        .review(null, input("r"))
                        .block()
                        .value();
        assertTrue(low.followUps().isEmpty());
        assertEquals(2, low.calls().size());
        var uncertain =
                reviewer(r -> answer(r, .5, Map.of(), Map.of())).review(null, input("r")).block();
        assertEquals(JevExecution.Status.INCONCLUSIVE, uncertain.status());
        assertFalse(uncertain.value().complete());
        assertEquals(.5, uncertain.value().matrix().get(0).probabilities().get("security"));
    }

    @Test
    void fullSourceScreens160LineChunksAndLocates80LineRegions() {
        var input =
                new JevReviewInput(
                        "r",
                        JevReviewInput.Mode.CODEBASE,
                        List.of(
                                new JevReviewInput.File(
                                        "src/auth.rs", "read_record();\n".repeat(200), List.of())),
                        List.of());
        var report =
                reviewer(r -> answer(r, .99, Map.of("evidence", "R2"), Map.of()))
                        .review(null, input)
                        .block()
                        .value();
        assertEquals(2, report.calls().stream().filter(c -> c.stage().equals("screen")).count());
        assertEquals(81, report.findings().get(0).evidence().startLine());
        assertEquals(160, report.findings().get(0).evidence().endLine());
    }

    @Test
    void truncatedRelatedTestsAndSkippedFilesAreCoverageGaps() {
        var input =
                new JevReviewInput(
                        "r",
                        JevReviewInput.Mode.CODEBASE,
                        List.of(
                                new JevReviewInput.File(
                                        "src/code.go", "func f() {}", List.of("test.go")),
                                new JevReviewInput.File("empty.txt", "", List.of())),
                        List.of(
                                new JevReviewInput.TestEvidence(
                                        "test.go", "assertion\n".repeat(300))));
        var report = reviewer(JevCodeReviewTest::answer).review(null, input).block().value();
        assertFalse(report.complete());
        assertTrue(report.matrix().get(0).testContextLimited());
        assertEquals("SKIPPED", report.matrix().get(1).status());
    }

    @Test
    void followupLimitLeavesExplicitDeferredSignal() {
        var config =
                new JevCodeReviewer.Config(.2, .7, .55, 1.5, 2, 0, 0, 64, 50, 100000, 100000, 128);
        var report =
                new JevCodeReviewer(
                                JevCodeReviewTest::answer,
                                config,
                                options(JevExecution.Mode.SHADOW))
                        .review(null, input("r"))
                        .block()
                        .value();
        assertFalse(report.complete());
        assertEquals(1, report.calls().size());
        assertEquals(JevCodeReviewer.Disposition.DEFERRED, report.followUps().get(0).disposition());
    }

    @Test
    void requestLimitPreservesScreeningAndReportsUnfinishedReview() {
        var config =
                new JevCodeReviewer.Config(.2, .7, .55, 1.5, 2, 5, 8, 1, 50, 100000, 100000, 128);
        var decision =
                new JevCodeReviewer(
                                JevCodeReviewTest::answer,
                                config,
                                options(JevExecution.Mode.SHADOW))
                        .review(null, input("r"))
                        .block();
        assertEquals("REQUEST_LIMIT", decision.reason());
        assertFalse(decision.value().complete());
        assertEquals(1, decision.value().matrix().size());
        assertEquals(1, decision.value().calls().size());
    }

    @Test
    void invalidResponsesAndFailuresNeverBecomeCleanReviews() {
        var invalid =
                reviewer(r -> Mono.just(new SystemOneResult("broken", Map.of(), new Usage(10, 0))))
                        .review(null, input("r"))
                        .block();
        assertEquals(JevExecution.Status.ERROR, invalid.status());
        assertFalse(invalid.value().complete());
        assertEquals("ERROR", invalid.value().matrix().get(0).status());
        assertNotNull(invalid.value().calls().get(0).usage());
        var fail =
                reviewer(
                                r ->
                                        r.questions().containsKey("category")
                                                ? Mono.error(new IllegalStateException("outage"))
                                                : answer(r))
                        .review(null, input("r"))
                        .block();
        assertEquals(JevExecution.Status.ERROR, fail.status());
        assertEquals(1, fail.value().matrix().size());
        assertEquals(
                JevCodeReviewer.Disposition.DEFERRED,
                fail.value().followUps().get(0).disposition());
    }

    @Test
    void acquisitionAndModelTimeoutAreBothBounded() {
        StepVerifier.withVirtualTime(
                        () -> reviewer(JevCodeReviewTest::answer).review(null, Mono::never))
                .thenAwait(Duration.ofSeconds(3))
                .assertNext(d -> assertEquals("TIMEOUT", d.reason()))
                .verifyComplete();
        StepVerifier.withVirtualTime(() -> reviewer(r -> Mono.never()).review(null, input("r")))
                .thenAwait(Duration.ofSeconds(3))
                .assertNext(
                        d -> {
                            assertEquals("TIMEOUT", d.reason());
                            assertFalse(d.value().complete());
                            assertEquals("CANCELLED", d.value().calls().get(0).status());
                        })
                .verifyComplete();
    }

    @Test
    void cancellationDisposesTheRequestWithoutAnyFollowup() {
        AtomicInteger started = new AtomicInteger(), cancelled = new AtomicInteger();
        var reviewer =
                reviewer(
                        r -> {
                            started.incrementAndGet();
                            return Mono.<SystemOneResult>never()
                                    .doOnCancel(cancelled::incrementAndGet);
                        });
        StepVerifier.create(reviewer.review(null, input("r")))
                .thenAwait(Duration.ofMillis(1))
                .thenCancel()
                .verify();
        assertEquals(1, started.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    void offDoesNotAcquireEvidenceAndEnforceIsRejected() {
        var reviewer =
                new JevCodeReviewer(
                        r -> {
                            throw new AssertionError();
                        },
                        JevCodeReviewer.Config.referencePolicy(),
                        options(JevExecution.Mode.OFF));
        assertEquals(
                JevExecution.Status.SKIPPED,
                reviewer.review(
                                null,
                                () -> {
                                    throw new AssertionError();
                                })
                        .block()
                        .status());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JevCodeReviewer(
                                JevCodeReviewTest::answer,
                                JevCodeReviewer.Config.referencePolicy(),
                                options(JevExecution.Mode.ENFORCE)));
    }

    @Test
    void concurrentRunsDoNotShareRevisionCountsOrFindings() {
        var reviewer = reviewer(r -> answer(r).delayElement(Duration.ofMillis(1)));
        var reports =
                Mono.zip(reviewer.review(null, input("a")), reviewer.review(null, input("b")))
                        .block();
        assertEquals("a", reports.getT1().value().revision());
        assertEquals("b", reports.getT2().value().revision());
        assertEquals(6, reports.getT1().value().calls().size());
        assertEquals(6, reports.getT2().value().calls().size());
    }

    @Test
    void toolUsesScopedHostSnapshotAndNeverReadsAnArbitraryFilesystemPath() {
        AtomicInteger reads = new AtomicInteger();
        var source =
                new JevCodeReviewTool.Source(
                        (ctx, id) -> {
                            assertEquals("u", ctx.getUserId());
                            assertEquals("s", ctx.getSessionId());
                            reads.incrementAndGet();
                            return id.equals("authorized")
                                    ? Mono.just(input("r"))
                                    : Mono.error(new SecurityException("not authorized"));
                        });
        var tool = new JevCodeReviewTool(reviewer(JevCodeReviewTest::answer));
        var ctx =
                RuntimeContext.builder()
                        .userId("u")
                        .sessionId("s")
                        .put(JevCodeReviewTool.Source.class, source)
                        .build();
        assertEquals(JevExecution.Status.DECIDED, tool.review("authorized", ctx).block().status());
        assertEquals(JevExecution.Status.ERROR, tool.review("/etc/passwd", ctx).block().status());
        assertEquals(JevExecution.Status.ERROR, tool.review("authorized", null).block().status());
        assertEquals(2, reads.get());
    }

    @Test
    void emptyScopeDoesNotCallModel() {
        var decision =
                reviewer(
                                r -> {
                                    throw new AssertionError();
                                })
                        .review(
                                null,
                                new JevReviewInput(
                                        "r", JevReviewInput.Mode.CHANGES, List.of(), List.of()))
                        .block();
        assertEquals(JevExecution.Status.SKIPPED, decision.status());
        assertEquals("NO_SOURCE_FILES", decision.reason());
    }

    @Test
    void routingFailurePreservesConfirmedEvidenceButDoesNotAssignAnOwner() {
        var decision =
                reviewer(
                                r ->
                                        r.questions().containsKey("owner")
                                                ? Mono.error(
                                                        new IllegalStateException(
                                                                "routing unavailable"))
                                                : answer(r))
                        .review(null, input("r"))
                        .block();
        assertEquals(JevExecution.Status.INCONCLUSIVE, decision.status());
        assertEquals(
                JevCodeReviewer.Disposition.ERROR,
                decision.value().followUps().get(0).disposition());
        assertEquals(1, decision.value().findings().size());
        assertEquals(null, decision.value().findings().get(0).owner());
        assertEquals(6, decision.value().calls().size());
    }

    @Test
    void actualAgentExecutesTheReviewToolExactlyOnce() {
        var run = io.agentscope.examples.jev.JevCodeReviewExample.runOffline();
        assertEquals(1, run.snapshotReads());
        assertEquals(6, run.reviewRequests());
        assertEquals(2, run.agentModelCalls());
    }

    @Test
    void malformedTextReplyRetainsKnownBillingWithoutRecordingRawContent() {
        var backend =
                new io.agentscope.extensions.judge.jev.evaluation.JevTextBackend(
                        "bad", body -> Mono.just(JevTextBackendTest.reply("{}")));
        var reviewer =
                new JevCodeReviewer(
                        backend,
                        JevCodeReviewer.Config.referencePolicy(),
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(2),
                                "test",
                                (ctx, record) -> {}));
        var result =
                reviewer.review(null, io.agentscope.examples.jev.JevCodeReviewExample.snapshot())
                        .block();
        assertEquals(JevExecution.Status.ERROR, result.status());
        assertEquals(90, result.value().calls().get(0).usage().inputTokens());
        assertEquals("90", result.recommendation().get("inputTokens"));
    }

    @Test
    void fixedBenchmarkEvidenceIsValidAndLabelsDoNotEnterRequests() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var resource = getClass().getResourceAsStream("/jev/review-cases.jsonl");
        var lines =
                new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .lines()
                        .toList();
        assertEquals(12, lines.size());
        var ids = new java.util.HashSet<String>();
        for (String line : lines) {
            var fixture = json.readTree(line);
            assertTrue(ids.add(fixture.path("id").asText()));
            var input = io.agentscope.examples.jev.JevCodeReviewBenchmark.input(fixture);
            var file = input.files().get(0);
            var regions =
                    input.mode()
                                    == io.agentscope.extensions.judge.jev.review.JevReviewInput.Mode
                                            .CHANGES
                            ? io.agentscope.extensions.judge.jev.review.ReviewRegions.patch(
                                    file.text())
                            : io.agentscope.extensions.judge.jev.review.ReviewRegions.source(
                                    file.text(), 80);
            assertFalse(regions.isEmpty());
            for (var gold : fixture.path("expectedFindings"))
                assertTrue(
                        regions.stream()
                                .anyMatch(
                                        r ->
                                                r.id().equals(gold.path("region").asText())
                                                        && r.side()
                                                                .name()
                                                                .equals(
                                                                        gold.path("side")
                                                                                .asText())));
            assertFalse(json.writeValueAsString(input).contains("goldScreen"));
            assertFalse(json.writeValueAsString(input).contains("expectedFindings"));
        }
    }
}
