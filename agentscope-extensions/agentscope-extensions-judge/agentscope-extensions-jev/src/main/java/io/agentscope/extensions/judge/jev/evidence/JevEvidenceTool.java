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

package io.agentscope.extensions.judge.jev.evidence;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.judge.jev.JevExecution;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Agent-visible results contain delivered evidence only, never filtered passages or ACL metadata. */
public final class JevEvidenceTool {
    public record Request(RuntimeContext context, String query, int candidateLimit) {}

    public record Source(
            Function<Request, Mono<List<JevPassage>>> search,
            BiPredicate<RuntimeContext, JevPassage> authorized) {
        public Source {
            Objects.requireNonNull(search);
            Objects.requireNonNull(authorized);
        }
    }

    public record Passage(
            String id, String version, String text, String classification, Double score) {}

    public record Result(String status, List<Passage> passages) {
        public Result {
            passages = List.copyOf(passages);
        }
    }

    private final JevEvidenceProcessor processor;
    private final int candidateLimit, topK;

    public JevEvidenceTool(JevEvidenceProcessor processor, int candidateLimit, int topK) {
        this.processor = Objects.requireNonNull(processor);
        if (topK < 1 || candidateLimit < topK || candidateLimit > 1024)
            throw new IllegalArgumentException("require 1 <= topK <= candidateLimit <= 1024");
        this.candidateLimit = candidateLimit;
        this.topK = topK;
    }

    @Tool(
            name = "search_evidence",
            readOnly = true,
            description =
                    "Search authorized evidence. Cite each passage id and version. CONFLICTING"
                            + " evidence challenges the query premise and must not be treated as"
                            + " supporting it. Empty or unavailable evidence is not permission to"
                            + " invent an answer.")
    public Mono<Result> search(
            @ToolParam(name = "query", description = "Question to retrieve evidence for")
                    String query,
            RuntimeContext context) {
        return Mono.defer(
                () -> {
                    if (context == null
                            || context.getUserId() == null
                            || context.getUserId().isBlank()
                            || context.getSessionId() == null
                            || context.getSessionId().isBlank())
                        return Mono.error(new IllegalArgumentException("EVIDENCE_SCOPE_REQUIRED"));
                    var source = context.get(Source.class);
                    if (source == null)
                        return Mono.error(new IllegalStateException("EVIDENCE_SOURCE_REQUIRED"));
                    return processor
                            .process(
                                    context,
                                    query,
                                    () ->
                                            source.search()
                                                    .apply(
                                                            new Request(
                                                                    context,
                                                                    query,
                                                                    candidateLimit)),
                                    source.authorized(),
                                    topK)
                            .flatMap(
                                    decision -> {
                                        var report = decision.value();
                                        if (report == null)
                                            return Mono.error(
                                                    new IllegalStateException(
                                                            "EVIDENCE_SOURCE_UNAVAILABLE"));
                                        boolean applied =
                                                processor.mode() == JevExecution.Mode.ENFORCE;
                                        var delivered =
                                                report.delivered().stream()
                                                        .map(
                                                                p -> {
                                                                    var classified =
                                                                            applied
                                                                                    ? report
                                                                                            .suggested()
                                                                                            .stream()
                                                                                            .filter(
                                                                                                    s ->
                                                                                                            s.passage()
                                                                                                                    .id()
                                                                                                                    .equals(
                                                                                                                            p
                                                                                                                                    .id()))
                                                                                            .findFirst()
                                                                                            .orElse(
                                                                                                    null)
                                                                                    : null;
                                                                    return new Passage(
                                                                            p.id(),
                                                                            p.version(),
                                                                            p.text(),
                                                                            classified == null
                                                                                    ? null
                                                                                    : classified
                                                                                            .classification()
                                                                                            .name(),
                                                                            classified == null
                                                                                    ? null
                                                                                    : classified
                                                                                            .rerankScore());
                                                                })
                                                        .toList();
                                        String status =
                                                applied && !report.complete()
                                                        ? "EVIDENCE_REVIEW_INCOMPLETE"
                                                        : delivered.isEmpty()
                                                                ? "NO_EVIDENCE"
                                                                : "RETRIEVED";
                                        return Mono.just(new Result(status, delivered));
                                    });
                });
    }
}
