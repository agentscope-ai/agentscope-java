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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.judge.jev.application.JevBrowserPlanner;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
import io.agentscope.extensions.judge.jev.application.JevContextPlanner;
import io.agentscope.extensions.judge.jev.application.JevCustomerSupport;
import io.agentscope.extensions.judge.jev.application.JevDraftPipeline;
import io.agentscope.extensions.judge.jev.application.JevMemoryGate;
import io.agentscope.extensions.judge.jev.application.JevRag;
import io.agentscope.extensions.judge.jev.application.JevSupervisor;
import io.agentscope.extensions.judge.jev.application.JevTeamPlanner;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevApplicationsTest {
    private JevExecution.Options options() {
        return new JevExecution.Options(
                JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "test", (c, r) -> {});
    }

    private Function<SystemOneRequest, Mono<SystemOneResult>> answers(double probability) {
        return request -> {
            Map<String, Answer> answers = new LinkedHashMap<>();
            request.questions().keySet().forEach(k -> answers.put(k, new NoulAnswer(probability)));
            return Mono.just(new SystemOneResult("fake", answers, null));
        };
    }

    private JevCandidateSelector selector(double p) {
        return new JevCandidateSelector(answers(p), options(), 0.2, 0.8);
    }

    private JevJudge judge(double p) {
        return new JevJudge(answers(p), Duration.ofSeconds(1));
    }

    private JevJudge.Definition check() {
        return new JevJudge.Definition(
                "check-v1",
                List.of(
                        new JevJudge.Criterion(
                                "ok", new NoulQuestion("is acceptable?", null), true, 0.2, 0.8)));
    }

    @Test
    void emptyEvidenceNeverInvokesSemanticVerification() {
        var j =
                new JevJudge(
                        r -> {
                            throw new AssertionError();
                        },
                        Duration.ofSeconds(1));
        assertEquals(
                JevJudge.Status.FAIL,
                new JevRag(selector(0.9), j).verify("q", "claim", List.of()).block().status());
    }

    @Test
    void aclRunsBeforeAnyDocumentIsSent() {
        AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
        var selector =
                new JevCandidateSelector(
                        r -> {
                            captured.set(r);
                            return answers(0.9).apply(r);
                        },
                        options(),
                        0.2,
                        0.8);
        var rag = new JevRag(selector, judge(0.9));
        var result =
                rag.retrieve(
                                null,
                                "query",
                                List.of(
                                        new JevRag.Document("public", "visible", "1"),
                                        new JevRag.Document("private", "secret", "1")),
                                d -> d.id().equals("public"),
                                2)
                        .block();
        assertEquals(1, result.evidence().size());
        assertEquals(1, captured.get().questions().size());
        assertTrue(!captured.get().toString().contains("secret"));
    }

    @Test
    void noEvidenceAndUnscoredAreDifferent() {
        var docs = List.of(new JevRag.Document("a", "text", "1"));
        assertEquals(
                "NO_EVIDENCE",
                new JevRag(selector(0.1), judge(0.9))
                        .retrieve(null, "q", docs, d -> true, 1)
                        .block()
                        .reason());
        var uncertain =
                new JevRag(selector(0.5), judge(0.9))
                        .retrieve(null, "q", docs, d -> true, 1)
                        .block();
        assertEquals(List.of("a"), uncertain.unscored());
        assertTrue(uncertain.evidence().isEmpty());
    }

    @Test
    void invalidSelectionDoesNotReturnEvidence() {
        var selector =
                new JevCandidateSelector(
                        r -> Mono.just(new SystemOneResult("fake", Map.of(), null)),
                        options(),
                        0.2,
                        0.8);
        var result =
                new JevRag(selector, judge(0.9))
                        .retrieve(
                                null,
                                "q",
                                List.of(new JevRag.Document("a", "text", "1")),
                                d -> true,
                                1)
                        .block();
        assertEquals(JevExecution.Status.ERROR, result.status());
        assertEquals(List.of("a"), result.unscored());
    }

    @Test
    void inputRefusalDoesNotGenerate() {
        var pipeline = new JevDraftPipeline(judge(0.1), 100, 2, Duration.ofSeconds(2));
        var result =
                pipeline.run(
                                "request",
                                "evidence",
                                check(),
                                check(),
                                () -> {
                                    throw new AssertionError();
                                },
                                r -> {
                                    throw new AssertionError();
                                })
                        .block();
        assertNull(result.publishedText());
        assertEquals("INPUT_NOT_ACCEPTED", result.reason());
    }

    @Test
    void boundedRevisionPublishesOnlyReviewedFinalText() {
        AtomicInteger calls = new AtomicInteger();
        var judge =
                new JevJudge(
                        r -> answers(calls.incrementAndGet() == 2 ? 0.1 : 0.9).apply(r),
                        Duration.ofSeconds(1));
        var pipeline = new JevDraftPipeline(judge, 100, 1, Duration.ofSeconds(3));
        var result =
                pipeline.run(
                                "request",
                                "evidence",
                                check(),
                                check(),
                                () -> Flux.just("bad", " draft"),
                                r -> Flux.just("corrected"))
                        .block();
        assertEquals("corrected", result.publishedText());
        assertEquals(1, result.revisions());
        assertEquals(3, calls.get());
    }

    @Test
    void sizeBoundAndUnchangedDraftStopPublication() {
        assertEquals(
                "DRAFT_TOO_LARGE",
                new JevDraftPipeline(judge(0.9), 3, 1, Duration.ofSeconds(2))
                        .run("r", "e", check(), check(), () -> Flux.just("long"), r -> Flux.empty())
                        .block()
                        .reason());
        AtomicInteger calls = new AtomicInteger();
        var j =
                new JevJudge(
                        r -> answers(calls.incrementAndGet() == 1 ? 0.9 : 0.1).apply(r),
                        Duration.ofSeconds(1));
        var result =
                new JevDraftPipeline(j, 20, 2, Duration.ofSeconds(2))
                        .run(
                                "r",
                                "e",
                                check(),
                                check(),
                                () -> Flux.just("same"),
                                r -> Flux.just("same"))
                        .block();
        assertEquals("UNCHANGED_DRAFT", result.reason());
        assertNull(result.publishedText());
    }

    @Test
    void pipelineTotalBudgetBoundsGenerator() {
        StepVerifier.withVirtualTime(
                        () ->
                                new JevDraftPipeline(judge(0.9), 20, 1, Duration.ofSeconds(2))
                                        .run(
                                                "r",
                                                "e",
                                                check(),
                                                check(),
                                                Flux::never,
                                                r -> Flux.empty()))
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(r -> assertEquals("TIMEOUT", r.reason()))
                .verifyComplete();
    }

    @Test
    void unsupportedVerificationCannotProduceCompletionAdvice() {
        var j =
                new JevJudge(
                        r ->
                                Mono.just(
                                        new SystemOneResult(
                                                "fake",
                                                Map.of(
                                                        "complete",
                                                        new NoulAnswer(0.99),
                                                        "off_track",
                                                        new NoulAnswer(0.01)),
                                                null)),
                        Duration.ofSeconds(1));
        assertEquals(
                JevSupervisor.Advice.REQUEST_VERIFICATION,
                new JevSupervisor(j).assess("task", "worker claims done", false).block().advice());
    }

    @Test
    void archivePreservesPairsPinnedFactsAndRestoresOriginalOrder() {
        var original =
                List.of(
                        new JevContextPlanner.Exchange("old", "call", "result", false),
                        new JevContextPlanner.Exchange("new", "call2", "result2", true));
        var plan = new JevContextPlanner(selector(0.1)).plan(null, "task", original).block();
        assertEquals(1, plan.retained().size());
        assertEquals(original.get(0), plan.archive().get("old"));
        assertEquals(original, plan.restore());
    }

    @Test
    void memoryRejectsCrossOwnerAndKeepsConflictSeparate() {
        var gate = new JevMemoryGate(judge(0.9));
        assertThrows(
                IllegalArgumentException.class,
                () -> gate.assess("a", new JevMemoryGate.Fact("b", "fact", "source"), List.of()));
        assertEquals(
                JevJudge.Status.FAIL,
                gate.assess("a", new JevMemoryGate.Fact("a", "fact", "source"), List.of())
                        .block()
                        .status());
    }

    @Test
    void browserRejectsStalePageAndUnverifiedDone() {
        var browser = new JevBrowserPlanner(selector(0.9));
        var actions =
                List.of(
                        new JevBrowserPlanner.Action(
                                "done", JevBrowserPlanner.Operation.DONE, "goal"));
        assertEquals(
                "STALE_PAGE",
                browser.propose(null, "goal", "1", actions, () -> "2", true).block().reason());
        assertEquals(
                "COMPLETION_NOT_VERIFIED",
                browser.propose(null, "goal", "1", actions, () -> "1", false).block().reason());
    }

    @Test
    void teamOnlyConsidersEligibleMembersAndCustomerSupportsMultipleIntents() {
        var team = new JevTeamPlanner(selector(0.9));
        var result =
                team.recommend(
                                null,
                                "review",
                                "task",
                                List.of(
                                        new JevTeamPlanner.Member("a", "review"),
                                        new JevTeamPlanner.Member("b", "restricted")),
                                m -> m.id().equals("a"))
                        .block();
        assertEquals(List.of("a"), result.value().selected());
        var report =
                new JevCustomerSupport(selector(0.9), judge(0.9))
                        .review(null, "退款并查订单", "已退款", "未退款")
                        .block();
        assertEquals(3, report.triage().value().selected().size());
        assertEquals(JevJudge.Status.FAIL, report.review().status());
    }
}
