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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
import io.agentscope.extensions.judge.jev.evaluation.Evaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluationRunner;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluator;
import io.agentscope.extensions.judge.jev.integration.JevKnowledge;
import io.agentscope.extensions.judge.jev.integration.JevKnowledgeTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("removal")
class JevRetrievalEvaluationTest {
    static Document doc(String id, String text) {
        return new Document(new DocumentMetadata(TextBlock.builder().text(text).build(), id, "0"));
    }

    static Knowledge source(List<Document> docs, AtomicReference<RetrieveConfig> seen) {
        return new Knowledge() {
            @Override
            public Mono<Void> addDocuments(List<Document> ignored) {
                return Mono.empty();
            }

            @Override
            public Mono<List<Document>> retrieve(String query, RetrieveConfig c) {
                seen.set(c);
                return Mono.just(docs);
            }
        };
    }

    @Test
    void existingKnowledgeApiFiltersBeforeJudgeAndRetainsIdentity() {
        var secret = doc("s", "SECRET");
        var irrelevant = doc("n", "noise");
        var relevant = doc("y", "evidence");
        relevant.setScore(.42);
        var options = JevResponseIntegrationTest.options(JevExecution.Mode.ENFORCE);
        AtomicReference<RetrieveConfig> seen = new AtomicReference<>();
        var selector =
                new JevCandidateSelector(
                        r -> {
                            assertFalse(r.toString().contains("SECRET"));
                            return Mono.just(
                                    new SystemOneResult(
                                            "fake",
                                            Map.of(
                                                    "item_0",
                                                    new NoulAnswer(0d),
                                                    "item_1",
                                                    new NoulAnswer(.99)),
                                            null));
                        },
                        options,
                        .2,
                        .8);
        var adapter =
                new JevKnowledge(
                        source(List.of(secret, irrelevant, relevant), seen),
                        selector,
                        options,
                        (ctx, d) -> !d.getId().equals(secret.getId()),
                        10);
        var result =
                adapter.retrieve("query", RetrieveConfig.builder().limit(1).build())
                        .contextWrite(
                                c ->
                                        c.put(
                                                RuntimeContext.class,
                                                RuntimeContext.builder().userId("u").build()))
                        .block();
        assertEquals(List.of(relevant), result);
        assertSame(relevant, result.get(0));
        assertEquals(.42, relevant.getScore());
        assertEquals(10, seen.get().getLimit());
        StepVerifier.create(adapter.retrieve("q", RetrieveConfig.builder().build()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void modesPreserveVisibleCandidatesAndSessionAclIsIsolated() {
        var a = doc("a", "a");
        var b = doc("b", "b");
        AtomicInteger calls = new AtomicInteger();
        for (var mode : JevExecution.Mode.values()) {
            var options = JevResponseIntegrationTest.options(mode);
            var selector =
                    new JevCandidateSelector(
                            r -> {
                                calls.incrementAndGet();
                                return Mono.just(
                                        new SystemOneResult(
                                                "fake",
                                                Map.of("item_0", new NoulAnswer(0d)),
                                                null));
                            },
                            options,
                            .2,
                            .8);
            var adapter =
                    new JevKnowledge(
                            source(List.of(a, b), new AtomicReference<>()),
                            selector,
                            options,
                            (ctx, d) -> d.getMetadata().getDocId().equals(ctx.getUserId()),
                            5);
            var tool = new JevKnowledgeTool(adapter, RetrieveConfig.builder().build());
            var both =
                    Mono.zip(
                                    tool.search("q", RuntimeContext.builder().userId("a").build()),
                                    tool.search("q", RuntimeContext.builder().userId("b").build()))
                            .block();
            if (mode == JevExecution.Mode.ENFORCE) {
                assertTrue(both.getT1().isEmpty());
                assertTrue(both.getT2().isEmpty());
            } else {
                assertEquals(a.getId(), both.getT1().get(0).id());
                assertEquals(b.getId(), both.getT2().get(0).id());
            }
        }
        assertEquals(4, calls.get());
    }

    @Test
    void retrievalFailuresAreNotReportedAsNoEvidence() {
        var options = JevResponseIntegrationTest.options(JevExecution.Mode.ENFORCE);
        var selector =
                new JevCandidateSelector(r -> Mono.error(new RuntimeException()), options, .2, .8);
        var adapter =
                new JevKnowledge(
                        source(List.of(doc("a", "a")), new AtomicReference<>()),
                        selector,
                        options,
                        (ctx, d) -> true,
                        5);
        StepVerifier.create(
                        adapter.retrieve(
                                RuntimeContext.builder().build(),
                                "q",
                                RetrieveConfig.builder().build()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void evaluatorMapsEvidenceAndBatchKeepsErrorsInDenominator() {
        var judge =
                JevResponseIntegrationTest.judge(
                        r -> {
                            var state = (Map<?, ?>) r.state();
                            assertEquals(List.of("evidence"), state.get("supporting_context"));
                            return switch ((String) state.get("assistant_answer")) {
                                case "yes" -> Mono.just(JevResponseIntegrationTest.scored(.99));
                                case "no" -> Mono.just(JevResponseIntegrationTest.scored(0));
                                case "unknown" -> Mono.just(JevResponseIntegrationTest.scored(.5));
                                default -> Mono.error(new RuntimeException());
                            };
                        });
        Evaluator evaluator = new JevEvaluator(judge, JevResponseIntegrationTest.definition());
        List<JevEvaluationRunner.Case> cases = new ArrayList<>();
        for (String answer : List.of("yes", "no", "unknown", "error"))
            cases.add(
                    new JevEvaluationRunner.Case(
                            "support",
                            new Evaluator.Request(answer, "question", List.of("evidence"), answer),
                            !answer.equals("no")));
        var report = new JevEvaluationRunner().run(evaluator, cases, 2).block();
        assertEquals(4, report.overall().total());
        assertEquals(2, report.overall().correct());
        assertEquals(1, report.overall().abstained());
        assertEquals(1, report.overall().errors());
        assertEquals(.5, report.overall().accuracy());
        assertEquals(1, report.rows().get(0).response().score());
        assertEquals(0, report.rows().get(2).response().score());
        assertEquals(
                List.of("yes", "no", "unknown", "error"),
                report.rows().stream().map(JevEvaluationRunner.Row::id).toList());
        assertFalse(report.toString().contains("question"));
        assertFalse(report.toString().contains("evidence"));
    }

    @Test
    void batchRejectsDuplicateIdsAndPropagatesCancellation() {
        var request = new Evaluator.Request("id", "q", List.of(), "a");
        var c = new JevEvaluationRunner.Case("s", request, true);
        var runner = new JevEvaluationRunner();
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(r -> Mono.never(), List.of(c, c), 1));
        StepVerifier.create(runner.run(r -> Mono.never(), List.of(c), 1)).thenCancel().verify();
        assertEquals(1, runner.run(r -> Mono.empty(), List.of(c), 1).block().overall().errors());
    }
}
