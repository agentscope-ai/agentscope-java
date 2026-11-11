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

package io.agentscope.extensions.judge.jev.application;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/** ACL-first application RAG selection; no dependency on the deprecated core Knowledge API. */
public final class JevRag {
    public record Document(String id, String text, String version) {
        public Document {
            if (id == null || id.isBlank() || text == null || version == null)
                throw new IllegalArgumentException("document id/text/version required");
        }
    }

    public record Retrieval(
            JevExecution.Status status,
            List<Document> evidence,
            List<String> unscored,
            String reason) {
        public Retrieval {
            evidence = List.copyOf(evidence);
            unscored = List.copyOf(unscored);
        }
    }

    private final JevCandidateSelector selector;
    private final JevJudge judge;

    public JevRag(JevCandidateSelector selector, JevJudge judge) {
        this.selector = java.util.Objects.requireNonNull(selector);
        this.judge = java.util.Objects.requireNonNull(judge);
    }

    public Mono<Retrieval> retrieve(
            RuntimeContext ctx,
            String query,
            List<Document> documents,
            Predicate<Document> authorized,
            int limit) {
        if (limit < 1) throw new IllegalArgumentException("positive limit required");
        List<Document> visible = documents.stream().filter(authorized).toList();
        Map<String, Object> candidates = new LinkedHashMap<>();
        for (Document d : visible)
            if (candidates.putIfAbsent(d.id(), d) != null)
                throw new IllegalArgumentException("duplicate document id");
        return selector.select(
                        ctx,
                        Map.of("query", query),
                        "Does this passage supply relevant evidence for the query?",
                        candidates)
                .map(
                        d -> {
                            if (d.status() != JevExecution.Status.DECIDED)
                                return new Retrieval(
                                        d.status(),
                                        List.of(),
                                        visible.stream().map(Document::id).toList(),
                                        d.reason());
                            List<Document> selected =
                                    d.value().selected().stream()
                                            .limit(limit)
                                            .map(id -> (Document) candidates.get(id))
                                            .toList();
                            return new Retrieval(
                                    d.status(),
                                    selected,
                                    d.value().uncertain(),
                                    selected.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE_SELECTED");
                        });
    }

    public Mono<JevJudge.Result> verify(String query, String answer, List<Document> evidence) {
        if (evidence.isEmpty())
            return Mono.just(
                    new JevJudge.Result(
                            "rag-grounding-v1",
                            JevJudge.Status.FAIL,
                            Map.of("grounded", new JevJudge.Finding(JevJudge.Status.FAIL, 0, 0)),
                            JevJudge.ErrorCode.NONE,
                            null,
                            null,
                            java.time.Duration.ZERO));
        return judge.judge(
                Map.of("query", query, "answer", answer, "evidence", List.copyOf(evidence)),
                new JevJudge.Definition(
                        "rag-grounding-v1",
                        List.of(
                                new JevJudge.Criterion(
                                        "grounded",
                                        new NoulQuestion(
                                                "Are all factual claims in the answer supported by"
                                                    + " the supplied evidence? If evidence is empty"
                                                    + " answer no.",
                                                null),
                                        true,
                                        0.2,
                                        0.8))));
    }
}
