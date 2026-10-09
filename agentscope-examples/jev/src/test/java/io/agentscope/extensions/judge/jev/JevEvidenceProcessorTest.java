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
import io.agentscope.extensions.judge.jev.evidence.JevEvidenceProcessor;
import io.agentscope.extensions.judge.jev.evidence.JevPassage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevEvidenceProcessorTest {
    static JevPassage passage(String id) {
        return new JevPassage(id, id, "v1", Map.of("private-metadata", "not-for-model"));
    }

    static JevExecution.Options options(JevExecution.Mode mode) {
        return new JevExecution.Options(mode, Duration.ofSeconds(2), "test", (ctx, r) -> {});
    }

    static JevEvidenceProcessor processor(
            Function<SystemOneRequest, Mono<SystemOneResult>> call, JevExecution.Mode mode) {
        return new JevEvidenceProcessor(
                call,
                JevEvidenceProcessor.Policy.demonstration(),
                JevEvidenceProcessor.Limits.defaults(),
                0,
                options(mode));
    }

    static SystemOneResult reply(
            SystemOneRequest request,
            double injection,
            double contradiction,
            double relevance,
            double evidence,
            double rank) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        if (request.questions().containsKey("answers_query"))
            answers.put("answers_query", new NoulAnswer(rank));
        else {
            answers.put("is_relevant", new NoulAnswer(relevance));
            answers.put("contains_answer_evidence", new NoulAnswer(evidence));
            answers.put("contradicts_query_premise", new NoulAnswer(contradiction));
            answers.put("contains_prompt_injection", new NoulAnswer(injection));
        }
        return new SystemOneResult("offline", answers, new Usage(10, 5));
    }

    static List<String> ids(List<JevPassage> passages) {
        return passages.stream().map(JevPassage::id).toList();
    }

    static String text(SystemOneRequest r) {
        return (String) ((Map<?, ?>) r.state()).get("passage");
    }

    static boolean rank(SystemOneRequest r) {
        return r.questions().containsKey("answers_query");
    }

    @Test
    void classifiesFourIndependentChecksThenReranksWhileAclRunsBeforeAnyModelCall() {
        List<SystemOneRequest> requests = new ArrayList<>();
        var pipeline =
                processor(
                        r -> {
                            requests.add(r);
                            return Mono.just(
                                    reply(
                                            r,
                                            0,
                                            text(r).equals("conflict") ? .99 : 0,
                                            .9,
                                            .9,
                                            text(r).equals("conflict") ? .95 : .7));
                        },
                        JevExecution.Mode.ENFORCE);
        var p = List.of(passage("support"), passage("private"), passage("conflict"));
        var report =
                pipeline.process(null, "query", p, (ctx, d) -> !d.id().equals("private"), 2)
                        .block()
                        .value();
        assertEquals(List.of("conflict", "support"), ids(report.delivered()));
        assertEquals(
                JevEvidenceProcessor.Classification.CONFLICTING,
                report.suggested().get(0).classification());
        assertEquals(4, requests.size());
        assertTrue(report.complete());
        for (var request : requests) {
            assertEquals(
                    java.util.Set.of("query", "passage"), ((Map<?, ?>) request.state()).keySet());
            assertFalse(request.state().toString().contains("private"));
            assertFalse(request.state().toString().contains("not-for-model"));
        }
        assertEquals("not-for-model", p.get(0).attributes().get("private-metadata"));
        assertThrows(UnsupportedOperationException.class, () -> report.delivered().clear());
    }

    @Test
    void injectionWinsOverConflictAndUncertainInjectionNeverBecomesSupportingEvidence() {
        AtomicInteger calls = new AtomicInteger();
        var p =
                processor(
                        r -> {
                            calls.incrementAndGet();
                            return Mono.just(
                                    reply(
                                            r,
                                            text(r).equals("injection") ? .99 : .4,
                                            .99,
                                            .99,
                                            .99,
                                            1));
                        },
                        JevExecution.Mode.ENFORCE);
        var result =
                p.process(
                                null,
                                "query",
                                List.of(passage("injection"), passage("uncertain")),
                                (c, d) -> true,
                                2)
                        .block();
        assertTrue(result.value().delivered().isEmpty());
        assertEquals(2, calls.get());
        assertEquals(
                JevEvidenceProcessor.Classification.EXCLUDED,
                result.value().assessments().get(0).classification());
        assertEquals(
                JevEvidenceProcessor.Classification.INCONCLUSIVE,
                result.value().assessments().get(1).classification());
        assertEquals(JevExecution.Status.INCONCLUSIVE, result.status());
    }

    @Test
    void filterFailureIsWithheldButRankFailureFollowsScoredEvidence() {
        var p =
                processor(
                        r -> {
                            if (text(r).equals("unscreened")
                                    || rank(r) && text(r).equals("unranked"))
                                return Mono.error(new IllegalStateException("down"));
                            return Mono.just(reply(r, 0, 0, 1, 1, .01));
                        },
                        JevExecution.Mode.ENFORCE);
        var docs = List.of(passage("unranked"), passage("unscreened"), passage("scored"));
        var report = p.process(null, "query", docs, (c, d) -> true, 3).block().value();
        assertEquals(List.of("scored", "unranked"), ids(report.delivered()));
        assertFalse(report.complete());
        assertEquals(null, report.suggested().get(1).rerankScore());
        var top = p.process(null, "query", docs, (c, d) -> true, 1).block().value();
        assertEquals(List.of("scored"), ids(top.delivered()));
    }

    @Test
    void equalScoresKeepRetrieverOrderInsteadOfSortingIds() {
        var p = processor(r -> Mono.just(reply(r, 0, 0, 1, 1, .8)), JevExecution.Mode.ENFORCE);
        var result =
                p.process(null, "query", List.of(passage("z"), passage("a")), (c, d) -> true, 2)
                        .block();
        assertEquals(List.of("z", "a"), ids(result.value().delivered()));
    }

    @Test
    void shadowKeepsAuthorizedInputOrderEvenWhenTheSuggestedActionIsExclude() {
        var p = processor(r -> Mono.just(reply(r, .99, 0, 1, 1, .8)), JevExecution.Mode.SHADOW);
        var result =
                p.process(
                                null,
                                "query",
                                List.of(passage("z"), passage("private"), passage("a")),
                                (c, d) -> !d.id().equals("private"),
                                2)
                        .block();
        assertEquals(List.of("z", "a"), ids(result.value().delivered()));
        assertTrue(result.value().suggested().isEmpty());
        assertFalse(result.value().applied());
    }

    @Test
    void offRetrievesAndEnforcesAclWithoutReadingKeysOrCallingModel() {
        var records = new ArrayList<JevExecution.Record>();
        var p =
                new JevEvidenceProcessor(
                        r -> {
                            throw new AssertionError("OFF");
                        },
                        JevEvidenceProcessor.Policy.demonstration(),
                        JevEvidenceProcessor.Limits.defaults(),
                        0,
                        new JevExecution.Options(
                                JevExecution.Mode.OFF,
                                Duration.ofSeconds(1),
                                "off",
                                (c, r) -> records.add(r)));
        var r =
                p.process(
                                null,
                                "query",
                                List.of(passage("secret"), passage("visible")),
                                (c, d) -> d.id().equals("visible"),
                                1)
                        .block();
        assertEquals(JevExecution.Status.SKIPPED, r.status());
        assertEquals(List.of("visible"), ids(r.value().delivered()));
        assertEquals(1, records.size());
        assertFalse(r.value().complete());
    }

    @Test
    void emptyAuthorizedSetMakesNoRequest() {
        var p =
                processor(
                        r -> {
                            throw new AssertionError("empty");
                        },
                        JevExecution.Mode.ENFORCE);
        var result =
                p.process(null, "query", List.of(passage("secret")), (c, d) -> false, 1).block();
        assertEquals(JevExecution.Status.SKIPPED, result.status());
        assertTrue(result.value().delivered().isEmpty());
    }

    @Test
    void boundedInputsAndRequestLimitRetainExplicitUnscreenedItems() {
        AtomicInteger calls = new AtomicInteger();
        var p =
                new JevEvidenceProcessor(
                        r -> {
                            calls.incrementAndGet();
                            return Mono.just(reply(r, 0, 0, 1, 1, .8));
                        },
                        JevEvidenceProcessor.Policy.demonstration(),
                        new JevEvidenceProcessor.Limits(2, 5, 10, 100, 1),
                        0,
                        options(JevExecution.Mode.ENFORCE));
        var result =
                p.process(
                                null,
                                "query",
                                List.of(passage("small"), passage("long-text")),
                                (c, d) -> true,
                                2)
                        .block();
        assertEquals(1, calls.get());
        assertFalse(result.value().complete());
        assertEquals("REQUEST_LIMIT", result.value().suggested().get(0).rankingStatus());
        assertEquals(
                JevEvidenceProcessor.Classification.UNSCREENED,
                result.value().assessments().get(1).classification());
        assertEquals(1, result.value().delivered().size());
        var over =
                p.process(
                                null,
                                "query",
                                List.of(passage("a"), passage("b"), passage("c")),
                                (c, d) -> true,
                                2)
                        .block();
        assertTrue(over.value().delivered().isEmpty());
        assertEquals(1, calls.get());
        assertThrows(
                IllegalArgumentException.class,
                () -> p.process(null, "too long query", List.of(), (c, d) -> true, 1));
    }

    @Test
    void sourceAndInFlightJudgmentShareBudgetAndCancellation() {
        var cancelled = new AtomicBoolean();
        var p =
                processor(
                        r -> Mono.<SystemOneResult>never().doOnCancel(() -> cancelled.set(true)),
                        JevExecution.Mode.ENFORCE);
        StepVerifier.withVirtualTime(
                        () -> p.process(null, "query", List.of(passage("x")), (c, d) -> true, 1))
                .thenAwait(Duration.ofSeconds(3))
                .assertNext(
                        r -> {
                            assertEquals("TIMEOUT", r.reason());
                            assertTrue(r.value().delivered().isEmpty());
                        })
                .verifyComplete();
        assertTrue(cancelled.get());
        StepVerifier.withVirtualTime(() -> p.process(null, "query", Mono::never, (c, d) -> true, 1))
                .thenAwait(Duration.ofSeconds(3))
                .assertNext(r -> assertEquals("TIMEOUT", r.reason()))
                .verifyComplete();
        cancelled.set(false);
        StepVerifier.create(p.process(null, "query", List.of(passage("x")), (c, d) -> true, 1))
                .thenAwait(Duration.ofMillis(1))
                .thenCancel()
                .verify();
        assertTrue(cancelled.get());
    }

    @Test
    void minimumScoreDoesNotAlterClassificationOrOriginalPassage() {
        var p =
                new JevEvidenceProcessor(
                        r -> Mono.just(reply(r, 0, 0, 1, 1, .3)),
                        JevEvidenceProcessor.Policy.demonstration(),
                        JevEvidenceProcessor.Limits.defaults(),
                        .5,
                        options(JevExecution.Mode.ENFORCE));
        var result = p.process(null, "query", List.of(passage("x")), (c, d) -> true, 1).block();
        assertTrue(result.value().delivered().isEmpty());
        assertEquals(
                JevEvidenceProcessor.Classification.INCLUDED,
                result.value().assessments().get(0).classification());
        assertEquals(.3, result.value().assessments().get(0).rerankScore());
    }

    @Test
    void concurrentSessionsNeverShareEvidenceAndObserverExceptionsAreIsolated() {
        var p =
                new JevEvidenceProcessor(
                        r -> Mono.delay(Duration.ofMillis(2)).map(t -> reply(r, 0, 0, 1, 1, .8)),
                        JevEvidenceProcessor.Policy.demonstration(),
                        JevEvidenceProcessor.Limits.defaults(),
                        0,
                        new JevExecution.Options(
                                JevExecution.Mode.ENFORCE,
                                Duration.ofSeconds(2),
                                "test",
                                (c, r) -> {
                                    throw new IllegalStateException("observer");
                                }));
        var docs = List.of(passage("alice"), passage("bob"));
        var runs =
                Flux.merge(
                                List.of("alice", "bob").stream()
                                        .map(
                                                user ->
                                                        p.process(
                                                                        RuntimeContext.builder()
                                                                                .userId(user)
                                                                                .sessionId(user)
                                                                                .build(),
                                                                        "query",
                                                                        docs,
                                                                        (c, d) ->
                                                                                c.getUserId()
                                                                                        .equals(
                                                                                                d
                                                                                                        .id()),
                                                                        1)
                                                                .map(
                                                                        r ->
                                                                                ids(
                                                                                        r.value()
                                                                                                .delivered())))
                                        .toList())
                        .collectList()
                        .block();
        assertTrue(runs.contains(List.of("alice")));
        assertTrue(runs.contains(List.of("bob")));
    }

    @Test
    void invalidRepliesCannotClassifyAsIncludedAndKnownBillingSurvives() {
        var backend =
                new io.agentscope.extensions.judge.jev.evaluation.JevTextBackend(
                        "fake", body -> Mono.just(JevTextBackendTest.reply("{}")));
        var p = processor(backend, JevExecution.Mode.ENFORCE);
        var result = p.process(null, "query", List.of(passage("x")), (c, d) -> true, 1).block();
        assertTrue(result.value().delivered().isEmpty());
        assertEquals(
                JevEvidenceProcessor.Classification.ERROR,
                result.value().assessments().get(0).classification());
        assertEquals(90, result.value().calls().get(0).usage().inputTokens());
        assertThrows(IllegalArgumentException.class, () -> p.classify(Map.of()));
    }

    @Test
    void toolDoesNotReturnExcludedTextOrPrivateAttributesToTheAgent() {
        var p =
                processor(
                        r ->
                                Mono.just(
                                        reply(
                                                r,
                                                text(r).equals("injected-command") ? 1 : 0,
                                                0,
                                                1,
                                                1,
                                                .9)),
                        JevExecution.Mode.ENFORCE);
        var tool = new io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool(p, 10, 3);
        var ctx =
                RuntimeContext.builder()
                        .userId("u")
                        .sessionId("s")
                        .put(
                                io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool.Source
                                        .class,
                                new io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool
                                        .Source(
                                        req ->
                                                Mono.just(
                                                        List.of(
                                                                passage("visible"),
                                                                passage("injected-command"),
                                                                passage("private"))),
                                        (c, d) -> !d.id().equals("private")))
                        .build();
        var result = tool.search("query", ctx).block();
        assertEquals("RETRIEVED", result.status());
        assertEquals(1, result.passages().size());
        assertEquals("INCLUDED", result.passages().get(0).classification());
        assertFalse(result.toString().contains("injected-command"));
        assertFalse(result.toString().contains("private"));
        assertEquals("v1", result.passages().get(0).version());
        StepVerifier.create(
                        tool.search(
                                "query",
                                RuntimeContext.builder().userId("u").sessionId("s").build()))
                .expectErrorMessage("EVIDENCE_SOURCE_REQUIRED")
                .verify();
    }

    @Test
    void shadowToolOutputEqualsOffToolOutputDespiteDifferentRecommendations() {
        var ctx =
                RuntimeContext.builder()
                        .userId("u")
                        .sessionId("s")
                        .put(
                                io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool.Source
                                        .class,
                                new io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool
                                        .Source(
                                        req -> Mono.just(List.of(passage("visible"))),
                                        (c, d) -> true))
                        .build();
        var shadow = processor(r -> Mono.just(reply(r, 1, 0, 1, 1, 1)), JevExecution.Mode.SHADOW);
        var off =
                processor(
                        r -> {
                            throw new AssertionError("off");
                        },
                        JevExecution.Mode.OFF);
        var first =
                new io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool(shadow, 10, 2)
                        .search("query", ctx)
                        .block();
        var second =
                new io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool(off, 10, 2)
                        .search("query", ctx)
                        .block();
        assertEquals(second, first);
    }

    @Test
    void actualAgentRetrievesOnceAndOnlyRevisesItsFinalDraftBeforePublication() {
        var run = io.agentscope.examples.jev.JevEvidenceExample.runOffline();
        assertEquals(1, run.retrievals());
        assertEquals(3, run.modelCalls());
        assertEquals(5, run.judgmentRequests());
    }

    @Test
    void benchmarkFixturesParseWithoutLeakingGoldIntoTypedPassages() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var stream = getClass().getResourceAsStream("/jev/evidence-cases.jsonl");
        var lines =
                new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .lines()
                        .toList();
        assertEquals(12, lines.size());
        for (String line : lines) {
            var fixture = json.readTree(line);
            var passages = io.agentscope.examples.jev.JevEvidenceBenchmark.passages(fixture);
            assertFalse(passages.isEmpty());
            assertFalse(passages.toString().contains("goldClassifications"));
            assertEquals(passages.size(), passages.stream().map(JevPassage::id).distinct().count());
        }
    }
}
